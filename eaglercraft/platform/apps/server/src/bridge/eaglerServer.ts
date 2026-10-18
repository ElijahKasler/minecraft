/**
 * Eagler WebSocket bridge.
 *
 * Client connects to `ws[s]://<origin>/eagler?ticket=<play-ticket>`, negotiates
 * the v3/v4/v5 handshake, then exchanges raw vanilla packets - one
 * non-encrypted `[VarInt pid][payload]` packet per binary WS frame (vanilla
 * framing is never used between client and bridge, and compression/encryption
 * is never negotiated; see WebSocketNetworkManager in the client).
 *
 * The bridge resolves the ticket to (user, server), adopts the user's platform
 * username as its offline profile name, dials the Java server through
 * JavaClient, and relays packets in both directions. Injected 0xEE EAG frames
 * from the client are consumed (no texture/voice extensions are implemented).
 */

import { createHash } from "node:crypto";
import { WebSocketServer, type WebSocket } from "ws";
import type { Server } from "node:http";
import { URL } from "node:url";
import { log } from "../logger.js";
import { EaglerHandshake, type HandshakeEvent } from "./handshake.js";
import { JavaClient, type JavaDisconnectReason } from "../proxy/javaClient.js";
import { redeemPlayTicket, getServerById, getUserById } from "../repo.js";
import type { Db } from "../db.js";
import { EAG } from "@eaglerplatform/shared";
import { frameForClientPacket, isEagFrame, type StreamPacket } from "../game/frames.js";
import { skipPlayPayload } from "../game/shallow.js";

export interface BridgeOptions {
  path: string;
  db: Db;
  /** Alternative ticket resolution (used by tests / local dev). */
  resolveTicket?: (ticket: string) => Promise<{ userId: string; serverId: string } | null>;
  /** Injectable repo lookups (plain defaults call through to src/repo.ts). */
  getServer?: typeof getServerById;
  getUser?: typeof getUserById;
  redeemTicket?: typeof redeemPlayTicket;
  onTicketInvalid?: (ticket: string, ip: string) => void;
}

export interface Bridge {
  liveCount: number;
  getLiveStats: () => { players: { username: string; server: string }[] };
}

export function createBridge(server: Server, opts: BridgeOptions): Bridge {
  const wss = new WebSocketServer({ server, path: opts.path, maxPayload: 512 * 1024 });
  const live = new Set<Session>();

  const bridge: Bridge = {
    get liveCount() {
      return live.size;
    },
    getLiveStats: () => ({ players: [...live].map((s) => ({ username: s.username, server: s.serverName })) }),
  };

  wss.on("connection", (ws, req) => {
    const ip = req.socket.remoteAddress ?? "unknown";
    const u = new URL(req.url ?? "/", "http://localhost");
    const ticket = u.searchParams.get("ticket") ?? "";
    if (!ticket) {
      ws.close(4001, "missing ticket");
      return;
    }
    const session = new Session(ws, ticket, ip, opts, {
      onJoin: () => live.add(session),
      onClose: () => live.delete(session),
    });
    void session.start();
  });

  return bridge;
}

interface SessionServices {
  onJoin: () => void;
  onClose: () => void;
}

class Session {
  username = "";
  serverName = "";
  private handshake = new EaglerHandshake({ brand: "eaglerplatform", version: "0.1.0" });
  private java?: JavaClient;
  private closed = false;
  private target?: { userId: string; serverId: string; address: string; port: number };
  private earlyPackets: StreamPacket[] = [];
  private ready = false;
  private pending: Buffer[] = [];

  constructor(
    private readonly ws: WebSocket,
    private readonly ticketValue: string,
    private readonly ip: string,
    private readonly opts: BridgeOptions,
    private readonly services: SessionServices,
  ) {}

  private getServerValue(id: string) {
    return this.opts.getServer ? this.opts.getServer(this.opts.db, id) : getServerById(this.opts.db, id);
  }

  private getUserValue(userId: string) {
    return this.opts.getUser ? this.opts.getUser(this.opts.db, userId) : getUserById(this.opts.db, userId);
  }

