# Eaglercraft + mwc-proxy + web-client features, JEI, run setup

Everything below is new in this workspace. Eaglercraft stays authentic 1.8;
features from `mwc-proxy` and `minecraft-web-client-next` were ported **into**
the Eaglercraft client source, plus a bridge/launcher that shares one proxy.

## Run it

`start.bat` (workspace root) — **one tab/window**, all three components:

- **Minecraft 1.8.8 server** — `java -jar server.jar --nogui`, port `25565` (background)
- **MWC proxy** — `bun dist/app.js`, port `2344`, signal reporting off
- **Eaglercraft desktop client** — `gradlew target_lwjgl_desktop:eaglercraftDebugRuntime` (foreground)

**Python GUI menu:** `python start_menu.py` — start/stop all components
individually or together from a single window.

In-game: Multiplayer → **Vanilla via Proxy...** · **J** → **JEI** side panel.
Desktop runtime is for testing; touch/mobile and TeaVM quirks still need the
browser build (`eaglercraft/target_teavm_javascript/MakeOfflineDownload.bat`,
then serve `javascript/` on `:8081`).

## New folder: `eagler-mwc-integration/`

| File | What it does |
|---|---|
| `README.md` | Bridge architecture, install, try-it table, limits |
| `js/eaglerMwcBridge.js` | No-dep browser shell: `POST /connect → token`, builds `/socket?token=` URL, `?ip ?proxy ?username ?version ?autoConnect ?serversList ?setting` parsing, `probeAuthSupport()`, `window.__eaglerMwcJoin` watcher |
| `js/unifiedLauncher.html` | Version-router iframe launcher. `1.8 offline → Eagler`, everything else → web client. Copy-link sharable URLs, `force=web/eagler` override |
| `config/eaglercraftXOpts.patch.js` | Eagler shell `?params` mapping (`?mwcSocket ?serversList ?setting ?lockConnect`), `joinServer` wiring |
| `config/mwc-proxy.env.js` | `global.MWC_PROXY_CONFIG_OVERRIDES` preset (generous per-IP cap, metrics on). Auto-copied to `mwc-proxy/env.js` on first `start.bat` run |
| `docs/FEATURE_MATRIX.md` | Every mwc-proxy + web-client ability mapped to Eaglercraft status |
| `docs/PORTED_FEATURES.md` | UX parity notes (touch, packs, worlds, P2P, voice, query params) |
| `java/.../socket/mwc/*.java` | Mirror of the 3 MWC files now living in `eaglercraft/src` (backup/reference) |
| `java-jei/.../jei/*.java` | Mirror of the 3 JEI files now living in `eaglercraft/src` |

## Edits inside `eaglercraft/src` (the actual client)

**MWC proxy (join vanilla 1.8 offline servers, no EaglerXServer needed):**
- `main/.../socket/mwc/MwcProxyNetworkManager.java` (new) — raw vanilla TCP
  over proxy WS: VarInt framing + reassembly, 1.8 handshake (protocol 47) +
  Login Start, Set Compression support, `proxy-shutdown:` surfacing.
  Online-mode Encryption Request triggers yggdrasil auth flow (RSA + AES-CTR).
- `main/.../socket/mwc/MwcProxyHandshake.java` (new) — `pendingMcHost/Port/
  Username` statics, `isMwcSocketUrl()`, `#mchost=&mcport=&user=` fragment
  parsing (share links need no client-side state), login → `NetHandlerPlayClient`
  (valid protocol object, injected controller detached for vanilla payloads).
- `main/.../socket/mwc/MwcProxyTokenFetch.java` (new) — pure-Java proxy token
  fetch (`POST /api/vm/net/connect` via `PlatformRuntime.postJSON`, org.json
  parsing). **No JavaScript bridge needed.**
- `main/.../socket/mwc/GuiScreenMwcProxyConnect.java` (new) — host + proxy
  base + username form, persists `mwcProxyBase`, fetches the token itself and
  opens `GuiConnecting` on the socket URL.
- `platform-api/.../internal/PlatformRuntime.java` — new `postJSON(url, body)`
  native (`"status\nbody"` or null). Implemented per runtime: `lwjgl` =
  HttpURLConnection, `teavm` = fetch/XHR via `TeaVMFetchJS` (`@Async`, no UI
  block), `wasm-gc-teavm` = sync XHR `@JSBody`.
- `game/.../client/gui/GuiMultiplayer.java` — **Vanilla via Proxy...** button.
- `game/.../client/multiplayer/GuiConnecting.java` — detects
  `/api/vm/net/socket` URLs, runs MWC handshake instead of Eagler v3/v4/v5.

**Yggdrasil / online-mode support (desktop):**
- `main/.../socket/mwc/MwcCrypto.java` — crypto interface (RSA + AES-CTR)
- `lwjgl/.../socket/mwc/MwcCryptoDesktop.java` — JCE RSA/ECB/PKCS1Padding +
  AES/CTR/NoPadding
- `main/.../socket/mwc/MwcAuthDriver.java`, `MwcAuthState.java` — auth flow
  abstraction loaded by reflection per platform
