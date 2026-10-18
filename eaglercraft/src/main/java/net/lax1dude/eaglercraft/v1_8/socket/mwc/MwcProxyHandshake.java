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
	private int ticks = 0;

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
		if (finished) return;
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
					} else {
						fail("connect.failed", ConnectErrorCodes.mwc(ConnectErrorCodes.Play.CLOSED,
								"Proxy socket closed before login. Token expired? Rejoin from the menu."));
					}
					return;
				case CONNECTED:
					break;
			}
			if (!networkManager.isLoginFinished()) {
				networkManager.tickLogin();
				if (networkManager.getDisconnectReason() != null) {
					fail(null, networkManager.getDisconnectReason());
					return;
				}
				return;
			}
			// Login complete -> enter PLAY like HandshakerHandler.handleSuccess, but vanilla.
			finish();
		} catch (Exception ex) {
			logger.error("MWC handshake failed");
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
