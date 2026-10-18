/**
 * Static file serving:
 *  - the built Eagler client (classes.js, assets.epk, ...) from the JS build
 *    output directory (config.clientStaticDir), served under /client/*
 *  - the optional Vite-built web SPA (config.webStaticDir), served at /
 *  - a minimal built-in launcher page when the SPA bundle is absent
 *    (development / first boot).
 */

import { createReadStream, existsSync, statSync } from "node:fs";
import { extname, join, normalize, sep } from "node:path";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Config } from "../config.js";

const MIME: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".epk": "application/octet-stream",
  ".map": "application/json",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".svg": "image/svg+xml",
  ".wasm": "application/wasm",
  ".txt": "text/plain; charset=utf-8",
};

function safeJoin(root: string, urlPath: string): string | null {
  const normalized = normalize(urlPath).replace(/^([.][.][/\\])+/, "");
  const full = join(root, normalized);
  if (full !== root && !full.startsWith(root + sep)) return null;
  return full;
}

function serveFile(res: ServerResponse, absPath: string): void {
  if (!existsSync(absPath) || !statSync(absPath).isFile()) {
    res.writeHead(404, { "content-type": "text/plain" });
    res.end("Not found");
    return;
  }
  const mime = MIME[extname(absPath).toLowerCase()] ?? "application/octet-stream";
  res.writeHead(200, {
    "content-type": mime,
    "cache-control": absPath.includes("client") ? "public, max-age=3600" : "no-cache",
  });
  createReadStream(absPath).pipe(res);
}

function serveIndex(res: ServerResponse, body: string): void {
  res.writeHead(200, { "content-type": "text/html; charset=utf-8", "cache-control": "no-cache" });
  res.end(body);
}

export function createStaticHandler(config: Config): (req: IncomingMessage, res: ServerResponse) => boolean {
  const webIndex = join(config.webStaticDir, "index.html");
  const webDistExists = existsSync(webIndex);

  const launcher = buildLauncherPage(config);

  return function handle(req, res): boolean {
    const url = new URL(req.url ?? "/", "http://localhost");

    if (url.pathname.startsWith("/client/") || url.pathname === "/client" || url.pathname === "/client/") {
      const rel = (url.pathname === "/client" ? "/" : url.pathname.slice("/client".length)) || "/";
      const relFile = rel === "/" ? "index.html" : rel.slice(1);
      const absPath = safeJoin(config.clientStaticDir, relFile);
      if (!absPath) {
        res.writeHead(403, { "content-type": "text/plain" });
        res.end("Forbidden");
        return true;
      }
      serveFile(res, absPath);
      return true;
    }

    if (url.pathname === "/" || url.pathname === "/index.html") {
      if (webDistExists) serveFile(res, webIndex);
      else serveIndex(res, launcher);
      return true;
    }

    if (webDistExists && req.method === "GET") {
      const absPath = safeJoin(config.webStaticDir, url.pathname.slice(1));
      if (absPath && existsSync(absPath) && statSync(absPath).isFile()) {
        serveFile(res, absPath);
        return true;
      }
      serveFile(res, webIndex); // SPA fallback
      return true;
    }

    return false;
  };
}

/** Minimal launcher page: login/register, server list, and a PLAY button that
 *  requests a play ticket and boots the client into the bridge. */
