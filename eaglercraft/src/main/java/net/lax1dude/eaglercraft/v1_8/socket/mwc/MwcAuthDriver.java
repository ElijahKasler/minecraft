package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;

/**
 * Platform-specific yggdrasil authentication driver.
 * <p>
 * Desktop performs RSA+AES crypto locally via JCE.
 * Browser builds delegate yggdrasil to minecraft-web-client-next.
 * Uses reflection to load platform-specific implementations.
 */
public interface MwcAuthDriver {

	/** Callback invoked when auth completes (success or failure). */
	interface AuthCallback {
		void onSuccess(MwcAuthState state);
		void onFailure(MwcAuthState state, String reason);
	}

	/** Begin the yggdrasil auth flow for the given Encryption Request data. */
	void begin(MwcAuthState state, AuthCallback callback);

	/** Obtain the platform auth driver instance via reflection. */
	static MwcAuthDriver get() {
		String className;
		if (EagRuntime.getPlatformType() == EnumPlatformType.DESKTOP) {
			className = "net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcAuthDriverDesktop";
		} else {
			className = "net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcAuthDriverTeaVM";
		}
		try {
			Class<?> cls = Class.forName(className);
			java.lang.reflect.Method m = cls.getDeclaredMethod("getInstance");
			return (MwcAuthDriver) m.invoke(null);
		} catch (Throwable t) {
			throw new RuntimeException("Cannot load auth driver: " + className, t);
		}
	}
}