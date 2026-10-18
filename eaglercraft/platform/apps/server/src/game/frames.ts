/**
 * Vanilla 1.8 on-the-wire framing for the Java proxy side.
 *
 * The vanilla server always runs a length-framing ("splitter") decoder:
 *   - login + play without compression:
 *       `[VarInt PacketLen][VarInt pid][payload]`
 *   - play with compression (after S03/S46):
 *       `[VarInt OuterLen][VarInt UncompressedLen][raw|zlib]`
 *     where UncompressedLen == 0 means the packet was below threshold and the
 *     remaining OuterLen bytes are the raw `[VarInt pid][payload]`, otherwise
 *     they are ZLIB compressed `[VarInt pid][payload]` of that size.
 *
 * The Eagler client side never sees this framing: the bridge sends/receives
 * exactly one vanilla packet per WebSocket frame (`[VarInt pid][payload]`),
 * per WebSocketNetworkManager in the client.
 */

import { deflateSync, inflateSync } from "node:zlib";
import { skipPlayPayload } from "./shallow.js";
import type { Dir } from "./shallow.js";

export interface CompressionConfig {
  threshold: number;
}

export interface StreamPacket {
  pid: number;
  payload: Buffer;
  raw: Buffer;
}

export function encodeVarInt(v: number): Buffer {
  let n = v >>> 0;
  const out: number[] = [];
  while ((n & ~0x7f) !== 0) {
    out.push((n & 0x7f) | 0x80);
    n >>>= 7;
  }
  out.push(n & 0x7f);
  return Buffer.from(out);
}

function readVarIntAt(buf: Buffer, pos: number): { value: number; len: number; complete: boolean } {
  let value = 0;
  let shift = 0;
  for (let i = 0; i < 5; i++) {
    if (pos + i >= buf.length) return { value, len: i, complete: false };
    const b = buf[pos + i];
    value |= (b & 0x7f) << shift;
    if ((b & 0x80) === 0) {
      return { value: value >>> 0, len: i + 1, complete: true };
    }
    shift += 7;
  }
  throw new Error("varint too big");
}

/** One vanilla packet `[VarInt pid][payload]` (used on the Eagler WS side). */
export function packetBytes(pid: number, payload: Buffer): Buffer {
  return Buffer.concat([encodeVarInt(pid), payload]);
}

/**
 * Frame a packet for the Java server (proxy -> server):
 *  - compression off: `[VarInt len][pid][payload]`
 *  - compression on, below threshold: `[VarInt len+1][VarInt 0][pid][payload]`
 *  - compression on, at/above threshold: `[VarInt uLenSize+zlen][VarInt uLen][zlib]`
 */
export function frameForServer(pid: number, payload: Buffer, compression?: CompressionConfig): Buffer {
  const inner = packetBytes(pid, payload);
  if (!compression) {
    return Buffer.concat([encodeVarInt(inner.length), inner]);
  }
  if (inner.length < compression.threshold) {
    const block = Buffer.concat([Buffer.from([0x00]), inner]);
    return Buffer.concat([encodeVarInt(block.length), block]);
  }
  const z = deflateSync(inner);
  const block = Buffer.concat([encodeVarInt(inner.length), z]);
  return Buffer.concat([encodeVarInt(block.length), block]);
}

export type StreamStage = "login" | "play";

/**
 * Incrementally decodes the Java-server byte stream. Caller owns buffering;
 * `parse(buf, offset)` consumes as many complete frames as possible and returns
 * the number of bytes consumed. Throws on malformed data.
 */
export class StreamFramer {
  private stage: StreamStage;
  private compression?: CompressionConfig;
  private consumed = 0;

  constructor(opts: { stage?: StreamStage; dir?: Dir } = { stage: "login", dir: "S2C" }) {
    this.stage = opts.stage ?? "login";
    this.dir = opts.dir ?? "S2C";
  }

