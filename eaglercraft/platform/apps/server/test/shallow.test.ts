import { test } from "node:test";
import assert from "node:assert/strict";
import { PacketWriter } from "../src/game/writer.js";
import { skipPlayPayload, chunkDataBytes, type Dir } from "../src/game/shallow.js";

function w(fn: (w: PacketWriter) => void): Buffer {
  const writer = new PacketWriter();
  fn(writer);
  return writer.toBuffer();
}

/** Assert the shallow parser consumes exactly the given payload from its start. */
function ok(pid: number, dir: Dir, payload: Buffer): void {
  assert.equal(
    skipPlayPayload(pid, dir, payload, 0),
    payload.length,
    `pid 0x${pid.toString(16)} (${dir}) should consume ${payload.length} bytes`,
  );
}

test("S2C Chat", () => {
  ok(0x02, "S2C", w((w) => w.writeMCString('{"text":"hi"}').writeByte(0)));
});

test("S2C JoinGame", () => {
  ok(0x01, "S2C", w((w) => w.writeInt(1).writeByte(0).writeByte(0).writeByte(0).writeByte(20).writeMCString("default").writeByte(0)));
});

test("S2C PlayerPosLook has no teleport id", () => {
  ok(0x08, "S2C", w((w) => w.writeDouble(1).writeDouble(2).writeDouble(3).writeFloat(0).writeFloat(0).writeByte(0)));
});

test("S2C SoundEffect uses ints for coords", () => {
  ok(0x29, "S2C", w((w) => w.writeMCString("random.levelup").writeInt(100).writeInt(200).writeInt(300).writeFloat(0.5).writeByte(1)));
});

test("S2C Particles: iconcrack_ expects 2 varint args", () => {
  ok(0x2a, "S2C", w((w) => w.writeInt(36).writeByte(0).writeFloat(0).writeFloat(0).writeFloat(0).writeFloat(0).writeFloat(0).writeFloat(0).writeFloat(0).writeInt(3).writeVarInt(1).writeVarInt(0)));
  ok(0x2a, "S2C", w((w) => w.writeInt(37).writeByte(0).writeFloat(1).writeFloat(2).writeFloat(3).writeFloat(0).writeFloat(0).writeFloat(0).writeFloat(0).writeInt(2).writeVarInt(0)));
  ok(0x2a, "S2C", w((w) => w.writeInt(33).writeByte(1).writeFloat(1).writeFloat(2).writeFloat(3).writeFloat(0).writeFloat(0).writeFloat(0).writeFloat(0).writeInt(1)));
});

test("S2C WorldBorder actions", () => {
  ok(0x44, "S2C", w((w) => w.writeVarInt(0).writeDouble(100)));
  ok(0x44, "S2C", w((w) => w.writeVarInt(1).writeDouble(100).writeDouble(50).writeVarLong(1000n)));
  ok(0x44, "S2C", w((w) => w.writeVarInt(2).writeDouble(0).writeDouble(0)));
  ok(0x44, "S2C", w((w) => w.writeVarInt(3).writeVarInt(5)));
  ok(0x44, "S2C", w((w) => w.writeVarInt(4).writeVarInt(15)));
  ok(0x44, "S2C", w((w) => w.writeVarInt(5).writeDouble(0).writeDouble(0).writeDouble(100).writeDouble(100).writeVarLong(0n).writeVarInt(5).writeVarInt(15).writeVarInt(29999984)));
});

test("S2C Title times uses ints", () => {
  ok(0x45, "S2C", w((w) => w.writeVarInt(2).writeInt(20).writeInt(20).writeInt(20)));
  ok(0x45, "S2C", w((w) => w.writeVarInt(0).writeMCString('{"text":"T"}')));
  ok(0x45, "S2C", w((w) => w.writeVarInt(4)));
});

test("S2C CombatEvent variants", () => {
  ok(0x42, "S2C", w((w) => w.writeVarInt(0)));
  ok(0x42, "S2C", w((w) => w.writeVarInt(1).writeVarInt(0).writeInt(10)));
  ok(0x42, "S2C", w((w) => w.writeVarInt(2).writeVarInt(5).writeInt(10).writeMCString('{"text":"x"}')));
});

test("S2C Maps with and without data", () => {
  ok(0x34, "S2C", w((w) => w.writeVarInt(1).writeByte(0).writeVarInt(0).writeByte(0)));
  ok(0x34, "S2C", w((w) => w.writeVarInt(1).writeByte(1).writeVarInt(2).writeByte(0x11).writeByte(1).writeByte(2).writeByte(0x22).writeByte(3).writeByte(4).writeByte(1).writeByte(2).writeByte(3).writeByte(4).writeVarInt(16).writeBytes(Buffer.alloc(16))));
});

test("S2C ChunkData", () => {
  const data = Buffer.alloc(100, 7);
  ok(0x21, "S2C", w((w) => w.writeInt(1).writeInt(-2).writeByte(1).writeShort(0b1111).writeVarIntBytes(data)));
});

