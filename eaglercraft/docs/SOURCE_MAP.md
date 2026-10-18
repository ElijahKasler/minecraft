# EaglercraftX 1.8 — Source Map (Phase 1 audit)

Companion document to `docs/ARCHITECTURE.md` and `EAGLERCRAFTX_README.md`.

This file documents how the supplied EaglercraftX 1.8 client source actually works so that the
platform (website, accounts, OAuth, server registry, WebSocket bridge, Java proxy) can be built
around it **without modifying the client's UI or game code**.

## 1. Project facts

| Item | Value |
| --- | --- |
| Fork | EaglercraftX 1.8, `projectForkName = EaglercraftX` |
| Version | `u53` (`EaglercraftVersion.java`), Minecraft `1.8.8` |
| Vendor | lax1dude (upstream: gitlab.com/lax1dude/eaglercraftx-1.8) |
| Build | Gradle 8.14 (wrapper), Java 17 toolchain, source/target compat 11 |
| Targets | `:target_lwjgl_desktop`, `:target_teavm_javascript`, `:target_teavm_wasm_gc` |
| Client asset bundle | `assets.epk` (EPK v53), also `lang.tmp.epk` locales |

### Build commands (verified)
- Root Java compile: `gradlew.bat compileJava`
- JS/TeaVM client + single-file offline downloads:
  `gradlew.bat :target_teavm_javascript:makeMainOfflineDownload`
- WASM-GC client bundle: `:target_teavm_wasm_gc:makeMainWasmClientBundle` (or `MakeWASMClientBundle.bat`)
- Desktop debug runtime: `:target_lwjgl_desktop:eaglercraftDebugRuntime` (or `StartDesktopRuntime.bat`)

`makeMainOfflineDownload` (verified 2026-09-22, JDK 17) writes to:
`target_teavm_javascript/javascript/` → `classes.js`, `classes.js.map`, `assets.epk`,
`EaglercraftX_1.8_Offline_en_US.html`, `EaglercraftX_1.8_Offline_International.html`.

## 2. Directory layout

| Path | Purpose |
| --- | --- |
| `src/main/.../v1_8/` | Eaglercraft API: networking, sockets, handshake, boot, platform hooks, singleplayer server |
| `src/game/java/net/minecraft/` | Deobfuscated vanilla Minecraft 1.8.8 client (c) 2015 Mojang AB |
| `src/protocol-game/` | EAG "plugin message" protocol (`GamePluginMessageProtocol`) shared formats |
| `src/protocol-relay/` | relay / voice / LAN peer protocol message formats |
| `src/protocol-auth/` | Eagler auth protocol types |
| `src/platform-api/` | generic platform API stubs (WebView, runtime, assets, etc.) |
| `src/sp/` (under main) | in-browser dedicated **singleplayer** server (`EaglerMinecraftServer`) |
| `src/teavm/` | TeaVM JS backend (browser indexDB, WebSocket, OpenGL) |
| `src/teavmc-shader/` | JS GLSL shader sources |
| `src/teavmc-classpath/` | TeaVM classpath providers |
| `src/wasm-gc-teavm/` | WASM-GC browser backend (its own WebSocket + platform JS) |
| `src/wasm-gc-teavm-bootstrap/`, `-loader/` | wasm loader glue |
| `src/lwjgl/` | LWJGL3 desktop backend |
| `target_*` | per-target Gradle projects and build outputs |

## 3. UI flow the platform must preserve (do NOT rebuild)

Multiplayer menu (`GuiMultiplayer`, `ServerList`, `ServerSelectScreen`) → connect to a server
entry (`ServerData`, `ServerAddress`) → `GuiConnecting` → `PlatformNetworking.openWebSocket(uri)`
→ `HandshakerHandler(handler=GuiConnecting, ws, username, ...)`.

- `ServerAddress.resolve()` + `AddressResolver.resolveURI()`: plain host → prepend `ws://`;
  `wss://…`/`ws://…` URLs are passed through untouched. Full URL (scheme/host/port/path/query)
  is preserved and visible to the bridge at the WebSocket upgrade. **This is how the platform
  passes server selection / auth tickets without touching the client** (e.g.
  `wss://bridge.example/eagler?ticket=<opaque>` or `?server=<registryId>`).
