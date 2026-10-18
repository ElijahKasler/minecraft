/**
 * Minimal Java 1.8 server client used by the bridge.
 *
 * Speaks the vanilla MC login protocol over TCP (always length-framed; then
 * compression after SetCompression), then relays PLAY packets one at a time.
 *
 * Encrypted (online-mode) servers are not supported by the proxy in this
 * build - if the server sends EncryptionRequest the connection fails with a
 * clear auth_required reason (never forwarded to the Eagler client, which
 * has no encryption).
 */

import { createConnection, type Socket } from "node:net";
import { PacketWriter } from "../game/writer.js";
import { StreamFramer, frameForServer, type StreamPacket } from "../game/frames.js";
import type { Dir } from "../game/shallow.js";
import { log } from "../logger.js";

export interface JavaClientOptions {
  host: string;
  port: number;
  username: string;
  /** value sent in the C00 handshake address field */
  handshakeAddress?: string;
  protocolVersion?: number;
  loginTimeoutMs?: number;
}

export type JavaDisconnectReason =
  | { kind: "connect_error"; message: string }
  | { kind: "protocol_error"; message: string }
  | { kind: "server_disconnect"; message: string; detail?: string }
  | { kind: "auth_required"; message: string }
  | { kind: "closed"; message: string };

export interface JavaClientCallbacks {
  onPlayPacket(pkt: StreamPacket): void;
  onDisconnect(reason: JavaDisconnectReason): void;
}

const READ_DIR: Dir = "S2C";
const DEFAULT_LOGIN_TIMEOUT_MS = 15_000;
const MAX_BUFFER = 4 * 1024 * 1024;

function readVarInt(buf: Buffer): { value: number; len: number } | null {
  let value = 0;
  let shift = 0;
  for (let i = 0; i < 5 && i < buf.length; i++) {
    const b = buf[i];
    value |= (b & 0x7f) << shift;
    if ((b & 0x80) === 0) return { value: value >>> 0, len: i + 1 };
    shift += 7;
  }
  return null;
}

export class JavaClient {
  private socket?: Socket;
  private framer = new StreamFramer({ stage: "login", dir: READ_DIR });
  private compression?: { threshold: number };
  private inPlay = false;
  private closed = false;
  private loginTimer?: NodeJS.Timeout;
  private playPending: { pid: number; payload: Buffer }[] = [];

  private resolvePlay?: () => void;
  private rejectPlay?: (e: unknown) => void;

  constructor(
    private readonly opts: JavaClientOptions,
    private readonly cb: JavaClientCallbacks,
  ) {}

  /** Connect, complete the login handshake, resolve once in PLAY. */
  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.resolvePlay = resolve;
      this.rejectPlay = reject;

      const { host, port } = this.opts;
      const handshakeAddress = this.opts.handshakeAddress ?? host;
      const protocolVersion = this.opts.protocolVersion ?? 47;

      const socket = createConnection({ host, port });
      this.socket = socket;
      socket.setNoDelay(true);

      this.loginTimer = setTimeout(() => {
        this.close();
        this.failLogin({ kind: "connect_error", message: `timed out connecting to ${host}:${port}` });
      }, this.opts.loginTimeoutMs ?? DEFAULT_LOGIN_TIMEOUT_MS);

      let buf = Buffer.alloc(0);

      socket.on("error", (err: NodeJS.ErrnoException) => {
        if (this.inPlay) {
          this.cb.onDisconnect({ kind: "connect_error", message: err.message });
          return;
        }
        this.failLogin({ kind: "connect_error", message: err.message });
      });

      socket.on("close", () => {
        if (this.closed) return;
        this.closed = true;
        if (this.inPlay) {
          this.cb.onDisconnect({ kind: "closed", message: "connection to server closed" });
        } else {
          this.failLogin({ kind: "closed", message: "connection closed during login" });
        }
      });

      socket.on("connect", () => {
        this.writeLoginPacket(0x00, (w) => {
          w.writeVarInt(protocolVersion);
          w.writeMCString(handshakeAddress);
          w.writeUShort(port);
          w.writeVarInt(2); // next state: login
        });
        this.writeLoginPacket(0x00, (w) => {
          w.writeMCString(this.opts.username);
        });
      });

