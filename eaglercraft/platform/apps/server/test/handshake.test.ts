import { test } from "node:test";
import assert from "node:assert/strict";
import { EaglerHandshake } from "../src/bridge/handshake.js";
import { PacketWriter } from "../src/game/writer.js";

const w = (fn: (w: PacketWriter) => void): Buffer => {
  const ww = new PacketWriter();
  fn(ww);
  return ww.toBuffer();
};

function clientVersionPacket(): Buffer {
  // mirrors HandshakerHandler.beginHandshake()
  const b = w((w) => {
    w.writeByte(0x01);
    w.writeByte(0x02); // legacy
    w.writeShort(3); // eagler count
    w.writeShort(3);
    w.writeShort(4);
    w.writeShort(5);
    w.writeShort(1); // game count
    w.writeShort(47);
    w.writeASCII("eaglerplatform");
    w.writeASCII("1.0.0");
    w.writeBoolean(false);
    w.writeASCII("Player123");
  });
  return b;
}

function v5RequestLogin(): Buffer {
  // mirrors HandshakerV5.sendClientRequestLogin with nicknameSelection=false,
  // no password, no cookies, zero caps
  return w((w) => {
    w.writeByte(0x04);
    w.writeByte(0); // nicknameSelection disabled -> 0 length
    w.writeByte(7); // requestedServer "default"
    w.writeBytes(Buffer.from("default", "latin1"));
    w.writeByte(0); // no password
    w.writeBoolean(false); // cookies off
    w.writeByte(0); // cookie length
    w.writeVarInt(0); // standard caps
    w.writeByte(0); // extended caps
  });
}

test("v5: server version reply matches client parse", () => {
  const hs = new EaglerHandshake({ brand: "eaglerplatform", version: "1.0.0" });
  const r = hs.processFrame(clientVersionPacket());
  assert.equal(r.incoming.length, 1);
  const frame = r.incoming[0];
  const buf = frame;
  assert.equal(buf[0], 0x02, "SERVER_VERSION");
  const eag = buf.readUInt16BE(1);
  assert.equal(eag, 5);
  assert.equal(buf.readUInt16BE(3), 47);
  // brand: [u8 len][brand]
  assert.equal(buf[5], "eaglerplatform".length);
  let pos = 6 + "eaglerplatform".length;
  assert.equal(buf[pos], "1.0.0".length);
  pos += 1 + "1.0.0".length;
  assert.equal(buf[pos], 0x00, "auth type none");
  const saltLen = buf.readUInt16BE(pos + 1);
  assert.equal(saltLen, 16);
  pos += 3 + saltLen;
  assert.equal(buf[pos], 0, "nicknameSelection false");
});

test("v5: request login -> allow login, profile data, finish", () => {
  const hs = new EaglerHandshake({ brand: "x", version: "y" });
  hs.processFrame(clientVersionPacket());
  const r = hs.processFrame(v5RequestLogin());
  assert.ok(r.event);
  assert.equal(r.event.type, "request_login");
  const ev = r.event as { username: string; requestedServer: string; password?: string };
  assert.equal(ev.requestedServer, "default");
  assert.equal(ev.password, undefined);

  const allow = hs.sendAllowLogin("Player123", Buffer.from("0123456789abcdef", "utf8"));
  assert.equal(allow[0], 0x05);
  assert.equal(allow[1], 9); // username len "Player123"

  // client sends profile data (v4+: [count][keyLen][key][u16 len][data])
  const pd = w((w) => {
    w.writeByte(0x07);
    w.writeByte(1);
    w.writeASCII("brand_uuid_v1");
    w.writeShort(16);
    w.writeBytes(Buffer.alloc(16, 7));
  });
  const pdr = hs.processFrame(pd);
  assert.equal(pdr.event?.type, "profile_data");

  const fin = hs.processFrame(Buffer.from([0x08]));
  assert.equal(fin.finished, true);
  assert.equal(fin.incoming[0][0], 0x09); // SERVER_FINISH_LOGIN
});

test("v3: request login uses plain username and no cookie/caps", () => {
  const hs = new EaglerHandshake({ brand: "x", version: "y" });
  // force v3 negotiation: advertise only v3
  const cv = w((w) => {
    w.writeByte(1);
    w.writeByte(2);
    w.writeShort(1);
    w.writeShort(3);
    w.writeShort(1);
    w.writeShort(47);
    w.writeASCII("x");
    w.writeASCII("y");
    w.writeBoolean(false);
    w.writeASCII("Bob");
  });
  const rv = hs.processFrame(cv);
  assert.equal(rv.incoming[0].readUInt16BE(1), 3, "negotiated v3");
  const req = w((w) => {
    w.writeByte(4);
    w.writeASCII("Bob");
    w.writeASCII("default");
    w.writeByte(0);
  });
  const rr = hs.processFrame(req);
  assert.equal(rr.event?.type, "request_login");
  const ev = rr.event as { username: string };
  assert.equal(ev.username, "Bob");
  const allow = hs.sendAllowLogin("Bob", Buffer.alloc(16, 1));
  assert.equal(allow[0], 5);
  assert.equal(allow.length, 2 + 3 + 16, "v3 allow login has no caps tail");
});

test("deny login frame is u16-length JSON (client reads it)", () => {
  const hs = new EaglerHandshake({ brand: "x", version: "y" });
  const deny = hs.sendDeny("bad username");
  assert.equal(deny[0], 0x06);
  const len = deny.readUInt16BE(1);
  const json = deny.subarray(3, 3 + len).toString("utf8");
  assert.equal(JSON.parse(json).text, "bad username");
});

test("server error custom message uses u8 length for v4+", () => {
  const hs = new EaglerHandshake();
  hs.processFrame(clientVersionPacket());
  const err = hs.sendServerError("nope");
  assert.equal(err[0], 0xff);
  assert.equal(err[1], 0x08); // CUSTOM_MESSAGE
  assert.equal(err[2], 4); // u8 length
});