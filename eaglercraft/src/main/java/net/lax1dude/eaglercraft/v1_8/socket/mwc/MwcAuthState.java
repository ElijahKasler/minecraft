package net.lax1dude.eaglercraft.v1_8.socket.mwc;

/**
 * Holds state during yggdrasil online-mode authentication.
 */
public class MwcAuthState {

	/** Server ID from Encryption Request (used to validate auth response). */
	public final String serverId;

	/** Server's RSA public key (DER SubjectPublicKeyInfo). */
	public final byte[] publicKey;

	/** Server's verify token (encrypted with shared secret). */
	public final byte[] verifyToken;

	/** mwc-proxy HTTP base (http(s)://host:port) used for /session join. */
	public String proxyBase = null;

	/** Randomly generated 16-byte AES shared secret. */
	public byte[] sharedSecret;

	/** RSA-encrypted shared secret (to send to server). */
	public byte[] encryptedSharedSecret;

	/** RSA-encrypted verify token (to send to server). */
	public byte[] encryptedVerifyToken;

	/** Microsoft access token obtained via yggdrasil auth. */
	public String accessToken;

	/** Selected profile UUID. */
	public String selectedProfileId;

	/** Selected profile name (online mode). */
	public String profileName;

	/** Whether authentication is complete. */
	public boolean complete = false;

	public MwcAuthState(String serverId, byte[] publicKey, byte[] verifyToken) {
		this.serverId = serverId;
		this.publicKey = publicKey;
		this.verifyToken = verifyToken;
	}
}