  private dir: Dir;

  /** Enable compression at the given threshold; switches framing immediately. */
  setCompression(threshold: number): void {
    this.compression = { threshold };
  }

  enterPlay(): void {
    this.stage = "play";
  }

  get currentStage(): StreamStage {
    return this.stage;
  }

  get bytesConsumed(): number {
    return this.consumed;
  }

  parse(buf: Buffer, offset: number): { packets: StreamPacket[]; consumed: number } {
    const packets: StreamPacket[] = [];
    let pos = offset;
    for (;;) {
      const before = pos;
      const p = this.tryReadOne(buf, pos);
      if (p === null) break; // need more bytes
      packets.push(p);
      pos += p.raw.length;
      if (pos <= before) throw new Error("no progress in stream framer");
      // SetCompression / LoginSuccess switch the wire codec (compression,
      // login->play). Stop the batch so the caller applies the new state to
      // the framer before the *next* frame is decoded.
      if (this.stage === "login" && (p.pid === 0x03 || p.pid === 0x02)) break;
    }
    this.consumed = pos - offset;
    return { packets, consumed: this.consumed };
  }

  private tryReadOne(buf: Buffer, pos: number): StreamPacket | null {
    if (this.compression) {
      return this.readCompressedFrame(buf, pos);
    }
    if (this.stage === "login") {
      return this.readLengthFramed(buf, pos, READ_LOGIN_LAYOUT);
    }
    return this.readPlayFramed(buf, pos);
  }

  /** No compression, play stage: `[VarInt len][pid][payload]` with shallow cross-check. */
  private readPlayFramed(buf: Buffer, pos: number): StreamPacket | null {
    const head = readVarIntAt(buf, pos);
    if (!head.complete) return null;
    if (buf.length < pos + head.len + head.value) return null;
    const pktStart = pos + head.len;
    const pktEnd = pktStart + head.value;
    const pidAt = buf[pktStart];
    let expectBytes: number | undefined;
    try {
      expectBytes = skipPlayPayload(pidAt, this.dir, buf, pktStart + 1);
    } catch {
      /* unknown pid – trust the length field */
    }
    if (expectBytes !== undefined && expectBytes + 1 !== head.value) {
      throw new Error(`length field mismatch for play packet 0x${pidAt.toString(16)}`);
    }
    return this.slice(buf, pos, pktStart, pktStart + head.value, pidAt);
  }

  /** Login stage: `[VarInt len][pid][payload]` with a whitelisted layout. */
  private readLengthFramed(buf: Buffer, pos: number, table: Record<number, LoginSkip>): StreamPacket | null {
    const head = readVarIntAt(buf, pos);
    if (!head.complete) return null;
    if (buf.length < pos + head.len + head.value) return null;
    const pktStart = pos + head.len;
    const pidAt = buf[pktStart];
    const sk = table[pidAt];
    if (!sk) throw new Error(`unknown server login packet ${pidAt}`);
    const need = (() => {
      try {
        return sk(buf, pktStart + 1);
      } catch {
        return undefined; // incomplete
      }
    })();
    if (need !== undefined && need + 1 !== head.value) {
      throw new Error(`login packet 0x${pidAt.toString(16)} length mismatch`);
    }
    return this.slice(buf, pos, pktStart, pktStart + head.value, pidAt);
  }

