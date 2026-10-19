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

	public interface TokenCallback {
		void onComplete(TokenResult result);
	}

	/**
	 * Fire-and-forget token fetch on a daemon thread so the GUI never freezes.
	 * The callback runs off the render thread; a watchdog guarantees it is
	 * invoked exactly once even if the fetch thread hangs.
	 */
	public static void requestTokenAsync(final String proxyBase, final String mcHost, final int mcPort,
			final TokenCallback callback) {
		final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
		final Runnable[] watchdog = new Runnable[1];
		watchdog[0] = new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(30000l);
				} catch (Throwable t) {
					return;
				}
				if (done.compareAndSet(false, true)) {
					TokenResult timeout = new TokenResult();
					String shownBase;
					try {
						shownBase = normalizeBase(proxyBase);
					} catch (Throwable t2) {
						shownBase = proxyBase;
					}
					timeout.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
							net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.TIMEOUT,
							"Proxy token request timed out (30s, 3 attempts).\nProxy: " + shownBase
									+ "\nFix: check the proxy process and its logs.");
					try {
						callback.onComplete(timeout);
					} catch (Throwable t2) {
					}
				}
			}
		};
		Thread worker = new Thread(new Runnable() {
			@Override
			public void run() {
				TokenResult res;
				try {
					res = requestToken(proxyBase, mcHost, mcPort);
				} catch (Throwable t) {
					res = new TokenResult();
					res.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
							net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.UNREACHABLE,
							"Token request crashed: " + t);
				}
				if (done.compareAndSet(false, true)) {
					try {
						callback.onComplete(res);
					} catch (Throwable t2) {
						logger.warn("Token callback threw", t2);
					}
				}
			}
		}, "MwcTokenFetch");
		worker.setDaemon(true);
		try {
			worker.start();
			Thread wd = new Thread(watchdog[0], "MwcTokenFetchWatchdog");
			wd.setDaemon(true);
			wd.start();
		} catch (Throwable t) {
			if (done.compareAndSet(false, true)) {
				TokenResult res = new TokenResult();
				res.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
						net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.UNREACHABLE,
						"Background token fetch is not supported on this platform: " + t);
				callback.onComplete(res);
			}
		}
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
		return requestToken(proxyBaseRaw, mcHost, mcPort, true);
	}

	/**
	 * @param retry when false (server-list MOTD ping), try each base exactly once
	 *              with no sleeps, so an unreachable proxy fails in milliseconds
	 *              instead of hammering it with retries
	 */
	public static TokenResult requestToken(String proxyBaseRaw, String mcHost, int mcPort, boolean retry) {
		TokenResult ret = new TokenResult();
		String httpBase;
		try {
			httpBase = normalizeBase(proxyBaseRaw);
		} catch (Throwable t) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.BAD_URL,
					"Bad proxy URL: " + proxyBaseRaw + "\nFix: use e.g. http://localhost:2344");
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
		String lastCode = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.UNREACHABLE;
		int attempts = retry ? 3 : 1;
		for (int bi = 0; bi < bases.length && resp == null; ++bi) {
			String base = bases[bi];
			String url = base + "/api/vm/net/connect";
			for (int attempt = 0; attempt < attempts && resp == null; attempt++) {
				String raw = null;
				boolean threw = false;
				try {
					raw = PlatformRuntime.postJSON(url, body);
				} catch (Throwable t) {
					threw = true;
					lastErr = t.getClass().getSimpleName() + ": " + t.getMessage();
					lastCode = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.classifyTransportError(lastErr);
					logger.warn("Token request attempt {} to {} threw: [{}] {}", attempt + 1, url, lastCode, lastErr);
				}
				if (raw != null) {
					int nl = raw.indexOf('\n');
					int st = 200;
					String payload = raw;
					if (nl >= 0) {
						try {
							st = Integer.parseInt(raw.substring(0, nl).trim());
							payload = raw.substring(nl + 1);
						} catch (Throwable t) {
							st = 200;
						}
					}
					if (st <= 0) {
						// 0 = browser network/CORS failure, -1 = desktop transport failure marker
						lastErr = payload.trim();
						if (lastErr.isEmpty()) {
							lastErr = st == 0 ? "no HTTP response (offline, request blocked, or proxy not running)"
									: "no HTTP response from proxy";
						}
						lastCode = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.classifyTransportError(
								lastErr);
						logger.warn("Token request attempt {} to {} failed: [{}] {}", attempt + 1, url, lastCode,
								lastErr);
					} else {
						resp = raw; // got a real HTTP response (even an error response from the proxy)
					}
				} else if (!threw) {
					lastErr = "no response (offline mode, request blocked, or proxy not running)";
					lastCode = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.UNREACHABLE;
					logger.warn("Token request attempt {} to {} returned no response", attempt + 1, url);
				}
				if (resp == null && retry) {
					try {
						Thread.sleep(500l);
					} catch (Throwable t) {
					}
				}
			}
			if (resp != null) {
				httpBase = base; // remember which base worked
			}
		}

		if (resp == null) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.describeUnreachableProxy(httpBase,
					lastCode, lastErr);
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
					"Too many connections from this IP.\nFix: wait a minute, or try another proxy.");
			return ret;
		}
		if (status == 403) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.FORBIDDEN,
					"Proxy refused this destination: " + mcHost.trim() + ":" + mcPort
							+ "\nFix: ask the proxy owner to allow it on their allowlist.");
			return ret;
		}
		if (status != 200) {
			String detail = "";
			try {
				JSONObject err = new JSONObject(payload);
				detail = err.optString("error", "");
			} catch (Throwable t) {
			}
			if (detail.isEmpty()) {
				String pl = payload.trim();
				detail = pl.isEmpty() ? "" : (pl.length() > 120 ? pl.substring(0, 117) + "..." : pl);
			}
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.HTTP + "-" + status,
					(detail.isEmpty() ? "Proxy request failed." : detail)
							+ "\nFix: the proxy answered with an HTTP error; check its address and logs.");
			return ret;
		}
		String token;
		try {
			token = new JSONObject(payload).getString("token");
		} catch (Throwable t) {
			String pl = payload.trim();
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.BAD_RESPONSE,
					"Proxy answered without a token."
							+ (pl.isEmpty() ? "" : "\nBody: " + (pl.length() > 120 ? pl.substring(0, 117) + "..." : pl))
							+ "\nFix: this URL may not be an mwc-proxy; check Proxy Settings.");
			return ret;
		}
		if (token == null || token.isEmpty()) {
			ret.error = net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
					net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.BAD_RESPONSE,
					"Proxy answered with an empty token.\nFix: restart the proxy; check its logs.");
			return ret;
		}
		ret.ok = true;
		ret.socketUrl = toWebSocketBase(httpBase) + "/api/vm/net/socket?token=" + token;
		return ret;
	}
}
