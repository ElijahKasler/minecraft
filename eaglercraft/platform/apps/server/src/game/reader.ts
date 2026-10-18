/** Binary reader mirroring Minecraft's PacketBuffer primitive readers. */

export class PacketReader {
  private pos = 0;

  constructor(
    private data: Buffer,
    offset = 0,
  ) {
    this.pos = offset;
  }

  get remaining(): number {
    return this.data.length - this.pos;
  }

  get position(): number {
    return this.pos;
  }

  readUnsignedByte(): number {
    if (this.remaining < 1) throw new Error("buffer underflow (byte)");
    return this.data[this.pos++];
  }

  readBoolean(): boolean {
    return this.readUnsignedByte() !== 0;
  }

  readShort(): number {
    if (this.remaining < 2) throw new Error("buffer underflow (short)");
    const v = this.data.readInt16BE(this.pos);
    this.pos += 2;
    return v;
  }

  readUnsignedShort(): number {
    if (this.remaining < 2) throw new Error("buffer underflow (ushort)");
    const v = this.data.readUInt16BE(this.pos);
    this.pos += 2;
    return v;
  }

  readInt(): number {
    if (this.remaining < 4) throw new Error("buffer underflow (int)");
    const v = this.data.readInt32BE(this.pos);
    this.pos += 4;
    return v;
  }

  readLong(): bigint {
    if (this.remaining < 8) throw new Error("buffer underflow (long)");
    const v = this.data.readBigInt64BE(this.pos);
    this.pos += 8;
    return v;
  }

  readVarInt(): number {
    let value = 0;
    let shift = 0;
    for (let i = 0; i < 5; i++) {
      const b = this.readUnsignedByte();
      value |= (b & 0x7f) << shift;
      if ((b & 0x80) === 0) return value >>> 0;
      shift += 7;
    }
    throw new Error("varint too big");
  }

  /** Len(1 byte) + Latin-1 string, used in the Eagler handshake. */
  readASCII(): string {
    const len = this.readUnsignedByte();
    return this.readBytes(len).toString("latin1");
  }

  /** VarInt(len) + UTF-8 string, used in vanilla MC packets. */
  readMCString(): string {
    const len = this.readVarInt();
    if (len > 32767) throw new Error("string too long");
    return this.readBytes(len).toString("utf8");
  }

  readBytes(len: number): Buffer {
    if (len < 0 || this.remaining < len) throw new Error("buffer underflow (bytes)");
    const out = this.data.subarray(this.pos, this.pos + len);
    this.pos += len;
    return out;
  }

  peekByte(): number {
    if (this.remaining < 1) throw new Error("buffer underflow (peek)");
    return this.data[this.pos];
  }
}