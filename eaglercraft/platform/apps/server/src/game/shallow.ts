/**
 * Minimal "shallow" parsers for vanilla Minecraft 1.8 packets: given a raw
 * packet's payload bytes (after the packet-id VarInt), compute the exact
 * payload length by skipping fields. This is required because with network
 * compression enabled, packets below the threshold are transmitted WITHOUT a
 * length prefix, so the only way to demark a stream is to know each packet's
 * layout. Also used to make the play relay robust to coalesced frames.
 *
 * Every layout below mirrors the client's readPacketData() implementations
 * (MCP 9.18 mappings) + EnumConnectionState registration order (see
 * docs/SOURCE_MAP.md section 8). Verified against the bundled client source:
 *   - entity ids are VarInts in most packets, but S01 JoinGame / S08 PosLook /
 *     S29 Sound / S2A Particles / S2B / S34 Maps use fixed ints in places.
 *   - metadata string values are VarInt-length strings (NOT short-length).
 *   - CustomPayload (0x17 C2S / 0x3F S2C) consumes the remainder of the frame.
 */

export class Skip {
  constructor(private data: Buffer, public pos = 0) {}

  need(n: number): void {
    if (n < 0 || this.data.length - this.pos < n) throw new Error(`underrun (${n})`);
  }
  byte(): void {
    this.need(1);
    this.pos += 1;
  }
  short(): void {
    this.need(2);
    this.pos += 2;
  }
  int(): void {
    this.need(4);
    this.pos += 4;
  }
  long(): void {
    this.need(8);
    this.pos += 8;
  }
  float(): void {
    this.need(4);
    this.pos += 4;
  }
  double(): void {
    this.need(8);
    this.pos += 8;
  }
  varint(): number {
    let value = 0;
    let shift = 0;
    for (let i = 0; i < 5; i++) {
      this.need(1);
      const b = this.data[this.pos++];
      value |= (b & 0x7f) << shift;
      if ((b & 0x80) === 0) return value >>> 0;
      shift += 7;
    }
    throw new Error("varint too big");
  }
  /** Byte just consumed (after byte()). */
  u8(): number {
    this.byte();
    return this.data[this.pos - 1];
  }
  /** Short just consumed (after short()). */
  i16(): number {
    this.short();
    return this.data.readInt16BE(this.pos - 2);
  }
  /** Unsigned short just consumed. */
  u16(): number {
    this.short();
    return this.data.readUInt16BE(this.pos - 2);
  }
  /** Int just consumed. */
  i32(): number {
    this.int();
    return this.data.readInt32BE(this.pos - 4);
  }
  /** Boolean (1 byte) just consumed. */
  bool(): boolean {
    this.byte();
    return this.data[this.pos - 1] !== 0;
  }
  /** Byte at the current cursor without advancing. */
  peekU8(): number {
    this.need(1);
    return this.data[this.pos];
  }
  /** ReadPacketBuffer.readVarLong: signed, up to 10 bytes. */
  varLong(): void {
    let shift = 0;
    for (let i = 0; i < 10; i++) {
      this.need(1);
      const b = this.data[this.pos++];
      if ((b & 0x80) === 0) return;
      shift += 7;
    }
    throw new Error("varlong too big");
  }
  /** 16-bit-length string ("MC String" as used inside NBT compounds). */
  str16(): void {
    this.short();
    const len = this.data.readUInt16BE(this.pos - 2);
    this.need(len);
    this.pos += len;
  }
  /** VarInt-length string (readStringFromBuffer). */
  strVarInt(): void {
    const len = this.varint();
    this.need(len);
    this.pos += len;
  }
  /** Consume everything remaining in the current frame buffer. */
  remaining(): void {
    this.pos = this.data.length;
  }
  bytes(len: number): void {
    this.need(len);
    this.pos += len;
  }
  uuid(): void {
    this.long();
    this.long();
  }

  /** Vanilla ItemStack: short id; if == -1 nothing else count+damage+NBT. */
  item(): void {
    this.short();
    const id = this.data.readInt16BE(this.pos - 2);
    if (id === -1) return;
    this.byte();
    this.short();
    this.nbt();
  }