  private redeemTicketValue(token: string) {
    return this.opts.redeemTicket ? this.opts.redeemTicket(this.opts.db, token) : redeemPlayTicket(this.opts.db, token);
  }

  async start(): Promise<void> {
    // Attach handlers immediately so frames aren't dropped while the ticket
    // and server row are resolved (the client may send the version packet at
    // the same moment the connection opens). Incoming data is buffered and
    // flushed once the session target is ready.
    this.ws.on("message", (data: Buffer | ArrayBuffer) => {
      const b = Buffer.isBuffer(data) ? data : Buffer.from(data);
      if (!this.ready) {
        this.pending.push(b);
        return;
      }
      this.onMessage(b);
    });
    this.ws.on("close", () => this.teardown("client closed"));
    this.ws.on("error", () => this.teardown("client error"));
    try {
      const ticket = await this.resolveTicket(this.ticketValue);
      if (!ticket) {
        this.opts.onTicketInvalid?.(this.ticketValue, this.ip);
        this.safeClose(4003, "invalid or expired play ticket");
        return;
      }
      const server = await this.getServerValue(ticket.serverId);
      if (!server || !server.enabled) {
        this.safeClose(4003, "server unavailable");
        return;
      }
      if (server.maintenance) {
        this.safeClose(4003, "server is under maintenance");
        return;
      }
      this.target = { userId: ticket.userId, serverId: ticket.serverId, address: server.address, port: server.port };
      this.serverName = server.name;

      this.ready = true;
      for (const frame of this.pending.splice(0)) this.onMessage(frame);
      this.services.onJoin();
    } catch (err) {
      log.warn(`bridge session start failed`, err);
      this.safeClose(4000, "internal error");
    }
  }

  private async resolveTicket(token: string): Promise<{ userId: string; serverId: string } | null> {
    const t = await this.redeemTicketValue(token);
    if (t) return t;
    if (this.opts.resolveTicket) return this.opts.resolveTicket(token);
    return null;
  }

  private onMessage(raw: Buffer): void {
    if (this.closed) return;
    try {
      if (this.handshake.finished) {
        this.onPlayFrame(raw);
        return;
      }
      const result = this.handshake.processFrame(raw);
      for (const frame of result.incoming) this.ws.send(frame);
      if (result.event) this.onHandshakeEvent(result.event);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      log.warn(`handshake error from ${this.ip}: ${message}`);
      if (!this.handshake.finished) this.ws.send(this.handshake.sendServerError(message));
      this.safeClose(4002, "handshake failure");
    }
  }

  private onHandshakeEvent(ev: HandshakeEvent): void {
    switch (ev.type) {
      case "request_login": {
        const target = this.target;
        if (!target) {
          this.ws.send(this.handshake.sendDeny("Server not yet resolved; try again."));
          return;
        }
        // The client will adopt whatever username we echo back (v5 sends an
        // empty nickname in this build). Use the platform account username.
        this.getUserValue(target.userId)
          .then((user) => {
            if (!user) {
              this.ws.send(this.handshake.sendDeny("Account not found."));
              return;
            }
            this.username = user.username;
            if (!EAG.USERNAME_RE.test(this.username)) {
              this.ws.send(this.handshake.sendDeny("Your username is not allowed on Minecraft servers."));
              return;
            }
            this.ws.send(this.handshake.sendAllowLogin(this.username, offlineUuid(this.username)));
            void this.dialJava();
          })
          .catch(() => {
            this.ws.send(this.handshake.sendDeny("Could not load your account."));
          });
        break;
      }
      case "profile_data":
        break;
      case "finish_login": {
        // Java connection may have produced packets already - flush them now
        if (this.handshake.finished) {
          for (const pkt of this.earlyPackets) {
            if (this.closed) break;
            this.ws.send(frameForClientPacket(pkt.pid, pkt.payload));
          }
          this.earlyPackets = [];
          this.services.onJoin();
        }
        break;
      }
      case "protocol_error":
        this.ws.send(this.handshake.sendServerError(ev.message));
        break;
    }
  }

