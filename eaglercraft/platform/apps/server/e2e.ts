/**
 * Live happy-path E2E for the whole platform:
 *   - register (first user becomes admin) -> create a server row -> play ticket
 *   - real Eagler v5 WS client handshake against the running platform server
 *   - bridge hands off to a REAL vanilla offline Java 1.8 server
 *   - assert bidirection play relay (keepalive in, chat echo out)
 *   - assert SPA + client assets are served
 *
 * Prereqs: platform server running; Postgres migrated; vanilla Java server up.
 *   E2E_BASE=http://localhost:3007 E2E_JAVA_ADDR=127.0.0.1 E2E_JAVA_PORT=25570 npx tsx e2e.ts
 */
import { WebSocket } from "ws";
import { PacketWriter } from "./src/game/writer.js";
import { frameForClientPacket, parseSingle } from "./src/game/frames.js";
import { EAG } from "@eaglerplatform/shared";

const BASE = process.env.E2E_BASE ?? "http://localhost:3007";
const JAVA_ADDR = process.env.E2E_JAVA_ADDR ?? "127.0.0.1";
const JAVA_PORT = Number(process.env.E2E_JAVA_PORT ?? "25570");

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

let failures = 0;
function ok(name: string, cond: boolean, detail = ""): void {
  console.log(`${cond ? "PASS" : "FAIL"}  ${name}${detail ? `  [${detail}]` : ""}`);
  if (!cond) failures++;
}

async function httpJson(path: string, opts: { method?: string; body?: unknown; cookie?: string } = {}): Promise<{ status: number; body: any; setCookie?: string }> {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (opts.cookie) headers.cookie = `sid=${opts.cookie}`;
  const res = await fetch(BASE + path, {
    method: opts.method ?? "GET",
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
  const text = await res.text();
  let body: any;
  try {
    body = JSON.parse(text);
  } catch {
    body = text;
  }
  const sc = res.headers.get("set-cookie") ?? "";
  const m = /sid=([^;]+)/.exec(sc);
  return { status: res.status, body, setCookie: m?.[1] };
}

class Frames {
  private queue: Buffer[] = [];
  private waiters: { pred: (b: Buffer) => boolean; resolve: (b: Buffer) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }[] = [];
  push(b: Buffer): void {
    for (const w of this.waiters) {
      if (w.pred(b)) {
        clearTimeout(w.timer);
        this.waiters.splice(this.waiters.indexOf(w), 1);
        w.resolve(b);
        return;
      }
    }
    this.queue.push(b);
  }
  waitFor(pred: (b: Buffer) => boolean, ms: number, label: string): Promise<Buffer> {
    let found: Buffer | undefined;
    const idx = this.queue.findIndex(pred);
    if (idx >= 0) found = this.queue.splice(idx, 1)[0];
    if (found) return Promise.resolve(found);
    return new Promise<Buffer>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w.timer !== timer);
        reject(new Error(`timeout waiting for ${label}`));
      }, ms);
      this.waiters.push({ pred, resolve, reject, timer });
    });
  }
}

const w = (fn: (ww: PacketWriter) => void): Buffer => {
  const writer = new PacketWriter();
  fn(writer);
  return writer.toBuffer();
};

const clientVersion = (username: string): Buffer =>
  w((ww) => {
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
    ww.writeASCII(username);
  });

const v5RequestLogin = (): Buffer =>
  w((ww) => {
    ww.writeByte(0x04);
    ww.writeByte(0); // nickSelection=false
    ww.writeByte(1);
    ww.writeByte(0x64); // "d"
    ww.writeByte(0); // no pass
    ww.writeBoolean(false);
    ww.writeByte(0); // no cookie
    ww.writeVarInt(0); // caps
    ww.writeByte(0); // ext
  });

