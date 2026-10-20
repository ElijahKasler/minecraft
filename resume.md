# Eaglercraft + MWC Proxy Custom Work — Resume & Handoff

**Workspace root:** `D:\Web Ports\minecraft\`

---

## What was built

### 1. Chunk loading optimization (fast PCs)
**Files:**
- `eaglercraft/src/game/java/net/minecraft/client/settings/GameSettings.java`
- `eaglercraft/src/game/java/net/minecraft/client/renderer/EntityRenderer.java`
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/minecraft/ChunkUpdateManager.java`
- `eaglercraft/target_lwjgl_desktop/build.gradle.kts`

**Changes:**
- New `GameSettings.fastChunkLoading` (default ON, persisted).
- Vanilla budgets chunk meshing at ¼ frame (~4ms at 60fps, ~1ms at 240fps — high FPS loaded chunks *slower*); fast mode uses a flat 10ms budget.
- `ChunkUpdateManager` mesh queue cap 100 → 512 in fast mode so teleports/fresh joins don't drop the backlog.
- Desktop runtime heap `-Xmx1G` → `-Xmx2G`.

### 2. MWC proxy integration — offline 1.8 servers
**Files (shared main source):**
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcProxyNetworkManager.java`
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcProxyHandshake.java`
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcProxyTokenFetch.java`

**Changes:**
- Raw vanilla TCP tunneled over MWC proxy WebSocket (VarInt framing, reassembly, 1.8 handshake protocol 47 + Login Start).
- Set Compression support; `proxy-shutdown:` surfacing.
- **Full vanilla 1.8 compression framing** (see §6c): a frame is `[dataLength VarInt][data]` where `dataLength == 0` means the raw packet follows and otherwise it is the **uncompressed** size of a zlib stream. Applied to **both** login-phase and play-phase inbound packets, and to **all** outbound packets once the threshold is negotiated (`compressOutboundFrame`).
- `MwcProxyTokenFetch` performs pure-Java `POST /api/vm/net/connect` (no JS bridge needed).
- `MwcProxyHandshake` supports `#mchost=&mcport=&user=` URL fragment parsing (shareable links).

### 3. Yggdrasil / online-mode (premium) auth — desktop only
Delegates Microsoft OAuth2 (device-code) **and** the Mojang session join to the
mwc-proxy, which already implements both under `{proxyBase}/api/vm/net`:

- `POST /auth` — SSE stream running `prismarine-auth`; emits the device code,
  a refreshed token cache, then the Minecraft access token + profile.
- `POST /session` — performs the `sessionserver.mojang.com/.../join` call by
  taking the plaintext shared secret + server public key and computing the
  Minecraft signed SHA-1 hash itself (responds `204`).

The client only generates + RSA-encrypts the shared secret/verify token locally
(via JCE), then sends the Encryption Response. Sign-in state is persisted
(access token, profile, prismarine-auth cache) in `EagRuntime` storage key `"A"`.

**Sign-in UX:** Multiplayer > "Microsoft Sign-in" link → `GuiMsaLogin` shows the
device code + verification URL; the flow runs on a daemon thread and can be
completed in any browser (leaving the screen does not cancel it).

**Files:**
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcCrypto.java` — crypto interface (RSA + AES-CTR).
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthDriver.java` — auth driver interface (reflection-based platform loading) + sign-in API.
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthState.java` — auth state holder (+ `proxyBase`, `profileName`).
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcMsaSignInState.java` — device-code flow state (volatile, GUI-pollable).
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcMsaAccountStore.java` — persisted account + prismarine-auth cache (storage key `"A"`).
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/GuiMsaLogin.java` — sign-in screen.
- `src/main/java/net/lax1dude/eaglercraft/v1_8/sp/gui/GuiMsaSettingsButton.java` — "Microsoft Sign-in" text link.
- `src/lwjgl/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcCryptoDesktop.java` — JCE: RSA/ECB/PKCS1Padding + AES/CTR/NoPadding.
- `src/lwjgl/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthDriverDesktop.java` — SSE `/auth` device-code flow + `/session` join.
- `src/teavm/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcCryptoTeaVM.java` — browser stub (delegates to web-client-next).
- `src/teavm/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthDriverTeaVM.java` — browser stub (reports unsupported).

