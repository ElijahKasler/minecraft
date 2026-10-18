package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/** Desktop JCE implementation of {@link MwcCrypto}. */
public final class MwcCryptoDesktop implements MwcCrypto {

	private static final MwcCryptoDesktop INSTANCE = new MwcCryptoDesktop();

	public static MwcCryptoDesktop getInstance() {
		return INSTANCE;
	}

	private MwcCryptoDesktop() {
	}

	@Override
	public byte[] rsaEncrypt(byte[] publicKey, byte[] data) {
		try {
			X509EncodedKeySpec keySpec = new X509EncodedKeySpec(publicKey);
			KeyFactory kf = KeyFactory.getInstance("RSA");
			PublicKey pubKey = kf.generatePublic(keySpec);
			Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
			cipher.init(Cipher.ENCRYPT_MODE, pubKey);
			return cipher.doFinal(data);
		} catch (Exception e) {
			throw new RuntimeException("RSA encrypt failed", e);
		}
	}

	@Override
	public byte[] aesCtr(byte[] key, long counter, byte[] data, boolean encrypt) {
		try {
			SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
			byte[] ivBytes = new byte[16];
			// Convert counter to big-endian bytes for IV
			for (int i = 0; i < 8; i++) {
				ivBytes[i] = (byte) ((counter >> (8 * (7 - i))) & 0xFF);
				ivBytes[i + 8] = 0; // Upper 64 bits = 0 (initial block)
			}
			IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
			Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
			cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, keySpec, ivSpec);
			return cipher.doFinal(data);
		} catch (Exception e) {
			throw new RuntimeException("AES/CTR failed", e);
		}
	}
}
