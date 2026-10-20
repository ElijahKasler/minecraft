package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import org.json.JSONObject;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

/**
 * Persisted Microsoft / Minecraft account used for online-mode (premium)
 * server authentication.
 *
 * <p>Stores the Minecraft services access token + selected profile (uuid/name)
 * together with the {@code newCache} blob returned by the mwc-proxy
 * {@code /auth} endpoint. The cache lets {@code prismarine-auth} silently
 * refresh the token later without asking the user to re-enter a device code.</p>
 *
 * <p>Persisted via {@link EagRuntime} blob storage (key {@code "A"}), so it
 * never leaves the client except as the session-join call routed through the
 * user's own proxy.</p>
 */
public class MwcMsaAccountStore {

	public static final Logger logger = LogManager.getLogger("MwcMsaAccountStore");

	private static final String STORAGE_KEY = "A";

	public static String accessToken = null;
	public static String profileId = null;
	public static String profileName = null;
	public static JSONObject cache = null;

	private static boolean loaded = false;

	public static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			byte[] data = EagRuntime.getStorage(STORAGE_KEY);
			if (data != null && data.length > 0) {
				JSONObject o = new JSONObject(new String(data, "UTF-8"));
				accessToken = o.has("accessToken") ? o.getString("accessToken") : null;
				profileId = o.has("profileId") ? o.getString("profileId") : null;
				profileName = o.has("profileName") ? o.getString("profileName") : null;
				cache = o.optJSONObject("cache");
			}
		} catch (Throwable t) {
			logger.warn("Could not read MSA account storage: {}", t.toString());
		}
	}

	public static synchronized void save() {
		ensureLoaded();
		try {
			JSONObject o = new JSONObject();
			if (accessToken != null) {
				o.put("accessToken", accessToken);
			}
			if (profileId != null) {
				o.put("profileId", profileId);
			}
			if (profileName != null) {
				o.put("profileName", profileName);
			}
			if (cache != null) {
				o.put("cache", cache);
			}
			EagRuntime.setStorage(STORAGE_KEY, o.toString().getBytes("UTF-8"));
		} catch (Throwable t) {
			logger.warn("Could not save MSA account: {}", t.toString());
		}
	}

	public static synchronized boolean isSignedIn() {
		ensureLoaded();
		return accessToken != null && !accessToken.isEmpty() && profileId != null && !profileId.isEmpty();
	}

	public static synchronized void clear() {
		ensureLoaded();
		accessToken = null;
		profileId = null;
		profileName = null;
		cache = null;
		try {
			EagRuntime.setStorage(STORAGE_KEY, new byte[0]);
		} catch (Throwable t) {
			logger.warn("Could not clear MSA account: {}", t.toString());
		}
	}
}
