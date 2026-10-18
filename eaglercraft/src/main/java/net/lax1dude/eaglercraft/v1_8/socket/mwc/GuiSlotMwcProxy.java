package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import net.lax1dude.eaglercraft.v1_8.opengl.GlStateManager;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.util.ResourceLocation;

/**
 * Scrollable proxy list, drawn exactly like the relay server list
 * ({@code GuiSlotRelay}): name on top, address underneath, and the same
 * "primary" star icon on the entry that is the active proxy.
 */
class GuiSlotMwcProxy extends GuiSlot {

	private static final ResourceLocation eaglerGuiTex = new ResourceLocation("eagler:gui/eagler_gui.png");

	final GuiScreenMwcProxyList screen;

	public GuiSlotMwcProxy(GuiScreenMwcProxyList screen) {
		super(GuiScreenMwcProxyList.getMinecraft(screen), screen.width, screen.height, 32, screen.height - 64, 26);
		this.screen = screen;
	}

	@Override
	protected int getSize() {
		return MwcProxyListManager.proxies.count();
	}

	@Override
	protected void elementClicked(int index, boolean doubleClick, int var3, int var4) {
		screen.selected = index;
		screen.updateButtons();
		if (doubleClick) {
			screen.useProxy(index);
		}
	}

	@Override
	protected boolean isSelected(int index) {
		return screen.selected == index;
	}

	@Override
	protected void drawBackground() {
		screen.drawDefaultBackground();
	}

	@Override
	protected void drawSlot(int id, int xx, int yy, int width, int height, int slotIdx) {
		if (id >= MwcProxyListManager.proxies.count()) {
			return;
		}
		MwcProxyListManager.MwcProxyEntry entry = MwcProxyListManager.proxies.get(id);
		GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
		if (isActive(entry)) {
			GlStateManager.pushMatrix();
			GlStateManager.translate(xx + 4, yy + 5, 0.0f);
			GlStateManager.scale(0.8f, 0.8f, 0.8f);
			this.mc.getTextureManager().bindTexture(eaglerGuiTex);
			screen.drawTexturedModalRect(0, 0, 48, 0, 16, 16);
			GlStateManager.popMatrix();
		}
		this.mc.fontRendererObj.drawString(entry.name, xx + 22, yy + 2, 0xFFFFFFFF);
		this.mc.fontRendererObj.drawString(entry.address, xx + 22, yy + 12, 0xFF999999);
	}

	private boolean isActive(MwcProxyListManager.MwcProxyEntry entry) {
		try {
			return MwcProxyTokenFetch.normalizeBase(entry.address)
					.equals(MwcProxyTokenFetch.normalizeBase(this.mc.gameSettings.mwcProxyBase));
		} catch (Throwable t) {
			return entry.address.equals(this.mc.gameSettings.mwcProxyBase);
		}
	}

}
