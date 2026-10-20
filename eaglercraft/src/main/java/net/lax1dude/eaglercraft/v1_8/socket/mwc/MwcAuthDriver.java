package net.lax1dude.eaglercraft.v1_8.socket.mwc;

/**
 * yggdrasil (Microsoft / online-mode) authentication driver.
 *
 * <p>Every platform uses the same {@link MwcAuthDriverShared} implementation:
 * RSA/CFB8 crypto runs locally via {@link MwcCrypto}, while the Microsoft token
 * exchange and Mojang session join are delegated to the user's mwc-proxy over
 * plain JSON HTTP. This works on the desktop JVM, TeaVM (JS) and TeaVM
 * (WASM-GC) alike.</p>
 */
public interface MwcAuthDriver {

	/** Callback invoked when auth completes (success or failure). */
	interface AuthCallback {
		void onSuccess(MwcAuthState state);
		void onFailure(MwcAuthState state, String reason);
	}

	/** Begin the yggdrasil auth flow for the given Encryption Request data. */
	void begin(MwcAuthState state, AuthCallback callback);

	/** True if this platform can sign in to Microsoft accounts. */
	boolean isSupported();

	/** True if a Microsoft account is currently signed in. */
	boolean isSignedIn();

	/** Signed-in Minecraft profile name, or null. */
	String getSignedInName();

	/** Forget the signed-in account. */
	void signOut();

	/** Shared, GUI-pollable state for the device-code sign-in flow. */
	MwcMsaSignInState getSignInState();

	/** Start the Microsoft device-code sign-in flow against the given proxy. */
	void startSignIn(String proxyBase);

	/** Abort an in-progress sign-in flow. */
	void cancelSignIn();

	/**
	 * Advance the sign-in flow by at most one short HTTP request. Called once per
	 * GUI tick from the sign-in screen; a no-op when nothing is in progress.
	 */
	void tick();

	/** Obtain the shared auth driver instance. */
	static MwcAuthDriver get() {
		return MwcAuthDriverShared.getInstance();
	}
}
