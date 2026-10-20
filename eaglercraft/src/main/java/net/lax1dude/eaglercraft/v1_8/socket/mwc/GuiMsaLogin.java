package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;

/**
 * Microsoft account sign-in screen (device-code flow).
 *
 * <p>Shows the device code and verification URL produced by the mwc-proxy
 * {@code /auth/start} endpoint while the {@link MwcAuthDriver} advances the
 * flow one short poll at a time from {@link #updateScreen()}. Once a Minecraft
 * token/profile is obtained, the account is persisted and this client can join
 * online-mode (premium) servers.</p>
 *
 * <p>Works on the desktop, JS and WASM builds (no background threads needed).</p>
 */
public class GuiMsaLogin extends GuiScreen {

	private final GuiScreen parent;

	private MwcAuthDriver driver;
	private MwcMsaSignInState state;

	private GuiButton btnSignIn;
	private GuiButton btnBrowser;
	private GuiButton btnSignOut;

	private boolean wasDone = false;

	public GuiMsaLogin(GuiScreen parent) {
		this.parent = parent;
	}

	private MwcAuthDriver driver() {
		if (driver == null) {
			try {
				driver = MwcAuthDriver.get();
				state = driver.getSignInState();
			} catch (Throwable t) {
				driver = null;
				state = null;
			}
		}
		return driver;
	}

	@Override
	public void initGui() {
		buttonList.clear();
		int cx = this.width / 2;
		int by = this.height / 2 + 20;
		buttonList.add(btnSignIn = new GuiButton(0, cx - 100, by, 200, 20, "Sign In with Microsoft"));
		buttonList.add(btnBrowser = new GuiButton(1, cx - 100, by + 24, 200, 20, "Open Browser"));
		buttonList.add(btnSignOut = new GuiButton(2, cx - 100, by + 48, 200, 20, "Sign Out"));
		buttonList.add(new GuiButton(3, cx - 100, this.height - 28, 200, 20, I18n.format("gui.done")));
		refreshButtons();
	}

	private void refreshButtons() {
		MwcAuthDriver d = driver();
		boolean supported = d != null && d.isSupported();
		boolean running = state != null && state.running;
		boolean signedIn = d != null && d.isSignedIn();
		boolean hasUri = state != null && state.verificationUri != null;

		btnSignIn.enabled = supported && !running;
		btnSignIn.displayString = signedIn ? "Sign In Again" : "Sign In with Microsoft";
		btnBrowser.enabled = supported && hasUri;
		btnSignOut.enabled = supported && signedIn && !running;
	}

	@Override
	public void updateScreen() {
		super.updateScreen();
		MwcAuthDriver d = driver();
		if (d != null) {
			d.tick();
		}
		refreshButtons();
		if (state != null && state.done && !wasDone) {
			wasDone = true;
			this.mc.getSoundHandler().playSound(
					net.minecraft.client.audio.PositionedSoundRecord.create(
							new net.minecraft.util.ResourceLocation("random.levelup"), 1.0F));
		}
	}

	@Override
	protected void actionPerformed(GuiButton btn) {
		if (!btn.enabled) {
			return;
		}
		if (btn.id == 0) {
			MwcAuthDriver d = driver();
			if (d != null) {
				String proxyBase = Minecraft.getMinecraft().gameSettings.mwcProxyBase;
				wasDone = false;
				d.startSignIn(proxyBase);
			}
		} else if (btn.id == 1) {
			if (state != null && state.verificationUri != null) {
				EagRuntime.openLink(state.verificationUri);
			}
		} else if (btn.id == 2) {
			MwcAuthDriver d = driver();
			if (d != null) {
				d.signOut();
			}
			wasDone = false;
		} else if (btn.id == 3) {
			this.mc.displayGuiScreen(parent);
		}
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		int cx = this.width / 2;
		int y = this.height / 2 - 60;

		this.drawCenteredString(this.fontRendererObj, "Microsoft Account", cx, y, 0xFFFFFF);
		y += 22;

		MwcAuthDriver d = driver();
		boolean supported = d != null && d.isSupported();
		boolean signedIn = d != null && d.isSignedIn();
		boolean running = state != null && state.running;

		if (!supported) {
			this.drawCenteredString(this.fontRendererObj,
					"Online-mode sign-in is unavailable on this platform.", cx, y, 0xFF5555);
			y += 14;
			this.drawCenteredString(this.fontRendererObj,
					"Join offline/cracked servers as usual on this build.", cx, y, 0xAAAAAA);
		} else if (running) {
			if (state.userCode != null) {
				this.drawCenteredString(this.fontRendererObj, "To sign in, open this page in a browser:", cx, y,
						0xFFFFFF);
				y += 14;
				this.drawCenteredString(this.fontRendererObj, EnumChatFormatting.YELLOW + state.verificationUri, cx,
						y, 0xFFFF55);
				y += 16;
				this.drawCenteredString(this.fontRendererObj, "and enter the code:", cx, y, 0xFFFFFF);
				y += 16;
				this.drawCenteredString(this.fontRendererObj,
						EnumChatFormatting.AQUA + "" + EnumChatFormatting.BOLD + state.userCode, cx, y, 0x55FFFF);
				y += 16;
				if (state.message != null && !state.message.isEmpty()) {
					this.drawCenteredString(this.fontRendererObj, EnumChatFormatting.GRAY + state.message, cx, y,
							0xAAAAAA);
				}
			} else {
				this.drawCenteredString(this.fontRendererObj, "Contacting the authentication server...", cx, y,
						0xAAAAAA);
			}
		} else if (state != null && state.failed) {
			this.drawCenteredString(this.fontRendererObj, "Sign-in failed:", cx, y, 0xFF5555);
			y += 14;
			drawWrapped(this.state.error == null ? "Unknown error" : this.state.error, cx, y, 0xAAAAAA);
		} else if (signedIn) {
			this.drawCenteredString(this.fontRendererObj,
					EnumChatFormatting.GREEN + "Signed in as " + EnumChatFormatting.WHITE + d.getSignedInName() + EnumChatFormatting.GREEN + ".",
					cx, y, 0x55FF55);
			y += 14;
			this.drawCenteredString(this.fontRendererObj, "You can now join online-mode (premium) servers.", cx, y,
					0xAAAAAA);
		} else {
			this.drawCenteredString(this.fontRendererObj, "Not signed in.", cx, y, 0xFFFFFF);
			y += 14;
			drawWrapped("Signing in with a Microsoft account lets this client join servers that use "
					+ "online-mode=true. Your account stays on this device.", cx, y, 0xAAAAAA);
		}

		super.drawScreen(mouseX, mouseY, partialTicks);
	}

	private void drawWrapped(String text, int cx, int y, int color) {
		for (Object line : this.fontRendererObj.listFormattedStringToWidth(text, this.width - 60)) {
			this.drawCenteredString(this.fontRendererObj, (String) line, cx, y, color);
			y += 10;
		}
	}
}
