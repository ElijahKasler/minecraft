package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.netty.ByteBuf;
import net.lax1dude.eaglercraft.v1_8.netty.Unpooled;
import net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes;
import net.lax1dude.eaglercraft.v1_8.socket.EaglercraftNetworkManager;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

/**
 * MWC-proxy compatible network manager for Eaglercraft 1.8.
 *
 * <p>Unlike {@code WebSocketNetworkManager} (1 WS message = 1 MC packet,
 * EaglercraftXServer handshake), this class speaks <b>raw vanilla TCP</b>
 * tunnelled over mwc-proxy:</p>
 *
 * <pre>
 * POST {proxyBase}/api/vm/net/connect {"host": mcHost, "port": mcPort} -&gt; {"token": "..."}
 * WS   {proxyBase}/api/vm/net/socket?token=...  (binary, raw TCP bytes)
 * </pre>
 *
 * <p>The JS shell ({@code js/eaglerMwcBridge.js}) performs the POST and opens
 * the WS URL above via {@code PlatformNetworking.openWebSocket(wsUrl)}. Java
 * then performs a vanilla 1.8 handshake (protocol 47) + Login Start over that
 * stream, reassembles TCP frames (VarInt length prefix, coalesced/split WS
 * messages), strips the length, and dispatches PLAY packets with the same code
 * path as vanilla/Eagler play.</p>
 *
 * <p>Supports both offline (cracked) and online (premium) 1.8 servers via
 * yggdrasil authentication. All platforms use the same pure-Java RSA PKCS#1
 * v1.5 + AES-128/CFB8 implementation ({@link MwcCryptoShared}). CFB8 is a
 * streaming cipher: the length prefixes are encrypted too, so the ciphers are
 * applied to the raw byte stream rather than to individual packets.</p>
 */
public class MwcProxyNetworkManager extends EaglercraftNetworkManager {

	protected final IWebSocketClient webSocketClient;

	/** Raw TCP reassembly buffer (length-prefixed frames). */
	private byte[] tcpBuf = new byte[65536];
	private int tcpLen = 0;
	private boolean loggedFirstData = false;

	/** Login state machine. */
	public enum MwcLoginState {
		SEND_HANDSHAKE,
		SEND_LOGIN_START,
		AWAIT_LOGIN,
		PLAY
	}

	private MwcLoginState loginState = MwcLoginState.SEND_HANDSHAKE;
	private final String mcHost;
	private final int mcPort;
	private final String username;
	private int compressionThreshold = -1;
	private boolean loginFinished = false;
	private IChatComponent disconnectReason = null;

	/** Yggdrasil auth state for online-mode servers. */
	private MwcAuthState authState = null;
	private MwcAuthDriver authDriver = null;

	/** AES shared secret for encrypted play mode (online servers). */
	private byte[] sharedSecret = null;
	/** Stateful AES-128/CFB8 cipher for the inbound byte stream. */
	private MwcCfb8 decryptCipher = null;
	/** Stateful AES-128/CFB8 cipher for the outbound byte stream. */
	private MwcCfb8 encryptCipher = null;
	/** Whether the tunnel byte stream is encrypted (online mode). */
	private boolean encrypted = false;
	/** Real UUID from Login Success (online mode). */
	private String lastLoginUuid = null;
	/** Real username from Login Success (online mode). */
	private String lastLoginName = null;

	public String getLastLoginUuid() {
		return lastLoginUuid;
	}

	public String getLastLoginName() {
		return lastLoginName;
	}

	public MwcProxyNetworkManager(IWebSocketClient webSocketClient, String mcHost, int mcPort, String username) {
		super(webSocketClient.getCurrentURI());
		this.webSocketClient = webSocketClient;
		this.mcHost = mcHost;
		this.mcPort = mcPort;
		this.username = username;
	}

	public MwcLoginState getLoginState() {
		return loginState;
	}

	/** True once any binary data arrived from the server through the tunnel. */
	public boolean hasReceivedTunnelData() {
		return loggedFirstData;
	}