**Server-side (mwc-proxy):**
- `mwc-proxy/src/authEndpoint.ts` — `/auth` (SSE device-code) + `/session` (yggdrasil join).
- `mwc-proxy/src/api.ts` — `registerAuthEndpoint`; `urlRoot` default `/api/vm/net`.

**Changes to `MwcProxyNetworkManager`:**
- Handles Encryption Request (0x01) via yggdrasil auth instead of disconnecting.
- Sets `authState.proxyBase` from `webSocketClient.getCurrentURI()` for the join call.
- Sends Encryption Response (0x01) with RSA-encrypted shared secret + verify token.
- AES/CTR encrypt-outbound / decrypt-inbound on play packets after Login Success.
- Uses real UUID/username from Login Success for online mode; offline UUID for cracked servers.

### 4. Multiplayer UI — proxy integrated into the normal multiplayer menu
**Files:**
- `src/game/java/net/minecraft/client/gui/GuiMultiplayer.java` — "Proxy Settings" link under Network Settings (`GuiProxySettingsButton`), styled like the relay button.
- `src/game/java/net/minecraft/client/gui/GuiScreenAddServer.java` / `GuiScreenServerList.java` — store plain `host:port`.
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/GuiProxySettingsButton.java` — link button.
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/GuiScreenMwcProxyList.java` + `GuiSlotMwcProxy.java` — proxy list screen styled exactly like the relay server list (name + address, star on the active proxy, double-click or "Use Proxy" to activate).
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/GuiScreenMwcProxyEdit.java` — add/edit a proxy entry (name + address); the default "Local Proxy" entry's address is settable here too.
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcProxyListManager.java` — persisted proxy list (EagRuntime storage key `"m"`), defaults to `Local Proxy` @ `http://localhost:2344`.

**Flow:** type `host:port` in Add Server or Direct Connect (or select a saved one) and `GuiConnecting` fetches a proxy token off-thread via the active proxy, then joins. No separate "Vanilla via Proxy" screen and no username field — the username is always the current profile name. Saved `host:port` entries ping their MOTD through the proxy too (`MwcProxyStatusQuery`).

### 5. JEI recipe overlay (ported web-client JEI)
**Files:**
- `src/game/java/net/minecraft/client/gui/inventory/jei/JeiRegistry.java`
- `src/game/java/net/minecraft/client/gui/inventory/jei/JeiRecipe.java`
- `src/game/java/net/minecraft/client/gui/inventory/jei/GuiScreenJei.java`
- `src/game/java/net/minecraft/client/gui/JeiSidePanel.java`
- `src/game/java/net/minecraft/client/gui/inventory/ShapedRecipes.java` (+ `getJeiWidth/Height/Items`)
- `src/game/java/net/minecraft/client/gui/inventory/ShapelessRecipes.java` (+ `getJeiWidth/Height/Items`)
- `src/game/java/net/minecraft/client/Minecraft.java` (J key toggle)
- `src/game/java/net/minecraft/client/gui/inventory/GuiInventory.java`
- `src/game/java/net/minecraft/client/gui/inventory/GuiContainerCreative.java`
- `desktopRuntime/.../lang/en_US.lang`

**Controls:** type to search · wheel to scroll · left-click in creative = take full stack · right-click/R = recipes · middle/U = uses · J or [x] hides.

