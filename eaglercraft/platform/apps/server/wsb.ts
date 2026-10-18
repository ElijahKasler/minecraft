import { WebSocket } from "ws";

const base = process.env.E2E_BASE ?? "http://localhost:3007";
const pass = "password123";
const u16 = (v: number) => { const b = Buffer.alloc(2); b.writeUInt16BE(v, 0); return b; };
const str = (s: string) => { const b = Buffer.from(s, "utf8"); return Buffer.concat([u16(b.length), b]); };

function clientVersion(username: string): Buffer {
  const parts = [Buffer.from([0x01, 0x02]), u16(3), u16(3), u16(4), u16(5), u16(1), u16(47),
    str("eaglerplatform"), str("1.0.0"), Buffer.from([0x00]), str(username)];
  return Buffer.concat(parts);
}

async function main() {
  const ts = Date.now().toString().slice(-8);
  const username = `wsb${ts}`.slice(0, 16);
  const reg = await fetch(base + "/api/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email: `wsb${ts}@example.com`, username, password: pass }),
  });
  const sc = (reg.headers.get("set-cookie") ?? "").match(/sid=([^;]+)/)?.[1] ?? "";
  console.log(`register ${reg.status} user=${username}`);

  const create = await fetch(base + "/api/admin/servers", {
    method: "POST",
    headers: { "content-type": "application/json", cookie: `sid=${sc}` },
    body: JSON.stringify({ name: "LB Vanilla", address: "127.0.0.1", port: 25570 }),
  });
  const srv = (await create.json()) as { id: string };
  console.log(`create ${create.status} id=${srv.id}`);

  const play = await fetch(base + `/api/servers/${srv.id}/play`, { method: "POST", headers: { cookie: `sid=${sc}` } });
  const playJ = (await play.json()) as { bridgeUrl: string; ticket: string };
  console.log(`play ${play.status} url=${playJ.bridgeUrl}`);

  const ws = new WebSocket(playJ.bridgeUrl);
  const events: string[] = [];
  const onClose = () => { console.log("WS close code=", (ws as any)._closeCode ?? "?"); };
  ws.on("open", () => {
    console.log("WS open; sending version", clientVersion(username).toString("hex"));
    ws.send(clientVersion(username));
  });
  ws.on("message", (d) => {
    const b = Buffer.isBuffer(d) ? d : Buffer.from(d as ArrayBuffer);
    console.log("WS MSG len=" + b.length + " hex=" + b.toString("hex"));
    if (b[0] === 0x05) {
      console.log("ALLOW received");
    }
  });
  ws.on("close", (code, reason) => console.log("WS close", code, reason.toString()));
  ws.on("error", (e) => console.log("WS error", e.message));

  ws.on("unexpected-response", (_req, res) => console.log("WS unexpected-response", res.statusCode));

  setTimeout(() => { console.log("probe done", JSON.stringify(events)); ws.terminate(); process.exit(0); }, 6000);
}
void main();