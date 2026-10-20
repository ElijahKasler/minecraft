package net.lax1dude.eaglercraft.v1_8.socket.mwc;

/**
 * Transient state for the Microsoft device-code sign-in flow, updated by the
 * platform {@link MwcAuthDriver} on a background thread and polled by
 * {@code GuiMsaLogin} once per client tick.
 */
public class MwcMsaSignInState {

	/** True while the device-code flow is in progress. */
	public volatile boolean running = false;

	/** True once a Minecraft token/profile was obtained and saved. */
	public volatile boolean done = false;

	/** True if the flow failed (see {@link #error}). */
	public volatile boolean failed = false;

	/** Microsoft device code the user must enter (e.g. "GX8-QW2"). */
	public volatile String userCode = null;

	/** URL the user must visit (e.g. https://microsoft.com/link). */
	public volatile String verificationUri = null;

	/** Human-readable status message from the auth server. */
	public volatile String message = null;

	/** Failure detail, when {@link #failed} is true. */
	public volatile String error = null;

	/** Minecraft profile name once signed in. */
	public volatile String profileName = null;

	public void reset() {
		running = false;
		done = false;
		failed = false;
		userCode = null;
		verificationUri = null;
		message = null;
		error = null;
		profileName = null;
	}
}
