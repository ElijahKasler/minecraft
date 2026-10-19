package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import java.util.LinkedList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import net.lax1dude.eaglercraft.v1_8.Base64;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.EnumServerRateLimit;
import net.lax1dude.eaglercraft.v1_8.internal.IServerQuery;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.internal.QueryResponse;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.opengl.ImageData;
import net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes;

/**
 * Server-list MOTD ping for vanilla servers reached through the mwc proxy.
 *
 * <p>Flow (all off the render thread): fetch a proxy token, open the proxy
 * socket, send a vanilla status handshake (protocol 47, next state 1) plus an
 * empty status request, parse the JSON response and publish it in the Eagler
 * {@link QueryResponse} shape so {@code ServerData.setMOTDFromQuery} works
 * unchanged.</p>
 */
public class MwcProxyStatusQuery implements IServerQuery {

	static final Logger logger = LogManager.getLogger("MwcProxyStatusQuery");

	private static final long OVERALL_TIMEOUT = 15000l;

	private final List<QueryResponse> queryResponses = new LinkedList<>();
	private final String proxyBase;
	private final String host;
	private final int port;

	private volatile boolean open = true;
	private volatile boolean alive = true;
	private volatile boolean done = false;
	private volatile byte[] pendingBinary = null;

	public static MwcProxyStatusQuery dispatch(String proxyBase, String host, int port) {
		return new MwcProxyStatusQuery(proxyBase, host, port);
	}

	private MwcProxyStatusQuery(String proxyBase, String host, int port) {
		this.proxyBase = proxyBase;
		this.host = host;
		this.port = port;
		Thread worker = new Thread(new Runnable() {
			@Override
			public void run() {
				runQuery();
			}
		}, "MwcStatusQuery-" + host + ":" + port);
		worker.setDaemon(true);
		try {
			worker.start();
		} catch (Throwable t) {
			logger.warn("Could not start status query thread: {}", t.toString());
			done = true;
			open = false;
		}
	}

	private void runQuery() {
		IWebSocketClient ws = null;
		try {
			MwcProxyTokenFetch.TokenResult token = MwcProxyTokenFetch.requestToken(proxyBase, host, port, false);
			if (!token.ok) {
				logger.info("Status query token failed for {}:{} - {}", host, port, token.error);
				publishError(token.error, host, port);
				return;
			}
			long deadline = EagRuntime.steadyTimeMillis() + OVERALL_TIMEOUT;
			ws = PlatformNetworking.openWebSocket(token.socketUrl);
			if (ws == null) {
				logger.info("Status query could not open socket to {}", token.socketUrl);
				publishError(ConnectErrorCodes.mwc(ConnectErrorCodes.Token.SOCKET,
						"Proxy token was ok but the WebSocket could not be opened.\n"
								+ "Fix: check the proxy process and its logs."), host, port);
				return;
			}
			while (ws.getState() != EnumEaglerConnectionState.CONNECTED) {
				EnumEaglerConnectionState st = ws.getState();
				if (st == EnumEaglerConnectionState.FAILED || st == EnumEaglerConnectionState.CLOSED) {
					logger.info("Status query socket closed before open for {}:{}", host, port);
					publishError(ConnectErrorCodes.mwc(ConnectErrorCodes.Token.SOCKET,
							"Proxy closed the socket before connect finished.\n"
									+ "Fix: check the proxy and target server logs."), host, port);
					return;
				}
				if (EagRuntime.steadyTimeMillis() > deadline) {
					logger.info("Status query socket timed out for {}:{}", host, port);
					publishError(ConnectErrorCodes.mwc(ConnectErrorCodes.Token.TIMEOUT,
							"Timed out waiting for the proxy socket to open.\n"
									+ "Fix: the proxy may be overloaded; retry or check its load."), host, port);
					return;
				}
				sleep(25);
			}
			long pingStart = EagRuntime.steadyTimeMillis();
			ws.send(statusHandshakePacket(host, port));
			ws.send(statusRequestPacket());

			byte[] buf = new byte[8192];
			int bufLen = 0;
			String json = null;
			while (EagRuntime.steadyTimeMillis() < deadline) {
				List<IWebSocketFrame> frames = ws.getNextBinaryFrames();
				if (frames != null) {
					for (int i = 0; i < frames.size(); ++i) {
						byte[] chunk = frames.get(i).getByteArray();
						if (bufLen + chunk.length > buf.length) {
							byte[] nb = new byte[Math.max(buf.length * 2, bufLen + chunk.length)];
							System.arraycopy(buf, 0, nb, 0, bufLen);
							buf = nb;
						}
						System.arraycopy(chunk, 0, buf, bufLen, chunk.length);
						bufLen += chunk.length;
					}
				}
				json = tryParseStatusResponse(buf, bufLen);
				if (json != null) {
					break;
				}
				if (ws.isClosed()) {
					break;
				}
				sleep(25);
			}
			long ping = EagRuntime.steadyTimeMillis() - pingStart;
			if (ping < 1l) {
				ping = 1l;
			}
			if (json != null) {
				publish(json, ping);
			} else {
				logger.info("Status query for {}:{} got no response", host, port);
				publishError(ConnectErrorCodes.mwc(ConnectErrorCodes.Token.SOCKET,
						"Server sent no status response (socket closed or " + (EagRuntime.steadyTimeMillis() > deadline
								? "15s timeout hit"
								: "server does not answer status pings")
								+ ").\nFix: confirm the address/port and that it is a 1.8-compatible server."),
						host, port);
			}
		} catch (Throwable t) {
			logger.warn("Status query for {}:{} failed: {}", host, port, t.toString());
			publishError(ConnectErrorCodes.mwc(ConnectErrorCodes.Token.UNREACHABLE,
					"Status ping crashed: " + t + "\nFix: retry; check the proxy process logs."), host, port);
		} finally {
			if (ws != null) {
				try {
					ws.close();
				} catch (Throwable t) {
				}
			}
			done = true;
			open = false;
		}
	}