	/** Human-readable login stage for timeout/error messages and progress logs. */
	public String describeLoginStage() {
		switch (loginState) {
			case SEND_HANDSHAKE:
				return "preparing vanilla handshake";
			case SEND_LOGIN_START:
				return "sending login start";
			case AWAIT_LOGIN:
				if (authState != null) {
					return authState.sharedSecret != null
							? "encryption response sent, awaiting Login Success"
							: "online-mode (Yggdrasil) authentication in progress";
				}
				return "login start sent, awaiting server reply";
			case PLAY:
				return "play";
			default:
				return loginState.name();
		}
	}

	public boolean isLoginFinished() {
		return loginFinished;
	}

	public IChatComponent getDisconnectReason() {
		return disconnectReason;
	}

	@Override
	public void connect() {
		// WS already opened by PlatformNetworking; login packets are flushed on first tick.
	}

	@Override
	public EnumEaglerConnectionState getConnectStatus() {
		if (disconnectReason != null) return EnumEaglerConnectionState.FAILED;
		return webSocketClient.getState();
	}

	@Override
	public void closeChannel(IChatComponent reason) {
		if (disconnectReason == null) disconnectReason = reason;
		try { webSocketClient.close(); } catch (Throwable t) {}
		if (nethandler != null && !clientDisconnected) {
			nethandler.onDisconnect(reason);
		}
		clientDisconnected = true;
	}

	/**
	 * Called once per client tick during LOGIN. Sends handshake + login start,
	 * then consumes login-phase packets until Login Success (0x02) switches us
	 * to PLAY.
	 */
	public void tickLogin() throws IOException {
		if (loginState == MwcLoginState.SEND_HANDSHAKE) {
			sendVanillaHandshake(mcHost, mcPort, 2); // 2 = login
			loginState = MwcLoginState.SEND_LOGIN_START;
			logger.info("Sent vanilla handshake (protocol 47, login) for {}:{}", mcHost, mcPort);
		}
		if (loginState == MwcLoginState.SEND_LOGIN_START) {
			sendLoginStart(username);
			loginState = MwcLoginState.AWAIT_LOGIN;
			packetState = EnumConnectionState.LOGIN;
			logger.info("Sent login start for \"{}\"", username);
		}
		if (loginState == MwcLoginState.AWAIT_LOGIN) {
			pumpTcpFrames(true);
		}
	}

	@Override
	public void processReceivedPackets() throws IOException {
		if (nethandler == null) return;
		if (!loginFinished) {
			tickLogin();
			return;
		}
		pumpTcpFrames(false);
	}

	/**
	 * The tunnel WS has closed. A server {@code Login Disconnect} (or a
	 * {@code proxy-shutdown:} notice) frequently arrives in the same batch as the
	 * close event, so the frames are still sitting in the WS receive buffer while
	 * {@link IWebSocketClient#getState()} already reports CLOSED. Drain whatever
	 * is buffered so the real reason is surfaced instead of a misleading
	 * "closed without sending a single byte" message.
	 *
	 * @return true if a disconnect reason was set by the drained packets
	 */
	boolean drainBufferedOnClose() {
		try {
			if (webSocketClient.availableStringFrames() <= 0 && webSocketClient.availableBinaryFrames() <= 0) {
				return disconnectReason != null;
			}
			if (loginFinished) {
				pumpTcpFrames(false);
			} else {
				pumpTcpFrames(true);
			}
		} catch (Throwable t) {
			// A Login Disconnect / proxy-shutdown sets disconnectReason then throws
			// to unwind the packet loop - that is expected.
		}
		return disconnectReason != null;
	}

