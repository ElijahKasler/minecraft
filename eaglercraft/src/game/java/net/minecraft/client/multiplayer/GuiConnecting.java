package net.minecraft.client.multiplayer;

import java.util.List;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.cookie.ServerCookieDataStore;
import net.lax1dude.eaglercraft.v1_8.internal.EnumEaglerConnectionState;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketClient;
import net.lax1dude.eaglercraft.v1_8.internal.IWebSocketFrame;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.profile.EaglerProfile;
import net.lax1dude.eaglercraft.v1_8.socket.AddressResolver;
import net.lax1dude.eaglercraft.v1_8.socket.RateLimitTracker;
import net.lax1dude.eaglercraft.v1_8.socket.protocol.handshake.HandshakerHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ChatComponentText;

/**+
 * This portion of EaglercraftX contains deobfuscated Minecraft 1.8 source code.
 * 
 * Minecraft 1.8.8 bytecode is (c) 2015 Mojang AB. "Do not distribute!"
 * Mod Coder Pack v9.18 deobfuscation configs are (c) Copyright by the MCP Team
 * 
 * EaglercraftX 1.8 patch files (c) 2022-2025 lax1dude, ayunami2000. All Rights Reserved.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 */
public class GuiConnecting extends GuiScreen {
	private static final Logger logger = LogManager.getLogger();
	private IWebSocketClient webSocket;
	private HandshakerHandler handshaker;
	private net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake mwcHandshaker;
	private boolean useMwcProxy = false;
	private String currentAddress;
	private String currentPassword;
	private boolean allowPlaintext;
	private boolean allowCookies;
	private boolean cancel;
	private final GuiScreen previousGuiScreen;
	private int timer = 0;

	/** Vanilla-via-proxy: {host, portString, username} while the token is fetched. */
	private String[] proxyTarget = null;
	private boolean proxyTokenPhase = false;
	private boolean proxyTokenStarted = false;
	private volatile String proxySocketUrl = null;
	private volatile String proxyTokenError = null;

	public GuiConnecting(GuiScreen parGuiScreen, Minecraft mcIn, ServerData parServerData) {
		this(parGuiScreen, mcIn, parServerData, false);
	}

	public GuiConnecting(GuiScreen parGuiScreen, Minecraft mcIn, ServerData parServerData, boolean allowPlaintext) {
		this(parGuiScreen, mcIn, parServerData, null, allowPlaintext);
	}

	public GuiConnecting(GuiScreen parGuiScreen, Minecraft mcIn, ServerData parServerData, String password) {
		this(parGuiScreen, mcIn, parServerData, password, false);
	}

	public GuiConnecting(GuiScreen parGuiScreen, Minecraft mcIn, ServerData parServerData, String password,
			boolean allowPlaintext) {
		this.mc = mcIn;
		this.previousGuiScreen = parGuiScreen;
		String serveraddress = AddressResolver.resolveURI(parServerData);
		mcIn.loadWorld((WorldClient) null);
		mcIn.setServerData(parServerData);
		if (RateLimitTracker.isLockedOut(serveraddress)) {
			logger.error("Server locked this client out on a previous connection, will not attempt to reconnect");
		} else {
			if (net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake
					.isProxyAddress(parServerData.serverIP)) {
				String[] hp = net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake
						.parseProxyAddress(parServerData.serverIP);
				String user = net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake.pendingUsername;
				net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake.clearPendingTarget();
				this.proxyTarget = new String[] { hp[0], hp[1], user };
				this.proxyTokenPhase = true;
			}
			this.connect(serveraddress, password, allowPlaintext,
					parServerData.enableCookies && EagRuntime.getConfiguration().isEnableServerCookies());
		}
	}

	public GuiConnecting(GuiScreen parGuiScreen, Minecraft mcIn, String hostName, int port) {
		this(parGuiScreen, mcIn, hostName, port, false, EagRuntime.getConfiguration().isEnableServerCookies());
	}

	public GuiConnecting(GuiScreen parGuiScreen, Minecraft mcIn, String hostName, int port, boolean allowCookies) {
		this(parGuiScreen, mcIn, hostName, port, false, allowCookies);
	}

