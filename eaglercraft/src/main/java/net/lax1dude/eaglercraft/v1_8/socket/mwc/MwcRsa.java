package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import java.math.BigInteger;

import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;

/**
 * Pure-Java RSA PKCS#1 v1.5 encryption plus a minimal X.509
 * SubjectPublicKeyInfo (DER) parser, used to encrypt the Minecraft shared
 * secret / verify token with the server's public key.
 *
 * <p>Implemented with {@link BigInteger} so it works on the desktop JVM and
 * both TeaVM targets. {@code window.crypto.subtle} cannot be used because it
 * only offers RSA-OAEP, whereas vanilla Minecraft requires PKCS#1 v1.5
 * padding.</p>
 */
public final class MwcRsa {

	private MwcRsa() {
	}

	/**
	 * Encrypt {@code data} (at most {@code k - 11} bytes) with the RSA public
	 * key encoded as an X.509 SubjectPublicKeyInfo structure.
	 */
	public static byte[] encrypt(byte[] x509PublicKey, byte[] data) {
		BigInteger[] key = parseRsaPublicKey(x509PublicKey);
		BigInteger n = key[0];
		BigInteger e = key[1];

		int k = (n.bitLength() + 7) / 8;
		if (data.length > k - 11) {
			throw new IllegalArgumentException("RSA payload too large (" + data.length + " > " + (k - 11) + ")");
		}

		int psLen = k - data.length - 3;
		byte[] em = new byte[k];
		em[0] = 0x00;
		em[1] = 0x02;
		// PKCS#1 v1.5 padding: at least 8 non-zero random bytes.
		byte[] ps = new byte[psLen];
		while (true) {
			PlatformRuntime.fillRandomBytes(ps);
			boolean ok = true;
			for (int i = 0; i < psLen; ++i) {
				if (ps[i] == 0) {
					ok = false;
					break;
				}
			}
			if (ok) {
				break;
			}
		}
		System.arraycopy(ps, 0, em, 2, psLen);
		int sep = 2 + psLen;
		em[sep] = 0x00;
		System.arraycopy(data, 0, em, sep + 1, data.length);

		BigInteger m = new BigInteger(1, em);
		BigInteger c = m.modPow(e, n);
		return toFixedLength(c, k);
	}

	/**
	 * Parse an X.509 SubjectPublicKeyInfo (DER) and return
	 * {@code {modulus, publicExponent}}.
	 */
	public static BigInteger[] parseRsaPublicKey(byte[] der) {
		int[] p = { 0 };
		readTag(der, p, 0x30); // outer SEQUENCE
		readLength(der, p);
		readTag(der, p, 0x30); // AlgorithmIdentifier SEQUENCE
		int algLen = readLength(der, p);
		p[0] += algLen; // skip OID + NULL
		readTag(der, p, 0x03); // BIT STRING
		readLength(der, p);
		p[0]++; // unused-bits byte
		readTag(der, p, 0x30); // RSAPublicKey SEQUENCE
		readLength(der, p);
		readTag(der, p, 0x02); // INTEGER modulus
		int nLen = readLength(der, p);
		byte[] nBytes = new byte[nLen];
		System.arraycopy(der, p[0], nBytes, 0, nLen);
		p[0] += nLen;
		readTag(der, p, 0x02); // INTEGER exponent
		int eLen = readLength(der, p);
		byte[] eBytes = new byte[eLen];
		System.arraycopy(der, p[0], eBytes, 0, eLen);
		p[0] += eLen;
		return new BigInteger[] { new BigInteger(1, nBytes), new BigInteger(1, eBytes) };
	}

	private static int readTag(byte[] der, int[] p, int expected) {
		if (p[0] >= der.length) {
			throw new IllegalArgumentException("DER: unexpected end of data");
		}
		int tag = der[p[0]++] & 0xFF;
		if (tag != expected) {
			throw new IllegalArgumentException("DER: expected tag 0x" + Integer.toHexString(expected) + " got 0x"
					+ Integer.toHexString(tag));
		}
		return tag;
	}

	private static int readLength(byte[] der, int[] p) {
		int first = der[p[0]++] & 0xFF;
		if ((first & 0x80) == 0) {
			return first;
		}
		int count = first & 0x7F;
		int len = 0;
		for (int i = 0; i < count; ++i) {
			len = (len << 8) | (der[p[0]++] & 0xFF);
		}
		return len;
	}

	private static byte[] toFixedLength(BigInteger value, int length) {
		byte[] b = value.toByteArray();
		if (b.length == length) {
			return b;
		}
		byte[] out = new byte[length];
		if (b.length > length) {
			// trim leading sign byte(s)
			System.arraycopy(b, b.length - length, out, 0, length);
		} else {
			System.arraycopy(b, 0, out, length - b.length, b.length);
		}
		return out;
	}
}
