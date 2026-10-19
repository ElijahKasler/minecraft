package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

/**
 * Bridges the vanilla "Connecting" screen to a mwc-proxy socket.
 *
 * <p>Usage from JS shell (see {@code js/eaglerMwcBridge.js}):</p>
 * <pre>
 *   // 1. JS: token = await POST {proxy}/api/vm/net/connect {host,port}
 *   // 2. JS: wsUrl = proxyWsBase + "/api/vm/net/socket?token=" + token
 *   // 3. Java: MwcProxyHandshake.connect(mc, prevScreen, wsUrl, mcHost, mcPort, username)
 * </pre>
 *
 * <p>This polls the WS until open (like {@code HandshakerHandler} does for
 * Eagler servers), runs the vanilla login via {@link MwcProxyNetworkManager},
 * then installs {@link NetHandlerPlayClient} with the vanilla game protocol
 * (no Eagler plugin-message protocol).</p>
 */
public class MwcProxyHandshake {

	static final Logger logger = LogManager.getLogger("MwcProxyHandshake");

	/** Pending vanilla target set by GUI or ?mwcSocket auto-join before GuiConnecting opens. */
	public static String pendingMcHost = null;
	public static int pendingMcPort = 25565;
	public static String pendingUsername = null;

	public static boolean isMwcSocketUrl(String addr) {
		return addr != null && addr.contains("/api/vm/net/socket");
	}

	/**
	 * True for server-list addresses that must be reached through the MWC proxy:
	 * explicit {@code proxy://host:port} or a bare {@code host:port} (no scheme).
	 * Eagler server addresses always carry a {@code ws://} or {@code wss://}
	 * scheme and are unaffected.
	 */
	public static boolean isProxyAddress(String addr) {
		if (addr == null) {
			return false;
		}
		String a = addr.trim();
		if (a.isEmpty()) {
			return false;
		}
		if (a.startsWith("proxy://")) {
			return true;
		}
		return a.indexOf("://") < 0;
	}

	/** {@code proxy://host[:port]} or bare {@code host[:port]} -> {host, portString}. */
	public static String[] parseProxyAddress(String addr) {
		String a = addr == null ? "" : addr.trim();
		if (a.startsWith("proxy://")) {
			a = a.substring(8);
		}
		return MwcProxyNetworkManager.splitHostPort(a, 25565);
	}

	public static String formatProxyAddress(String host, int port) {
		return port == 25565 ? ("proxy://" + host) : ("proxy://" + host + ":" + port);
	}

	public static void setPendingTarget(String mcHost, int mcPort, String username) {
		pendingMcHost = mcHost;
		pendingMcPort = mcPort;
		pendingUsername = username;
	}

	public static void clearPendingTarget() {
		pendingMcHost = null;
		pendingUsername = null;
	}

	/**
	 * Reads a {@code #mchost=&mcport=&user=} fragment appended to an MWC socket
	 * URL (fragments never hit the wire, browsers strip them). Lets share links
	 * and launchers pass the vanilla target without any JS bridge state.
	 *
	 * @return {host, port, user} or null
	 */
	public static String[] parseFragmentTarget(String url) {
		try {
			if (url == null) {
				return null;
			}
			int hash = url.indexOf('#');
			if (hash < 0 || hash == url.length() - 1) {
				return null;
			}
			String frag = url.substring(hash + 1);
			String host = null, port = "25565", user = null;
			int i = 0;
			while (i < frag.length()) {
				int amp = frag.indexOf('&', i);
				String pair = amp < 0 ? frag.substring(i) : frag.substring(i, amp);
				i = amp < 0 ? frag.length() : amp + 1;
				int eq = pair.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String k = pair.substring(0, eq);
				String v = decodeFrag(pair.substring(eq + 1));
				if (k.equals("mchost")) {
					host = v;
				} else if (k.equals("mcport")) {
					port = v;
				} else if (k.equals("user")) {
					user = v;
				}
			}
			if (host == null || host.isEmpty()) {
				return null;
			}
			return new String[] { host, port, user };
		} catch (Throwable t) {
			return null;
		}
	}

	private static String decodeFrag(String s) {
		try {
			StringBuilder sb = new StringBuilder(s.length());
			for (int i = 0; i < s.length(); ++i) {
				char c = s.charAt(i);
				if (c == '%' && i + 2 < s.length()) {
					int h = Character.digit(s.charAt(i + 1), 16);
					int l = Character.digit(s.charAt(i + 2), 16);
					if (h >= 0 && l >= 0) {
						sb.append((char) ((h << 4) + l));
						i += 2;
						continue;
					}
				}
				sb.append(c == '+' ? ' ' : c);
			}
			return sb.toString();
		} catch (Throwable t) {
			return s;
		}
	}