  private async dialJava(): Promise<void> {
    const target = this.target;
    if (!target) return;

    this.java = new JavaClient(
      { host: target.address, port: target.port, username: this.username, handshakeAddress: target.address },
      {
        onPlayPacket: (pkt) => this.onJavaPacket(pkt),
        onDisconnect: (reason) => this.onJavaDisconnect(reason),
      },
    );

    try {
      await this.java.connect();
    } catch (err) {
      const reason = err as JavaDisconnectReason;
      const message = reason?.message ?? "failed to connect to server";
      log.info(`java connect failed for ${this.username}: ${message}`);
      if (this.handshake.finished && !this.closed) {
        this.kickClient(message);
      } else if (!this.handshake.failed && !this.closed) {
        this.ws.send(this.handshake.sendDeny(message));
        this.safeClose(4005, "server disconnected");
      }
      return;
    }

    // From here the Java server relays reach the client via onJavaPacket
    if (this.handshake.finished) {
      for (const pkt of this.earlyPackets.splice(0)) {
        if (this.closed) break;
        this.ws.send(frameForClientPacket(pkt.pid, pkt.payload));
      }
    }
  }

  private onJavaPacket(pkt: StreamPacket): void {
    if (this.closed) return;
    if (!this.handshake.finished) {
      this.earlyPackets.push(pkt);
      return;
    }
    this.ws.send(frameForClientPacket(pkt.pid, pkt.payload));
  }

  private onJavaDisconnect(reason: JavaDisconnectReason): void {
    if (this.closed) return;
    log.info(`java disconnect for ${this.username}: ${reason.message}`);
    if (this.handshake.finished) {
      this.kickClient(reason.message);
    } else if (!this.handshake.failed) {
      this.ws.send(this.handshake.sendDeny(reason.message));
      this.safeClose(4005, "server disconnected");
    } else {
      this.safeClose(4005, "server disconnected");
    }
  }

  private onPlayFrame(raw: Buffer): void {
    if (raw.length === 0) return;
    if (isEagFrame(raw)) return; // injected EAG frames: not implemented, drop
    const pidInfo = readVarIntAt(raw, 0);
    if (!pidInfo) {
      this.kickClient("Invalid packet header.");
      return;
    }
    const pid = pidInfo.value;
    try {
      skipPlayPayload(pid, "C2S", raw, pidInfo.len);
    } catch {
      this.kickClient(`Invalid client packet 0x${pid.toString(16)}`);
      return;
    }
    this.java?.sendPlayPacket(pid, raw.subarray(pidInfo.len));
  }

  private kickClient(message: string): void {
    if (this.closed) return;
    const json = Buffer.from(JSON.stringify({ text: message, color: "red" }), "utf8");
    try {
      this.ws.send(frameForClientPacket(0x40, json));
    } catch {
      /* noop */
    }
    this.teardown("kicked");
  }

  private teardown(reason: string): void {
    if (this.closed) return;
    this.closed = true;
    void reason;
    try {
      this.java?.close();
    } catch {
      /* noop */
    }
    this.services.onClose();
    try {
      this.ws.close();
    } catch {
      /* noop */
    }
  }

  private safeClose(code: number, reason: string): void {
    if (this.closed) return;
    try {
      this.ws.close(code, reason.slice(0, 120));
    } catch {
      /* noop */
    } finally {
      this.teardown(`close(${code}) ${reason}`);
    }
  }
}

function readVarIntAt(buf: Buffer, pos: number): { value: number; len: number } | null {
  let value = 0;
  let shift = 0;
  for (let i = 0; i < 5 && pos + i < buf.length; i++) {
    const b = buf[pos + i];
    value |= (b & 0x7f) << shift;
    if ((b & 0x80) === 0) return { value: value >>> 0, len: i + 1 };
    shift += 7;
  }
  return null;
}

function offlineUuid(username: string): Buffer {
  // Java offline-mode convention: UUID = MD5("OfflinePlayer:"+name), v3 layout
  const hash = createHash("md5").update(`OfflinePlayer:${username}`, "utf8").digest();
  hash[6] = (hash[6] & 0x0f) | 0x30;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  return Buffer.from(hash);
}