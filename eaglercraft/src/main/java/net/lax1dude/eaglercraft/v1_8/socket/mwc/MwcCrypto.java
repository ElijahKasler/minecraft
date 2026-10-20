package net.lax1dude.eaglercraft.v1_8.socket.mwc;

/**
 * Crypto backend for yggdrasil online-mode authentication.
 *
 * <p>All platforms share the same pure-Java implementation
 * ({@link MwcCryptoShared}): AES-128/CFB8 for the transport stream and RSA
 * PKCS#1 v1.5 for encrypting the shared secret. This is required because
 * vanilla Minecraft's cipher (CFB8 with PKCS#1 v1.5) is not offered by Web
 * Crypto, and it keeps desktop and browser behaviour byte-identical.</p>
 */
public interface MwcCrypto {

	/** Generate a 16-byte random shared secret for AES. */
	byte[] newSharedSecret();

	/** RSA PKCS#1 v1.5 encrypt {@code data} with a DER X.509 public key. */
	byte[] rsaEncrypt(byte[] publicKey, byte[] data);

	/** Create a fresh AES-128/CFB8 stream cipher (key = IV = {@code secret}). */
	MwcCfb8 createCfb8(byte[] secret);

	/** Obtain the platform crypto instance. */
	static MwcCrypto get() {
		return MwcCryptoShared.getInstance();
	}
}