	/**
	 * Drain WS binary frames into tcpBuf, extract complete length-prefixed
	 * packets, strip length (+ decompress if threshold negotiated), dispatch.
	 *
	 * @param loginOnly if true, dispatch via handleLoginPacket, else via play dispatcher
	 */
	protected void pumpTcpFrames(boolean loginOnly) throws IOException {
		if (webSocketClient.availableStringFrames() > 0) {
			String s = null;
			try {
				IWebSocketFrame sf = webSocketClient.getNextStringFrame();
				if (sf != null) s = sf.getString();
			} catch (Throwable t) {}
			if (s != null && s.startsWith("proxy-shutdown:")) {
				disconnectReason = new ChatComponentText(ConnectErrorCodes.mwc(ConnectErrorCodes.Play.PROXY_SHUTDOWN,
						s.substring("proxy-shutdown:".length())));
				doClientDisconnect(disconnectReason);
				throw new IOException(disconnectReason.getUnformattedText());
			}
			webSocketClient.clearStringFrames();
		}
		List<IWebSocketFrame> frames = webSocketClient.getNextBinaryFrames();
		if (frames == null || frames.isEmpty()) return;
		int totalBytes = 0;
		for (int fi = 0; fi < frames.size(); ++fi) {
			byte[] chunk = frames.get(fi).getByteArray();
			totalBytes += chunk.length;
			if (encrypted && decryptCipher != null) {
				// CFB8 is a stream cipher; decrypt in arrival order, before framing,
				// because vanilla encrypts the VarInt length prefixes too.
				decryptCipher.decrypt(chunk);
			}
			ensureCapacity(tcpLen + chunk.length);
			System.arraycopy(chunk, 0, tcpBuf, tcpLen, chunk.length);
			tcpLen += chunk.length;
		}
		if (!loggedFirstData) {
			loggedFirstData = true;
			logger.info("First data from {}:{} via tunnel: {} bytes in {} frame(s)", mcHost, mcPort, totalBytes,
					frames.size());
		}
		int offset = 0;
		while (true) {
			if (tcpLen - offset < 1) break;
			int[] lenVar = readVarInt(tcpBuf, offset, tcpLen - offset);
			if (lenVar == null) break; // incomplete length
			int pktLen = lenVar[0];
			int lenBytes = lenVar[1];
			if (pktLen < 0 || pktLen > 2097151) {
				throw new IOException(ConnectErrorCodes.mwc(ConnectErrorCodes.Play.FRAME,
						"Bad TCP frame length " + pktLen + ". Proxy or server sent garbage; retry."));
			}
			if (tcpLen - offset - lenBytes < pktLen) break; // incomplete packet
			byte[] pkt = new byte[pktLen];
			System.arraycopy(tcpBuf, offset + lenBytes, pkt, 0, pktLen);
			offset += lenBytes + pktLen;
			++debugPacketCounter;
			if (compressionThreshold >= 0) {
				pkt = handleDecompression(pkt);
				if (pkt == null) continue;
			}
			if (loginOnly) {
				handleLoginPacket(pkt);
				if (loginFinished) break; // remaining bytes belong to PLAY, re-parse next tick
			} else {
				dispatchPlayPacket(pkt);
			}
		}
		if (offset > 0) {
			int remaining = tcpLen - offset;
			if (remaining > 0) System.arraycopy(tcpBuf, offset, tcpBuf, 0, remaining);
			tcpLen = remaining;
		}
	}