  /**
   * NBT: tagId byte; if non-zero skip the root name (network NBT always
   * writes an element name, even at the root) then the value tree.
   */
  nbt(): void {
    const tag = this.data[this.pos++];
    if (tag === 0) return;
    this.str16(); // root element name (usually "")
    this.tagValue(tag);
  }

  private tagValue(tag: number): void {
    switch (tag) {
      case 1: return this.byte();
      case 2: return this.short();
      case 3: return this.int();
      case 4: return this.long();
      case 5: return this.float();
      case 6: return this.double();
      case 7: return this.bytes(this.data.readInt32BE((this.int(), this.pos - 4)));
      case 8:
        this.short();
        return this.bytes(this.data.readUInt16BE(this.pos - 2));
      case 9: {
        const child = this.data[this.pos++];
        const count = this.data.readInt32BE((this.int(), this.pos - 4));
        for (let i = 0; i < count; i++) this.tagValue(child);
        return;
      }
      case 10:
        for (;;) {
          const t = this.data[this.pos++];
          if (t === 0) return;
          this.str16();
          this.tagValue(t);
        }
      case 11: {
        const count = this.data.readInt32BE((this.int(), this.pos - 4));
        return this.bytes(count * 4);
      }
      default:
        throw new Error(`unknown NBT tag ${tag}`);
    }
  }

  /**
   * 1.8 entity metadata: terminates at 0x7F; type = top 3 bits, index = low 5.
   * Vanilla = DataWatcher.readWatchedListFromPacketBuffer:
   *   type 4 (string) reads a VarInt-length string; 5 = ItemStack; 6 = 3 ints;
   *   7 = Rotations (3 floats).
   */
  metadata(): void {
    for (;;) {
      this.need(1);
      const b = this.data[this.pos++];
      if (b === 0x7f) return;
      const type = b >>> 5;
      switch (type) {
        case 0: this.byte(); break;
        case 1: this.short(); break;
        case 2: this.int(); break;
        case 3: this.float(); break;
        case 4: this.strVarInt(); break;
        case 5: this.item(); break;
        case 6: this.int(); this.int(); this.int(); break;
        case 7: this.float(); this.float(); this.float(); break;
        default: throw new Error(`unknown metadata type ${type}`);
      }
    }
  }
}

export type Dir = "S2C" | "C2S";

/** Skip an entire play/status payload starting at `offset`; returns consumed length. */
export function skipPlayPayload(pid: number, dir: Dir, data: Buffer, offset: number): number {
  const s = new Skip(data, offset);
  try {
    skipPlay(s, pid, dir);
  } catch (err) {
    if (err instanceof Error) err.message = `packet ${pid} (${dir}): ${err.message}`;
    throw err;
  }
  return s.pos - offset;
}

function popcount32(n: number): number {
  n = n - ((n >>> 1) & 0x55555555);
  n = (n & 0x33333333) + ((n >>> 2) & 0x33333333);
  return (((n + (n >>> 4)) & 0x0f0f0f0f) * 0x01010101) >>> 24;
}

/** S21PacketChunkData.func_180737_a: bytes of payload for `sections` chunks. */
export function chunkDataBytes(sections: number, skylight: boolean): number {
  return sections * 8192 + sections * 4096 + (skylight ? sections * 4096 : 0) + 256;
}