### 6. Connect error codes + proxy diagnostics
**File:** `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/ConnectErrorCodes.java`
- `EAG-001`..`EAG-009` cause+fix texts for handshake errors.
- `MWC-TOKEN-*` (incl. new `DNS`/`REFUSED`/`TLS`), `MWC-SOCKET`, `MWC-HTTP-*`, `MC-LOGIN-*`, `MC-COMPRESS`, `MC-FRAME`, `MC-ENCRYPT`, `MC-AUTH`, `MC-TIMEOUT`, `MC-CLOSED` prefixes.
- `classifyTransportError(detail)` maps transport failure strings to a code; `describeUnreachableProxy(base, code, cause)` builds the full cause+fix disconnect text.
- Desktop `PlatformRuntime.postJSON` returns `-1\n<Exception: message>` on transport failure (was `null`), so disconnects show the real cause (DNS, refused, timeout, TLS).
- `MwcProxyTokenFetch.requestToken(base, host, port, retry)`: `retry=false` (used by the server-list ping) tries each base once with no sleeps; retries classify each failure and keep `lastCode`/`lastErr`.
- **Server-list ping failures show detail:** `MwcProxyStatusQuery.publishError()` publishes a MOTD response with `§c[CODE] reason` / `§g Fix: ...` lines on every failure path (token, socket open/close/timeout, no response, crash); `ServerData.setMOTDFromQuery` marks those as `pingToServer=-1` (red X).
- **Root-cause fix:** `GameSettings` loaded `mwcProxyBase` via `split(":")`, truncating `mwcProxyBase:http://localhost:2344` to `http` (caused `UnknownHostException: http`). Now parsed as substring-after-first-colon + validated (`://` or `host:port`, else reset to default); valueless option lines are skipped (kills `Skipping bad option: lastServer:` spam).

### 6b. Tunnel lifetime + login stall diagnostics (fixed "stuck on Logging in / 30s timeout / no proxy logs")
**Proxy (`mwc-proxy/src/api.ts`, rebuilt to `dist/` via `bun run build`):**
- **Root cause of login stalls:** `socket.setTimeout(1000 * 6)` stayed active for the whole tunnel lifetime and its handler called `socket.end()` — any 6s of TCP silence (slow server login, online-mode Mojang auth) killed the tunnel. Now the 6s timer only covers the pre-attach window (token issued, client WS never attached); the `/socket` handler calls `socket.setTimeout(0)` on attach.
- **"No proxy logs" fixed:** proxy logs went only to `dist/logs/access.log`. `app.ts` `log()` now mirrors every line to the console: `[C]` connect request, `[U]` token issued, `[S]` tunnel attached / rejected token, `[T]` timeout drops, `[D]` disconnect.
- Verified live: token + WS attach + 9s idle + data send — tunnel stays open (previously died at 6s); full lifecycle visible on console.

**Client:**
- `MwcProxyHandshake`: staged 25s login timeout — on stall, disconnect shows the exact stage (`describeLoginStage()`: handshake/login-start sent, Yggdrasil auth in progress, encryption response sent...) + target `host:port` + fix text. Progress logs every 5s while waiting; logs WS connected / Login Start sent.
- `MwcProxyNetworkManager`: `describeLoginStage()`; logs handshake/login-start sends and first tunnel data from the server (bytes + frames).
- `WebSocketClientImpl.onMessage(ByteBuffer)`: no longer uses `array()` blindly (corrupted frames when the buffer was a slice); copies only `remaining()` bytes.
- Tunnel-close-before-login message no longer blames "Token expired?" — now says the proxy or server dropped the connection and points at the proxy console/access.log.

### 6c. Vanilla 1.8 packet compression (fixed the "This client is 1.8" join failure)
**Symptom:** joining a 1.8.8 server through the proxy showed the disconnect text `... (Version mismatch? This client is 1.8. ...)` even though the server logged `<player> joined the game`.

**Root cause (two bugs in `MwcProxyNetworkManager`):**
1. **Inbound:** the login-phase pump (`pumpTcpFrames(true)`) dispatched packets to `handleLoginPacket` *without* running compression handling (only the play branch called `handleDecompression`). A vanilla 1.8.8 server always sends `Set Compression (0x03, default threshold 256)` right after Login Start, then sends **Login Success below the threshold as a marker-0 frame** (`[0x00][0x02 ...]`). The client read the `0x00` marker as packet id `0x00` = *Disconnect (login)*, parsed the UUID string as the kick reason, and surfaced it with our version-mismatch hint.
2. **Outbound:** `sendPacket`/`injectRawFrame` never applied compression framing, so after the threshold was negotiated the vanilla server's decompressor saw garbage (e.g. a KeepAlive reply `[0x00 id][long]` reads as the `0x00` marker with a truncated packet) — the session would be dropped right after spawn.

