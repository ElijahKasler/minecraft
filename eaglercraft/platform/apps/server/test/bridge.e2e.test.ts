/**
 * End-to-end: a real HTTP server + bridge, a raw WebSocket client speaking the
 * v5 handshake, and a mock vanilla 1.8 Java server on the other side. Repo
 * lookups are injected so no Postgres is required.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createServer as createNetServer, type Socket, type AddressInfo } from "node:net";
import WebSocket from "ws";
import { EAG } from "@eaglerplatform/shared";
import { PacketWriter } from "../src/game/writer.js";
import { frameForServer, frameForClientPacket } from "../src/game/frames.js";
import { createBridge, type BridgeOptions } from "../src/bridge/eaglerServer.js";
import type { Db } from "../src/db.js";

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function listen0(server: { listen: (p: number, h: string, cb?: () => void) => unknown; address: () => AddressInfo | string | null }): Promise<number> {
  return new Promise((res) => {
    server.listen(0, "127.0.0.1", () => {
      const a = server.address();
      res(typeof a === "object" && a ? a.port : 0);
    });
  });
}

/** Resolve when the server has fully closed (Windows: 'close' event, not the close() callback). */
async function closeServer<T extends { listening: boolean; close: () => T; once: (e: "close", cb: () => void) => unknown }>(s: T): Promise<void> {
  if (!s.listening) return;
  await new Promise<void>((res) => {
    const timeout = setTimeout(() => res(), 3000);
    s.once("close", () => {
      clearTimeout(timeout);
      res();
    });
    s.close();
  });
}

const w = (fn: (w: PacketWriter) => void): Buffer => {
  const writer = new PacketWriter();
  fn(writer);
  return writer.toBuffer();
};

// ---------------------------------------------------------------------------
// mock java server: LoginSuccess + keepalive after it sees two login packets
// ---------------------------------------------------------------------------

function makeJavaServer() {
  const sockets: Socket[] = [];
  let sentPlay = false;
  const server = createNetServer((socket) => {
    sockets.push(socket);
    socket.setNoDelay(true);
    let loginPkts = 0;
    let rx = Buffer.alloc(0);
    socket.on("data", (chunk) => {
      rx = Buffer.concat([rx, chunk]);
      // parse `[VarInt len][pid][payload]` login frames, not raw data events
      for (;;) {
        const head = readVarIntAt(rx, 0);
        if (!head || rx.length < head.len + head.value) break;
        loginPkts++;
        rx = rx.subarray(head.len + head.value);
      }
      if (loginPkts >= 2 && !sentPlay) {
        sentPlay = true;
        socket.write(frameForServer(0x02, w((ww) => {
          ww.writeMCString("00000000-0000-0000-0000-000000000001");
          ww.writeMCString("Player123");
        })));
        socket.write(frameForServer(0x00, w((ww) => ww.writeVarInt(4242))));
      }
    });
    socket.on("error", () => {});
  });
  return {
    server,
    close: async () => {
      for (const s of sockets) s.destroy();
      await closeServer(server);
    },
  };
}

// ---------------------------------------------------------------------------
// client wire builders (mirror the Teavm client)
// ---------------------------------------------------------------------------

function clientVersion(): Buffer {
  return w((ww) => {
    ww.writeByte(0x01);
    ww.writeByte(0x02);
    ww.writeShort(3);
    ww.writeShort(3);
    ww.writeShort(4);
    ww.writeShort(5);
    ww.writeShort(1);
    ww.writeShort(47);
    ww.writeASCII("eaglerplatform");
    ww.writeASCII("1.0.0");
    ww.writeBoolean(false);
    ww.writeASCII("Playertest");
  });
}

function v5RequestLogin(): Buffer {
  return w((ww) => {
    ww.writeByte(0x04);
    ww.writeByte(0); // nickSelection=false
    ww.writeByte(1); // server len
    ww.writeByte(0x64); // "d"
    ww.writeByte(0); // no pass
    ww.writeBoolean(false);
    ww.writeByte(0); // cookie len
    ww.writeVarInt(0); // caps
    ww.writeByte(0); // ext caps
  });
}

