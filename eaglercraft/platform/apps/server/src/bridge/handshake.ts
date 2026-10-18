/**
 * Server side of the Eagler v3/v4/v5 WebSocket handshake, byte-for-byte
 * compatible with the client's HandshakerHandler / HandshakerV3/V4/V5.
 *
 * The Eagler client sends exactly ONE handshake packet per WS binary frame, so
 * processFrame() parses `data` (one frame) directly - there is no cross-frame
 * buffering (see WebSocketNetworkManager in the client).
 *
 * Wire formats (verified against client source):
 *   C->S VERSION      01,02,[3 shorts 3/4/5],[1 short][47],[len brand],[len ver],bool,[len user]
 *   S->C VERSION      02,[u16 eaglerVer],[u16 47],[len brand],[len ver],byte auth,[u16 saltLen][salt],[v5: bool nickSel]
 *   C->S REQUEST_LOGIN 04, v3:[u8 user][u8 server][u8 pass], v4:+[bool][u8 cookie],
 *                          v5:+[varint caps][varint*][u8 ext][ext:long,long,varint]
 *   S->C ALLOW_LOGIN  05,[u8 user][8msb][8lsb]  v5:+[varint caps 0][u8 ext 0]
 *   C->S PROFILE_DATA 07, v3:[u8 key][u16 len][data] | v4/v5:[u8 count][entries]
 *   C->S FINISH_LOGIN 08
 *   S->C FINISH_LOGIN 09
 *   S->C DENY_LOGIN   06,[u16 len][json]
 *   S->C ERROR        ff,code,[v3: u16 len else u8 len][text]
 */

import { EAG } from "@eaglerplatform/shared";
import { PacketWriter } from "../game/writer.js";

export type EaglerVersion = 3 | 4 | 5;

export type HandshakeEvent =
  | { type: "request_login"; username: string; requestedServer: string; password?: string }
  | { type: "profile_data"; entries: { key: string; data: Buffer }[] }
  | { type: "finish_login" }
  | { type: "protocol_error"; message: string };

export interface HandshakeResult {
  incoming: Buffer[];
  event?: HandshakeEvent;
  finished?: boolean;
}

export class EaglerHandshake {
  version: EaglerVersion = 5;
  state: "version" | "login" | "complete" | "finished" | "failed" = "version";
  username = "";
  private salt = randomSalt();

  constructor(opts: { brand?: string; version?: string } = {}) {
    this.brand = opts.brand ?? "eaglerplatform";
    this.pluginVersion = opts.version ?? "1.0.0";
  }

  private brand: string;
  private pluginVersion: string;

  /** Process one inbound WS binary frame (one packet); returns frames to send + an event. */
  processFrame(data: Buffer): HandshakeResult {
    if (this.state === "version") {
      const pkt = this.parseVersionPacket(data);
      if (!pkt) return { incoming: [] };
      const ver = pkt.supportedEaglerVersions.includes(5)
        ? 5
        : pkt.supportedEaglerVersions.includes(4)
          ? 4
          : 3;
      this.version = checkVersion(ver);
      this.state = "login";
      return { incoming: [this.buildServerVersion()] };
    }

    if (this.state === "login") {
      const pkt = this.parseRequestLogin(data);
      if (!pkt) return { incoming: [] };
      this.username = pkt.username;
      return {
        incoming: [],
        event: {
          type: "request_login",
          username: pkt.username,
          requestedServer: pkt.requestedServer,
          ...(pkt.password !== undefined ? { password: pkt.password } : {}),
        },
      };
    }

    if (this.state === "complete") {
      const first = data[0];
      if (first === EAG.CLIENT_FINISH_LOGIN) {
        this.state = "finished";
        return { incoming: [Buffer.from([EAG.SERVER_FINISH_LOGIN])], event: { type: "finish_login" }, finished: true };
      }
      if (first === EAG.CLIENT_PROFILE_DATA) {
        const entries = this.parseProfileData(data);
        return { incoming: [], event: { type: "profile_data", entries } };
      }
      throw new Error(`unexpected handshake packet 0x${first.toString(16)} in complete state`);
    }

    throw new Error(`handshake in state ${this.state} cannot process frames`);
  }