      socket.on("data", (chunk) => {
        if (this.closed) return;

        buf = Buffer.concat([buf, chunk]);
        if (buf.length > MAX_BUFFER) {
          this.close();
          this.failLogin({ kind: "protocol_error", message: "received more than 4MB before full parse" });
          return;
        }
        try {
          for (;;) {
            const { packets, consumed } = this.framer.parse(buf, 0);
            if (consumed > 0) buf = buf.subarray(consumed);
            if (packets.length === 0) break;
            for (const pkt of packets) this.handlePacket(pkt);
            if (this.closed) return;
            if (consumed === 0) break;
          }
        } catch (err) {
          const message = err instanceof Error ? err.message : String(err);
          this.close();
          if (this.inPlay) {
            this.cb.onDisconnect({ kind: "protocol_error", message });
          } else {
            this.failLogin({ kind: "protocol_error", message });
          }
        }
      });
    });
  }

  private handlePacket(pkt: StreamPacket): void {
    if (this.inPlay) {
      if (pkt.pid === 0x46) {
        // S46 SetCompressionLevel in play (rare) - keep both directions in sync
        const v = readVarInt(pkt.payload);
        if (v) {
          this.framer.setCompression(v.value);
          this.compression = { threshold: v.value };
        }
        return;
      }
      this.cb.onPlayPacket(pkt);
      return;
    }

    switch (pkt.pid) {
      case 0x03: {
        const v = readVarInt(pkt.payload);
        if (!v) throw new Error("bad SetCompression payload");
        this.framer.setCompression(v.value);
        this.compression = { threshold: v.value };
        break;
      }
      case 0x02: // LoginSuccess (uuid, username)
this.inPlay = true;
      this.framer.enterPlay();
      this.resolvePlay?.();
      if (this.playPending.length > 0) {
        const pending = this.playPending.splice(0);
        for (const pkt of pending) this.writePlay(pkt.pid, pkt.payload);
      }
        break;
      case 0x01:
        this.close();
        this.failLogin({ kind: "auth_required", message: "This server requires an online-mode (premium) account." });
        break;
      case 0x00: {
        const detail = readVarInt(pkt.payload) ? pkt.payload.toString("utf8") : undefined;
        this.close();
        this.failLogin({ kind: "server_disconnect", message: "Login rejected by server", detail });
        break;
      }
      default:
        throw new Error(`unexpected login packet 0x${pkt.pid.toString(16)}`);
    }
  }

  private failLogin(e: unknown): void {
    if (this.loginTimer) clearTimeout(this.loginTimer);
    if (this.rejectPlay) {
      const reason = this.normalizeReason(e);
      this.rejectPlay(reason);
      this.rejectPlay = undefined;
      this.resolvePlay = undefined;
    } else if (!this.closed) {
      this.cb.onDisconnect(this.normalizeReason(e));
    }
  }

  private normalizeReason(e: unknown): JavaDisconnectReason {
    if (e && typeof e === "object" && "kind" in e) return e as JavaDisconnectReason;
    return { kind: "connect_error", message: e instanceof Error ? e.message : String(e) };
  }

  /** C2S packet while in play (uses whatever compression the server set). */
  sendPlayPacket(pid: number, payload: Buffer): void {
    if (!this.socket || this.closed) return;
    if (!this.inPlay) {
      // The web client may send play packets the instant it sees FINISH_LOGIN,
      // which can race the Java login handshake completing. Queue until play.
      this.playPending.push({ pid, payload });
      return;
    }
    this.writePlay(pid, payload);
  }

  private writePlay(pid: number, payload: Buffer): void {
    if (!this.socket || this.closed || !this.inPlay) return;
    const frame = frameForServer(pid, payload, this.compression);
    if (pid === 0x01) {
      // eslint-disable-next-line no-console
      console.error(`[tmp] play c2s pid=${pid} payload=${payload.length}b frame=${frame.length}b hex=${frame.toString("hex")}`);
    }
    this.socket.write(frame);
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    if (this.loginTimer) clearTimeout(this.loginTimer);
    try {
      this.socket?.destroy();
    } catch {
      /* noop */
    }
  }

  get isClosed(): boolean {
    return this.closed;
  }

  get isInPlay(): boolean {
    return this.inPlay;
  }

  private writeLoginPacket(pid: number, write: (w: PacketWriter) => void): void {
    const w = new PacketWriter();
    write(w);
    this.socket?.write(frameForServer(pid, w.toBuffer()));
  }
}

export { log };