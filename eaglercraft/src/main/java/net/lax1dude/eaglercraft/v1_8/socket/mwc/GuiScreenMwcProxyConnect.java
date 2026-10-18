package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ChatComponentText;

import net.lax1dude.eaglercraft.v1_8.Keyboard;
import net.lax1dude.eaglercraft.v1_8.internal.KeyboardConstants;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

/**
 * "Join Vanilla via Proxy" screen. No JavaScript bridge needed: the proxy
 * token ({@code POST /api/vm/net/connect}) is fetched from pure Java via
 * {@link MwcProxyTokenFetch} (desktop: HttpURLConnection, browser:
 * fetch/XHR), then the client opens the returned socket URL like any server.
 */
public class GuiScreenMwcProxyConnect extends GuiScreen {

	private static final Logger LOGGER = LogManager.getLogger("MwcProxyConnect");

	private final GuiScreen parent;
	private GuiTextField ipField;
	private GuiTextField proxyField;
	private GuiTextField nameField;
	private GuiButton joinButton;
	private String status = "";
	private String lastIpHost = "";

	public GuiScreenMwcProxyConnect(GuiScreen parent) {
		this.parent = parent;
	}

	@Override
	public void initGui() {
		Keyboard.enableRepeatEvents(true);
		buttonList.clear();
		int y = height / 2 - 60;
		ipField = new GuiTextField(0, fontRendererObj, width / 2 - 150, y, 300, 20);
		ipField.setMaxStringLength(253);
		ipField.setText("localhost:25565");
		lastIpHost = "localhost";
		y += 28;
		proxyField = new GuiTextField(1, fontRendererObj, width / 2 - 150, y, 300, 20);
		proxyField.setMaxStringLength(253);
		String savedProxy = "http://localhost:2344";
		try {
			if (mc.gameSettings.mwcProxyBase != null && !mc.gameSettings.mwcProxyBase.trim().isEmpty()) {
				savedProxy = mc.gameSettings.mwcProxyBase;
			}
		} catch (Throwable t) {
		}
		proxyField.setText(savedProxy);
		y += 28;
		nameField = new GuiTextField(2, fontRendererObj, width / 2 - 150, y, 300, 20);
		nameField.setMaxStringLength(16);
		try {
			nameField.setText(net.lax1dude.eaglercraft.v1_8.profile.EaglerProfile.getName());
		} catch (Throwable t) {
			nameField.setText("Player");
		}
		y += 32;
		joinButton = new GuiButton(0, width / 2 - 152, y, 150, 20, I18n.format("selectServer.select"));
		buttonList.add(joinButton);
		buttonList.add(new GuiButton(1, width / 2 + 2, y, 150, 20, I18n.format("gui.cancel")));
	}