test("S2C MapChunkBulk total size computed from masks", () => {
  // 2 chunks, skylight true; masks with 3 and 1 sections
  const m1 = 0b0111; // 3 sections
  const m2 = 0b0001; // 1 section
  const total = chunkDataBytes(popcount(m1), true) + chunkDataBytes(popcount(m2), true);
  ok(0x26, "S2C", w((w) => {
    w.writeByte(1).writeVarInt(2);
    w.writeInt(0).writeInt(0).writeShort(m1);
    w.writeInt(1).writeInt(1).writeShort(m2);
    w.writeBytes(Buffer.alloc(total));
  }));
});

function popcount(n: number): number {
  let c = 0;
  while (n) {
    c += n & 1;
    n >>>= 1;
  }
  return c;
}

test("S2C MultiBlockChange = chunkPos + short,varint pairs", () => {
  ok(0x22, "S2C", w((w) => w.writeInt(1).writeInt(2).writeVarInt(3).writeShort(0).writeVarInt(100).writeShort(1).writeVarInt(101).writeShort(2).writeVarInt(102)));
});

test("S2C DestroyEntities uses varint ids", () => {
  ok(0x13, "S2C", w((w) => w.writeVarInt(2).writeVarInt(1000).writeVarInt(2000)));
});

test("S2C EntityEffect has hideParticles byte", () => {
  ok(0x1d, "S2C", w((w) => w.writeVarInt(9).writeByte(11).writeByte(0).writeVarInt(200).writeByte(0)));
});

test("S2C WindowItems uses short count", () => {
  ok(0x30, "S2C", w((w) => w.writeByte(2).writeShort(2).writeShort(-1).writeShort(1).writeByte(1).writeShort(0).writeByte(0)));
});

test("S2C ScoreboardObjective: two strings when create/update", () => {
  ok(0x3b, "S2C", w((w) => w.writeMCString("obj").writeByte(0).writeMCString("Display").writeMCString("integer")));
  ok(0x3b, "S2C", w((w) => w.writeMCString("obj").writeByte(1)));
});

test("S2C UpdateScore: objective always present", () => {
  ok(0x3c, "S2C", w((w) => w.writeMCString("player").writeVarInt(0).writeMCString("obj").writeVarInt(100)));
  ok(0x3c, "S2C", w((w) => w.writeMCString("player").writeVarInt(1).writeMCString("obj")));
});

test("S2C Teams: no collision byte, up to 4 modes", () => {
  ok(0x3e, "S2C", w((w) => w.writeMCString("team").writeByte(0).writeMCString("D").writeMCString("P").writeMCString("S").writeByte(0).writeMCString("always").writeByte(1).writeVarInt(2).writeMCString("a").writeMCString("b")));
  ok(0x3e, "S2C", w((w) => w.writeMCString("team").writeByte(3).writeVarInt(1).writeMCString("a")));
  ok(0x3e, "S2C", w((w) => w.writeMCString("team").writeByte(4).writeVarInt(1).writeMCString("a")));
});

test("S2C PlayerListItem ADD with properties", () => {
  ok(0x38, "S2C", w((w) => {
    w.writeVarInt(0).writeVarInt(1);
    w.writeLong(1n).writeLong(2n);
    w.writeMCString("Player");
    w.writeVarInt(1);
    w.writeMCString("textures").writeMCString("base64").writeByte(1).writeMCString("sig");
    w.writeVarInt(0).writeVarInt(42).writeByte(0);
  }));
  ok(0x38, "S2C", w((w) => {
    w.writeVarInt(4).writeVarInt(1);
    w.writeLong(1n).writeLong(2n);
  }));
});

test("metadata: string value is varint-length", () => {
  // SpawnMob with metadata entry type 4 (string) + item type 5 + terminator
  ok(0x0f, "S2C", w((w) => {
    w.writeVarInt(50).writeByte(32);
    w.writeInt(0).writeInt(0).writeInt(0);
    w.writeByte(0).writeByte(0).writeByte(0);
    w.writeShort(0).writeShort(0).writeShort(0);
    w.writeByte((4 << 5) | 2); // string, index 2
    w.writeMCString("customName");
    w.writeByte((5 << 5) | 0); // item, index 0
    w.writeShort(-1); // empty item
    w.writeByte(0x7f);
  }));
});

test("NBT compound inside UpdateEntityNBT", () => {
  ok(0x49, "S2C", w((w) => {
    w.writeVarInt(5);
    w.writeByte(10); // root compound
    w.writeShort(0); // root name ""
    w.writeByte(8); // string child
    w.writeShort(4);
    w.writeBytes(Buffer.from("name"));
    w.writeShort(5);
    w.writeBytes(Buffer.from("value"));
    w.writeByte(7); // byte array child
    w.writeShort(2);
    w.writeBytes(Buffer.from("ba"));
    w.writeInt(2);
    w.writeBytes(Buffer.from([1, 2]));
    w.writeByte(0); // end
  }));
});

test("C2S ClickWindow with full item", () => {
  ok(0x0e, "C2S", w((w) => w.writeByte(0).writeShort(5).writeByte(1).writeShort(3).writeByte(2).writeShort(1).writeByte(1).writeShort(10).writeByte(0)));
});

test("C2S Input is floats not bytes", () => {
  ok(0x0c, "C2S", w((w) => w.writeFloat(1).writeFloat(-1).writeByte(1)));
});

test("C2S TabComplete with blockpos", () => {
  ok(0x14, "C2S", w((w) => w.writeMCString("/say ").writeByte(1).writeLong(55n)));
});