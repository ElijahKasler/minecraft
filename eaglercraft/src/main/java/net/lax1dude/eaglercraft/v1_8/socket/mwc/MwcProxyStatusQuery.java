package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import java.util.LinkedList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

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
			MwcProxyTokenFetch.TokenResult token = MwcProxyTokenFetch.requestToken(proxyBase, host, port);
			if (!token.ok) {
				logger.info("Status query token failed for {}:{} - {}", host, port, token.error);
				return;
			}
			long deadline = EagRuntime.steadyTimeMillis() + OVERALL_TIMEOUT;
			ws = PlatformNetworking.openWebSocket(token.socketUrl);
			if (ws == null) {
				logger.info("Status query could not open socket to {}", token.socketUrl);
				return;
			}
			while (ws.getState() != EnumEaglerConnectionState.CONNECTED) {
				EnumEaglerConnectionState st = ws.getState();
				if (st == EnumEaglerConnectionState.FAILED || st == EnumEaglerConnectionState.CLOSED) {
					logger.info("Status query socket closed before open for {}:{}", host, port);
					return;
				}
				if (EagRuntime.steadyTimeMillis() > deadline) {
					logger.info("Status query socket timed out for {}:{}", host, port);
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
			}
		} catch (Throwable t) {
			logger.warn("Status query for {}:{} failed: {}", host, port, t.toString());
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
				desc = flattenChat((JSONObject) d);
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
			motdData.put("icon", false);
			JSONObject out = new JSONObject();
			out.put("type", "MOTD");
			out.put("data", motdData);
			out.put("vers", versionName);
			out.put("brand", "vanilla");
			out.put("name", host + ":" + port);
			out.put("time", EagRuntime.steadyTimeMillis());
			synchronized (queryResponses) {
				queryResponses.add(new QueryResponse(out, ping));
			}
		} catch (Throwable t) {
			logger.warn("Could not decode vanilla status response for {}:{}", host, port);
			logger.warn(t.toString());
		}
	}

	private static String flattenChat(JSONObject obj) {
		StringBuilder sb = new StringBuilder();
		String text = obj.optString("text", "");
		sb.append(text);
		JSONArray extra = obj.optJSONArray("extra");
		if (extra != null) {
			for (int i = 0; i < extra.length(); ++i) {
				Object o = extra.opt(i);
				if (o instanceof JSONObject) {
					String s = flattenChat((JSONObject) o);
					if (text.isEmpty() || s.length() > 0) {
						if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
							// keep concatenated text; vanilla MOTDs use \n in text itself
						}
					}
					sb.append(s);
				} else if (o instanceof String) {
					sb.append((String) o);
				}
			}
		}
		return sb.toString();
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
		return 0;
	}

	@Override
	public byte[] getBinaryResponse() {
		return null;
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