	/**
	 * @return the status JSON string if buf holds one complete length-prefixed
	 *         status response packet, else null (incomplete).
	 */
	private static String tryParseStatusResponse(byte[] buf, int len) {
		if (len < 1) {
			return null;
		}
		int[] lenVar = readVarInt(buf, 0, len);
		if (lenVar == null) {
			return null;
		}
		int pktLen = lenVar[0];
		int lenBytes = lenVar[1];
		if (pktLen < 0 || pktLen > 2097151) {
			return null;
		}
		if (len - lenBytes < pktLen) {
			return null;
		}
		int[] idVar = readVarInt(buf, lenBytes, len - lenBytes);
		if (idVar == null || idVar[0] != 0x00) {
			return null;
		}
		int pos = lenBytes + idVar[1];
		int[] strLenVar = readVarInt(buf, pos, len - pos);
		if (strLenVar == null) {
			return null;
		}
		int strLen = strLenVar[0];
		pos += strLenVar[1];
		if (strLen < 0 || len - pos < strLen) {
			return null;
		}
		try {
			return new String(buf, pos, strLen, "UTF-8");
		} catch (Throwable t) {
			return new String(buf, pos, strLen);
		}
	}

	/** Convert vanilla status JSON to the MOTD QueryResponse shape. */
	private void publish(String vanillaJson, long ping) {
		try {
			JSONObject vanilla = new JSONObject(vanillaJson);
			String versionName = "vanilla";
			JSONObject ver = vanilla.optJSONObject("version");
			if (ver != null) {
				versionName = ver.optString("name", "vanilla");
			}
			String desc = "";
			Object d = vanilla.opt("description");
			if (d instanceof String) {
				desc = (String) d;
			} else if (d instanceof JSONObject) {
				desc = flattenChat((JSONObject) d, null);
			} else if (d instanceof JSONArray) {
				desc = flattenChatArray((JSONArray) d, null);
			}
			String[] lines = desc.split("\n", 2);
			int online = 0;
			int max = 0;
			JSONArray sample = null;
			JSONObject players = vanilla.optJSONObject("players");
			if (players != null) {
				online = players.optInt("online", 0);
				max = players.optInt("max", 0);
				sample = players.optJSONArray("sample");
			}
			JSONArray names = new JSONArray();
			if (sample != null) {
				for (int i = 0; i < sample.length() && i < 12; ++i) {
					JSONObject p = sample.optJSONObject(i);
					if (p != null) {
						names.put(p.optString("name", ""));
					}
				}
			}
			JSONObject motdData = new JSONObject();
			motdData.put("motd", new JSONArray(new String[] { lines[0], lines.length > 1 ? lines[1] : "" }));
			motdData.put("online", online);
			motdData.put("max", max);
			motdData.put("players", names);
			// Vanilla favicon -> Eagler icon packet (64x64 RGBA, 16384 bytes)
			byte[] icon = null;
			String favicon = vanilla.optString("favicon", null);
			if (favicon != null && favicon.length() > 0) {
				icon = decodeFavicon(favicon);
			}
			motdData.put("icon", icon != null);
			JSONObject out = new JSONObject();
			out.put("type", "MOTD");
			out.put("data", motdData);
			out.put("vers", versionName);
			out.put("brand", "vanilla");
			out.put("name", host + ":" + port);
			out.put("time", EagRuntime.steadyTimeMillis());
			// Publish the binary icon before the JSON so ServerList sees both together.
			if (icon != null) {
				pendingBinary = icon;
			}
			synchronized (queryResponses) {
				queryResponses.add(new QueryResponse(out, ping));
			}
		} catch (Throwable t) {
			logger.warn("Could not decode vanilla status response for {}:{}", host, port);
			logger.warn(t.toString());
		}
	}