	protected void handleLoginPacket(byte[] pkt) throws IOException {
		PacketBuffer input = toBuffer(pkt);
		int pktId = input.readVarIntFromBuffer();
		switch (pktId) {
			case 0x00: { // Disconnect (login)
				String reasonJson = input.readStringFromBuffer(32767);
				IChatComponent reason;
				try {
					reason = IChatComponent.Serializer.jsonToComponent(reasonJson);
				} catch (Throwable t) {
					reason = new ChatComponentText(reasonJson);
				}
				String plain;
				try {
					plain = reason.getUnformattedText();
				} catch (Throwable t) {
					plain = reasonJson;
				}
				disconnectReason = new ChatComponentText(ConnectErrorCodes.mwc(ConnectErrorCodes.Play.KICK,
						plain + "\n(Version mismatch? This client is 1.8. If the server needs 1.13+, route through"
								+ " ViaProxy or use web-client-next.)"));
				logger.error("Login disconnect from server: {}", plain);
				doClientDisconnect(disconnectReason);
				throw new IOException(disconnectReason.getUnformattedText());
			}
			case 0x01: { // Encryption Request -> online mode, use yggdrasil auth
				if (authState != null) {
					// Already authenticating, ignore duplicate
					return;
				}
				// Read Encryption Request fields:
				// - serverId (String)
				// - publicKey (byte[])
				// - verifyToken (byte[])
				String serverId = input.readStringFromBuffer(32767);
				int pubKeyLen = input.readVarIntFromBuffer();
				byte[] publicKey = new byte[pubKeyLen];
				input.readBytes(publicKey);
				int tokenLen = input.readVarIntFromBuffer();
				byte[] verifyToken = new byte[tokenLen];
				input.readBytes(verifyToken);

				// Start yggdrasil auth flow
				authState = new MwcAuthState(serverId, publicKey, verifyToken);
				authState.proxyBase = MwcProxyTokenFetch.httpBaseFromSocketUrl(webSocketClient.getCurrentURI());
				authDriver = MwcAuthDriver.get();
				authDriver.begin(authState, new MwcAuthDriver.AuthCallback() {
					@Override
					public void onSuccess(MwcAuthState state) {
						// Auth succeeded - enable encryption NOW: the server turns its cipher
						// on right after validating our response, so Set Compression and
						// Login Success arrive encrypted.
						if (state.sharedSecret != null) {
							enableEncryption(state.sharedSecret);
						}
						// Auth succeeded - send Encryption Response
						try {
							sendEncryptionResponse(state);
						} catch (IOException e) {
							logger.error("Failed to send encryption response", e);
							disconnectReason = new ChatComponentText(ConnectErrorCodes.mwc(
									ConnectErrorCodes.Play.ENCRYPTION,
									"Failed to send encryption response: " + e.getMessage()
											+ "\nFix: retry; a VPN/antivirus/firewall may be breaking the connection."));
							doClientDisconnect(disconnectReason);
						}
					}

					@Override
					public void onFailure(MwcAuthState state, String reason) {
						logger.error("Online authentication failed: {}", reason);
						disconnectReason = new ChatComponentText(ConnectErrorCodes.mwc(
								ConnectErrorCodes.Play.AUTH,
								"Online authentication failed: " + reason
										+ "\nFix: if the server is cracked/offline-mode, set it to offline mode;"
										+ " otherwise check your network and retry."));
						doClientDisconnect(disconnectReason);
					}
				});
				return;
			}
			case 0x02: { // Login Success -> switch to PLAY
				// UUID + username strings
				String uuid = null;
				String name = null;
				try { uuid = input.readStringFromBuffer(36); } catch (Throwable t) {}
				try { name = input.readStringFromBuffer(16); } catch (Throwable t) {}
				loginFinished = true;
				loginState = MwcLoginState.PLAY;
				packetState = EnumConnectionState.PLAY;
				setPluginInfo("vanilla-via-mwc", "1.8.8", null);
				// Enable AES/CTR encryption if yggdrasil auth set it up (normally
				// already enabled in onSuccess; this is a late-secret fallback only —
				// never reset counters here, the stream position must be preserved)
				if (!encrypted && authState != null && authState.sharedSecret != null) {
					enableEncryption(authState.sharedSecret);
				}
				// Store real session data for offline/online UUID
				if (uuid != null && name != null) {
					this.lastLoginUuid = uuid;
					this.lastLoginName = name;
				}
				return;
			}
			case 0x03: { // Set Compression (login)
				int threshold = input.readVarIntFromBuffer();
				if (threshold >= 0) {
					this.compressionThreshold = threshold;
				}
				return;
			}
			default:
				logger.warn("Ignoring unknown login packet 0x{}", Integer.toHexString(pktId));
				return;
		}
	}

