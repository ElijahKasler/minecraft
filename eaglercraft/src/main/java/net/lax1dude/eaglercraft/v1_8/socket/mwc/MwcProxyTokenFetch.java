package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import org.json.JSONObject;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

/**
 * Resolves a mwc-proxy connection token from pure Java, no JavaScript bridge.
 *
 * <p>Performs {@code POST {proxyBase}/api/vm/net/connect {"host","port"}} via
 * {@link PlatformRuntime#postJSON} (desktop: HttpURLConnection, browser:
 * fetch/XHR, WASM: sync XHR) and builds the
 * {@code {proxyBase}/api/vm/net/socket?token=} URL that
 * {@link MwcProxyHandshake} opens with {@code PlatformNetworking}.</p>
 */
public class MwcProxyTokenFetch {

	static final Logger logger = LogManager.getLogger("MwcProxyTokenFetch");

	public static class TokenResult {
		public boolean ok = false;
		/** wss:// URL incl. ?token=, ready for PlatformNetworking.openWebSocket */
		public String socketUrl = null;
		public String error = null;
	}

	public static String normalizeBase(String proxyBase) {
		String base = proxyBase == null ? "" : proxyBase.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		if (base.startsWith("ws://")) {
			base = "http://" + base.substring(5);
		} else if (base.startsWith("wss://")) {
			base = "https://" + base.substring(6);
		} else if (!base.startsWith("http://") && !base.startsWith("https://")) {
			base = "http://" + base;
		}
		return base;
	}

	public static String toWebSocketBase(String httpBase) {
		if (httpBase.startsWith("https://")) {
			return "wss://" + httpBase.substring(8);
		} else if (httpBase.startsWith("http://")) {
			return "ws://" + httpBase.substring(7);
		}
		return httpBase;
	}

	private static String quote(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 2);
		for (int i = 0, l = s.length(); i < l; ++i) {
			char c = s.charAt(i);
			if (c == '"' || c == '\\') {
				sb.append('\\');
			}
			sb.append(c);
		}
		return sb.toString();
	}

	/**
	 * Blocking call (short timeouts inside). Call from a button handler, then
	 * open {@code GuiConnecting} with a ServerData holding {@link #socketUrl}.
	 */
	public static TokenResult requestToken(String proxyBaseRaw, String mcHost, int mcPort) {
		TokenResult ret = new TokenResult();
		String httpBase;
		try {
			httpBase = normalizeBase(proxyBaseRaw);
		} catch (Throwable t) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.BAD_URL, "Bad proxy URL.");
			return ret;
		}
		if (mcHost == null || mcHost.trim().isEmpty()) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.NO_SERVER,
					"Enter a server IP.");
			return ret;
		}
		String body = "{\"host\":\"" + quote(mcHost.trim()) + "\",\"port\":" + mcPort + "}";

		// Try up to 3 times; if localhost fails, fall back to 127.0.0.1 (IPv4 only).
		String[] bases = { httpBase };
		if (httpBase.contains("://localhost")) {
			bases = new String[] { httpBase, httpBase.replace("://localhost", "://127.0.0.1") };
		}

		String resp = null;
		String lastErr = null;
		for (String base : bases) {
			String url = base + "/api/vm/net/connect";
			for (int attempt = 0; attempt < 3; attempt++) {
				try {
					resp = PlatformRuntime.postJSON(url, body);
					if (resp != null) {
						break; // got a response (even an error response from the proxy)
					}
				} catch (Throwable t) {
					lastErr = t.toString();
					logger.warn("Token request attempt {} to {} failed: {}", attempt + 1, url, t.toString());
				}
				try { Thread.sleep(500); } catch (Throwable t) {}
			}
			if (resp != null) {
				httpBase = base; // remember which base worked
				break;
			}
		}

		if (resp == null) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.UNREACHABLE,
					"Could not reach proxy. Is it running on " + httpBase + "? Last error: " + lastErr);
			return ret;
		}

		int status = 0;
		String payload = "";
		int nl = resp.indexOf('\n');
		try {
			if (nl >= 0) {
				status = Integer.parseInt(resp.substring(0, nl).trim());
				payload = resp.substring(nl + 1);
			} else {
				payload = resp;
			}
		} catch (Throwable t) {
			payload = resp;
		}
		if (status == 429) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.RATE_LIMITED,
					"Too many connections from this IP. Wait a bit or try another proxy.");
			return ret;
		}
		if (status == 403) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.FORBIDDEN,
					"Proxy refused: destination not on its allowlist.");
			return ret;
		}
		if (status != 200 && status != 0) {
			String detail = "";
			try {
				JSONObject err = new JSONObject(payload);
				detail = err.optString("error", "");
			} catch (Throwable t) {
			}
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.HTTP + "-" + status,
					detail.isEmpty() ? "Proxy request failed." : detail);
			return ret;
		}
		String token;
		try {
			token = new JSONObject(payload).getString("token");
		} catch (Throwable t) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.BAD_RESPONSE,
					"Proxy answered without a token.");
			return ret;
		}
		if (token == null || token.isEmpty()) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.BAD_RESPONSE,
					"Proxy answered without a token.");
			return ret;
		}
		ret.ok = true;
		ret.socketUrl = toWebSocketBase(httpBase) + "/api/vm/net/socket?token=" + token;
		return ret;
	}
}
