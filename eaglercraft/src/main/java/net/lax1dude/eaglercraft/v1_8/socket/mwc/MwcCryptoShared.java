package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;

/**
 * Platform-agnostic {@link MwcCrypto} implementation backed by pure-Java
 * AES-128/CFB8 ({@link MwcCfb8}) and RSA PKCS#1 v1.5 ({@link MwcRsa}).
 *
 * <p>Used on <b>all</b> targets (desktop JVM, TeaVM JS, TeaVM WASM-GC) so the
 * exact same crypto runs everywhere with no {@code javax.crypto} or
 * {@code window.crypto.subtle} dependency.</p>
 *
 * <p>Randomness comes from {@link PlatformRuntime#fillRandomBytes(byte[])}:
 * {@code SecureRandom} on desktop and Web Crypto {@code getRandomValues} on the
 * browser targets.</p>
 */
public final class MwcCryptoShared implements MwcCrypto {

	private static final MwcCryptoShared INSTANCE = new MwcCryptoShared();

	public static MwcCryptoShared getInstance() {
		return INSTANCE;
	}

	private MwcCryptoShared() {
	}

	@Override
	public byte[] newSharedSecret() {
		byte[] b = new byte[16];
		PlatformRuntime.fillRandomBytes(b);
		return b;
	}

	@Override
	public byte[] rsaEncrypt(byte[] publicKey, byte[] data) {
		return MwcRsa.encrypt(publicKey, data);
	}

	@Override
	public MwcCfb8 createCfb8(byte[] secret) {
		return new MwcCfb8(secret);
	}
}