function skipPlay(s: Skip, pid: number, dir: Dir): void {
  const item = () => s.item();
  const nbt = () => s.nbt();
  const metadata = () => s.metadata();
  const str = () => s.strVarInt();

  if (dir === "C2S") {
    switch (pid) {
      case 0x00: s.varint(); return; // KeepAlive
      case 0x01: str(); return; // ChatMessage
      case 0x02: // UseEntity
        s.varint();
        if (s.u8() === 2) {
          s.float(); s.float(); s.float();
        }
        return;
      case 0x03: s.byte(); return; // Player
      case 0x04: s.double(); s.double(); s.double(); s.byte(); return; // Position
      case 0x05: s.float(); s.float(); s.byte(); return; // Look
      case 0x06: s.double(); s.double(); s.double(); s.float(); s.float(); s.byte(); return; // PositionLook
      case 0x07: s.varint(); s.long(); s.byte(); return; // PlayerDigging
      case 0x08: s.long(); s.byte(); item(); s.float(); s.float(); s.float(); return; // BlockPlacement
      case 0x09: s.short(); return; // HeldItemChange
      case 0x0a: s.varint(); return; // Animation (hand)
      case 0x0b: s.varint(); s.varint(); s.varint(); return; // EntityAction
      case 0x0c: s.float(); s.float(); s.byte(); return; // Input
      case 0x0d: s.byte(); return; // CloseWindow
      case 0x0e: s.byte(); s.short(); s.byte(); s.short(); s.byte(); item(); return; // ClickWindow
      case 0x0f: s.byte(); s.short(); s.byte(); return; // ConfirmTransaction
      case 0x10: s.short(); item(); return; // CreativeInventoryAction
      case 0x11: s.byte(); s.byte(); return; // EnchantItem
      case 0x12: s.long(); str(); str(); str(); str(); return; // UpdateSign
      case 0x13: s.byte(); s.float(); s.float(); return; // PlayerAbilities
      case 0x14: str(); if (s.bool()) s.long(); return; // TabComplete
      case 0x15: str(); s.bytes(4); return; // ClientSettings (view, chatVis, colors, parts)
      case 0x16: s.varint(); return; // ClientStatus
      case 0x17: str(); s.remaining(); return; // CustomPayload (channel + rest of frame)
      case 0x18: s.uuid(); return; // Spectate
      case 0x19: s.varint(); return; // ResourcePackStatus
      default:
        throw new Error(`unknown C2S play packet ${pid}`);
    }
  }

  switch (pid) {
    case 0x00: s.varint(); return; // KeepAlive
    case 0x01: // JoinGame
      s.int(); s.byte(); s.byte(); s.byte(); s.byte(); str(); s.byte();
      return;
    case 0x02: str(); s.byte(); return; // Chat
    case 0x03: s.long(); s.long(); return; // TimeUpdate
    case 0x04: s.varint(); s.short(); item(); return; // EntityEquipment
    case 0x05: s.long(); return; // SpawnPosition
    case 0x06: s.float(); s.varint(); s.float(); return; // UpdateHealth
    case 0x07: s.int(); s.byte(); s.byte(); str(); return; // Respawn
    case 0x08: s.double(); s.double(); s.double(); s.float(); s.float(); s.byte(); return; // PlayerPosLook
    case 0x09: s.byte(); return; // HeldItemChange S2C
    case 0x0a: s.varint(); s.long(); return; // UseBed
    case 0x0b: s.varint(); s.byte(); return; // Animation S2C
    case 0x0c: // SpawnPlayer
      s.varint(); s.uuid(); s.int(); s.int(); s.int(); s.byte(); s.byte(); s.short(); metadata();
      return;
    case 0x0d: s.varint(); s.varint(); return; // CollectItem
    case 0x0e: // SpawnObject
      s.varint();
      s.byte(); // type
      s.int(); s.int(); s.int(); // x,y,z
      s.byte(); s.byte(); // pitch, yaw
      const extra = s.i32();
      if (extra !== 0) {
        s.short(); s.short(); s.short();
      }
      return;
    case 0x0f: // SpawnMob
      s.varint();
      s.byte();
      s.int(); s.int(); s.int();
      s.byte(); s.byte(); s.byte();
      s.short(); s.short(); s.short();
      metadata();
      return;
    case 0x10: s.varint(); str(); s.int(); s.int(); s.int(); s.byte(); return; // SpawnPainting
    case 0x11: s.varint(); s.int(); s.int(); s.int(); s.short(); return; // SpawnExperienceOrb
    case 0x12: s.varint(); s.short(); s.short(); s.short(); return; // EntityVelocity
    case 0x13: // DestroyEntities
      for (let i = s.varint(); i > 0; i--) s.varint();
      return;
    case 0x14: s.varint(); return; // Entity
    case 0x15: s.varint(); s.bytes(4); return; // EntityRelMove
    case 0x16: s.varint(); s.bytes(3); return; // EntityLook
    case 0x17: s.varint(); s.bytes(6); return; // EntityLookMove
    case 0x18: s.varint(); s.int(); s.int(); s.int(); s.byte(); s.byte(); s.byte(); return; // EntityTeleport
    case 0x19: s.varint(); s.byte(); return; // EntityHeadLook
    case 0x1a: s.varint(); s.byte(); return; // EntityStatus
    case 0x1b: s.varint(); s.varint(); s.byte(); return; // EntityAttach
    case 0x1c: s.varint(); metadata(); return; // EntityMetadata
    case 0x1d: s.varint(); s.byte(); s.byte(); s.varint(); s.byte(); return; // EntityEffect
    case 0x1e: s.varint(); s.byte(); return; // RemoveEntityEffect
    case 0x1f: s.float(); s.varint(); s.varint(); return; // SetExperience
    case 0x20: // EntityProperties
      s.varint();
      const props = s.varint();
      for (let i = 0; i < props; i++) {
        str();
        s.double();
        const mods = s.varint();
        for (let j = 0; j < mods; j++) {
          s.uuid();
          s.double();
          s.byte();
        }
      }
      return;
    case 0x21: s.int(); s.int(); s.byte(); s.short(); s.bytes(s.varint()); return; // ChunkData
    case 0x22: // MultiBlockChange
      s.int(); s.int();
      for (let i = s.varint(); i > 0; i--) {
        s.short();
        s.varint();
      }
      return;
    case 0x23: s.long(); s.varint(); return; // BlockChange
    case 0x24: s.varint(); s.byte(); s.byte(); s.long(); s.varint(); return; // BlockAction
    case 0x25: s.varint(); s.long(); s.byte(); return; // BlockBreakAnim
    case 0x26: {
      // MapChunkBulk
      const skylight = s.u8();
      const chunks = s.varint();
      let total = 0;
      for (let i = 0; i < chunks; i++) {
        s.int(); s.int();
        total += chunkDataBytes(popcount32(s.i16() & 0xffff), skylight === 1);
      }
      s.bytes(total);
      return;
    }
    case 0x27: // Explosion
      s.float(); s.float(); s.float(); s.float();
      const recs = s.i32();
      s.bytes(recs * 3);
      s.float(); s.float(); s.float();
      return;
    case 0x28: s.varint(); s.long(); s.varint(); s.byte(); return; // Effect
    case 0x29: str(); s.int(); s.int(); s.int(); s.float(); s.byte(); return; // SoundEffect
    case 0x2a: {
      // Particles (readInt id + arg count depends on the id)
      const id = s.i32();
      s.byte(); // longDistance
      s.float(); s.float(); s.float(); // x,y,z
      s.float(); s.float(); s.float(); // ox,oy,oz
      s.float(); // speed
      s.int(); // particleCount
      const args = id === 36 ? 2 : id === 37 || id === 38 ? 1 : 0; // iconcrack_/blockcrack_/blockdust_
      for (let i = 0; i < args; i++) s.varint();
      return;
    }
    case 0x2b: s.byte(); s.float(); return; // ChangeGameState
    case 0x2c: s.varint(); s.byte(); s.int(); s.int(); s.int(); return; // SpawnGlobalEntity
    case 0x2d: s.byte(); str(); str(); s.byte(); return; // OpenWindow
    case 0x2e: s.byte(); return; // CloseWindow S2C
    case 0x2f: s.byte(); s.short(); item(); return; // SetSlot
    case 0x30: // WindowItems
      s.byte();
      const slots = s.u16();
      for (let i = 0; i < slots; i++) item();
      return;
    case 0x31: s.byte(); s.short(); s.short(); return; // WindowProperty
    case 0x32: s.byte(); s.short(); s.byte(); return; // ConfirmTransaction S2C
    case 0x33: s.long(); str(); str(); str(); str(); return; // UpdateSign
    case 0x34: {
      // Maps
      s.varint();
      s.byte();
      const icons = s.varint();
      s.bytes(icons * 3);
      const maxX = s.u8();
      if (maxX > 0) {
        s.bytes(3); // maxY, minX, minY
        s.bytes(s.varint());
      }
      return;
    }
    case 0x35: s.long(); s.byte(); nbt(); return; // UpdateTileEntity
    case 0x36: s.long(); return; // SignEditorOpen
    case 0x37: // Statistics
      for (let i = s.varint(); i > 0; i--) {
        str();
        s.varint();
      }
      return;
    case 0x38: {
      // PlayerListItem
      const action = s.varint();
      const count = s.varint();
      for (let i = 0; i < count; i++) {
        s.uuid();
        if (action === 0) {
          str();
          const props = s.varint();
          for (let j = 0; j < props; j++) {
            str();
            str();
            if (s.bool()) str();
          }
          s.varint(); // gamemode
          s.varint(); // ping
          if (s.bool()) str();
        } else if (action === 1 || action === 2) {
          s.varint();
        } else if (action === 3) {
          if (s.bool()) str();
        }
      }
      return;
    }
    case 0x39: s.byte(); s.float(); s.float(); return; // PlayerAbilities S2C
    case 0x3a: for (let i = s.varint(); i > 0; i--) str(); return; // TabComplete
    case 0x3b: {
      // ScoreboardObjective
      str();
      const mode = s.peekU8();
      s.byte();
      if (mode === 0 || mode === 2) {
        str(); // objectiveValue
        str(); // renderType
      }
      return;
    }
    case 0x3c: {
      // UpdateScore
      str();
      const action = s.varint();
      str(); // objective (always present)
      if (action !== 1) s.varint(); // value
      return;
    }
    case 0x3d: s.byte(); str(); return; // DisplayScoreboard
    case 0x3e: {
      // Teams
      str();
      const mode = s.peekU8();
      s.byte();
      if (mode === 0 || mode === 2) {
        str(); // displayName
        str(); // prefix
        str(); // suffix
        s.byte(); // friendlyFlags
        str(); // nameTagVisibility
        s.byte(); // color
      }
      if (mode === 0 || mode === 3 || mode === 4) {
        for (let i = s.varint(); i > 0; i--) str();
      }
      return;
    }
    case 0x3f: str(); s.remaining(); return; // CustomPayload S2C
    case 0x40: str(); return; // Disconnect
    case 0x41: s.byte(); return; // ServerDifficulty
    case 0x42: {
      // CombatEvent
      const ev = s.varint();
      if (ev === 1) {
        // END_COMBAT
        s.varint();
        s.int();
      } else if (ev === 2) {
        // ENTITY_DIED
        s.varint();
        s.int();
        str();
      }
      return;
    }
    case 0x43: s.varint(); return; // Camera
    case 0x44: {
      // WorldBorder
      const action = s.varint();
      switch (action) {
        case 0: s.double(); return;
        case 1: s.double(); s.double(); s.varLong(); return;
        case 2: s.double(); s.double(); return;
        case 3: s.varint(); return;
        case 4: s.varint(); return;
        case 5: s.double(); s.double(); s.double(); s.double(); s.varLong(); s.varint(); s.varint(); s.varint(); return;
        default: return;
      }
    }
    case 0x45: {
      // Title
      const type = s.varint();
      if (type === 0 || type === 1) {
        str();
      } else if (type === 2) {
        s.int(); s.int(); s.int();
      }
      return;
    }
    case 0x46: s.varint(); return; // SetCompressionLevel
    case 0x47: str(); str(); return; // PlayerListHeaderFooter
    case 0x48: str(); str(); return; // ResourcePackSend
    case 0x49: s.varint(); nbt(); return; // UpdateEntityNBT
    default:
      throw new Error(`unknown S2C play packet ${pid}`);
  }
}