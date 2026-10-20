package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import org.json.JSONArray;
import org.json.JSONObject;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

/**
 * Platform-agnostic {@link MwcAuthDriver} used by the desktop JVM, TeaVM (JS)
 * and TeaVM (WASM-GC) targets.
 *
 * <p>All HTTP goes through {@link PlatformRuntime#postJSON(String, String)},
 * the one sync-POST primitive that every platform implements (HttpURLConnection
 * on desktop, fetch/XHR on browsers). Because browser builds cannot read the
 * proxy's SSE stream and cannot run background threads reliably, sign-in is
 * <b>tick-driven</b>: {@link #startSignIn(String)} only kicks off the flow, and
 * {@link #tick()} (called from the GUI's {@code updateScreen}) issues one short
 * request at a time against {@code /auth/start} and {@code /auth/poll}.</p>
 *
 * <p>RSA/AES for the actual login handshake is performed locally by
 * {@link MwcCrypto} on every platform; only the Microsoft token exchange and
 * the Mojang session join are delegated to the user's proxy.</p>
 */
public final class MwcAuthDriverShared implements MwcAuthDriver {

	static final Logger logger = LogManager.getLogger("MwcAuthDriverShared");

	private static final MwcAuthDriverShared INSTANCE = new MwcAuthDriverShared();

	public static MwcAuthDriverShared getInstance() {
		return INSTANCE;
	}

	private static final int PHASE_IDLE = 0;
	private static final int PHASE_STARTING = 1;
	private static final int PHASE_POLLING = 2;

	private final MwcMsaSignInState signInState = new MwcMsaSignInState();

	private int phase = PHASE_IDLE;
	private String proxyBase = null;
	private String sessionId = null;
	private String startBody = null;
	private long nextPoll = 0L;
	private int pollIntervalMs = 1500;

	private MwcAuthDriverShared() {
	}

	@Override
	public boolean isSupported() {
		return true;
	}

	@Override
	public boolean isSignedIn() {
		return MwcMsaAccountStore.isSignedIn();
	}

	@Override
	public String getSignedInName() {
		MwcMsaAccountStore.ensureLoaded();
		return MwcMsaAccountStore.profileName;
	}

	@Override
	public void signOut() {
		MwcMsaAccountStore.clear();
		cancelSignIn();
		signInState.reset();
	}

	@Override
	public MwcMsaSignInState getSignInState() {
		return signInState;
	}

	// ------------------------------------------------------------------
	// Device-code sign-in (tick-driven)
	// ------------------------------------------------------------------

	@Override
	public void startSignIn(String proxyBase) {
		if (signInState.running) {
			return;
		}
		this.proxyBase = MwcProxyTokenFetch.normalizeBase(proxyBase);
		signInState.reset();
		signInState.running = true;
		sessionId = null;
		phase = PHASE_STARTING;
		nextPoll = 0L;
		pollIntervalMs = 1500;

		JSONObject body = new JSONObject();
		body.put("connectingServer", "eaglercraft-client");
		MwcMsaAccountStore.ensureLoaded();
		JSONObject cache = MwcMsaAccountStore.cache;
		if (cache != null) {
			// Feed the previous prismarine-auth caches back in so an existing
			// account is silently refreshed instead of prompting for a new code.
			java.util.Iterator<?> it = cache.keys();
			while (it.hasNext()) {
				String k = String.valueOf(it.next());
				body.put(k, cache.get(k));
			}
		}
		startBody = body.toString();

		if (this.proxyBase.isEmpty() || "http://".equals(this.proxyBase) || "https://".equals(this.proxyBase)) {
			fail("No proxy configured. Set one in Multiplayer > Proxy Settings first.");
		}
	}

	@Override
	public void cancelSignIn() {
		phase = PHASE_IDLE;
		sessionId = null;
		signInState.running = false;
	}