function buildLauncherPage(config: Config): string {
  const bridgePath = config.bridgePath;
  return `<!doctype html><html lang="en"><head>
<meta charset="utf-8" /><meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Eagler Platform</title>
<style>
  body{font-family:system-ui,sans-serif;background:#0d1117;color:#e6edf3;margin:0}
  main{max-width:760px;margin:0 auto;padding:24px}
  h1{font-size:22px} input{display:block;width:100%;margin:6px 0 12px;padding:8px;border-radius:6px;border:1px solid #30363d;background:#161b22;color:#e6edf3}
  button{padding:8px 14px;border-radius:6px;border:0;background:#2ea043;color:#fff;cursor:pointer}
  .card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:12px 16px;margin:10px 0}
  .row{display:flex;justify-content:space-between;align-items:center;gap:12px}
  .muted{color:#8b949e;font-size:13px}
  #auth,#dashboard{display:none}
</style></head><body><main>
<h1>Eagler Platform</h1>
<section id="auth">
  <h2>Sign in</h2>
  <form id="loginForm">
    <input name="email" type="email" placeholder="Email" required />
    <input name="password" type="password" placeholder="Password" minlength="8" required />
    <button type="submit">Log in</button>
  </form>
  <h2>Create account</h2>
  <form id="registerForm">
    <input name="email" type="email" placeholder="Email" required />
    <input name="username" placeholder="Username (a-z A-Z 0-9 _)" pattern="[A-Za-z0-9_]{1,16}" required />
    <input name="password" type="password" placeholder="Password (min 8)" minlength="8" required />
    <button type="submit">Register</button>
  </form>
</section>
<section id="dashboard" style="display:none">
  <div class="row"><h2 id="whoami">…</h2><button id="logout">Log out</button></div>
  <div id="servers"></div>
  <p class="muted" id="gameStatus"></p>
</section>
<section id="game"><div id="game_frame" style="width:100%;display:none"></div></section>
<script>
(function () {
  var api = function (method, path, body) {
    return fetch(path, {
      method: method,
      headers: body ? { "Content-Type": "application/json" } : undefined,
      body: body ? JSON.stringify(body) : undefined,
      credentials: "same-origin"
    }).then(function (r) { return r.json().then(function (j) { return r.ok ? { ok: true, data: j } : { ok: false, error: j.error || ("HTTP " + r.status) }; }); });
  };
  function userBox(u) {
    document.getElementById("whoami").textContent = u.username + (u.role === "admin" ? " (admin)" : "");
  }
  function renderServers(list) {
    var el = document.getElementById("servers");
    el.innerHTML = "";
    (list || []).forEach(function (s) {
      var c = document.createElement("div");
      c.className = "card row";
      var meta = document.createElement("div");
      var name = document.createElement("strong");
      name.textContent = s.name + (s.maintenance ? " (maintenance)" : "");
      var desc = document.createElement("div");
      desc.className = "muted";
      desc.textContent = s.description || (s.address + ":" + s.port);
      meta.appendChild(name); meta.appendChild(desc);
      var btn = document.createElement("button");
      btn.textContent = "Play";
      btn.disabled = s.maintenance;
      btn.onclick = function () {
        api("POST", "/api/servers/" + s.id + "/play").then(function (r) {
          if (!r.ok) { document.getElementById("gameStatus").textContent = "Error: " + r.error; return; }
          launch(r.data.bridgeUrl, s.name);
        });
      };
      c.appendChild(meta); c.appendChild(btn);
      el.appendChild(c);
    });
  }
  function launch(bridgeUrl, name) {
    document.getElementById("gameStatus").textContent = "Connecting to " + name + "...";
    var frame = document.getElementById("game_frame");
    frame.style.height = "100vh";
    frame.style.display = "block";
    window.scrollTo(0, 0);
    var holder = {
      demoMode: false,
      container: "game_frame",
      assetsURI: "/client/assets.epk",
      localesURI: "/client/lang/",
      worldsDB: "worlds",
      logInvalidCerts: true,
      crashOnUncaughtExceptions: true,
      servers: [ { addr: bridgeUrl, name: name } ],
      relays: []
    };
    window.eaglercraftXOpts = holder;
    window.eaglercraftXOpts.joinServer = bridgeUrl;
    var s = document.createElement("script");
    s.src = "/client/classes.js";
    s.onload = function () { if (typeof main === "function") main(); };
    document.body.appendChild(s);
  }
  function gate() {
    api("GET", "/api/me").then(function (r) {
      if (r.ok) {
        document.getElementById("auth").style.display = "none";
        document.getElementById("dashboard").style.display = "block";
        userBox(r.data);
        api("GET", "/api/servers").then(function (r2) { if (r2.ok) renderServers(r2.data); });
      } else {
        document.getElementById("auth").style.display = "block";
        document.getElementById("dashboard").style.display = "none";
      }
    });
  }
  document.getElementById("loginForm").onsubmit = function (e) {
    e.preventDefault();
    var f = e.target;
    api("POST", "/api/login", { email: f.email.value, password: f.password.value }).then(gate);
  };
  document.getElementById("registerForm").onsubmit = function (e) {
    e.preventDefault();
    var f = e.target;
    api("POST", "/api/register", { email: f.email.value, username: f.username.value, password: f.password.value }).then(gate);
  };
  document.getElementById("logout").onclick = function () { api("POST", "/api/logout", {}).then(gate); };
  gate();
})();
</script>
</main></body></html>`;
}

export { buildLauncherPage };