package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;

/**
 * Browser (TeaVM) crypto via {@code window.crypto.subtle}.
 * <p>
 * For online-mode servers, the web-client-next handles yggdrasil auth
 * and provides the shared secret. This class only handles AES/CTR
 * encryption/decryption of game packets.
 */
public final class MwcCryptoTeaVM implements MwcCrypto {

	private static final MwcCryptoTeaVM INSTANCE = new MwcCryptoTeaVM();

	public static MwcCryptoTeaVM getInstance() {
		return INSTANCE;
	}

	private MwcCryptoTeaVM() {
	}

	@Override
	public byte[] rsaEncrypt(byte[] publicKey, byte[] data) {
		// In browser builds, RSA encryption is handled by web-client-next
		// during the yggdrasil auth flow.
		throw new UnsupportedOperationException(
				"RSA encryption handled by web-client-next for browser builds");
	}

	@Override
	public byte[] aesCtr(byte[] key, long counter, byte[] data, boolean encrypt) {
		// AES/CTR for browser builds will be implemented using window.crypto.subtle
		// once proper async handling is added to Teavm bindings.
		// For now, desktop handles online-mode; browser falls back to web-client-next.
		throw new UnsupportedOperationException(
				"AES/CTR implementation for Teavm pending. Use desktop client for online-mode.");
	}
}