- `lwjgl/.../socket/mwc/MwcAuthDriverDesktop.java` — generates shared secret,
  RSA-encrypts it with server public key
- `MwcProxyNetworkManager` — decrypt-inbound / encrypt-outbound AES-CTR on
  play packets after Login Success

**Web-client parity options (`GameSettings`, saved to `g` storage):**
`touchButtonsSize/Opacity`, `touchMovementType`, `alwaysShowMobileControls`,
`forceDisableTouchControls`, `chatSelect`, `serverResourcePacks`
(`prompt|always|never`), `showMinimap`, `voiceChatEnabled`, `autoFullscreen`,
`mouseRawInput`, `packetsRecording`, `mwcProxyBase`, plus `keyBindJei` (J).

**Enforced behavior:**
- `game/.../client/network/NetHandlerPlayClient.java` — global
  `serverResourcePacks` always/never bypasses the per-server prompt.
- `main/.../touch_gui/TouchOverlayRenderer.java` — `touchButtonsOpacity`
  scales overlay alpha, `forceDisableTouchControls` hides it.

**JEI recipe overlay (ported web-client JEI):**
- `game/.../item/crafting/ShapedRecipes.java`, `ShapelessRecipes.java` —
  added `getJeiWidth/Height/Items` getters (fields were private).
- `game/.../client/gui/inventory/jei/JeiRecipe.java` (new) — shaped /
  shapeless / furnace model.
- `game/.../client/gui/inventory/jei/JeiRegistry.java` (new) — all-items via
  creative tabs (metadata variants), recipes from `CraftingManager` +
  `FurnaceRecipes`, search, `getRecipesFor` / `getUsesFor`.
- `game/.../client/gui/JeiSidePanel.java` (new) — item list docked to the
  **right of the inventory** (search, wheel scroll, `[x]` hide + edge tab to
  re-open, touch friendly). Left-click takes a stack in creative,
  right-click / R = recipes, middle / U = uses.
- `game/.../client/gui/inventory/jei/GuiScreenJei.java` (new) — full-screen
  recipe browser (opened from the panel, Back returns to inventory).
- `game/.../client/Minecraft.java` — J toggles the side panel in
  inventories, opens the browser when no GUI is open.
- `game/.../client/gui/inventory/GuiInventory.java`,
  `GuiContainerCreative.java` — panel hooked into draw/click/keys/scroll.
- `desktopRuntime/.../lang/en_US.lang` — `key.jei` / `eaglercraft.key.jei`.

JEI controls: type to search · wheel to scroll · left-click in creative =
take full stack · right-click / R = recipes · middle / U = uses · J or [x]
hides, edge tab re-opens. Survival is view-only.

**Connect error codes (no more bare numbers):**
- `main/.../socket/ConnectErrorCodes.java` (new) — `EAG-001`..`EAG-009`
  cause+fix texts for handshake errors, `MWC-TOKEN-*` / `MWC-HTTP-*` /
  `MC-LOGIN-*` / `MC-COMPRESS` / `MC-FRAME` / `MC-TIMEOUT` / `MC-CLOSED`
  prefixes for proxy + vanilla paths.
- `.../protocol/handshake/HandshakerHandler.java` — all server errors (was:
  only custom messages) now show detailed codes; rate-limit screens show
  `EAG-006/007` detail instead of the generic kick.
- MWC token fetch, login kick, online-mode, compression, framing, timeout
  and close paths all carry codes.

Your `$5` was `EAG-005 Duplicate login session`: the server already had
your login open (double-clicked Join, stale tab, resent skin). Wait ~10s,
join once from Multiplayer; if it repeats, close other tabs and reset
your skin to default.

**Fast chunk loading (for fast PCs):**
- New `GameSettings.fastChunkLoading` (default ON, persisted). Vanilla
  budgets chunk meshing at 1/4 frame (~4ms at 60fps, ~1ms at 240fps — high
  FPS literally loaded chunks *slower*); fast mode uses a flat 10ms budget.
- `ChunkUpdateManager` mesh queue cap 100 → 512 in fast mode, so teleports
  and fresh joins don't drop the backlog. (`EntityRenderer`, `ChunkUpdateManager`)
- Desktop runtime heap `-Xmx1G` → `-Xmx2G` (`target_lwjgl_desktop/build.gradle.kts`).
- Honest limits: remote servers throttle chunk sends per tick (nothing
  client-side can pull faster), and singleplayer worldgen speed depends on
  world type. Verify with F3 chunk counters after `start.bat`.

## Limits (by design)

- Eaglercraft+MWC = **offline 1.8 only**. Online-mode needs MSA + session
  join + stream encryption (TeaVM has no JCE) — those servers go to
  web-client-next via the same proxy.
- No version spoofing: 1.8 protocol 47 cannot speak 1.9–1.26 packets.
  Example: `play.boholmc.net` (1.21+/Velocity) must use web-client
  `version=1.21.4`, never Eagler.
- JEI v1: no fuel tab, no survival give, key label needs `assets.epk`
  rebuild to show in browser.

## Verify

```powershell
cd C:\Users\elija\Downloads\minecraft\eaglercraft
.\gradlew target_lwjgl_desktop:compileJava   # should pass, notes only
```