import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  Cfb8Stream,
  mojangServerHash,
  signedBigIntegerHex,
} from "../src/game/mcrypt.js";

test("CFB8 round trip", () => {
  const secret = Buffer.alloc(16, 7);
  const stream = new Cfb8Stream(secret);
  const plain = Buffer.from("hello world");
  const enc = stream.encrypt(plain);
  assert.notDeepEqual(enc, plain);
  const stream2 = new Cfb8Stream(secret);
  assert.deepEqual(stream2.decrypt(enc), plain);
});

test("signedBigIntegerHex handles leading zeros (null)", () => {
  assert.equal(signedBigIntegerHex(Buffer.alloc(20)), "0");
});

test("signedBigIntegerHex mirrors Java BigInteger.toString(16)", () => {
  // positive
  assert.equal(signedBigIntegerHex(Buffer.from([0x00, 0xff, 0x10])), "ff10");
  assert.equal(signedBigIntegerHex(Buffer.from([0x12, 0x34])), "1234");
  // negative (two's complement) -> "-" + magnitude
  assert.equal(signedBigIntegerHex(Buffer.from([0xff, 0xff])), "-1");
  assert.equal(signedBigIntegerHex(Buffer.from([0x80, 0x00, 0x00, 0x01])), "-7fffffff");
});

function findJava(): string | null {
  const candidates = [
    "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot\\bin\\java.exe",
    ...(process.env.JAVA_HOME ? [join(process.env.JAVA_HOME, "bin", "java.exe")] : []),
    "java",
  ];
  for (const c of candidates) {
    try {
      execFileSync(c, ["-version"], { stdio: "ignore" });
      return c;
    } catch {
      /* try next */
    }
  }
  return null;
}

const JAVA_SOURCE = `
import java.math.BigInteger;
import java.security.MessageDigest;
public class HashCheck {
  public static void main(String[] a) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-1");
    md.update(a[0].getBytes("ISO-8859-1"));
    md.update(toBytes(a[1]));
    md.update(toBytes(a[2]));
    System.out.println(new BigInteger(md.digest()).toString(16));
  }
  static byte[] toBytes(String s) {
    int n = s.length() / 2;
    byte[] out = new byte[n];
    for (int i = 0; i < n; i++)
      out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    return out;
  }
}
`;

test("mojangServerHash matches Java BigInteger rendering", { skip: findJava() === null }, () => {
  const java = findJava()!;
  const dir = mkdtempSync(join(tmpdir(), "hashcheck-"));
  try {
    writeFileSync(join(dir, "HashCheck.java"), JAVA_SOURCE, "utf8");
    const vectors = [
      ["", "00000000000000000000000000000000", "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"],
      ["-deadbeef", "aabbccddeeff00112233445566778899", "0102030405060708090a0b0c0d0e0f1011121314151617"],
      ["hello", "ff000000000000000000000000000000", "00ff00112233445566778899aabbccddeeff0011"],
    ];
    for (const [serverId, secretHex, keyHex] of vectors) {
      const out = execFileSync(
        java,
        ["HashCheck.java", serverId, secretHex, keyHex],
        { cwd: dir, encoding: "utf8" },
      ).trim();
      const actual = mojangServerHash(serverId, Buffer.from(keyHex, "hex"), Buffer.from(secretHex, "hex"));
      assert.equal(actual, out, `serverId=${JSON.stringify(serverId)}`);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});