	protected void dispatchPlayPacket(byte[] pkt) throws IOException {
		if (injectedController != null && injectedController.handlePacket(pkt, 0)) return;
		PacketBuffer input = toBuffer(pkt);
		int pktId = input.readVarIntFromBuffer();
		Packet packet;
		try {
			packet = packetState.getPacket(EnumPacketDirection.CLIENTBOUND, pktId);
		} catch (IllegalAccessException | InstantiationException ex) {
			throw new IOException("Received packet type " + pktId + " which is invalid!");
		}
		if (packet == null) {
			throw new IOException("Received packet type " + pktId + " undefined in state " + packetState);
		}
		try {
			packet.readPacketData(input);
		} catch (Throwable t) {
			throw new IOException("Failed to read packet '" + packet.getClass().getSimpleName() + "'", t);
		}
		try {
			packet.processPacket(nethandler);
		} catch (Throwable t) {
			logger.error("Failed to process {}! Skipped.", packet.getClass().getSimpleName());
			logger.error(t);
		}
	}

	@Override
	public void sendPacket(Packet pkt) {
		if (!isChannelOpen()) {
			logger.error("Packet sent on closed MWC connection: {}", pkt.getClass().getSimpleName());
			return;
		}
		int i;
		try {
			i = packetState.getPacketId(EnumPacketDirection.SERVERBOUND, pkt);
		} catch (Throwable t) {
			logger.error("Incorrect packet for state: {}", pkt.getClass().getSimpleName());
			return;
		}
		temporaryBuffer.clear();
		temporaryBuffer.writeVarIntToBuffer(i);
		try {
			pkt.writePacketData(temporaryBuffer);
		} catch (IOException ex) {
			logger.error("Failed to write packet {}!", pkt.getClass().getSimpleName());
			return;
		}
		byte[] payload = temporaryBufferToBytes();
		sendRawPlayPayload(payload);
	}

	@Override
	public void injectRawFrame(byte[] data) {
		// Custom payload channel: already a complete packet payload (id + body), needs length prefix.
		sendRawPlayPayload(data);
	}

	public void sendRawPlayPayload(byte[] payload) {
		if (!isChannelOpen()) return;
		if (compressionThreshold >= 0) {
			payload = compressOutboundFrame(payload);
		}
		byte[] lenPrefix = encodeVarInt(payload.length);
		byte[] out = new byte[lenPrefix.length + payload.length];
		System.arraycopy(lenPrefix, 0, out, 0, lenPrefix.length);
		System.arraycopy(payload, 0, out, lenPrefix.length, payload.length);
		if (encrypted && encryptCipher != null) {
			// Encrypt the length prefix and payload together as one stream chunk.
			encryptCipher.encrypt(out);
		}
		webSocketClient.send(out);
	}

	/**
	 * Turn on transport encryption. Both ciphers start at IV = shared secret and
	 * must advance over every subsequent byte, matching the server exactly.
	 */
	private void enableEncryption(byte[] secret) {
		this.sharedSecret = secret;
		this.decryptCipher = MwcCrypto.get().createCfb8(secret);
		this.encryptCipher = MwcCrypto.get().createCfb8(secret);
		this.encrypted = true;
		logger.info("MWC encryption enabled (AES-128/CFB8, shared secret={} bytes)", secret.length);
	}

	@Override
	public boolean checkDisconnected() {
		if (disconnectReason != null) {
			doClientDisconnect(disconnectReason);
			return true;
		}
		if (webSocketClient.isClosed()) {
			try { processReceivedPackets(); } catch (IOException e) {}
			if (disconnectReason != null) {
				doClientDisconnect(disconnectReason);
			} else {
				logger.error("WebSocket closed unexpectedly (proxy or server dropped the socket)");
				doClientDisconnect(new ChatComponentText(ConnectErrorCodes.mwc(ConnectErrorCodes.Play.CLOSED,
						"Connection closed. The proxy or server dropped the socket; retry.")));
			}
			return true;
		}
		return false;
	}

	// ---------- vanilla handshake/login writers ----------

	protected void sendVanillaHandshake(String host, int port, int nextState) {
		PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
		buf.writeVarIntToBuffer(0x00);
		buf.writeVarIntToBuffer(47); // MC 1.8.x
		writeMCString(buf, host);
		buf.writeShort(port);
		buf.writeVarIntToBuffer(nextState);
		webSocketClient.send(lengthPrefix(buf));
	}