- The client's Eagler handshake `CLIENT_REQUEST_LOGIN` always contains the hardcoded
  `requestedServer = "default"` (`HandshakerInstance.begin()`), so address selection must come
  from the WS URL, not the handshake field.

## 4. Eagler WebSocket handshake (server-side must implement)

Implemented client-side in `src/main/java/net/lax1dude/eaglercraft/v1_8/socket/protocol/handshake/`.

Packet opcodes (`HandshakePacketTypes.java`):
| Op | Name | Direction |
| --- | --- | --- |
| 0x01 | CLIENT_PROTOCOL_VERSION | C→S |
| 0x02 | SERVER_PROTOCOL_VERSION | S→C |
| 0x03 | PROTOCOL_VERSION_MISMATCH | S→C |
| 0x04 | CLIENT_REQUEST_LOGIN | C→S (username, requestedServer, password, [cookies v4], [caps v5]) |
| 0x05 | SERVER_ALLOW_LOGIN | S→C (username, uuid) |
| 0x06 | SERVER_DENY_LOGIN | S→C |
| 0x07 | CLIENT_PROFILE_DATA | C→S (uuid, skin/cape URLs) |
| 0x08 | CLIENT_FINISH_LOGIN | C→S (connack to allow-login) |
| 0x09 | SERVER_FINISH_LOGIN | S→C (enters PLAY) |
| 0x0A | SERVER_REDIRECT_TO | S→C |
| 0xFF | SERVER_ERROR | S→C |
| 0x0B | CLIENT_AUTHENTICATE / SERVER_AUTHENTICATE (EAGLER AUTH) | C↔S |
| 0x0C | CLIENT_AUTHENTICATE_RESPONSE | C→S |

Version negotiation: v3 = base, v4 = adds cookies + `EAG|1.8` payloads, v5 = adds
`ClientCapabilities` map + supports the "injected" `0xEE` packet framing.
Client version starts at 3 and may upgrade to the server's v4/v5 if the server announces
`ServerCapabilities` (SERVER_PROTOCOL_VERSION frame carries the highest supported version).

Sequence (`HandshakerHandler`):
1. Server sends `CLIENT_PROTOCOL_VERSION` frame.
2. Client may request version upgrade → server responds (code 0x02 with negotiated version `vN`).
3. Client sends `CLIENT_REQUEST_LOGIN` (see above).
4. Server:
   - `SERVER_ALLOW_LOGIN(username, uuid)` → client sends `CLIENT_PROFILE_DATA`, then `CLIENT_FINISH_LOGIN`; server sends `SERVER_FINISH_LOGIN` → **PLAY**.
   - or `SERVER_DENY_LOGIN` (client shows deny text).
   - or redirect (0x0A) to another wss URI.

Auth:
- `AuthTypes.AUTH_METHOD_NONE` = no password.
- `AUTH_METHOD_EAGLER_SHA256`: `passwordHash = applyEaglerSHA256(password, salt)`, a
  server-issued 32-byte salt challenge (EaglerX `EAGLER_SHA256_SALT_SAVE`). Client never sends
  plaintext; server must validate the hash. (Not required by this platform's primary flows;
  implemented defensively.)

Play-state entry: on `SERVER_FINISH_LOGIN` the client does **not** run the vanilla Minecraft
login. `HandshakerHandler.handleSuccess()` creates `WebSocketNetworkManager`, sets connection
state to `EnumConnectionState.PLAY`, constructs `NetHandlerPlayClient` directly, and the game
starts. Consequences for the bridge (critical):

1. The bridge must perform the entire **vanilla 1.8 login to the Java server on the user's
   behalf** (proxy-side), then signal success to the Eagler client.
2. The WebSocket carries **lower-layer transport** — one WebSocket binary message
   (server→client or client→server) = **one vanilla 1.8 packet** (VarInt packet ID + payload).
