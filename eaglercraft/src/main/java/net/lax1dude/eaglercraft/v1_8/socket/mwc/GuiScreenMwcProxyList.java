package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import java.io.IOException;

import net.lax1dude.eaglercraft.v1_8.Keyboard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.minecraft.client.resources.I18n;

/**
 * Proxy server list screen, styled after the relay server list
 * ({@code GuiScreenRelay}). Entries are managed by
 * {@link MwcProxyListManager}; picking one and pressing "Use" (or
 * double-clicking it) makes it the active {@code mwcProxyBase} used by
 * every vanilla server join and server-list ping.
 */
public class GuiScreenMwcProxyList extends GuiScreen implements GuiYesNoCallback {

	private final GuiScreen parent;
	private GuiSlotMwcProxy slots;
	int selected = -1;
	private boolean deleting = false;

	private GuiButton editButton;
	private GuiButton deleteButton;
	private GuiButton useButton;

	public GuiScreenMwcProxyList(GuiScreen parent) {
		this.parent = parent;
	}

	@Override
	public void initGui() {
		Keyboard.enableRepeatEvents(true);
		selected = -1;
		buttonList.clear();
		buttonList.add(new GuiButton(0, this.width / 2 + 54, this.height - 28, 100, 20, I18n.format("gui.done")));
		buttonList.add(new GuiButton(1, this.width / 2 - 154, this.height - 52, 100, 20, "Add Server"));
		buttonList.add(editButton = new GuiButton(2, this.width / 2 - 50, this.height - 52, 100, 20, "Edit"));
		buttonList.add(deleteButton = new GuiButton(3, this.width / 2 + 54, this.height - 52, 100, 20, "Delete"));
		buttonList.add(useButton = new GuiButton(4, this.width / 2 - 154, this.height - 28, 100, 20, "Use Proxy"));
		buttonList.add(new GuiButton(5, this.width / 2 - 50, this.height - 28, 100, 20, "Load Defaults"));
		updateButtons();
		this.slots = new GuiSlotMwcProxy(this);
	}

	@Override
	public void onGuiClosed() {
		Keyboard.enableRepeatEvents(false);
	}

	void updateButtons() {
		editButton.enabled = selected >= 0;
		deleteButton.enabled = selected >= 0;
		useButton.enabled = selected >= 0;
	}

	@Override
	protected void actionPerformed(GuiButton btn) {
		if (!btn.enabled) {
			return;
		}
		if (btn.id == 0) {
			mc.displayGuiScreen(parent);
		} else if (btn.id == 1) {
			mc.displayGuiScreen(new GuiScreenMwcProxyEdit(this, -1));
		} else if (btn.id == 2) {
			if (selected >= 0 && selected < MwcProxyListManager.proxies.count()) {
				mc.displayGuiScreen(new GuiScreenMwcProxyEdit(this, selected));
			}
		} else if (btn.id == 3) {
			if (selected >= 0 && selected < MwcProxyListManager.proxies.count()) {
				MwcProxyListManager.MwcProxyEntry entry = MwcProxyListManager.proxies.get(selected);
				deleting = true;
				mc.displayGuiScreen(new GuiYesNo(this, "Delete proxy server?", "Remove \'" + entry.name + "\' ("
						+ entry.address + ") from your proxy list?", "Delete", "Cancel", selected));
			}
		} else if (btn.id == 4) {
			useProxy(selected);
		} else if (btn.id == 5) {
			MwcProxyListManager.proxies.loadDefaults();
			selected = -1;
			updateButtons();
		}
	}

	/** Make the entry at {@code index} the active proxy for all joins/pings. */
	void useProxy(int index) {
		if (index < 0 || index >= MwcProxyListManager.proxies.count()) {
			return;
		}
		String address = MwcProxyListManager.proxies.get(index).address;
		try {
			address = MwcProxyTokenFetch.normalizeBase(address);
		} catch (Throwable t) {
		}
		mc.gameSettings.mwcProxyBase = address;
		mc.gameSettings.saveOptions();
	}

	@Override
	public void confirmClicked(boolean confirmed, int index) {
		if (deleting) {
			if (confirmed) {
				MwcProxyListManager.proxies.remove(index);
				if (selected == index) {
					selected = -1;
				}
			}
			deleting = false;
			updateButtons();
		}
		mc.displayGuiScreen(this);
	}

	@Override
	public void updateScreen() {
	}

	@Override
	public void drawScreen(int mx, int my, float pt) {
		slots.drawScreen(mx, my, pt);
		this.drawCenteredString(this.fontRendererObj, "Proxy Settings", this.width / 2, 16, 16777215);
		String active = mc.gameSettings.mwcProxyBase;
		this.drawString(this.fontRendererObj, "Active: " + (active != null ? active : ""), 3, 3, 0xDDDDDD);
		super.drawScreen(mx, my, pt);
	}

	@Override
	public void handleMouseInput() throws IOException {
		super.handleMouseInput();
		this.slots.handleMouseInput();
	}

	@Override
	public void handleTouchInput() throws IOException {
		super.handleTouchInput();
		this.slots.handleTouchInput();
	}

	static Minecraft getMinecraft(GuiScreenMwcProxyList screen) {
		return screen.mc;
	}

}