	private final Minecraft mc;
	private final GuiScreen prevScreen;
	private final GuiScreen parentProgress;
	private final IWebSocketClient websocket;
	private final String mcHost;
	private final int mcPort;
	private final String username;
	private final MwcProxyNetworkManager networkManager;

	private boolean finished = false;
	private boolean enteredPlay = false;
	private int ticks = 0;
	private int connectedTicks = 0;
	private boolean loggedConnected = false;
	private boolean loggedLoginSent = false;

	/** No Login Success within this after WS open -> fail with stage info. */
	private static final int LOGIN_TIMEOUT_TICKS = 25 * 20;

	public static MwcProxyHandshake connect(Minecraft mc, GuiScreen prevScreen, GuiScreen progressScreen,
			String proxySocketUrl, String mcHost, int mcPort, String username) {
		IWebSocketClient ws = PlatformNetworking.openWebSocket(proxySocketUrl);
		MwcProxyNetworkManager mgr = new MwcProxyNetworkManager(ws, mcHost, mcPort, username);
		MwcProxyHandshake h = new MwcProxyHandshake(mc, prevScreen, progressScreen, ws, mcHost, mcPort, username, mgr);
		return h;
	}

	private MwcProxyHandshake(Minecraft mc, GuiScreen prevScreen, GuiScreen progressScreen,
			IWebSocketClient websocket, String mcHost, int mcPort, String username,
			MwcProxyNetworkManager networkManager) {
		this.mc = mc;
		this.prevScreen = prevScreen;
		this.parentProgress = progressScreen;
		this.websocket = websocket;
		this.mcHost = mcHost;
		this.mcPort = mcPort;
		this.username = username;
		this.networkManager = networkManager;
	}

	/** Used by GuiConnecting when joinServer is already an open MWC socket URL. */
	public MwcProxyHandshake(net.minecraft.client.multiplayer.GuiConnecting parent,
			IWebSocketClient websocket, String mcHost, int mcPort, String username) {
		this(net.minecraft.client.multiplayer.GuiConnecting.getMC(parent),
				net.minecraft.client.multiplayer.GuiConnecting.getPrevScreen(parent),
				parent, websocket, mcHost, mcPort, username,
				new MwcProxyNetworkManager(websocket, mcHost, mcPort, username));
	}

	/** Call every client tick from the connecting screen. */
	public void tick() {
		if (finished) {
			// Login already succeeded. Keep draining the tunnel each tick until
			// Join Game is processed: that creates the world and swaps the screen
			// to GuiDownloadTerrain, after which Minecraft.runTick's world loop
			// (PlayerControllerMP.updateController) pumps packets on its own.
			// Without this the client would sit on "Logging in..." forever and
			// never answer KeepAlive, so the server times the session out.
			if (enteredPlay) {
				pumpPlay();
			}
			return;
		}
		++ticks;
		try {
			switch (websocket.getState()) {
				case CONNECTING:
					if (ticks > 20 * 15) fail("connect.timeout", ConnectErrorCodes.mwc(ConnectErrorCodes.Play.TIMEOUT,
							"Proxy socket timed out (15s). Proxy down, wrong URL, or blocked WS."));
					return;
				case FAILED:
				case CLOSED:
					if (networkManager.getDisconnectReason() != null) {
						fail(null, networkManager.getDisconnectReason());
					} else if (!networkManager.hasReceivedTunnelData()) {
						fail("connect.failed", ConnectErrorCodes.mwc(ConnectErrorCodes.Play.CLOSED,
								"The server closed the connection without sending a single byte.\n"
										+ "Fix: it is likely NOT a vanilla Minecraft 1.8 server (e.g. a 1.7.x or"
										+ " Eaglercraft server). The MWC proxy requires a vanilla MC 1.8"
										+ " (protocol 47) server. Check the server console."));
					} else {
						fail("connect.failed", ConnectErrorCodes.mwc(ConnectErrorCodes.Play.CLOSED,
								"Proxy tunnel closed before login completed.\n"
										+ "The proxy or the target server dropped the connection"
										+ (loggedLoginSent ? " after Login Start was sent" : "") + ".\n"
										+ "Fix: check the proxy console/access.log and the server console, then retry."));
					}
					return;
				case CONNECTED:
					if (!loggedConnected) {
						loggedConnected = true;
						logger.info("MWC tunnel WebSocket connected: {}", websocket.getCurrentURI());
					}
					break;
			}
			if (!networkManager.isLoginFinished()) {
				networkManager.tickLogin();
				if (networkManager.getDisconnectReason() != null) {
					fail(null, networkManager.getDisconnectReason());
					return;
				}
				++connectedTicks;
				String stage = networkManager.describeLoginStage();
				if (!loggedLoginSent && networkManager.getLoginState() == MwcProxyNetworkManager.MwcLoginState.AWAIT_LOGIN) {
					loggedLoginSent = true;
					logger.info("Login Start sent to {}:{} as \"{}\", awaiting server reply", mcHost, mcPort,
							username);
				}
				if (connectedTicks % 100 == 0 && connectedTicks > 0) {
					logger.info("Still awaiting login from {}:{} (stage: {}, {}s elapsed)", mcHost, mcPort, stage,
							connectedTicks / 20);
				}
				if (connectedTicks > LOGIN_TIMEOUT_TICKS) {
					String hint = networkManager.hasReceivedTunnelData()
							? "Fix: the server may be doing a slow online-mode (premium) auth, or it stopped"
									+ " answering mid-login. Check the server console; retry."
							: "Fix: the server accepted the connection but never sent a single byte — it is"
									+ " likely NOT a vanilla Minecraft 1.8 server (e.g. a 1.7.x or Eaglercraft"
									+ " server). The MWC proxy requires a vanilla MC 1.8 (protocol 47) server.";
					fail("connect.timeout", ConnectErrorCodes.mwc(ConnectErrorCodes.Play.TIMEOUT,
							"Server did not complete login within 25s.\n" + "Stage: " + stage + "\n" + "Target: "
									+ mcHost + ":" + mcPort + "\n" + hint));
					return;
				}
				return;
			}
			// Login complete -> enter PLAY like HandshakerHandler.handleSuccess, but vanilla.
			finish();
		} catch (Exception ex) {
			logger.error("MWC handshake failed (stage: {})", networkManager.describeLoginStage());
			logger.error(ex);
			if (networkManager.getDisconnectReason() != null) {
				fail(null, networkManager.getDisconnectReason());
			} else {
				fail("connect.failed", ex.getMessage() != null ? ex.getMessage() : "Handshake failed");
			}
		}
	}