3. The client supports **no compression** (`setCompressionTreshold` throws) and **no
   client-side encryption** (`getIsencrypted() == false`, no encryption packets handled).
   The bridge must: never forward `S46PacketSetCompressionLevel`/`S03PacketEnableCompression`
   or any encryption handshake to the client; instead absorb server-side compression and AES
   encryption in the proxy, decompress/decrypt server→client and compress/encrypt
   client→server per the server's threshold.
4. The client sends a keep-alive every ~20s (`C00PacketKeepAlive`) and expects
   `S00PacketKeepAlive`.

## 5. Post-handshake packet flow (EAG plugin messages)

File `src/protocol-game/java/net/lax1dude/eaglercraft/v1_8/socket/protocol/`.

- v3/v4: EAG payloads ride vanilla `C17PacketCustomPayload` / receive `S3FPacketCustomPayload`
  on channel `EAG|1.8` (getLegacyName) — traffic decoded/re-encoded by `GamePluginMessageProtocol`.
- v5: `InjectedMessageController` matches a synthetic packet ID `0xEE` in the vanilla VarInt
  stream; the payload begins with the EAG packet ID byte. The bridge must translate these to a
  vanilla CustomPayload channel called `eagler:1-8` (getModernName) toward real Java servers,
  like the universal EaglerXBungee protocol. EAG "legacy" names used on vanilla channels:

| Channel (legacy) | Channel (modern) | Purpose |
| --- | --- | --- |
| `EAG|Skins-1.8` | `eagler:skins-1-8` | skin service request |
| `EAG|Capes-1.8` | `eagler:capes-1-8` | cape service request |
| `EAG|Voice-1.8` | `eagler:voice-1-8` | voice chat payloads |
| `EAG|UpdateCert-1.8` | `eagler:update-cert-1-8` | update certificate |
| `EAG|FNAWSEn-1.8` | `eagler:fnaws-1-8` | FNAWS skin provider |
| `EAG|1.8` | `eagler:1-8` | generic EAG plugin message (v4) |

`SPacketGetOtherSkinEAG` / `CPacketGetOtherSkinEAG` etc. are defined in
`src/protocol-game/.../pkt/client` and `.../pkt/server`.

## 6. Client identity & profiles

- No Microsoft/OAuth concept anywhere in the client. It is purely offline-capable.
- `net.minecraft.util.Session` wraps a `GameProfile(username, uuid)`;
  on new game the client fabricates a random offline uuid (`Session.reset()`).
- `EaglerProfile` (`src/main/.../v1_8/profile/`) holds the current username + chosen preset
  skin/cape id; persisted via NBT storage (browser indexDB/localStorage).
- `GuiAuthenticationScreen` is the Eagler "Authentication" screen (metadata for relaying
  authentication; not really auth).

## 7. Boot / launch options (how the platform embeds the client)

consume options from `window.eaglercraftXOpts` (`src/teavm/.../opts/JSEaglercraftXOptsRoot.java`
and wasm-gc equivalent). `ClientMain._main()` (JS) / `MainClass` (wasm) calls `main()`.

Supported fields (confirmed via `target_teavm_javascript/javascript/index.html`):
```js
window.eaglercraftXOpts = {
  container: "game_frame",        // element id / selector
  assetsURI: "assets.epk",        // asset bundle URL (or data URI in offline builds)
  localesURI: "lang/",            // optional
  worldsDB: "worlds",             // singleplayer worlds storage key
  relayId / relays: [ { addr, comment, primary } ],
  servers: [ { addr: "wss://bridge.example/eagler", name: "…", hideAddr: false } ],
  joinServer: "wss://bridge.example/eagler?ticket=…",  // auto-join on startup
  demoMode: false, logInvalidCerts: true, crashOnUncaughtExceptions: true
};
```
The platform website (or `/play` launcher page) sets `joinServer` and/or `servers` before
loading `classes.js` and calling `main()`. **Zero client source changes required.**