  sendAllowLogin(username: string, uuid: Buffer): Buffer {
    if (this.state === "failed") throw new Error("handshake already failed");
    const w = new PacketWriter();
    w.writeByte(EAG.SERVER_ALLOW_LOGIN);
    w.writeASCII(username);
    const msb = uuid.subarray(0, 8);
    const lsb = uuid.subarray(8, 16);
    w.writeBytes(asBigEndian(msb));
    w.writeBytes(asBigEndian(lsb));
    if (this.version >= 5) {
      w.writeVarInt(0); // standard caps = none
      w.writeByte(0); // extended caps count
    }
    this.state = "complete";
    return w.toBuffer();
  }

  sendDeny(message: string): Buffer {
    this.state = "failed";
    const json = JSON.stringify({ text: message });
    const w = new PacketWriter();
    w.writeByte(EAG.SERVER_DENY_LOGIN);
    w.writeShort(Buffer.byteLength(json, "utf8"));
    w.writeBytes(Buffer.from(json, "utf8"));
    return w.toBuffer();
  }

  sendServerError(message: string): Buffer {
    this.state = "failed";
    const bytes = Buffer.from(message, "utf8");
    const w = new PacketWriter();
    w.writeByte(EAG.SERVER_ERROR);
    w.writeByte(EAG.SERVER_ERROR_CUSTOM_MESSAGE);
    if (this.version === 3) {
      w.writeShort(bytes.length);
    } else {
      w.writeByte(bytes.length);
    }
    w.writeBytes(bytes);
    return w.toBuffer();
  }

  get finished(): boolean {
    return this.state === "finished";
  }

  get failed(): boolean {
    return this.state === "failed";
  }

  private parseVersionPacket(data: Buffer): { supportedEaglerVersions: number[]; gameVersions: number[] } | null {
    const buf = data;
    if (buf.length < 1) return null;
    if (buf[0] !== EAG.CLIENT_VERSION) throw new Error(`expected version handshake, got 0x${buf[0].toString(16)}`);
    let pos = 1;
    const peekLen = (need: number): boolean => buf.length >= pos + need;
    if (!peekLen(1)) return null; // legacy version byte
    pos += 1;
    if (!peekLen(2)) return null; // supported eagler count
    const eagCount = buf.readUInt16BE(pos);
    pos += 2;
    if (!peekLen(eagCount * 2)) return null;
    const eagVers: number[] = [];
    for (let i = 0; i < eagCount; i++) {
      eagVers.push(buf.readUInt16BE(pos));
      pos += 2;
    }
    if (!peekLen(2)) return null;
    const gameCount = buf.readUInt16BE(pos);
    pos += 2;
    if (!peekLen(gameCount * 2)) return null;
    const gameVers: number[] = [];
    for (let i = 0; i < gameCount; i++) {
      gameVers.push(buf.readUInt16BE(pos));
      pos += 2;
    }
    return { supportedEaglerVersions: eagVers, gameVersions: gameVers };
  }

  private buildServerVersion(): Buffer {
    const w = new PacketWriter();
    w.writeByte(EAG.SERVER_VERSION);
    w.writeShort(this.version);
    w.writeShort(47);
    w.writeASCII(this.brand);
    w.writeASCII(this.pluginVersion);
    w.writeByte(EAG.AUTH_METHOD_NONE);
    w.writeShort(this.salt.length);
    w.writeBytes(this.salt);
    if (this.version >= 5) {
      w.writeByte(0); // nicknameSelection: false
    }
    return w.toBuffer();
  }