	private void finish() {
		if (finished) return;
		finished = true;
		enteredPlay = true;
		websocket.setEnableStringFrames(false);
		websocket.clearStringFrames();
		// Use real UUID/name from Login Success if online mode, else offline UUID.
		String realUuid = networkManager.getLastLoginUuid();
		String realName = networkManager.getLastLoginName();
		if (realUuid != null && realName != null) {
			mc.getSession().update(realName, net.lax1dude.eaglercraft.v1_8.EaglercraftUUID.fromString(realUuid));
		} else {
			// Offline-mode UUID (same scheme vanilla uses for cracked servers).
			mc.getSession().update(username, net.minecraft.entity.player.EntityPlayer.getOfflineUUID(username));
		}
		networkManager.setConnectionState(EnumConnectionState.PLAY);
		// Vanilla play: pass a valid Eagler protocol object (required non-null by
		// NetHandlerPlayClient ctor) then detach its injected controller so vanilla
		// custom payloads (MC|Brand etc.) flow as normal packets.
		new NetHandlerPlayClient(mc, prevScreen, networkManager, mc.getSession().getProfile(),
				net.lax1dude.eaglercraft.v1_8.socket.protocol.GamePluginMessageProtocol.getByVersion(5));
		networkManager.setInjectedMessageController(null);
		mc.clearTitles();
	}

	/**
	 * Once login is done we are still the active connecting screen until Join Game
	 * arrives, so keep the tunnel drained. Failures here surface directly because
	 * {@link #fail} no-ops after {@link #finished} is set.
	 */
	private void pumpPlay() {
		try {
			networkManager.processReceivedPackets();
		} catch (Exception ex) {
			logger.error("MWC play packet pump failed", ex);
			try {
				websocket.close();
			} catch (Throwable t) {
			}
			if (mc.currentScreen == this.parentProgress) {
				mc.displayGuiScreen(new GuiDisconnected(prevScreen, "connect.failed", new ChatComponentText(
						ex.getMessage() != null ? ex.getMessage() : "Play packet pump failed")));
			}
		}
	}

	private void fail(String transKey, String detail) {
		fail(transKey, new ChatComponentText(detail != null ? detail : ""));
	}

	private void fail(String transKey, IChatComponent detail) {
		if (finished) return;
		finished = true;
		try { websocket.close(); } catch (Throwable t) {}
		IChatComponent msg = detail != null ? detail : new ChatComponentText("");
		if (transKey != null) {
			mc.displayGuiScreen(new GuiDisconnected(prevScreen, transKey, msg));
		} else {
			mc.displayGuiScreen(new GuiDisconnected(prevScreen, "connect.failed", msg));
		}
	}

	public boolean isFinished() {
		return finished;
	}

	public boolean isPlay() {
		return networkManager.isLoginFinished();
	}
}