**Fix:**
- `pumpTcpFrames`: decrypt (if encrypted) then decompress (if `compressionThreshold >= 0`) **before** dispatching, for both login and play branches.
- New `compressOutboundFrame(payload)`: `< threshold` → `[0x00][payload]`; otherwise `VarInt(uncompressed length) + zlib(payload)` using `java.util.zip.Deflater` (fresh stream per packet, `finish()`ed).
- `sendRawPlayPayload` now compresses first, then encrypts (`MwcCrypto.aesCtr`) — matching the wire order `frame(encrypt(compress(packet)))`. The encrypt step was moved out of `sendPacket`/`injectRawFrame` into `sendRawPlayPayload`.
- `handleDecompression` hardened to inflate in a loop (chunk packets decompress to ~800 KB) and to report truncation/size mismatch.
- Online-mode: encryption is now enabled in the auth `onSuccess` callback (before the Encryption Response is sent) because vanilla turns its cipher on immediately after validating the response, so `Set Compression` + `Login Success` arrive **encrypted**. Counter resets at Login Success were removed (they would desync the stream).

**Verified against a real vanilla 1.8.8 server through the proxy:** Set Compression parsed, Login Success decompressed, 44 chunk packets (id `0x26`, ~826 KB inflated each) decompressed, 22/22 KeepAlives answered over 40 s with the connection staying open, and serverbound `Chat` delivered (`<FixProbe> hello from FixProbe` in the server log).

### 7. Python GUI launcher — `start_menu.py`
- Separate console tab per component (Server, Proxy, Client).
- Individual Start/Stop + Start All / Stop All buttons.
- Live logs per component.
- Auto-kills anything already bound to port 25565 / 2344 before starting.
- Startup validation for missing files (`server.jar`, `mwc-proxy/dist/app.js`, `gradlew.bat`).
- Connection help dialog.
- **Bundled server JVM:** `SERVER_CMD` now prefers `tools/temurin8/bin/java.exe` (Temurin 8u504) and falls back to `java` on PATH; `start.bat` does the same. Vanilla 1.8.8 runs on Java 27 too, but JDK 8 is the safe, canonical choice.

### 8. Python setup downloader — `setup_multiplayer.py`
- Downloads Minecraft 1.8.8 `server.jar` from Mojang.
- Creates `eula.txt` (`true`) and `server.properties` (`online-mode=false`).
- Optionally installs `bun` runtime.
- Runs `bun install` in `mwc-proxy/`.

### 9. `start.bat` — single tab/window
- Starts server (background), proxy (background), client (foreground) in one window/tab.
- Works both in cmd and Windows Terminal.

### 10. `README.md` — updated
- Documents all new features, limits, run commands, and verification steps.

### 11. Multi-version server support — ViaProxy integration
**Goal:** let the 1.8 (protocol 47) client join servers of *other* versions (1.7 through the latest) without changing any client code.