	/**
	 * Flatten a vanilla chat component to a MOTD string that keeps Minecraft
	 * formatting codes so custom colors/styles render in the server list.
	 */
	private static String flattenChat(JSONObject obj, ChatStyle parent) {
		ChatStyle st = ChatStyle.inherit(parent);
		applyStyle(obj, st);
		StringBuilder sb = new StringBuilder();
		sb.append(st.codes());
		sb.append(obj.optString("text", ""));
		JSONArray extra = obj.optJSONArray("extra");
		if (extra != null) {
			sb.append(flattenChatArray(extra, st));
		}
		JSONArray with = obj.optJSONArray("with");
		if (with != null) {
			sb.append(flattenChatArray(with, st));
		}
		return sb.toString();
	}

	private static String flattenChatArray(JSONArray arr, ChatStyle parent) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < arr.length(); ++i) {
			Object o = arr.opt(i);
			if (o instanceof JSONObject) {
				sb.append(flattenChat((JSONObject) o, parent));
			} else if (o instanceof String) {
				ChatStyle st = ChatStyle.inherit(parent);
				sb.append(st.codes()).append((String) o);
			}
		}
		return sb.toString();
	}

	private static void applyStyle(JSONObject o, ChatStyle st) {
		if (o.has("color")) {
			String c = colorCode(o.optString("color", null));
			if (c != null) {
				st.color = c;
			}
		}
		if (o.has("bold")) {
			st.bold = o.optBoolean("bold", st.bold);
		}
		if (o.has("italic")) {
			st.italic = o.optBoolean("italic", st.italic);
		}
		if (o.has("underlined")) {
			st.underlined = o.optBoolean("underlined", st.underlined);
		}
		if (o.has("strikethrough")) {
			st.strikethrough = o.optBoolean("strikethrough", st.strikethrough);
		}
		if (o.has("obfuscated")) {
			st.obfuscated = o.optBoolean("obfuscated", st.obfuscated);
		}
	}

	private static final class ChatStyle {
		String color = null;
		boolean bold = false;
		boolean italic = false;
		boolean underlined = false;
		boolean strikethrough = false;
		boolean obfuscated = false;

		static ChatStyle inherit(ChatStyle p) {
			ChatStyle s = new ChatStyle();
			if (p != null) {
				s.color = p.color;
				s.bold = p.bold;
				s.italic = p.italic;
				s.underlined = p.underlined;
				s.strikethrough = p.strikethrough;
				s.obfuscated = p.obfuscated;
			}
			return s;
		}

		String codes() {
			StringBuilder sb = new StringBuilder();
			sb.append('\u00a7').append('r');
			if (color != null) {
				sb.append(color);
			}
			if (obfuscated) {
				sb.append("\u00a7k");
			}
			if (bold) {
				sb.append("\u00a7l");
			}
			if (strikethrough) {
				sb.append("\u00a7m");
			}
			if (underlined) {
				sb.append("\u00a7n");
			}
			if (italic) {
				sb.append("\u00a7o");
			}
			return sb.toString();
		}
	}

	private static String colorCode(String name) {
		if (name == null) {
			return null;
		}
		switch (name.toLowerCase()) {
			case "black": return "\u00a70";
			case "dark_blue": return "\u00a71";
			case "dark_green": return "\u00a72";
			case "dark_aqua": return "\u00a73";
			case "dark_red": return "\u00a74";
			case "dark_purple": return "\u00a75";
			case "gold": return "\u00a76";
			case "gray": case "grey": return "\u00a77";
			case "dark_gray": case "dark_grey": return "\u00a78";
			case "blue": return "\u00a79";
			case "green": return "\u00a7a";
			case "aqua": return "\u00a7b";
			case "red": return "\u00a7c";
			case "light_purple": return "\u00a7d";
			case "yellow": return "\u00a7e";
			case "white": return "\u00a7f";
			case "reset": return "\u00a7r";
			default: return hexColorCode(name);
		}
	}

	/** Map a #rrggbb hex color to the nearest of the 16 legacy colors. */
	private static String hexColorCode(String name) {
		if (name.isEmpty() || name.charAt(0) != '#' || name.length() != 7) {
			return null;
		}
		try {
			int r = Integer.parseInt(name.substring(1, 3), 16);
			int g = Integer.parseInt(name.substring(3, 5), 16);
			int b = Integer.parseInt(name.substring(5, 7), 16);
			int best = 0;
			long bestDist = Long.MAX_VALUE;
			for (int i = 0; i < HEX_COLORS.length; ++i) {
				int cr = (HEX_COLORS[i] >> 16) & 0xFF;
				int cg = (HEX_COLORS[i] >> 8) & 0xFF;
				int cb = HEX_COLORS[i] & 0xFF;
				long dr = r - cr, dg = g - cg, db = b - cb;
				long dist = dr * dr + dg * dg + db * db;
				if (dist < bestDist) {
					bestDist = dist;
					best = i;
				}
			}
			return "\u00a7" + "0123456789abcdef".charAt(best);
		} catch (Throwable t) {
			return null;
		}
	}

	private static final int[] HEX_COLORS = { 0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00,
			0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };

	/** Decode a vanilla {@code data:image/png;base64,...} favicon to a 64x64 RGBA packet. */
	private static byte[] decodeFavicon(String dataUri) {
		try {
			int comma = dataUri.indexOf(',');
			String b64 = comma >= 0 ? dataUri.substring(comma + 1) : dataUri;
			byte[] png = Base64.decodeBase64(b64);
			ImageData img = ImageData.loadImageFile(png, "image/png");
			if (img == null || img.pixels == null || img.width <= 0 || img.height <= 0) {
				return null;
			}
			int[] px = img.pixels;
			byte[] out = new byte[64 * 64 * 4];
			int idx = 0;
			for (int y = 0; y < 64; ++y) {
				int sy = img.height == 64 ? y : (y * img.height / 64);
				for (int x = 0; x < 64; ++x) {
					int sx = img.width == 64 ? x : (x * img.width / 64);
					int p = px[sy * img.width + sx];
					out[idx++] = (byte) (p & 0xFF); // R
					out[idx++] = (byte) ((p >> 8) & 0xFF); // G
					out[idx++] = (byte) ((p >> 16) & 0xFF); // B
					out[idx++] = (byte) ((p >> 24) & 0xFF); // A
				}
			}
			return out;
		} catch (Throwable t) {
			logger.warn("Could not decode server favicon: {}", t.toString());
			return null;
		}
	}

	private static byte[] statusHandshakePacket(String host, int port) {
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
		byte[] hostBytes;
		try {
			hostBytes = host.getBytes("UTF-8");
		} catch (Throwable t) {
			hostBytes = host.getBytes();
		}
		writeVarInt(bos, 0); // packet id
		writeVarInt(bos, 47); // protocol 1.8
		writeVarInt(bos, hostBytes.length);
		bos.write(hostBytes, 0, hostBytes.length);
		bos.write((port >> 8) & 0xFF);
		bos.write(port & 0xFF);
		writeVarInt(bos, 1); // next state: status
		return lengthPrefix(bos.toByteArray());
	}

	private static byte[] statusRequestPacket() {
		return lengthPrefix(new byte[] { 0x00 });
	}

	private static byte[] lengthPrefix(byte[] payload) {
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(payload.length + 5);
		writeVarInt(bos, payload.length);
		bos.write(payload, 0, payload.length);
		return bos.toByteArray();
	}

	private static void writeVarInt(java.io.ByteArrayOutputStream out, int value) {
		while (true) {
			if ((value & ~0x7F) == 0) {
				out.write(value);
				break;
			}
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
	}

	/** @return int[]{value, bytesRead} or null if incomplete. */
	private static int[] readVarInt(byte[] buf, int off, int avail) {
		int numRead = 0;
		int result = 0;
		while (true) {
			if (numRead + 1 > avail) {
				return null;
			}
			byte b = buf[off + numRead];
			int value = b & 0x7F;
			result |= (value << (7 * numRead));
			++numRead;
			if (numRead > 5) {
				throw new RuntimeException("VarInt too big");
			}
			if ((b & 0x80) == 0) {
				break;
			}
		}
		return new int[] { result, numRead };
	}

	private void publishError(String errorMsg, String host, int port) {
		try {
			String err = errorMsg == null ? "" : errorMsg;
			String code = "[MWC-TOKEN-NET]";
			String detail = err;
			int bracket = err.indexOf(']');
			if (bracket >= 0) {
				code = err.substring(0, bracket + 1);
				detail = err.substring(bracket + 1);
			}
			// one visual line per MOTD row: fold newlines into spaces
			String flat = detail.replace('\n', ' ').replace('\r', ' ').replaceAll(" +", " ").trim();
			if (flat.startsWith(":")) {
				flat = flat.substring(1).trim();
			}
			String summary = flat;
			String fix = "";
			int fixIdx = flat.indexOf("Fix:");
			if (fixIdx > 0) {
				summary = flat.substring(0, fixIdx).trim();
				fix = flat.substring(fixIdx).trim();
			}
			if (summary.length() > 70) {
				summary = summary.substring(0, 67) + "...";
			}
			if (fix.length() > 70) {
				fix = fix.substring(0, 67) + "...";
			}
			String red = net.minecraft.util.EnumChatFormatting.RED.toString();
			String gold = net.minecraft.util.EnumChatFormatting.GOLD.toString();
			String line0 = red + code + (summary.isEmpty() ? "" : " " + summary);
			String line1 = fix.isEmpty() ? (summary.length() > 70 ? gold + flat.substring(0, 100) : "")
					: gold + fix;
			if (line1.isEmpty()) {
				line1 = gold + "Proxy ping failed - see log";
			}
			JSONObject motdData = new JSONObject();
			motdData.put("motd", new JSONArray(new String[] { line0, line1 }));
			motdData.put("online", 0);
			motdData.put("max", 0);
			motdData.put("players", new JSONArray());
			motdData.put("icon", false);
			JSONObject out = new JSONObject();
			out.put("type", "MOTD");
			out.put("data", motdData);
			out.put("vers", "unreachable");
			out.put("brand", "proxy");
			out.put("name", host + ":" + port);
			out.put("time", EagRuntime.steadyTimeMillis());
			synchronized (queryResponses) {
				queryResponses.add(new QueryResponse(out, -1l));
			}
		} catch (Throwable t) {
			logger.warn("Could not publish proxy ping error for {}:{}", host, port);
			logger.warn(t.toString());
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (Throwable t) {
		}
	}

	@Override
	public void update() {
		// results are published by the worker thread directly
	}

	@Override
	public void send(String str) {
	}

	@Override
	public void send(byte[] bytes) {
	}

	@Override
	public int responsesAvailable() {
		synchronized (queryResponses) {
			return queryResponses.size();
		}
	}

	@Override
	public QueryResponse getResponse() {
		synchronized (queryResponses) {
			return queryResponses.isEmpty() ? null : queryResponses.remove(0);
		}
	}

	@Override
	public int binaryResponsesAvailable() {
		return pendingBinary != null ? 1 : 0;
	}

	@Override
	public byte[] getBinaryResponse() {
		byte[] r = pendingBinary;
		pendingBinary = null;
		return r;
	}

	@Override
	public QueryReadyState readyState() {
		if (open) {
			return alive ? QueryReadyState.OPEN : QueryReadyState.CONNECTING;
		}
		return alive ? QueryReadyState.CLOSED : QueryReadyState.FAILED;
	}

	@Override
	public void close() {
		open = false;
		done = true;
	}

	@Override
	public EnumServerRateLimit getRateLimit() {
		return EnumServerRateLimit.OK;
	}
}