	protected void sendLoginStart(String name) {
		PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
		buf.writeVarIntToBuffer(0x00);
		writeMCString(buf, name);
		webSocketClient.send(lengthPrefix(buf));
	}

	private static void writeMCString(PacketBuffer buf, String s) {
		byte[] b;
		try { b = s.getBytes("UTF-8"); } catch (Throwable t) { b = s.getBytes(); }
		buf.writeVarIntToBuffer(b.length);
		buf.writeBytes(b);
	}

	private static byte[] lengthPrefix(PacketBuffer buf) {
		int len = buf.writerIndex();
		byte[] payload = new byte[len];
		buf.getBytes(0, payload);
		byte[] prefix = encodeVarInt(len);
		byte[] out = new byte[prefix.length + len];
		System.arraycopy(prefix, 0, out, 0, prefix.length);
		System.arraycopy(payload, 0, out, prefix.length, len);
		buf.clear();
		return out;
	}

	private byte[] temporaryBufferToBytes() {
		int len = temporaryBuffer.writerIndex();
		byte[] bytes = new byte[len];
		temporaryBuffer.getBytes(0, bytes);
		temporaryBuffer.clear();
		return bytes;
	}

	// ---------- helpers ----------

	private static PacketBuffer toBuffer(byte[] pkt) {
		ByteBuf nettyBuffer = Unpooled.buffer(pkt, pkt.length);
		nettyBuffer.writerIndex(pkt.length);
		return new PacketBuffer(nettyBuffer);
	}

	private void ensureCapacity(int needed) {
		if (needed <= tcpBuf.length) return;
		int next = tcpBuf.length * 2;
		while (next < needed) next *= 2;
		byte[] nb = new byte[next];
		System.arraycopy(tcpBuf, 0, nb, 0, tcpLen);
		tcpBuf = nb;
	}

	/** @return int[]{value, bytesRead} or null if incomplete. */
	private static int[] readVarInt(byte[] buf, int off, int avail) {
		int numRead = 0;
		int result = 0;
		while (true) {
			if (numRead + 1 > avail) return null;
			byte b = buf[off + numRead];
			int value = b & 0x7F;
			result |= (value << (7 * numRead));
			++numRead;
			if (numRead > 5) throw new RuntimeException("VarInt too big");
			if ((b & 0x80) == 0) break;
		}
		return new int[]{result, numRead};
	}

