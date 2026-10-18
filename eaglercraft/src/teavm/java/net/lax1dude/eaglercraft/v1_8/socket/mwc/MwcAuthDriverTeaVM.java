package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;

/** Browser (TeaVM) auth driver that delegates yggdrasil to web-client-next. */
public final class MwcAuthDriverTeaVM implements MwcAuthDriver {

	private static final MwcAuthDriverTeaVM INSTANCE = new MwcAuthDriverTeaVM();

	public static MwcAuthDriverTeaVM getInstance() {
		return INSTANCE;
	}

	private MwcAuthDriverTeaVM() {
	}

	@Override
	public void begin(MwcAuthState state, AuthCallback callback) {
		// The web-client-next handles the full yggdrasil auth flow.
		// For browser builds, online-mode connections require using
		// minecraft-web-client-next which handles Microsoft auth via
		// the yggdrasil protocol.
		callback.onFailure(state,
			"Browser auth requires web-client integration. " +
			"Please use minecraft-web-client-next for online-mode servers.");
	}
}
