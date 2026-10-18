package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;

/**
 * Crypto backend for yggdrasil online-mode authentication.
 * <p>
 * Desktop uses JCE {@code javax.crypto}; browser builds use
 * {@code window.crypto.subtle} via {@code @JSBody}.
 * Uses reflection to load platform-specific implementations.
 */
public interface MwcCrypto {

	/** Generate a 16-byte random shared secret for AES. */
	default byte[] newSharedSecret() {
		byte[] b = new byte[16];
		new java.security.SecureRandom().nextBytes(b);
		return b;
	}

	/** RSA encrypt {@code data} with {@code publicKey} (DER SubjectPublicKeyInfo). */
	byte[] rsaEncrypt(byte[] publicKey, byte[] data);

	/** AES-CTR crypt (encrypt or decrypt). {@code counter} is the 128-bit initial counter. */
	byte[] aesCtr(byte[] key, long counter, byte[] data, boolean encrypt);

	/** Obtain the platform crypto instance via reflection. */
	static MwcCrypto get() {
		String className;
		if (EagRuntime.getPlatformType() == EnumPlatformType.DESKTOP) {
			className = "net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcCryptoDesktop";
		} else {
			className = "net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcCryptoTeaVM";
		}
		try {
			Class<?> cls = Class.forName(className);
			java.lang.reflect.Method m = cls.getDeclaredMethod("getInstance");
			return (MwcCrypto) m.invoke(null);
		} catch (Throwable t) {
			throw new RuntimeException("Cannot load crypto: " + className, t);
		}
	}
}