function main() {
  const ts = Date.now().toString().slice(-8);
  const username = `e2e${ts}`.slice(0, 16);
  const email = `e2e${ts}@example.com`;
  const password = `hunter2${ts}`;

  const cookie = { value: "" as string };
  let serverId = "";

  const bus = new Frames();
  let ws: WebSocket | undefined;

  const closeWith = (code: number, reason: string) => {
    if (ws && ws.readyState === WebSocket.OPEN) ws.close(code, reason);
  };
  function cleanup() {
    closeWith(4001, "e2e done");
    if (ws) ws.terminate();
  }
  process.on("exit", cleanup);
  process.on("SIGINT", () => process.exit(0));

  const run = async (): Promise<void> => {
    // 1. accounts
    const reg = await httpJson(`/api/register`, { method: "POST", body: { email, username, password } });
    ok("register first user", reg.status === 201 && reg.body.role === "admin", `status=${reg.status}`);
    cookie.value = reg.setCookie ?? "";

    const me = await httpJson(`/api/me`, { cookie: cookie.value });
    ok("me returns session user", me.status === 200 && me.body.username === username && me.body.role === "admin" && typeof me.body.id === "string");

    // 2. admin creates the local vanilla server
    const create = await httpJson(`/api/admin/servers`, {
      method: "POST",
      body: { name: "Local Vanilla 1.8", address: JAVA_ADDR, port: JAVA_PORT, online_mode: false, max_players: 4 },
      cookie: cookie.value,
    });
    ok("admin create server", create.status === 201 && typeof create.body.id === "string", `status=${create.status}`);
    serverId = create.body?.id ?? "";

    const list = await httpJson(`/api/servers`);
    const found = Array.isArray(list.body) && list.body.find((s: any) => s.id === serverId);
    ok("server listed publicly", !!found && found.address === JAVA_ADDR && found.port === JAVA_PORT);
    ok("server marked online (vanilla)", found?.onlineMode === false, `onlineMode=${found?.onlineMode}`);

    // 3. play ticket
    const play = await httpJson(`/api/servers/${serverId}/play`, { method: "POST", cookie: cookie.value });
    ok("play issues ticket+bridgeUrl", play.status === 200 && typeof play.body.ticket === "string" && typeof play.body.bridgeUrl === "string",
      `url=/api/servers/${serverId}/play status=${play.status} body=${JSON.stringify(play.body).slice(0, 160)}`);
    const bridgeUrl = play.body?.bridgeUrl ?? "";
    const ticket = play.body?.ticket ?? "";

    // 4. WS handshake with the real bridge
    ws = await new Promise<WebSocket>((res, rej) => {
      const s = new WebSocket(bridgeUrl);
      s.on("open", () => res(s));
      s.on("error", rej);
    });
    ws.on("message", (data) => {
      const b = Buffer.isBuffer(data) ? data : Buffer.from(data as ArrayBuffer);
      bus.push(b);
    });
    ws.on("error", (e) => console.log("  [client error]", e.message));
    ws.on("close", (code) => console.log(`  (ws closed code=${code})`));

    ws.send(clientVersion(username));

    const ver = await bus.waitFor((b) => b[0] === 0x02, 5000, "S2C version");
    ok("lib version sent back", ver[0] === 0x02 && ver.readUInt16BE(1) === 5);

    ws.send(v5RequestLogin());
    const allow = await bus.waitFor((b) => b[0] === 0x05, 8000, "ALLOW_LOGIN");
    const allowName = allow.subarray(2, 2 + allow[1]).toString("utf8");
    ok(`allow login as "${allowName}"`, allow[0] === 0x05 && allowName === username);

    ws.send(w((ww) => { ww.writeByte(0x07); ww.writeByte(0); }));
    ws.send(Buffer.from([0x08]));

    const finish = await bus.waitFor((b) => b[0] === EAG.SERVER_FINISH_LOGIN, 8000, "FINISH_LOGIN");
    ok("server replies FINISH_LOGIN", finish[0] === EAG.SERVER_FINISH_LOGIN);

    // 5. play relay with a real vanilla server
    ws.send(frameForClientPacket(0x01, w((ww) => ww.writeMCString("e2e roundtrip chat"))));

    let keepaliveOk = false;
    let chatOk = false;
    let resent = false;
    const deadline = Date.now() + 26000;
    while (Date.now() < deadline && !(keepaliveOk && chatOk)) {
      const frame = await Promise.race([
        bus.waitFor(() => true, 2500, "any play frame"),
        new Promise<undefined>((r) => setTimeout(() => r(undefined), 2500)),
      ]);
      if (keepaliveOk && !resent && !chatOk) {
        resent = true;
        ws?.send(frameForClientPacket(0x01, w((ww) => ww.writeMCString("e2e roundtrip chat"))));
      }
      if (!frame) continue;
      const pkt = parseSingle(frame);
      if (pkt.pid === 0x00 && pkt.payload.length >= 1) keepaliveOk = true;
      if (pkt.pid === 0x02) {
        const txt = pkt.payload.toString("utf8");
        if (txt.includes("e2e roundtrip chat")) chatOk = true;
      }
    }
    ok("keepalive relayed from vanilla", keepaliveOk);
    ok("chat echoed back from vanilla", chatOk);

    // 6. static artifacts
    const spa = await fetch(BASE + "/");
    const spaText = await spa.text();
    ok("SPA index served", spa.status === 200 && spaText.includes("<div id=\"root\">"));
    const js = await fetch(BASE + "/client/classes.js");
    ok("client classes.js served", js.status === 200, `status=${js.status} len=${(await js.arrayBuffer()).byteLength}`);
    const epk = await fetch(BASE + "/client/assets.epk");
    ok("client assets.epk served", epk.status === 200, `len=${(await epk.arrayBuffer()).byteLength}`);
  };

  run()
    .then(() => {
      cleanup();
      console.log(failures === 0 ? "\nE2E HAPPY PATH OK" : `\nE2E FAILED (${failures} check(s))`);
      process.exit(failures === 0 ? 0 : 1);
    })
    .catch((e) => {
      console.error("\nE2E ERROR:", e?.message ?? e);
      console.log(`E2E FAILED (${failures} check(s))`);
      process.exit(1);
    });
}

void main();