const connectWs = (port: number, ticket: string): Promise<WebSocket> =>
  new Promise((res, rej) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/eagler?ticket=${ticket}`);
    ws.on("open", () => res(ws));
    ws.on("error", rej);
  });

function readVarInt(buf: Buffer): number {
  let out = 0;
  let shift = 0;
  for (let i = 0; i < buf.length && i < 5; i++) {
    out |= (buf[i] & 0x7f) << shift;
    if ((buf[i] & 0x80) === 0) break;
    shift += 7;
  }
  return out >>> 0;
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

// ---------------------------------------------------------------------------
// test 1: happy path
// ---------------------------------------------------------------------------

test("bridge: v5 handshake -> allow login -> play relay -> c2s relay", async () => {
  const java = makeJavaServer();
  const javaPort = await listen0(java.server);

  const serverRow = { id: "srv1", name: "Test Server", address: "127.0.0.1", port: javaPort, enabled: true, maintenance: false };
  const userRow = { id: "u1", username: "Player123", email: "a@b.c", role: "user", password_hash: "" };
  const opts: BridgeOptions = {
    path: "/eagler",
    db: {} as Db,
    redeemTicket: (async (_db, token: string) =>
      token === "ticket-abc" ? { userId: "u1", serverId: "srv1" } : null) as BridgeOptions["redeemTicket"],
    getServer: (async () => serverRow) as BridgeOptions["getServer"],
    getUser: (async () => userRow) as BridgeOptions["getUser"],
  };

  const http = createServer((_req, res) => res.writeHead(404).end());
  createBridge(http, opts);

  const httpPort = await listen0(http);

  try {
    const ws = await connectWs(httpPort, "ticket-abc");
    const frames: Buffer[] = [];
    ws.on("message", (data) => {
      const b = Buffer.isBuffer(data) ? data : Buffer.from(data);
      frames.push(b);
    });
    const waitNext = async (ms = 2000): Promise<Buffer> => {
      const t = Date.now();
      while (frames.length === 0) {
        if (Date.now() - t > ms) throw new Error("timed out waiting for frame");
        await sleep(10);
      }
      return frames.shift()!;
    };

    ws.send(clientVersion());
    const ver = await waitNext();
    assert.equal(ver[0], 0x02);
    assert.equal(ver.readUInt16BE(1), 5, "advertised v5");

    ws.send(v5RequestLogin());
    const allow = await waitNext();
    assert.equal(allow[0], 0x05);
    const ulen = allow[1];
    assert.equal(allow.subarray(2, 2 + ulen).toString("utf8"), "Player123");

    ws.send(w((ww) => {
      ww.writeByte(0x07);
      ww.writeByte(0);
    }));
    ws.send(Buffer.from([0x08]));

    const finish = await waitNext();
    assert.equal(finish[0], EAG.SERVER_FINISH_LOGIN, "server replies finish_login 0x09");

    const play = await waitNext(4000);
    assert.equal(play[0], 0x00, "keepalive relayed");
    assert.equal(readVarInt(play.subarray(1)), 4242);

    ws.send(frameForClientPacket(0x01, w((ww) => ww.writeMCString("hello world"))));
    await sleep(300);
    assert.equal(ws.readyState, WebSocket.OPEN, "client still connected after C2S");

    ws.close();
  } finally {
    await closeServer(http);
    await java.close();
  }
});

// ---------------------------------------------------------------------------
// test 2: bad ticket
// ---------------------------------------------------------------------------

test("bridge: invalid ticket rejected with close 4003", async () => {
  const opts: BridgeOptions = {
    path: "/eagler",
    db: {} as Db,
    redeemTicket: (async () => null) as BridgeOptions["redeemTicket"],
  };

  const http = createServer((_req, res) => res.writeHead(404).end());
  createBridge(http, opts);

  const httpPort = await listen0(http);
  try {
    const ws = await connectWs(httpPort, "not-a-ticket");
    const closed = await new Promise<number>((res2) => {
      ws.on("close", (code) => res2(code));
    });
    assert.equal(closed, 4003);
  } finally {
    await closeServer(http);
  }
});