	private static byte[] encodeVarInt(int value) {
		byte[] tmp = new byte[5];
		int n = 0;
		while (true) {
			if ((value & ~0x7F) == 0) { tmp[n++] = (byte) value; break; }
			tmp[n++] = (byte) ((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		byte[] out = new byte[n];
		System.arraycopy(tmp, 0, out, 0, n);
		return out;
	}

	/**
	 * Decompress a single PLAY packet if compression is negotiated.
	 * Eaglercraft's bundled inflater: uses java.util.zip (available on
	 * TeaVM/JVM). Returns the decompressed packet payload (id + body),
	 * or null if the packet was malformed.
	 */
	/**
	 * Vanilla 1.8 compression framing for an outbound packet payload: a 0 marker
	 * byte plus the raw payload when below the threshold, otherwise
	 * VarInt(uncompressed size) followed by a zlib stream. The server's
	 * decompressor expects exactly this format on serverbound frames too.
	 */
	private byte[] compressOutboundFrame(byte[] payload) {
		if (payload.length < compressionThreshold) {
			byte[] out = new byte[payload.length + 1];
			out[0] = 0;
			System.arraycopy(payload, 0, out, 1, payload.length);
			return out;
		}
		java.util.zip.Deflater deflater = new java.util.zip.Deflater();
		try {
			deflater.setInput(payload);
			deflater.finish();
			byte[] comp = new byte[Math.max(payload.length + 64, 128)];
			int total = 0;
			while (!deflater.finished()) {
				if (total + 256 >= comp.length) {
					comp = java.util.Arrays.copyOf(comp, comp.length << 1);
				}
				total += deflater.deflate(comp, total, comp.length - total);
			}
			byte[] len = encodeVarInt(payload.length);
			byte[] out = new byte[len.length + total];
			System.arraycopy(len, 0, out, 0, len.length);
			System.arraycopy(comp, 0, out, len.length, total);
			return out;
		} finally {
			deflater.end();
		}
	}

	private byte[] handleDecompression(byte[] pkt) throws IOException {
		int[] uncompressedLen = readVarInt(pkt, 0, pkt.length);
		if (uncompressedLen == null) throw new IOException(ConnectErrorCodes.mwc(ConnectErrorCodes.Play.COMPRESSION,
				"Bad compressed frame header. Retry; update client if it repeats."));
		int dataLen = uncompressedLen[0];
		int header = uncompressedLen[1];
		if (dataLen == 0) {
			byte[] out = new byte[pkt.length - header];
			System.arraycopy(pkt, header, out, 0, out.length);
			return out;
		}
		if (dataLen < compressionThreshold) throw new IOException(ConnectErrorCodes.mwc(ConnectErrorCodes.Play.COMPRESSION,
				"Server broke the compression threshold. Retry."));
		byte[] comp = new byte[pkt.length - header];
		System.arraycopy(pkt, header, comp, 0, comp.length);
		java.util.zip.Inflater inflater = new java.util.zip.Inflater();
		try {
			inflater.setInput(comp);
			byte[] out = new byte[dataLen];
			int total = 0;
			while (total < dataLen) {
				int n = inflater.inflate(out, total, dataLen - total);
				if (n == 0) {
					if (inflater.finished() || inflater.needsDictionary()) break;
					if (inflater.needsInput()) {
						throw new IOException(ConnectErrorCodes.mwc(ConnectErrorCodes.Play.COMPRESSION,
								"Truncated compressed packet from server. Retry."));
					}
				}
				total += n;
			}
			if (total != dataLen) throw new IOException(ConnectErrorCodes.mwc(ConnectErrorCodes.Play.COMPRESSION,
					"Decompressed size mismatch (" + total + "/" + dataLen + "). Retry."));
			return out;
		} catch (java.util.zip.DataFormatException e) {
			throw new IOException(ConnectErrorCodes.mwc(ConnectErrorCodes.Play.COMPRESSION,
					"Decompression failed. Retry; update client if it repeats."), e);
		} finally {
			inflater.end();
		}
	}

	/** Parse "host:port" with default 25565. Shared by GUI + query-param auto-join. */
	public static String[] splitHostPort(String ip, int defaultPort) {
		if (ip == null) return new String[]{"localhost", String.valueOf(defaultPort)};
		ip = ip.trim();
		int colon = ip.lastIndexOf(':');
		if (colon > 0 && colon < ip.length() - 1 && ip.indexOf(':') == colon) {
			try {
				int p = Integer.parseInt(ip.substring(colon + 1));
				return new String[]{ip.substring(0, colon), String.valueOf(p)};
			} catch (NumberFormatException e) {
				return new String[]{ip, String.valueOf(defaultPort)};
			}
		}
		return new String[]{ip, String.valueOf(defaultPort)};
	}

	/** Send Encryption Response (0x01) to complete yggdrasil auth. */
	private void sendEncryptionResponse(MwcAuthState state) throws IOException {
		PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
		buf.writeVarIntToBuffer(0x01); // Encryption Response ID
		writeByteArray(buf, state.encryptedSharedSecret);
		writeByteArray(buf, state.encryptedVerifyToken);
		byte[] payload = new byte[buf.writerIndex()];
		buf.getBytes(0, payload);
		buf.clear();
		byte[] len = encodeVarInt(payload.length);
		byte[] out = new byte[len.length + payload.length];
		System.arraycopy(len, 0, out, 0, len.length);
		System.arraycopy(payload, 0, out, len.length, payload.length);
		webSocketClient.send(out);
	}

	private static void writeByteArray(PacketBuffer buf, byte[] data) {
		buf.writeVarIntToBuffer(data.length);
		buf.writeBytes(data);
	}
}