	public GuiConnecting(GuiScreen parGuiScreen, Minecraft mcIn, String hostName, int port, boolean allowPlaintext,
			boolean allowCookies) {
		this(parGuiScreen, mcIn, hostName, port, null, allowPlaintext, allowCookies);
	}

	public GuiConnecting(GuiScreen parGuiScreen, Minecraft mcIn, String hostName, int port, String password,
			boolean allowCookies) {
		this(parGuiScreen, mcIn, hostName, port, password, false, allowCookies);
	}

	public GuiConnecting(GuiScreen parGuiScreen, Minecraft mcIn, String hostName, int port, String password,
			boolean allowPlaintext, boolean allowCookies) {
		this.mc = mcIn;
		this.previousGuiScreen = parGuiScreen;
		mcIn.loadWorld((WorldClient) null);
		this.connect(hostName, password, allowPlaintext,
				allowCookies && EagRuntime.getConfiguration().isEnableServerCookies());
	}

	public GuiConnecting(GuiConnecting previous, String password) {
		this(previous, password, false);
	}

	public GuiConnecting(GuiConnecting previous, String password, boolean allowPlaintext) {
		this.mc = previous.mc;
		this.previousGuiScreen = previous.previousGuiScreen;
		this.connect(previous.currentAddress, password, allowPlaintext, previous.allowCookies);
	}

	private void connect(String ip, String password, boolean allowPlaintext, boolean allowCookies) {
		this.currentAddress = ip;
		this.currentPassword = password;
		this.allowPlaintext = allowPlaintext;
		this.allowCookies = allowCookies;
	}

