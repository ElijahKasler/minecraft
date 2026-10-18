/**
 * JavaClient against a mock vanilla 1.8 TCP server exercising:
 *  - C00 handshake + LoginStart
 *  - SetCompression(256) -> LoginSuccess
 *  - compressed play framing in both directions
 *  - server disconnect during login (auth_required via EncryptionRequest)
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer as createNetServer, type Socket } from "node:net";
import { PacketWriter } from "../src/game/writer.js";
import { JavaClient, type JavaClientCallbacks, type JavaDisconnectReason } from "../src/proxy/javaClient.js";
import { frameForServer, StreamFramer, type StreamPacket } from "../src/game/frames.js";

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

function listen0(server: ReturnType<typeof createNetServer>): Promise<number> {
  return new Promise((res) => {
    server.listen(0, "127.0.0.1", () => {
      const a = server.address();
      res(typeof a === "object" && a ? a.port : 0);
    });
  });
}

/** Destroy tracked sockets, close the listener, wait for the 'close' event. */
function closeServer(server: ReturnType<typeof createNetServer>, sockets: Socket[]): Promise<void> {
  for (const s of sockets) s.destroy();
  if (!server.listening) return Promise.resolve();
  return new Promise((res) => {
    server.once("close", () => res());
    server.close();
  });
}

const w = (fn: (w: PacketWriter) => void): Buffer => {
  const writer = new PacketWriter();
  fn(writer);
  return writer.toBuffer();
};

/**
 * Minimal scripted vanilla server. After LoginStart it emits
 * [SetCompression 256][LoginSuccess][0x00 keepalive 98765][0x0a chat].
 * The first two client packets (handshake + LoginStart, both pid 0x00) are
 * parsed manually; after that a compressed play framer takes over.
 */
function makeVanillaServer() {
  const sockets: Socket[] = [];
  const received: StreamPacket[] = [];
  let loginPkts = 0;
  let loginDone = false;
  let framer: StreamFramer | null = null;

  const server = createNetServer((socket) => {
    sockets.push(socket);
    socket.setNoDelay(true);
    let buf = Buffer.alloc(0);

    socket.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (!loginDone) {
        // manual login parse: [VarInt len][pid 0x00][payload]
        for (;;) {
          const head = readVarIntAt(buf, 0);
          if (!head || buf.length < head.len + head.value) break;
          if (buf[head.len] !== 0x00) throw new Error(`unexpected login packet 0x${buf[head.len].toString(16)}`);
          buf = buf.subarray(head.len + head.value);
          loginPkts++;
          if (loginPkts === 2) {
            loginDone = true;
            framer = new StreamFramer({ stage: "play", dir: "C2S" });
            framer.setCompression(256);
            // SetCompression itself is sent in the OLD (plain) framing; the
            // new framing applies from the very next packet.
            sendPlain(0x03, encodeVarInt(256));
            send(0x02, w((ww) => {
                ww.writeMCString("00000000-0000-0000-0000-000000000001");
                ww.writeMCString("Tester");
              }),
            );
            send(0x00, w((ww) => ww.writeVarInt(98765)));
            send(
              0x0a,
              w((ww) => {
                ww.writeMCString(JSON.stringify({ text: "hello" }));
              }),
            );
            break;
          }
        }
      }
      // stray data arriving before framer created
      if (!loginDone || !framer) return;
      for (;;) {
        const { packets, consumed } = framer.parse(buf, 0);
        if (consumed > 0) buf = buf.subarray(consumed);
        if (packets.length === 0) break;
        for (const pkt of packets) {
          if (pkt.pid !== 0x00) received.push(pkt);
        }
      }
    });
    socket.on("error", () => {});
  });

  const send = (pid: number, payload: Buffer) => {
    for (const s of sockets) s.write(frameForServer(pid, payload, { threshold: 256 }));
  };

  const sendPlain = (pid: number, payload: Buffer) => {
    for (const s of sockets) s.write(frameForServer(pid, payload));
  };

  return {
    server,
    received,
    close: () => closeServer(server, sockets),
  };
}

function encodeVarInt(v: number): Buffer {
  const out: number[] = [];
  do {
    let b = v & 0x7f;
    v >>>= 7;
    if (v !== 0) b |= 0x80;
    out.push(b);
  } while (v !== 0);
  return Buffer.from(out);
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

test("JavaClient: full login + compressed play relay", async () => {
  const fake = makeVanillaServer();
  const port = await listen0(fake.server);

  const playPackets: StreamPacket[] = [];
  const disconnects: JavaDisconnectReason[] = [];
  const callbacks: JavaClientCallbacks = {
    onPlayPacket: (p) => playPackets.push(p),
    onDisconnect: (r) => disconnects.push(r),
  };

  const client = new JavaClient({ host: "127.0.0.1", port, username: "Player123", handshakeAddress: "myserver.local" }, callbacks);
  try {
    await client.connect();
    assert.ok(client.isInPlay, "should be in play after LoginSuccess");

    // give the server time to flush keepalive + chat
    await sleep(300);
    assert.equal(playPackets.length, 2, "expected keepalive + chat from server");
    assert.equal(playPackets[0].pid, 0x00);
    assert.equal(playPackets[1].pid, 0x0a);

    // send a client play packet (chat) back
    const chat = w((ww) => ww.writeMCString("hi there"));
    client.sendPlayPacket(0x01, chat);
    await sleep(300);
    assert.equal(fake.received.length, 1);
    assert.equal(fake.received[0].pid, 0x01);
  } finally {
    client.close();
    await fake.close();
  }
});

test("JavaClient: online-mode server -> auth_required", async () => {
  const sockets: Socket[] = [];
  const server = createNetServer((socket) => {
    sockets.push(socket);
    socket.setNoDelay(true);
    let buf = Buffer.alloc(0);
    const sendRaw = (pid: number, payload: Buffer) =>
      socket.write(frameForServer(pid, payload));
    socket.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      // naive: once we've seen pid 0x00 twice, send EncryptionRequest 0x01
      if (buf.includes(0x00) && buf.length > 4 && !socket.writableNeedDrain) {
        sendRaw(
          0x01,
          w((ww) => {
            // server id (empty), pubKey der len, verify token len
            ww.writeMCString("");
            ww.writeVarInt(1);
            ww.writeByte(0);
            ww.writeVarInt(4);
            ww.writeBytes(Buffer.from([1, 2, 3, 4]));
          }),
        );
      }
    });
    socket.on("error", () => {});
  });

  const port = await listen0(server);

  const callbacks: JavaClientCallbacks = {
    onPlayPacket: () => {},
    onDisconnect: () => {},
  };
  const client = new JavaClient({ host: "127.0.0.1", port, username: "Player123" }, callbacks);
  try {
    let reason: JavaDisconnectReason | undefined;
    await client
      .connect()
      .then(() => assert.fail("should not enter play"))
      .catch((e) => {
        reason = e as JavaDisconnectReason;
      });
    assert.ok(reason, "expected rejection reason");
    assert.equal(reason?.kind, "auth_required");
  } finally {
    client.close();
    await closeServer(server, sockets);
  }
});