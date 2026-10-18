package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.lax1dude.eaglercraft.v1_8.Keyboard;
import net.lax1dude.eaglercraft.v1_8.internal.KeyboardConstants;
import net.lax1dude.eaglercraft.v1_8.minecraft.EnumInputEvent;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

/**
 * Add or edit one proxy server entry (name + {@code http(s)://host:port}).
 * Used for every entry in the list, including the default "Local Proxy"
 * one - its address is set here like any other.
 */
public class GuiScreenMwcProxyEdit extends GuiScreen {

	private final GuiScreen parent;
	private final int index;
	private GuiTextField nameField;
	private GuiTextField addressField;
	private String status = "";

	/** {@code index < 0} adds a new entry, otherwise edits that slot. */
	public GuiScreenMwcProxyEdit(GuiScreen parent, int index) {
		this.parent = parent;
		this.index = index;
	}

	@Override
	public void initGui() {
		Keyboard.enableRepeatEvents(true);
		this.buttonList.clear();
		this.nameField = new GuiTextField(0, this.fontRendererObj, this.width / 2 - 100, 62, 200, 20);
		this.nameField.setMaxStringLength(32);
		this.addressField = new GuiTextField(1, this.fontRendererObj, this.width / 2 - 100, 103, 200, 20);
		this.addressField.setMaxStringLength(253);
		if (index >= 0 && index < MwcProxyListManager.proxies.count()) {
			MwcProxyListManager.MwcProxyEntry entry = MwcProxyListManager.proxies.get(index);
			this.nameField.setText(entry.name);
			this.addressField.setText(entry.address);
		} else {
			this.nameField.setText("");
			this.addressField.setText("");
		}
		this.nameField.setFocused(true);
		this.buttonList.add(new GuiButton(0, this.width / 2 - 100, 140, I18n.format("gui.done")));
		this.buttonList.add(new GuiButton(1, this.width / 2 - 100, 164, I18n.format("gui.cancel")));
	}

	@Override
	public void onGuiClosed() {
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	public void updateScreen() {
		this.nameField.updateCursorCounter();
		this.addressField.updateCursorCounter();
	}

	@Override
	protected void keyTyped(char c, int key) {
		if (key == KeyboardConstants.KEY_ESCAPE) {
			this.mc.displayGuiScreen(this.parent);
			return;
		}
		nameField.textboxKeyTyped(c, key);
		addressField.textboxKeyTyped(c, key);
		if (key == KeyboardConstants.KEY_RETURN || key == KeyboardConstants.KEY_NUMPADENTER) {
			this.actionPerformed((GuiButton) this.buttonList.get(0));
		}
	}

	@Override
	protected void mouseClicked(int x, int y, int b) {
		super.mouseClicked(x, y, b);
		nameField.mouseClicked(x, y, b);
		addressField.mouseClicked(x, y, b);
	}

	@Override
	protected void actionPerformed(GuiButton btn) {
		if (!btn.enabled) {
			return;
		}
		if (btn.id == 1) {
			mc.displayGuiScreen(parent);
			return;
		}
		if (btn.id != 0) {
			return;
		}
		String address = addressField.getText().trim();
		if (address.isEmpty()) {
			status = "Enter the proxy address.";
			return;
		}
		String normalized;
		try {
			normalized = MwcProxyTokenFetch.normalizeBase(address);
		} catch (Throwable t) {
			status = "Invalid proxy address.";
			return;
		}
		String name = nameField.getText().trim();
		if (name.isEmpty()) {
			name = normalized;
		}
		String previous = null;
		if (index >= 0 && index < MwcProxyListManager.proxies.count()) {
			previous = MwcProxyListManager.proxies.get(index).address;
			MwcProxyListManager.proxies.update(index, name, normalized);
		} else {
			MwcProxyListManager.proxies.add(name, normalized);
		}
		// If the edited entry was the active proxy, follow its new address.
		if (previous != null) {
			try {
				if (MwcProxyTokenFetch.normalizeBase(previous)
						.equals(MwcProxyTokenFetch.normalizeBase(mc.gameSettings.mwcProxyBase))) {
					mc.gameSettings.mwcProxyBase = normalized;
					mc.gameSettings.saveOptions();
				}
			} catch (Throwable t) {
			}
		}
		mc.displayGuiScreen(parent);
	}

	@Override
	public void drawScreen(int mx, int my, float pt) {
		this.drawDefaultBackground();
		this.drawCenteredString(this.fontRendererObj, index >= 0 ? "Edit Proxy Server" : "Add Proxy Server",
				this.width / 2, 17, 16777215);
		this.drawString(this.fontRendererObj, "Name (optional):", this.width / 2 - 100, 53, 10526880);
		this.drawString(this.fontRendererObj, "Proxy address (http(s)://host:port):", this.width / 2 - 100, 94, 10526880);
		if (!status.isEmpty()) {
			this.drawString(this.fontRendererObj, status, this.width / 2 - 100, 128, 0xFF5555);
		}
		this.drawCenteredString(this.fontRendererObj,
				"This proxy is used for every vanilla server you join through the proxy.",
				this.width / 2, 190, 0x808080);
		this.nameField.drawTextBox();
		this.addressField.drawTextBox();
		super.drawScreen(mx, my, pt);
	}

	@Override
	public boolean showCopyPasteButtons() {
		return nameField.isFocused() || addressField.isFocused();
	}

	@Override
	public void fireInputEvent(EnumInputEvent event, String param) {
		nameField.fireInputEvent(event, param);
		addressField.fireInputEvent(event, param);
	}

}