## 8. Vanilla 1.8 packet IDs (from `EnumConnectionState.java`, registration order)

Useful for proxy testing/tooling. State IDs: HANDSHAKING -1 → LOGIN 2 → PLAY 0.

LOGIN clientbound: 0=Disconnect, 1=EncryptionRequest(S01), 2=LoginSuccess(S02), 3=EnableCompression(S03).
LOGIN serverbound: 0=LoginStart(C00), 1=EncryptionResponse(C01).

PLAY clientbound (id → class):
0 S00KeepAlive, 1 S01JoinGame, 2 S02Chat, 3 S03TimeUpdate, 4 S04EntityEquipment, 5 S05SpawnPosition,
6 S06UpdateHealth, 7 S07Respawn, 8 S08PlayerPosLook, 9 S09HeldItemChange, 10 S0AUseBed,
11 S0BAnimation, 12 S0CSpawnPlayer, 13 S0DCollectItem, 14 S0ESpawnObject, 15 S0FSpawnMob,
16 S10SpawnPainting, 17 S11SpawnExperienceOrb, 18 S12EntityVelocity, 19 S13DestroyEntities,
20 S14Entity, 21 S15EntityRelMove, 22 S16EntityLook, 23 S17EntityLookMove, 24 S18EntityTeleport,
25 S19EntityHeadLook, 26 S1AEntityStatus, 27 S1BEntityAttach, 28 S1CEntityMetadata, 29 S1DEntityEffect,
30 S1ERemoveEntityEffect, 31 S1FSetExperience, 32 S20EntityProperties, 33 S21ChunkData,
34 S22MultiBlockChange, 35 S23BlockChange, 36 S24BlockAction, 37 S25BlockBreakAnim,
38 S26MapChunkBulk, 39 S27Explosion, 40 S28Effect, 41 S29SoundEffect, 42 S2AParticles,
43 S2BChangeGameState, 44 S2CSpawnGlobalEntity, 45 S2DOpenWindow, 46 S2ECloseWindow, 47 S2FSetSlot,
48 S30WindowItems, 49 S31WindowProperty, 50 S32ConfirmTransaction, 51 S33UpdateSign, 52 S34Maps,
53 S35UpdateTileEntity, 54 S36SignEditorOpen, 55 S37Statistics, 56 S38PlayerListItem,
57 S39PlayerAbilities, 58 S3ATabComplete, 59 S3BScoreboardObjective, 60 S3CUpdateScore,
61 S3DDisplayScoreboard, 62 S3ETeams, 63 S3FCustomPayload, 64 S40Disconnect, 65 S41ServerDifficulty,
66 S42CombatEvent, 67 S43Camera, 68 S44WorldBorder, 69 S45Title, 70 S46SetCompressionLevel,
71 S47PlayerListHeaderFooter, 72 S48ResourcePackSend, 73 S49UpdateEntityNBT.

PLAY serverbound (id → class):
0 C00KeepAlive, 1 C01ChatMessage, 2 C02UseEntity, 3 C03Player, 4 C04PlayerPosition,
5 C05PlayerLook, 6 C06PlayerPosLook, 7 C07PlayerDigging, 8 C08PlayerBlockPlacement,
9 C09HeldItemChange, 10 C0AAnimation, 11 C0BEntityAction, 12 C0CInput, 13 C0DCloseWindow,
14 C0EClickWindow, 15 C0FConfirmTransaction, 16 C10CreativeInventoryAction, 17 C11EnchantItem,
18 C12UpdateSign, 19 C13PlayerAbilities, 20 C14TabComplete, 21 C15ClientSettings, 22 C16ClientStatus,
23 C17CustomPayload, 24 C18Spectate, 25 C19ResourcePackStatus.

## 9. Not needed / out of scope for the platform

- Renaming or rebuilding the vanilla UI, main menu, world gen, renderer, etc.
- Adding auth to the client runtime (it stays offline-first; sessions live server-side).
- WASM-GC vs JS (both support the same startup protocol; platform defaults to JS build above,
  documenting wasm-gc activation as a config toggle).