	/**+
	 * Called from the main game loop to update the screen.
	 */
	public void updateScreen() {
		++timer;
		if (timer > 1) {
			if (proxyTokenPhase) {
				updateProxyTokenPhase();
				return;
			}
			if (this.currentAddress == null) {
				mc.displayGuiScreen(GuiDisconnected.createRateLimitKick(previousGuiScreen));
			} else if (webSocket == null) {
				logger.info("Connecting to: {}", currentAddress);
				useMwcProxy = net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake
						.isMwcSocketUrl(currentAddress);
				webSocket = PlatformNetworking.openWebSocket(currentAddress);
				if (webSocket == null) {
					mc.displayGuiScreen(new GuiDisconnected(previousGuiScreen, "connect.failed",
							new ChatComponentText("Could not open WebSocket to \"" + currentAddress + "\"!")));
				}
			} else {
				EnumEaglerConnectionState connState = webSocket.getState();
				if (connState == EnumEaglerConnectionState.CONNECTED) {
					if (useMwcProxy) {
						if (mwcHandshaker == null) {
							this.mc.getSession().reset();
							String mcHost = net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake.pendingMcHost;
							int mcPort = net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake.pendingMcPort;
							String username = net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake.pendingUsername;
							if (mcHost == null) {
								// Boot flows (?mwcSocket= joinServer) carry the
								// target in the URL fragment, never sent on wire.
								String[] frag = net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake
										.parseFragmentTarget(currentAddress);
								if (frag != null) {
									mcHost = frag[0];
									try {
										mcPort = Integer.parseInt(frag[1]);
									} catch (Throwable t) {
										mcPort = 25565;
									}
									username = frag[2];
								} else {
									mcHost = "localhost";
									mcPort = 25565;
								}
							}
							if (username == null || username.isEmpty()) {
								try {
									username = net.lax1dude.eaglercraft.v1_8.profile.EaglerProfile.getName();
								} catch (Throwable t) {
									username = "Player";
								}
							}
							logger.info("MWC vanilla login: {}:{} via {}", mcHost, mcPort, currentAddress);
							mwcHandshaker = new net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake(
									this, webSocket, mcHost, mcPort, username);
						}
						mwcHandshaker.tick();
						return;
					}
					if (handshaker == null) {
						this.mc.getSession().reset();

						logger.info("Logging in: {}", currentAddress);

						byte[] cookieData = null;
						if (allowCookies) {
							ServerCookieDataStore.ServerCookie cookie = ServerCookieDataStore
									.loadCookie(currentAddress);
							if (cookie != null) {
								cookieData = cookie.cookie;
							}
						}

						handshaker = new HandshakerHandler(this, webSocket, EaglerProfile.getName(), currentPassword,
								allowPlaintext, allowCookies, cookieData);
					}
					handshaker.tick();
				} else {
					if (handshaker != null) {
						handshaker.tick();
						if (connState == EnumEaglerConnectionState.FAILED) {
							this.mc.getSession().reset();
							checkRatelimit();
							if (mc.currentScreen == this) {
								if (RateLimitTracker.isProbablyLockedOut(currentAddress)) {
									mc.displayGuiScreen(GuiDisconnected.createRateLimitKick(previousGuiScreen));
								} else {
									mc.displayGuiScreen(new GuiDisconnected(previousGuiScreen, "connect.failed",
											new ChatComponentText(net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes
													.mwc(net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Play.CLOSED,
															"Connection failed or refused.\n"
																	+ "Fix: verify the address; if this uses a relay,"
																	+ " try another relay, then retry."))));
								}
							}
						}
					} else if (mwcHandshaker == null && useMwcProxy
							&& connState == EnumEaglerConnectionState.FAILED) {
						// mwc socket died before the handshaker object existed
						this.mc.getSession().reset();
						mc.displayGuiScreen(new GuiDisconnected(previousGuiScreen, "connect.failed",
								new ChatComponentText(net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
										net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Play.CLOSED,
										"Proxy socket closed before login completed.\n"
												+ "Fix: check that both the proxy and the target server are"
												+ " running, then retry."))));
					}
				}
			}
			if (timer > (useMwcProxy ? 600 : 200)) {
				if (webSocket != null) {
					webSocket.close();
				}
				mc.displayGuiScreen(new GuiDisconnected(previousGuiScreen, "connect.failed",
						new ChatComponentText(net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
								net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Play.TIMEOUT,
								"Handshake timed out after " + (useMwcProxy ? "30s" : "10s") + ".\n"
										+ "The socket opened but the server never answered.\n"
										+ "Fix: retry; check the server/proxy logs and your network."))));
			}
		}
	}

	/**
	 * Vanilla-via-proxy: fetch a connection token off-thread, then swap the
	 * address for the returned proxy socket URL and continue like any mwc join.
	 */
	private void updateProxyTokenPhase() {
		if (!proxyTokenStarted) {
			proxyTokenStarted = true;
			int port = 25565;
			try {
				port = Integer.parseInt(proxyTarget[1]);
			} catch (NumberFormatException e) {
			}
			final int mcPort = port;
			final String mcHost = proxyTarget[0];
			logger.info("Requesting MWC proxy token for {}:{} via {}", mcHost, mcPort,
					mc.gameSettings.mwcProxyBase);
			net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyTokenFetch.requestTokenAsync(
					mc.gameSettings.mwcProxyBase, mcHost, mcPort,
					new net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyTokenFetch.TokenCallback() {
						@Override
						public void onComplete(
								net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyTokenFetch.TokenResult res) {
							if (res != null && res.ok) {
								proxySocketUrl = res.socketUrl;
							} else {
								proxyTokenError = (res != null && res.error != null) ? res.error
										: "[MWC-TOKEN-NET] Proxy request failed.";
							}
						}
					});
		}
		if (proxyTokenError != null) {
			String err = proxyTokenError;
			proxyTokenError = null;
			mc.displayGuiScreen(new GuiDisconnected(previousGuiScreen, "connect.failed", new ChatComponentText(err)));
			return;
		}
		if (proxySocketUrl == null) {
			if (cancel) {
				return;
			}
			if (timer > 700) {
				String shownBase;
				try {
					shownBase = net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyTokenFetch
							.normalizeBase(mc.gameSettings.mwcProxyBase);
				} catch (Throwable t2) {
					shownBase = mc.gameSettings.mwcProxyBase;
				}
				mc.displayGuiScreen(new GuiDisconnected(previousGuiScreen, "connect.failed",
						new ChatComponentText(net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.mwc(
								net.lax1dude.eaglercraft.v1_8.socket.ConnectErrorCodes.Token.TIMEOUT,
								"Proxy token request timed out (35s).\nProxy: " + shownBase
										+ "\nFix: start the proxy or check its process/logs."))));
			}
			return;
		}
		proxyTokenPhase = false;
		timer = 0;
		int port = 25565;
		try {
			port = Integer.parseInt(proxyTarget[1]);
		} catch (NumberFormatException e) {
		}
		String username = proxyTarget[2];
		if (username == null || username.isEmpty()) {
			try {
				username = EaglerProfile.getName();
			} catch (Throwable t) {
				username = "Player";
			}
		}
		net.lax1dude.eaglercraft.v1_8.socket.mwc.MwcProxyHandshake.setPendingTarget(proxyTarget[0], port, username);
		currentAddress = proxySocketUrl;
		proxySocketUrl = null;
		logger.info("MWC proxy token acquired, opening socket for {}:{} -> {}", proxyTarget[0], port,
				currentAddress);
	}

	/**+
	 * Fired when a key is typed (except F11 which toggles full
	 * screen). This is the equivalent of
	 * KeyListener.keyTyped(KeyEvent e). Args : character (character
	 * on the key), keyCode (lwjgl Keyboard key code)
	 */
	protected void keyTyped(char parChar1, int parInt1) {
	}

	/**+
	 * Adds the buttons (and other controls) to the screen in
	 * question. Called when the GUI is displayed and when the
	 * window resizes, the buttonList is cleared beforehand.
	 */
	public void initGui() {
		this.buttonList.clear();
		this.buttonList.add(
				new GuiButton(0, this.width / 2 - 100, this.height / 2 - 10, I18n.format("gui.cancel", new Object[0])));
	}

	/**+
	 * Called by the controls from the buttonList when activated.
	 * (Mouse pressed for buttons)
	 */
	protected void actionPerformed(GuiButton parGuiButton) {
		if (parGuiButton.id == 0) {
			this.cancel = true;
			if (this.webSocket != null) {
				this.webSocket.close();
			}
			this.mc.displayGuiScreen(this.previousGuiScreen);
		}

	}

	/**+
	 * Draws the screen and all the components in it. Args : mouseX,
	 * mouseY, renderPartialTicks
	 */
	public void drawScreen(int i, int j, float f) {
		this.drawDefaultBackground();
		if (this.proxyTokenPhase) {
			this.drawCenteredString(this.fontRendererObj, "Contacting proxy...", this.width / 2,
					this.height / 2 - 50, 16777215);
			this.drawCenteredString(this.fontRendererObj,
					"Proxy: " + this.mc.gameSettings.mwcProxyBase + "  (server: " + this.proxyTarget[0] + ":"
							+ this.proxyTarget[1] + ")",
					this.width / 2, this.height / 2 - 38, 0x808080);
		} else if (this.handshaker == null && this.mwcHandshaker == null) {
			this.drawCenteredString(this.fontRendererObj, I18n.format("connect.connecting", new Object[0]),
					this.width / 2, this.height / 2 - 50, 16777215);
		} else {
			this.drawCenteredString(this.fontRendererObj, I18n.format("connect.authorizing", new Object[0]),
					this.width / 2, this.height / 2 - 50, 16777215);
		}

		super.drawScreen(i, j, f);
	}

	private void checkRatelimit() {
		if (this.webSocket != null) {
			List<IWebSocketFrame> strFrames = webSocket.getNextStringFrames();
			if (strFrames != null) {
				for (int i = 0; i < strFrames.size(); ++i) {
					String str = strFrames.get(i).getString();
					if (str.equalsIgnoreCase("BLOCKED")) {
						RateLimitTracker.registerBlock(currentAddress);
						mc.displayGuiScreen(GuiDisconnected.createRateLimitKick(previousGuiScreen));
						logger.info("Handshake Failure: Too Many Requests!");
					} else if (str.equalsIgnoreCase("LOCKED")) {
						RateLimitTracker.registerLockOut(currentAddress);
						mc.displayGuiScreen(GuiDisconnected.createRateLimitKick(previousGuiScreen));
						logger.info("Handshake Failure: Too Many Requests!");
						logger.info("Server has locked this client out");
					}
				}
			}
		}
	}

	public boolean canCloseGui() {
		return false;
	}

	public static Minecraft getMC(GuiConnecting gui) {
		return gui.mc;
	}

	public static GuiScreen getPrevScreen(GuiConnecting gui) {
		return gui.previousGuiScreen;
	}

}