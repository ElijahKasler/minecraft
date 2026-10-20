package net.lax1dude.eaglercraft.v1_8.socket.mwc;

/**
 * Stateful AES-128/CFB8 stream cipher, matching vanilla Minecraft's transport
 * encryption ({@code aes-128-cfb8}, key = IV = the 16-byte shared secret).
 *
 * <p>This is a <b>streaming</b> cipher with an 8-bit feedback segment: every
 * single byte re-encrypts the 128-bit feedback register and XORs one keystream
 * byte. State must be preserved across packet boundaries, so the network
 * manager keeps one instance for the inbound stream and one for the outbound
 * stream and feeds every byte (including the VarInt frame-length prefixes)
 * through it in order.</p>
 */
public final class MwcCfb8 {

	private final byte[] schedule;
	/** 128-bit feedback register, initialised to the IV (== key). */
	private final byte[] reg = new byte[16];
	private final byte[] keystream = new byte[16];

	public MwcCfb8(byte[] key) {
		if (key == null || key.length != 16) {
			throw new IllegalArgumentException("CFB8 key must be 16 bytes");
		}
		this.schedule = MwcAes.expandKey128(key);
		System.arraycopy(key, 0, reg, 0, 16);
	}

	/** Encrypt {@code data} in place, advancing the stream state. */
	public byte[] encrypt(byte[] data) {
		return process(data, true);
	}

	/** Decrypt {@code data} in place, advancing the stream state. */
	public byte[] decrypt(byte[] data) {
		return process(data, false);
	}

	private byte[] process(byte[] data, boolean encrypting) {
		for (int i = 0; i < data.length; ++i) {
			MwcAes.encryptBlock(schedule, reg, 0, keystream, 0);
			byte in = data[i];
			byte out = (byte) (in ^ keystream[0]);
			data[i] = out;
			byte feed = encrypting ? out : in;
			System.arraycopy(reg, 1, reg, 0, 15);
			reg[15] = feed;
		}
		return data;
	}
}