	@Override
	public void onGuiClosed() {
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	protected void keyTyped(char c, int key) {
		ipField.textboxKeyTyped(c, key);
		proxyField.textboxKeyTyped(c, key);
		nameField.textboxKeyTyped(c, key);
		// Auto-fill proxy host from server IP host (port 2344).
		String ipRaw = ipField.getText().trim();
		String host = ipRaw;
		int colon = host.lastIndexOf(':');
		if (colon > 0 && colon < host.length() - 1) {
			host = host.substring(0, colon);
		}
		if (!host.isEmpty() && !host.equals(lastIpHost)) {
			lastIpHost = host;
			String proxyText = proxyField.getText().trim();
			String proxyHost = proxyText;
			int pcolon = proxyHost.lastIndexOf(':');
			if (pcolon > 0 && pcolon < proxyHost.length() - 1) {
				proxyHost = proxyHost.substring(0, pcolon);
			}
			// Only auto-fill if proxy host is default/empty or matches old IP host.
			if (proxyHost.isEmpty()
					|| proxyHost.equals("localhost")
					|| proxyHost.equals(this.lastIpHost)) {
				proxyField.setText("http://" + host + ":2344");
			}
		}
		if (key == KeyboardConstants.KEY_RETURN) actionPerformed(joinButton);
		super.keyTyped(c, key);
	}

	@Override
	protected void mouseClicked(int x, int y, int b) {
		super.mouseClicked(x, y, b);
		ipField.mouseClicked(x, y, b);
		proxyField.mouseClicked(x, y, b);
		nameField.mouseClicked(x, y, b);
	}

	@Override
	protected void actionPerformed(GuiButton btn) {
		if (btn.id == 1) {
			mc.displayGuiScreen(parent);
			return;
		}
		if (btn.id == 0) {
			String ipRaw = ipField.getText().trim();
			String proxyRaw = proxyField.getText().trim();
			String name = nameField.getText().trim();
			if (name.isEmpty()) {
				status = "Enter a username (offline mode).";
				return;
			}
			String[] hp = MwcProxyNetworkManager.splitHostPort(ipRaw, 25565);
			int mcPort;
			try {
				mcPort = Integer.parseInt(hp[1]);
			} catch (NumberFormatException e) {
				mcPort = 25565;
			}
			try {
				mc.gameSettings.mwcProxyBase = proxyRaw;
				mc.gameSettings.saveOptions();
			} catch (Throwable t) {
			}
			status = "Contacting proxy...";
			drawScreen(0, 0, 0.0f);
			MwcProxyTokenFetch.TokenResult res;
			try {
				res = MwcProxyTokenFetch.requestToken(proxyRaw, hp[0], mcPort);
			} catch (Throwable t) {
				status = "[MWC-TOKEN-NET] Token request crashed: " + t;
				return;
			}
			if (!res.ok) {
				status = res.error != null ? res.error : "Proxy request failed.";
				return;
			}
			// Hand the vanilla target to GuiConnecting; the socket URL itself
			// carries no mc info, so statics (or #fragment, see GuiConnecting).
			MwcProxyHandshake.setPendingTarget(hp[0], mcPort, name);
			ServerData data = new ServerData(name + " @ " + hp[0], res.socketUrl, false);

			// Save the server entry so it shows up in the Multiplayer list
			// on future launches (not just a one-off direct connect).
			try {
				ServerList saved = ServerList.getServerList();
				saved.loadServerList();
				saved.addServerData(data);
				saved.saveServerList();
				LOGGER.info("Saved MWC proxy server entry: {} -> {}", data.serverName, data.serverIP);
			} catch (Throwable t) {
				LOGGER.warn("Failed to save MWC proxy server entry", t);
			}

			mc.displayGuiScreen(new GuiConnecting(parent, mc, data));
		}
	}

	@Override
	public void drawScreen(int mx, int my, float pt) {
		drawDefaultBackground();
		drawCenteredString(fontRendererObj, "Join Vanilla 1.8 Server via MWC Proxy (offline)", width / 2, height / 2 - 95, 0xFFFFFF);
		drawString(fontRendererObj, "Server IP (vanilla 1.8, offline mode):", width / 2 - 150, height / 2 - 78, 0xA0A0A0);
		drawString(fontRendererObj, "MWC proxy base (http(s)://host:port):", width / 2 - 150, height / 2 - 50, 0xA0A0A0);
		drawString(fontRendererObj, "Username:", width / 2 - 150, height / 2 - 22, 0xA0A0A0);
		ipField.drawTextBox();
		proxyField.drawTextBox();
		nameField.drawTextBox();
		if (!status.isEmpty()) {
			drawCenteredString(fontRendererObj, status, width / 2, height / 2 + 62, 0xFFAA00);
			drawCenteredString(fontRendererObj, "Online-mode servers: use web-client-next (Microsoft auth).", width / 2, height / 2 + 74, 0x808080);
		} else {
			drawCenteredString(fontRendererObj, new ChatComponentText("Eaglercraft+MWC v1: offline 1.8 only.").getUnformattedText(), width / 2, height / 2 + 62, 0x808080);
		}
		super.drawScreen(mx, my, pt);
	}
}