  private parseRequestLogin(data: Buffer): { username: string; requestedServer: string; password?: string } | null {
    const buf = data;
    if (buf.length === 0 || buf[0] !== EAG.CLIENT_REQUEST_LOGIN) return null;
    let pos = 1;

    const takeString = (): string | null => {
      if (buf.length < pos + 1) return null;
      const len = buf[pos++];
      if (buf.length < pos + len) return null;
      const s = buf.subarray(pos, pos + len).toString("latin1");
      pos += len;
      return s;
    };

    // v3/v4 send the username; v5 sends nickname selection (zero bytes when
    // we answered nicknameSelection=false, leaving user == "").
    const user = takeString();
    if (user === null) return null;
    const requestedServer = takeString();
    if (requestedServer === null) return null;

    if (buf.length < pos + 1) return null;
    const passLength = buf[pos++];
    let password: string | undefined;
    if (passLength > 0) {
      if (buf.length < pos + passLength) return null;
      password = buf.subarray(pos, pos + passLength).toString("latin1");
      pos += passLength;
    }

    if (this.version >= 5) {
      // v5: [varint caps][varint per set bit][u8 extCount][per ext 16 bytes + varint]
      const afterCaps = skipV5Caps(buf, pos);
      if (afterCaps === null) return null;
      pos = afterCaps;
    }

    return { username: user, requestedServer, password };
  }

  private parseProfileData(data: Buffer): { key: string; data: Buffer }[] {
    // v3: one entry per frame. v4+: [u8 count] + entries.
    let pos = 1;
    const count = this.version >= 4 ? data[pos++] : 1;
    const entries: { key: string; data: Buffer }[] = [];
    for (let i = 0; i < count; i++) {
      if (pos >= data.length) break;
      const keyLen = data[pos++];
      if (pos + keyLen > data.length) throw new Error("bad profile data key");
      const key = data.subarray(pos, pos + keyLen).toString("latin1");
      pos += keyLen;
      if (pos + 2 > data.length) throw new Error("bad profile data length");
      const dataLen = data.readUInt16BE(pos);
      pos += 2;
      if (pos + dataLen > data.length) throw new Error("bad profile data value");
      entries.push({ key, data: data.subarray(pos, pos + dataLen) });
      pos += dataLen;
    }
    return entries;
  }
}

function skipV5Caps(buf: Buffer, pos: number): number | null {
  const r = readVarIntAt(buf, pos);
  if (!r) return null;
  pos += r.len;
  const caps = r.value;
  const setBits = bitCount(caps);
  for (let i = 0; i < setBits; i++) {
    const v = readVarIntAt(buf, pos);
    if (!v) return null;
    pos += v.len;
  }
  if (pos >= buf.length) return null;
  const extCount = buf[pos++];
  for (let i = 0; i < extCount; i++) {
    if (pos + 16 > buf.length) return null;
    pos += 16; // msb + lsb
    const v = readVarIntAt(buf, pos);
    if (!v) return null;
    pos += v.len;
  }
  return pos;
}

function readVarIntAt(buf: Buffer, pos: number): { value: number; len: number } | null {
  let value = 0;
  let shift = 0;
  for (let i = 0; i < 5; i++) {
    if (pos + i >= buf.length) return null;
    const b = buf[pos + i];
    value |= (b & 0x7f) << shift;
    if ((b & 0x80) === 0) return { value: value >>> 0, len: i + 1 };
    shift += 7;
  }
  return null;
}

function bitCount(v: number): number {
  let n = v >>> 0;
  let c = 0;
  while (n) {
    n &= n - 1;
    c++;
  }
  return c;
}

function checkVersion(v: number): EaglerVersion {
  if (v === 3 || v === 4 || v === 5) return v;
  throw new Error(`unsupported eagler version ${v}`);
}

function randomSalt(): Buffer {
  // 16 bytes of fresh randomness for AUTH_METHOD_NONE (never used for hashing)
  const buf = Buffer.alloc(16);
  for (let i = 0; i < 16; i++) buf[i] = Math.floor(Math.random() * 256);
  return buf;
}

/** Reinterpret a UUID's 2x BE int64 as a single 16-byte big-endian blob (matches client EaglercraftUUID readLong/readLong). */
function asBigEndian(block: Buffer): Buffer {
  return Buffer.from(block);
}