**Approach:** bundle [ViaProxy](https://github.com/ViaVersion/ViaProxy) as a protocol-translation layer between the MWC tunnel and the real server. The client stays 1.8; ViaProxy translates 1.8 ↔ target.

**Architecture:**
```
Eaglercraft 1.8 client --WS--> mwc-proxy (WS<->TCP) --TCP--> ViaProxy (translates) --TCP--> target server
```
Routing needs **no** client or mwc-proxy change: the client just connects to the ViaProxy listen address (default `proxy://127.0.0.1:25568`); the raw-TCP mwc tunnel dials it.

**Files:**
- `tools/viaproxy/ViaProxy.jar` — ViaProxy 3.4.14 (Java 17+). `tools/` is gitignored.
- `tools/viaproxy/viaproxy.yml` — main config; `bind-address: 127.0.0.1:25568`, `target-address: 127.0.0.1:25565`.
- `start_menu.py` — new **ViaProxy (Version Translator)** component: constants (`VIA_PROXY_*`), `write_viaproxy_config(target, bind)` helper (rewrites the two address lines), a settings frame (target + listen fields), Start All order `(server, viaproxy, proxy, client)` / Stop All reverse, help text, and a soft warning if the jar is missing.
- `start.bat` — starts ViaProxy (guarded by `if exist`) before the client, matching Start All.

**Verified end-to-end** (raw protocol-47 probe through mwc tunnel → ViaProxy → target):
- → **1.8.8** (passthrough): login, Join Game, 18/18 keepalives, 43 chunks.
- → **1.7.10** (older translation): login, Join Game, 18/18 keepalives, 88 chunks.
- → **26.3** (latest): login, Join Game, 2/2 keepalives, 49 chunks.

**Notes:**
- ViaProxy supports servers Release 1.0.0–26.3, Beta b1.0–b1.8.1, Alpha a1.0.15–a1.2.6, Classic c0.0.15–c0.30, April Fools, Combat snapshots, Bedrock (WIP).
- Headless start: `java -DskipUpdateCheck=1 -DignoreSystemRequirements=1 -jar ViaProxy.jar config viaproxy.yml` (with `VP_RUN_DIR` set); requires an existing `viaproxy.yml`.
- `wildcard-domain-handling: internal` lets a client pick the target+version per-connection via the handshake (`address\7host:port\7version\7...`) — a future option for dynamic per-server routing on a single instance.
- **Modern server gotcha:** the 26.3 server generates `white-list=true` by default (old versions default to false); set `white-list=false` for open testing.

---

## Current limitations (by design)

| Area | Limitation |
|------|-----------|
| Online-mode servers | Supported on **all three targets** (desktop, JS, WASM-GC): sign in via Multiplayer > "Microsoft Sign-in", which uses the tick-driven `/api/vm/net/auth/start` + `/api/vm/net/auth/poll` endpoints and `/api/vm/net/session` for the Yggdrasil join. Requires the connected proxy to implement them. Not yet exercised end-to-end with a real Microsoft account against an `online-mode=true` server (no credentials in this environment) — device-code emission, polling, and `/session` shape are verified. |
| Browser (TeaVM / WASM-GC) | Online-mode crypto is **implemented** (pure-Java AES-128/CFB8 + RSA PKCS#1 v1.5, Web Crypto for RNG). Proxy MOTD pings are skipped on WASM-GC (no threads + blocking socket poll would freeze the render loop); the join itself works. |
| Server version | 1.8 protocol 47 only *natively*. Other versions (`1.7` → latest) are supported by routing through the bundled **ViaProxy** translator (see §11); the client itself still speaks 1.8. |
| JEI | No fuel tab, no survival give, key label needs `assets.epk` rebuild in browser. |

---

## How to continue

### Immediate next steps
1. **Fix port-binding on start** — The Python launcher now kills processes on ports 25565/2344 before starting, but verify this works reliably on your machine.
2. **Online-mode auth** — DONE on desktop, JS and WASM-GC: sign-in is tick-driven through the proxy `/auth/start` + `/auth/poll` endpoints, the Mojang session join goes through `/session`, and the GUI lives at Multiplayer > "Microsoft Sign-in". Remaining: exercise it end-to-end with a real Microsoft account against an `online-mode=true` server (could not be tested in this environment); verify token auto-refresh on a second launch (stored cache).
3. **Browser crypto** — DONE: pure-Java AES-128/CFB8 + RSA PKCS#1 v1.5 in `src/main/.../socket/mwc/Mwc{Aes,Cfb8,Rsa,CryptoShared}.java`, RNG via `PlatformRuntime.fillRandomBytes` (Web Crypto on browsers). No `window.crypto.subtle` dependency.
4. **Test end-to-end** — Run `python setup_multiplayer.py` → `python start_menu.py` → Start All → Multiplayer → Add Server `localhost:25565` → join. (Offline 1.8.8 join verified end-to-end through the proxy as of this session; the bundled server JVM is `tools/temurin8`.)
5. **Test other versions in the GUI** — Start All (includes ViaProxy), then Add Server `proxy://127.0.0.1:25568` and join to reach whatever `target-address` ViaProxy points at (set it in the ViaProxy settings frame). Protocol-level multi-version joins are verified (§11); GUI confirmation pending.

### Build commands
```powershell
cd D:\Web Ports\minecraft\eaglercraft
.\gradlew target_lwjgl_desktop:compileJava          # desktop
.\gradlew target_teavm_javascript:classes           # web (JS)
.\gradlew target_teavm_wasm_gc:classes              # web (WASM-GC)
.\gradlew target_lwjgl_desktop:eaglercraftDebugRuntime  # run desktop client
```

### Key files to modify next
| Goal | File |
|------|------|
| Real Microsoft auth | `src/main/.../socket/mwc/MwcAuthDriverShared.java` (driver), `src/main/.../socket/mwc/GuiMsaLogin.java` (GUI), `mwc-proxy/src/authEndpoint.ts` (server) |
| Browser crypto | `src/main/.../socket/mwc/Mwc{Aes,Cfb8,Rsa,CryptoShared}.java` + `PlatformRuntime.fillRandomBytes` |
| Proxy allowlist / routing | `mwc-proxy/dist/api.js` (`createProxyMiddleware` options) |
| New multiplayer UI | `src/game/.../client/gui/GuiMultiplayer.java` |
| Chunk loading tweaks | `src/game/.../client/renderer/EntityRenderer.java`, `src/main/.../minecraft/ChunkUpdateManager.java` |

---

## Files created / modified in this session

**Created:**
- `D:\Web Ports\minecraft\start_menu.py`
- `D:\Web Ports\minecraft\setup_multiplayer.py`
- `D:\Web Ports\minecraft\README.md`
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcCrypto.java`
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthDriver.java`
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthState.java`
- `eaglercraft/src/lwjgl/.../socket/mwc/MwcCryptoDesktop.java`
- `eaglercraft/src/lwjgl/.../socket/mwc/MwcAuthDriverDesktop.java`
- `eaglercraft/src/teavm/.../socket/mwc/MwcCryptoTeaVM.java`
- `eaglercraft/src/teavm/.../socket/mwc/MwcAuthDriverTeaVM.java`

### Online-mode (premium) auth additions
**Created:**
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcMsaSignInState.java`
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcMsaAccountStore.java`
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/GuiMsaLogin.java`
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/sp/gui/GuiMsaSettingsButton.java`

**Modified:**
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthDriver.java` (sign-in API)
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthState.java` (`proxyBase`, `profileName`)
- `eaglercraft/src/lwjgl/.../socket/mwc/MwcAuthDriverDesktop.java` (real `/auth` + `/session`)
- `eaglercraft/src/teavm/.../socket/mwc/MwcAuthDriverTeaVM.java` (unsupported stubs)
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcProxyNetworkManager.java` (set `proxyBase`)
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcProxyTokenFetch.java` (`httpBaseFromSocketUrl`)
- `eaglercraft/src/game/java/net/minecraft/client/gui/GuiMultiplayer.java` (sign-in link)

**Modified:**
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcProxyNetworkManager.java`
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcProxyTokenFetch.java`
- `eaglercraft/src/game/java/net/minecraft/client/gui/GuiMultiplayer.java`
- `eaglercraft/src/game/java/net/minecraft/client/gui/GuiScreenAddServer.java`
- `eaglercraft/src/game/java/net/minecraft/client/gui/GuiScreenServerList.java`
- `eaglercraft/src/game/java/net/minecraft/client/settings/GameSettings.java`
- `eaglercraft/src/game/java/net/minecraft/client/renderer/EntityRenderer.java`
- `eaglercraft/src/main/java/net/lax1dude/eaglercraft/v1_8/minecraft/ChunkUpdateManager.java`
- `D:\Web Ports\minecraft\start.bat`
- `D:\Web Ports\minecraft\README.md`

### 11 (multi-version) additions
**Created:**
- `tools/viaproxy/ViaProxy.jar` (gitignored), `tools/viaproxy/viaproxy.yml`

**Modified:**
- `D:\Web Ports\minecraft\start_menu.py` (ViaProxy component)
- `D:\Web Ports\minecraft\start.bat` (start ViaProxy)
- `D:\Web Ports\minecraft\.gitignore` (ignore `server17/`, `server263/` test servers)

---

### 12. Online-mode (premium) on desktop + JS + WASM-GC

**Crypto (platform-agnostic, shared by all targets):**
- Created `MwcAes.java` (AES-128 forward, `encryptBlock(w,in,inOff,out,outOff)`), `MwcCfb8.java` (true CFB8: re-encrypts the 128-bit register for **every** byte), `MwcRsa.java` (RSA PKCS#1 v1.5 + X.509 SPKI DER parser), `MwcCryptoShared.java` (singleton backend).
- `MwcCrypto.java` is now an interface with `newSharedSecret()`, `rsaEncrypt()`, `createCfb8()`, `get()`. Deleted `MwcCryptoDesktop.java` / `MwcCryptoTeaVM.java`.
- Validated against JCE: CFB8 encrypt/decrypt match; RSA round-trip matches (200/200).

**Tunnel encryption (vanilla-correct):**
- `MwcProxyNetworkManager` now holds stateful `MwcCfb8` ciphers, decrypts every inbound WS chunk **before** framing, and encrypts the VarInt length prefix + payload together. Enabled right before sending the (unencrypted, raw) Encryption Response; Login Success fallback retained.

**Sign-in without threads/SSE:**
- Proxy: `POST /api/vm/net/auth/start` (returns `sessionId` immediately, flow continues server-side) and `POST /api/vm/net/auth/poll` (`pending` + device code, then `complete` + `newCache` + `tokenData`, or `error`/404 `expired`). Sessions swept after 20 min.
- Client: `MwcAuthDriverShared` is a tick-driven state machine (`GuiMsaLogin.updateScreen` calls `tick()`), one short POST per tick. `begin()` does local RSA + proxy `/session` join.
- `PlatformRuntime` gained `fillRandomBytes(byte[])`, `threadsSupported()`, `runAsync(Runnable)`, `sleepThread(long)`. Desktop/JS use `SecureRandom`/`Thread`; WASM uses Web Crypto (`crypto.getRandomValues` over linear memory) and runs async work inline. All direct `Thread`/`SecureRandom` references were removed from shared code (`MwcProxyTokenFetch`, `MwcProxyStatusQuery`) so the WASM linker succeeds.
- WASM proxy MOTD pings are skipped (no threads; blocking socket poll would freeze the render loop).

**Build verification:** `target_lwjgl_desktop:compileJava`, `target_teavm_javascript:generateJavaScript`, and `target_teavm_wasm_gc:generateWasmGC` (note: **not** `generateWasm`) all pass. Proxy `bun run build` passes. `/auth/start` + `/auth/poll` verified live (device code returned).

---

### 13. Launcher HTML client + clickable sign-in link

**`start_menu.py`:**
- New "Open HTML Client" button: serves `eaglercraft/target_teavm_javascript/javascript` via `ThreadingHTTPServer` on port 8090 (falls through 8090-8109) and opens the browser. Returns a "build it first" error when `index.html` is missing. Static handler sends `Cache-Control: no-store` so rebuilt `classes.js` is always picked up.
- "Stop All" and window close now really terminate everything: child-process **tree** kill on Windows (`taskkill /PID <pid> /T /F`), port freeing for every `PORT_MAP` entry, and the HTML server is shut down on exit.

**Sign-in link fix:**
- `GuiMsaSettingsButton` (y=37) and `GuiProxySettingsButton` (y=21) draw their labels under `GlStateManager.scale(0.75)`, but their hover/click boxes used unscaled `y`. For y=37 the text sat at ~28-35 while the hitbox was 33-44, so the label was effectively unclickable -> "Microsoft Sign-in does nothing". Both hitboxes (and `GuiProxySettingsButton`'s) now use `ty = y*3/4`.
- Browser proxy path validated: `POST /api/vm/net/auth/start` with `Origin: http://127.0.0.1:8090` returns 200 + `Access-Control-Allow-Origin: *`; `OPTIONS` preflight returns 200 with the correct allow headers; `/auth/poll` returns a device code.
- Rebuilt `target_teavm_javascript:generateJavaScript` and `target_teavm_wasm_gc:generateWasmGC` (both successful).
