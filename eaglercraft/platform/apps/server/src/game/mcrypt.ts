import {
  constants,
  createCipheriv,
  createDecipheriv,
  createHash,
  randomBytes,
  publicEncrypt,
  type Cipher,
  type Decipher,
} from "node:crypto";

/**
 * Vanilla Minecraft 1.8 transport layer helpers used by the proxy (the client
 * side of the Java server connection).
 */

export class Cfb8Stream {
  private cipher: Cipher;
  private decipher: Decipher;

  constructor(secret: Buffer) {
    this.cipher = createCipheriv("aes-128-cfb8", secret, secret);
    this.decipher = createDecipheriv("aes-128-cfb8", secret, secret);
  }

  encrypt(data: Buffer): Buffer {
    return this.cipher.update(data);
  }

  decrypt(data: Buffer): Buffer {
    return this.decipher.update(data);
  }
}

/** RSA-PKCS1(v1.5) encrypt using the server's X.509 SubjectPublicKeyInfo DER. */
export function rsaEncrypt(publicKeyDer: Buffer, data: Buffer): Buffer {
  return publicEncrypt(
    { key: publicKeyDer, padding: constants.RSA_PKCS1_PADDING },
    data,
  );
}

/** 16-byte session shared secret (AES-128 key == IV). */
export function makeSharedSecret(): Buffer {
  return randomBytes(16);
}

/** 4-byte verify token (vanilla uses random or a fixed challenge). */
export function makeVerifyToken(): Buffer {
  return randomBytes(4);
}

/**
 * Mojang's server-id hash:
 *   sha1(serverId_ascii || sharedSecret || serverPublicKey) as signed hex
 * (mirrors `new BigInteger(md.digest()).toString(16)`).
 */
export function mojangServerHash(
  serverId: string,
  serverPublicKeyDer: Buffer,
  sharedSecret: Buffer,
): string {
  const digest = createHash("sha1")
    .update(Buffer.from(serverId, "latin1"))
    .update(sharedSecret)
    .update(serverPublicKeyDer)
    .digest();

  return signedBigIntegerHex(digest);
}

/** Interpret `bytes` (big-endian, two's-complement) and hex it like Java BigInteger. */
export function signedBigIntegerHex(bytes: Buffer): string {
  const negative = (bytes[0] & 0x80) !== 0;
  let hex: string;
  if (negative) {
    const abs = Buffer.from(bytes);
    for (let i = 0; i < abs.length; i++) abs[i] = ~abs[i] & 0xff;
    for (let i = abs.length - 1; i >= 0; i--) {
      abs[i] = (abs[i] + 1) & 0xff;
      if (abs[i] !== 0) break;
    }
    hex = abs.toString("hex");
  } else {
    hex = bytes.toString("hex");
  }
  const trimmed = hex.replace(/^0+/, "");
  return `${negative ? "-" : ""}${trimmed.length ? trimmed : "0"}`;
}