  /** Compression enabled: `[VarInt outer][VarInt uLen][raw|zlib]`. */
  private readCompressedFrame(buf: Buffer, pos: number): StreamPacket | null {
    const head = readVarIntAt(buf, pos);
    if (!head.complete) return null;
    if (buf.length < pos + head.len + head.value) return null;
    const frameEnd = pos + head.len + head.value;
    const bodyStart = pos + head.len;
    const uLen = readVarIntAt(buf, bodyStart);
    if (!uLen.complete) throw new Error("bad compression header");
    const dataStart = bodyStart + uLen.len;
    if (uLen.value === 0) {
      // raw `[VarInt pid][payload]` occupies the rest of the frame
      const pidAt = readVarIntAt(buf, dataStart);
      if (!pidAt.complete) throw new Error("bad raw frame header");
      return this.slice(buf, pos, dataStart, frameEnd, pidAt.value);
    }
    const zlen = buf.subarray(dataStart, frameEnd);
    let innerBuf: Buffer;
    try {
      innerBuf = inflateSync(zlen);
    } catch {
      throw new Error("bad zlib block");
    }
    const pid = readVarIntAt(innerBuf, 0);
    if (!pid.complete) throw new Error("bad compressed packet header");
    const payload = innerBuf.subarray(pid.len);
    return { pid: pid.value, payload, raw: buf.subarray(pos, frameEnd) };
  }

  private slice(buf: Buffer, rawStart: number, pidStart: number, end: number, pid: number): StreamPacket {
    const pidLen = encodeVarInt(pid).length;
    return { pid, payload: buf.subarray(pidStart + pidLen, end), raw: buf.subarray(rawStart, end) };
  }
}

type LoginSkip = (buf: Buffer, pos: number) => number;

function loginSkipString(buf: Buffer, pos: number): number {
  const { value: len, len: l, complete } = readVarIntAt(buf, pos);
  if (!complete) throw new Error("incomplete login string length");
  return l + len;
}

function loginSkipStringThenString(buf: Buffer, pos: number): number {
  const { value: lenA, len: la, complete } = readVarIntAt(buf, pos);
  if (!complete) throw new Error("incomplete login string length");
  pos += la + lenA;
  const { value: lenB, len: lb, complete: c2 } = readVarIntAt(buf, pos);
  if (!c2) throw new Error("incomplete login string length");
  return la + lenA + lb + lenB;
}

function loginSkipVarInt(buf: Buffer, pos: number): number {
  const { len, complete } = readVarIntAt(buf, pos);
  if (!complete) throw new Error("incomplete login varint");
  return len;
}

function loginSkipEncryptionRequest(buf: Buffer, pos: number): number {
  const { value: serverIdLen, len: a, complete: c1 } = readVarIntAt(buf, pos);
  if (!c1) throw new Error("incomplete login string length");
  pos += a + serverIdLen;
  const { value: keyLen, len: b, complete: c2 } = readVarIntAt(buf, pos);
  if (!c2) throw new Error("incomplete login bytes length");
  pos += b + keyLen;
  const { value: verifyLen, len: c, complete: c3 } = readVarIntAt(buf, pos);
  if (!c3) throw new Error("incomplete login bytes length");
  return a + serverIdLen + b + keyLen + c + verifyLen;
}

function loginSkipDisconnect(buf: Buffer, pos: number): number {
  return loginSkipString(buf, pos);
}

const READ_LOGIN_LAYOUT: Record<number, LoginSkip> = {
  0x00: loginSkipDisconnect, // Disconnect
  0x01: loginSkipEncryptionRequest, // EncryptionRequest
  0x02: loginSkipStringThenString, // LoginSuccess
  0x03: loginSkipVarInt, // SetCompression
};

/** Decode a single complete packet that fills its buffer (used for the Eagler WS side). */
export function parseSingle(data: Buffer): StreamPacket {
  const head = readVarIntAt(data, 0);
  if (!head.complete) throw new Error("bad packet header");
  return { pid: head.value, payload: data.subarray(head.len), raw: data };
}

/** Encode one vanilla packet for the Eagler client (one per WS frame): `[pid][payload]`. */
export function frameForClientPacket(pid: number, payload: Buffer): Buffer {
  return packetBytes(pid, payload);
}

/** True if the packet is an injected Eagler frame (`0xEE` prefix on the client WS). */
export function isEagFrame(data: Buffer): boolean {
  return data.length > 0 && data[0] === 0xee;
}