	/** Advance the sign-in flow by at most one (short) HTTP request. */
	@Override
	public void tick() {
		if (phase == PHASE_IDLE) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nextPoll) {
			return;
		}
		try {
			if (phase == PHASE_STARTING) {
				String resp = PlatformRuntime.postJSON(proxyBase + "/api/vm/net/auth/start", startBody);
				int status = parseStatus(resp);
				JSONObject o = parseBody(resp);
				if (o == null) {
					fail("Auth server returned an invalid response (HTTP " + status + ").");
					return;
				}
				String id = o.optString("sessionId", null);
				if (id == null || id.isEmpty()) {
					fail(o.optString("error", "Auth server did not start a sign-in session."));
					return;
				}
				sessionId = id;
				phase = PHASE_POLLING;
				nextPoll = now + 500L;
				handlePollResponse(o);
			} else if (phase == PHASE_POLLING) {
				JSONObject body = new JSONObject();
				body.put("sessionId", sessionId);
				String resp = PlatformRuntime.postJSON(proxyBase + "/api/vm/net/auth/poll", body.toString());
				JSONObject o = parseBody(resp);
				if (o == null) {
					fail("Auth server returned an invalid response (HTTP " + parseStatus(resp) + ").");
					return;
				}
				handlePollResponse(o);
			}
		} catch (Throwable t) {
			fail("Sign-in failed: " + t.getClass().getSimpleName()
					+ (t.getMessage() != null ? ": " + t.getMessage() : ""));
		}
	}

	private void handlePollResponse(JSONObject o) {
		String state = o.optString("state", "pending");
		if ("pending".equals(state)) {
			if (o.has("user_code") && !o.isNull("user_code")) {
				String code = o.optString("user_code", null);
				if (code != null && !code.isEmpty() && !code.equals(signInState.userCode)) {
					signInState.userCode = code;
					signInState.verificationUri = o.optString("verification_uri", null);
					signInState.message = o.optString("message", null);
					int interval = o.optInt("interval", 0);
					pollIntervalMs = interval > 0 ? Math.max(1000, interval * 1000) : 1500;
					logger.info("MSA device code {} at {}", code, signInState.verificationUri);
				}
			}
			nextPoll = System.currentTimeMillis() + pollIntervalMs;
			return;
		}

		if ("complete".equals(state)) {
			JSONObject newCache = o.optJSONObject("newCache");
			if (newCache != null) {
				MwcMsaAccountStore.cache = newCache;
			}
			JSONObject tokenData = o.optJSONObject("tokenData");
			String token = tokenData != null ? tokenData.optString("token", null) : null;
			JSONObject profile = tokenData != null ? tokenData.optJSONObject("profile") : null;
			String pid = profile != null ? profile.optString("id", null) : null;
			String pname = profile != null ? profile.optString("name", null) : null;
			if (token == null || token.isEmpty()) {
				fail("Auth server returned no Minecraft token.");
				return;
			}
			MwcMsaAccountStore.accessToken = token;
			MwcMsaAccountStore.profileId = pid;
			MwcMsaAccountStore.profileName = pname;
			MwcMsaAccountStore.save();
			signInState.profileName = pname;
			signInState.done = true;
			signInState.running = false;
			phase = PHASE_IDLE;
			sessionId = null;
			logger.info("Signed in to Microsoft as {} ({})", pname, pid);
			return;
		}

		if ("error".equals(state)) {
			fail(o.optString("error", "Unknown authentication error"));
			return;
		}

		if ("expired".equals(state)) {
			fail("Sign-in session expired. Please try again.");
			return;
		}

		// Unknown state: keep polling.
		nextPoll = System.currentTimeMillis() + pollIntervalMs;
	}

	private void fail(String msg) {
		signInState.error = msg;
		signInState.failed = true;
		signInState.running = false;
		phase = PHASE_IDLE;
		sessionId = null;
		logger.warn("MSA sign-in failed: {}", msg);
	}

	// ------------------------------------------------------------------
	// Connect-time auth (online-mode server join)
	// ------------------------------------------------------------------

	@Override
	public void begin(MwcAuthState state, AuthCallback callback) {
		try {
			if (state.publicKey == null || state.publicKey.length == 0) {
				throw new IllegalArgumentException("Server public key is empty");
			}
			if (state.verifyToken == null || state.verifyToken.length == 0) {
				throw new IllegalArgumentException("Server verify token is empty");
			}

			MwcCrypto crypto = MwcCrypto.get();
			state.sharedSecret = crypto.newSharedSecret();
			state.encryptedSharedSecret = crypto.rsaEncrypt(state.publicKey, state.sharedSecret);
			state.encryptedVerifyToken = crypto.rsaEncrypt(state.publicKey, state.verifyToken);

			MwcMsaAccountStore.ensureLoaded();
			if (!MwcMsaAccountStore.isSignedIn()) {
				callback.onFailure(state, "This is an online-mode (premium) server. "
						+ "Sign in first via Multiplayer > \"Microsoft Sign-in\", then reconnect.");
				return;
			}

			String base = MwcProxyTokenFetch.normalizeBase(state.proxyBase);
			JSONObject body = new JSONObject();
			body.put("accessToken", MwcMsaAccountStore.accessToken);
			body.put("selectedProfile", MwcMsaAccountStore.profileId);
			body.put("serverId", state.serverId == null ? "" : state.serverId);
			body.put("sharedSecret", new JSONObject().put("data", toJsonByteArray(state.sharedSecret)));
			body.put("publicKey", new JSONObject().put("data", toJsonByteArray(state.publicKey)));

			String resp = PlatformRuntime.postJSON(base + "/api/vm/net/session", body.toString());
			int status = parseStatus(resp);
			if (status == 204 || status == 200) {
				state.accessToken = MwcMsaAccountStore.accessToken;
				state.selectedProfileId = MwcMsaAccountStore.profileId;
				state.profileName = MwcMsaAccountStore.profileName;
				state.complete = true;
				callback.onSuccess(state);
			} else {
				String detail = bodyOf(resp);
				callback.onFailure(state, "Session join was rejected (HTTP " + status + ")."
						+ (detail.isEmpty() ? "" : " " + detail)
						+ " Your Microsoft token may be expired — open Multiplayer > \"Microsoft Sign-in\""
						+ " and sign in again.");
			}
		} catch (Throwable t) {
			StringBuilder sb = new StringBuilder("Online authentication failed: ");
			Throwable c = t;
			while (c != null) {
				sb.append(c.getClass().getSimpleName());
				if (c.getMessage() != null) {
					sb.append(": ").append(c.getMessage());
				}
				sb.append(" <- ");
				c = c.getCause();
			}
			sb.setLength(sb.length() - 4);
			callback.onFailure(state, sb.toString());
		}
	}

	private static JSONArray toJsonByteArray(byte[] b) {
		JSONArray a = new JSONArray();
		for (int i = 0; i < b.length; ++i) {
			a.put(b[i] & 0xFF);
		}
		return a;
	}

	// ------------------------------------------------------------------
	// Helpers (PlatformRuntime.postJSON returns "<status>\n<body>")
	// ------------------------------------------------------------------

	private static int parseStatus(String resp) {
		if (resp == null) {
			return -1;
		}
		int nl = resp.indexOf('\n');
		try {
			return Integer.parseInt((nl >= 0 ? resp.substring(0, nl) : resp).trim());
		} catch (Throwable t) {
			return -1;
		}
	}

	private static String bodyOf(String resp) {
		if (resp == null) {
			return "";
		}
		int nl = resp.indexOf('\n');
		return nl >= 0 ? resp.substring(nl + 1).trim() : "";
	}

	private static JSONObject parseBody(String resp) {
		String body = bodyOf(resp);
		if (body.isEmpty()) {
			return null;
		}
		try {
			return new JSONObject(body);
		} catch (Throwable t) {
			return null;
		}
	}
}
