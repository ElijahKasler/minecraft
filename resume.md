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
**Files:**
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcCrypto.java` — crypto interface (RSA + AES-CTR).
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthDriver.java` — auth driver interface (reflection-based platform loading).
- `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthState.java` — auth state holder.
- `src/lwjgl/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcCryptoDesktop.java` — JCE: RSA/ECB/PKCS1Padding + AES/CTR/NoPadding.
- `src/lwjgl/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthDriverDesktop.java` — generates shared secret, RSA-encrypts with server public key.
- `src/teavm/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcCryptoTeaVM.java` — browser stub (delegates to web-client-next).
- `src/teavm/java/net/lax1dude/eaglercraft/v1_8/socket/mwc/MwcAuthDriverTeaVM.java` — browser stub.

**Changes to `MwcProxyNetworkManager`:**
- Handles Encryption Request (0x01) via yggdrasil auth instead of disconnecting.
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

---

## Current limitations (by design)

| Area | Limitation |
|------|-----------|
| Online-mode servers | Desktop auth driver uses dummy credentials. Real Microsoft OAuth2 device-code flow not yet implemented. Use `online-mode=false` for now. |
| Browser (TeaVM / WASM-GC) | RSA/AES crypto not implemented; delegates to `minecraft-web-client-next` for online-mode. |
| Server version | 1.8 protocol 47 only. Cannot speak 1.9–1.26 packets. Newer servers must use `minecraft-web-client-next` directly. |
| JEI | No fuel tab, no survival give, key label needs `assets.epk` rebuild in browser. |

---

## How to continue

### Immediate next steps
1. **Fix port-binding on start** — The Python launcher now kills processes on ports 25565/2344 before starting, but verify this works reliably on your machine.
2. **Online-mode auth** — Implement the Microsoft OAuth2 device-code flow in `MwcAuthDriverDesktop.java`:
   - Device code request → user authenticates at `https://aka.ms/devicecode`
   - Exchange code for Microsoft access token
   - Yggdrasil session join (POST access token + shared secret + public key to session endpoint)
   - RSA encrypt shared secret (already implemented)
3. **Browser crypto** — Implement AES/CTR via `window.crypto.subtle` in `MwcCryptoTeaVM.java` so web builds can connect to online-mode servers without delegating to web-client-next.
4. **Test end-to-end** — Run `python setup_multiplayer.py` → `python start_menu.py` → Start All → Multiplayer → Add Server `localhost:25565` → join. (Offline 1.8.8 join verified end-to-end through the proxy as of this session; the bundled server JVM is `tools/temurin8`.)

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
| Real Microsoft auth | `src/lwjgl/.../socket/mwc/MwcAuthDriverDesktop.java` |
| Browser AES/CTR | `src/teavm/.../socket/mwc/MwcCryptoTeaVM.java` |
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
