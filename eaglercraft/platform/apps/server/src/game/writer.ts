/** Binary writer mirroring Minecraft's PacketBuffer primitive writers. */

export class PacketWriter {
  private parts: Buffer[] = [];

  writeByte(v: number): this {
    this.parts.push(Buffer.from([v & 0xff]));
    return this;
  }

  writeBoolean(v: boolean): this {
    this.parts.push(Buffer.from([v ? 1 : 0]));
    return this;
  }

  writeShort(v: number): this {
    const buf = Buffer.allocUnsafe(2);
    buf.writeInt16BE(v, 0);
    this.parts.push(buf);
    return this;
  }

  /** Unsigned 16-bit big-endian (used by the Java handshake port field). */
  writeUShort(v: number): this {
    const buf = Buffer.allocUnsafe(2);
    buf.writeUInt16BE(v, 0);
    this.parts.push(buf);
    return this;
  }

  writeInt(v: number): this {
    const buf = Buffer.allocUnsafe(4);
    buf.writeInt32BE(v, 0);
    this.parts.push(buf);
    return this;
  }

  writeFloat(v: number): this {
    const buf = Buffer.allocUnsafe(4);
    buf.writeFloatBE(v, 0);
    this.parts.push(buf);
    return this;
  }

  writeDouble(v: number): this {
    const buf = Buffer.allocUnsafe(8);
    buf.writeDoubleBE(v, 0);
    this.parts.push(buf);
    return this;
  }

  writeLong(v: bigint): this {
    const buf = Buffer.allocUnsafe(8);
    buf.writeBigInt64BE(v, 0);
    this.parts.push(buf);
    return this;
  }

  writeASCII(str: string, max = 255): this {
    const bytes = Buffer.from(str, "latin1");
    if (bytes.length > max) throw new Error(`string too long: ${bytes.length} > ${max}`);
    this.writeByte(bytes.length);
    this.parts.push(bytes);
    return this;
  }

  writeVarInt(v: number): this {
    let n = v >>> 0;
    const out: number[] = [];
    while ((n & ~0x7f) !== 0) {
      out.push((n & 0x7f) | 0x80);
      n >>>= 7;
    }
    out.push(n & 0x7f);
    this.parts.push(Buffer.from(out));
    return this;
  }

  writeVarLong(v: bigint): this {
    let n = v;
    const out: number[] = [];
    for (let i = 0; i < 10; i++) {
      const b = Number(n & 0x7fn);
      n >>= 7n;
      if ((n !== 0n || (b & 0x40) !== 0) && i < 9) {
        out.push(b | 0x80);
      } else {
        out.push(b);
        break;
      }
    }
    this.parts.push(Buffer.from(out));
    return this;
  }

  writeBytes(data: Buffer | Uint8Array): this {
    this.parts.push(Buffer.isBuffer(data) ? data : Buffer.from(data));
    return this;
  }

  writeVarIntBytes(data: Buffer | Uint8Array): this {
    this.writeVarInt(data.length);
    return this.writeBytes(data);
  }

  writeMCString(str: string): this {
    const bytes = Buffer.from(str, "utf8");
    return this.writeVarIntBytes(bytes);
  }

  toBuffer(): Buffer {
    return Buffer.concat(this.parts);
  }
}

export function makeWriter(): PacketWriter {
  return new PacketWriter();
}