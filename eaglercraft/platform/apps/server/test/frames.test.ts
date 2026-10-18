import { test } from "node:test";
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { PacketWriter } from "../src/game/writer.js";
import { StreamFramer, frameForServer, encodeVarInt, parseSingle } from "../src/game/frames.js";

function w(fn: (w: PacketWriter) => void): Buffer {
  const writer = new PacketWriter();
  fn(writer);
  return writer.toBuffer();
}

function readVarIntAt(buf: Buffer, pos: number): { value: number; len: number; complete: boolean } {
  let value = 0;
  let shift = 0;
  for (let i = 0; i < 5; i++) {
    if (pos + i >= buf.length) return { value, len: i, complete: false };
    const b = buf[pos + i];
    value |= (b & 0x7f) << shift;
    if ((b & 0x80) === 0) return { value: value >>> 0, len: i + 1, complete: true };
    shift += 7;
  }
  throw new Error("varint too big");
}

test("encodeVarInt round trip", () => {
  for (const v of [0, 1, 127, 128, 255, 300, 3000, 2097151, 2147483647]) {
    const r = readVarIntAt(encodeVarInt(v), 0);
    assert.equal(r.value, v);
    assert.equal(r.complete, true);
  }
});

test("frameForServer without compression is length prefixed", () => {
  const payload = Buffer.from("hi");
  const frame = frameForServer(0x02, payload);
  const h = readVarIntAt(frame, 0);
  assert.equal(h.value, 1 + payload.length);
  const p = parseSingle(frame.subarray(h.len));
  assert.equal(p.pid, 0x02);
  assert.equal(p.payload.toString(), "hi");
});

test("frameForServer below-threshold uses [len][0][pid][payload]", () => {
  const payload = Buffer.from("hello");
  const frame = frameForServer(0x02, payload, { threshold: 256 });
  const h = readVarIntAt(frame, 0);
  const block = frame.subarray(h.len);
  assert.equal(block[0], 0x00);
  const p = parseSingle(block.subarray(1));
  assert.equal(p.pid, 0x02);
  assert.equal(p.payload.toString(), "hello");
});

test("compressed frame larger than threshold decodes", () => {
  const payload = randomBytes(5000);
  const frame = frameForServer(0x00, payload, { threshold: 256 });
  const framer = new StreamFramer({ stage: "play", dir: "S2C" });
  framer.setCompression(256);
  const { packets, consumed } = framer.parse(frame, 0);
  assert.equal(consumed, frame.length);
  assert.equal(packets.length, 1);
  assert.equal(packets[0].pid, 0x00);
  assert.deepEqual(packets[0].payload, payload);
});

test("below-threshold compressed frame round trips through framer", () => {
  const body = w((w) => {
    w.writeVarInt(7);
    w.writeLong(1n).writeLong(2n);
    w.writeInt(0).writeInt(64).writeInt(0);
    w.writeByte(0).writeByte(0).writeShort(0);
    w.writeByte(0x7f); // metadata terminator
  });
  const frame = frameForServer(0x0c, body, { threshold: 256 });
  const framer = new StreamFramer({ stage: "play", dir: "S2C" });
  framer.setCompression(256);
  const { packets, consumed } = framer.parse(frame, 0);
  assert.equal(consumed, frame.length);
  assert.equal(packets.length, 1);
  assert.equal(packets[0].pid, 0x0c);
  assert.deepEqual(packets[0].payload, body);
});

test("multiple packets in one stream buffer", () => {
  const chat = frameForServer(0x02, w((w) => w.writeMCString("{\"text\":\"hi\"}").writeByte(0)), { threshold: 256 });
  const ka = frameForServer(0x00, w((w) => w.writeVarInt(123)), { threshold: 256 });
  const framer = new StreamFramer({ stage: "play", dir: "S2C" });
  framer.setCompression(256);
  const { packets, consumed } = framer.parse(Buffer.concat([chat, ka]), 0);
  assert.equal(consumed, chat.length + ka.length);
  assert.equal(packets.length, 2);
  assert.equal(packets[0].pid, 0x02);
  assert.equal(packets[1].pid, 0x00);
});

test("stream split across arbitrary TCP chunk boundaries", () => {
  const body = w((w) => {
    w.writeVarInt(1);
    w.writeLong(0x1020304050607080n).writeLong(0x0f0e0d0c0b0a0908n);
    w.writeInt(1).writeInt(2).writeInt(3);
    w.writeByte(10).writeByte(20).writeShort(77);
    w.writeByte(0x7f);
  });
  const frame = frameForServer(0x0c, body, { threshold: 256 });
  const framer = new StreamFramer({ stage: "play", dir: "S2C" });
  framer.setCompression(256);
  let buf = Buffer.alloc(0);
  let totalConsumed = 0;
  let got = 0;
  for (let i = 0; i < frame.length; i += 2) {
    const chunk = frame.subarray(i, i + 2);
    buf = Buffer.concat([buf.subarray(totalConsumed), chunk]);
    if (buf.length === 0) {
      totalConsumed = 0;
      continue;
    }
    const r = framer.parse(buf, 0);
    totalConsumed = r.consumed;
    got += r.packets.length;
  }
  const r = framer.parse(buf.subarray(totalConsumed), 0);
  got += r.packets.length;
  assert.equal(got, 1);
});

test("play without compression is length prefixed (no shallow needed)", () => {
  const frame = frameForServer(0x0c, w((w) => {
    w.writeVarInt(7);
    w.writeLong(1n).writeLong(2n);
    w.writeInt(0).writeInt(64).writeInt(0);
    w.writeByte(0).writeByte(0).writeShort(0);
    w.writeByte(0x7f);
  }));
  const framer = new StreamFramer({ stage: "play", dir: "S2C" });
  const { packets, consumed } = framer.parse(frame, 0);
  assert.equal(consumed, frame.length);
  assert.equal(packets.length, 1);
  assert.equal(packets[0].pid, 0x0c);
});

test("login stage parses LoginSuccess (length framed)", () => {
  const payload = w((w) => {
    w.writeMCString("8a3f2b0c-1111-2222-3333-444455556666");
    w.writeMCString("Player");
  });
  const frame = frameForServer(0x02, payload);
  const framer = new StreamFramer({ stage: "login", dir: "S2C" });
  const { packets, consumed } = framer.parse(frame, 0);
  assert.equal(consumed, frame.length);
  assert.equal(packets.length, 1);
  assert.equal(packets[0].pid, 2);
});

test("SetCompression flips framing", () => {
  const s03 = frameForServer(0x03, w((w) => w.writeVarInt(256)));
  const framer = new StreamFramer({ stage: "login", dir: "S2C" });
  const { packets } = framer.parse(s03, 0);
  assert.equal(packets[0].pid, 3);
  framer.setCompression(256);
  framer.enterPlay();
  const chat = frameForServer(0x02, w((w) => w.writeMCString("x").writeByte(0)), { threshold: 256 });
  const r = framer.parse(chat, 0);
  assert.equal(r.packets.length, 1);
  assert.equal(r.packets[0].pid, 0x02);
});