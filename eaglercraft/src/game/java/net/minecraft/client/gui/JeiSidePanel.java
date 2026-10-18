package net.minecraft.client.gui;

import java.util.List;

import net.lax1dude.eaglercraft.v1_8.Mouse;
import net.lax1dude.eaglercraft.v1_8.opengl.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.jei.GuiScreenJei;
import net.minecraft.client.gui.inventory.jei.JeiRegistry;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;

/**
 * JEI side panel docked to the right of inventory screens (ported from
 * minecraft-web-client-next JEI item list). Same package as GuiScreen so the
 * protected draw helpers are reachable.
 *
 * <p>Item list with search, wheel scroll, left-click take (creative), R /
 * right-click recipes, U / middle-click uses. Recipe view itself still opens
 * {@link GuiScreenJei} with the inventory as its parent.</p>
 */
public class JeiSidePanel {

	public static boolean visible = true;

	private static final int COLS = 6;
	private static final int SLOT = 18;
	private static final int SEARCH_H = 14;

	private GuiTextField search;
	private List<ItemStack> filtered;
	private int scrollRows;
	private ItemStack hover;
	private int px;
	private int py;
	private int panelW;
	private int panelH;
	private int rows;
	private int screenW;

	public JeiSidePanel() {
		this.panelW = COLS * SLOT + 10;
	}

	public void onInit(int screenW, int guiLeft, int guiTop, int xSize, int ySize, FontRenderer fr) {
		this.screenW = screenW;
		int wantX = guiLeft + xSize + 4;
		this.px = Math.min(wantX, screenW - this.panelW - 2);
		if (this.px < 2) {
			this.px = 2;
		}
		this.py = Math.max(2, guiTop);
		this.rows = Math.max(3, (ySize - 12 - SEARCH_H - 18) / SLOT);
		this.panelH = 12 + SEARCH_H + 6 + this.rows * SLOT + 12;
		if (this.search == null) {
			this.search = new GuiTextField(0, fr, this.px + 5, this.py + 14, this.panelW - 32, 12);
			this.search.setMaxStringLength(24);
		} else {
			this.search.xPosition = this.px + 5;
			this.search.yPosition = this.py + 14;
			this.search.setFocused(false);
		}
		refresh();
	}

	public void update() {
		if (this.search != null) {
			this.search.updateCursorCounter();
		}
	}

	public void unfocus() {
		if (this.search != null) {
			this.search.setFocused(false);
		}
	}

	private void refresh() {
		String q = this.search == null ? "" : this.search.getText();
		this.filtered = JeiRegistry.search(q);
		this.scrollRows = 0;
	}

	private int gridTop() {
		return this.py + 12 + SEARCH_H + 6;
	}

	private int indexAt(int mx, int my) {
		if (this.filtered == null) {
			return -1;
		}
		int dx = mx - (this.px + 5);
		int dy = my - gridTop();
		if (dx < 0 || dy < 0) {
			return -1;
		}
		int col = dx / SLOT;
		int row = dy / SLOT;
		if (col < 0 || col >= COLS || row < 0 || row >= this.rows) {
			return -1;
		}
		if (dx % SLOT >= 16 || dy % SLOT >= 16) {
			return -1;
		}
		int idx = (this.scrollRows + row) * COLS + col;
		return (idx < 0 || idx >= this.filtered.size()) ? -1 : idx;
	}

	public boolean isOver(int mx, int my) {
		return visible && mx >= this.px && my >= this.py && mx < this.px + this.panelW
				&& my < this.py + this.panelH;
	}

	public void draw(Minecraft mc, FontRenderer fr, RenderItem ri, int mx, int my) {
		this.hover = null;
		if (this.filtered == null) {
			return;
		}
		if (!visible) {
			// Slim re-open tab on the screen edge (touch friendly).
			int tx = this.screenW - 14;
			Gui.drawRect(tx, this.py, tx + 12, this.py + 40, 0xC0101010);
			Gui.drawRect(tx, this.py, tx + 12, this.py + 1, 0xFF606060);
			fr.drawString(">", tx + 3, this.py + 6, 0xA0A0A0);
			fr.drawString("J", tx + 3, this.py + 16, 0xA0A0A0);
			fr.drawString("E", tx + 3, this.py + 24, 0xA0A0A0);
			fr.drawString("I", tx + 3, this.py + 32, 0xA0A0A0);
			return;
		}
		Gui.drawRect(this.px, this.py, this.px + this.panelW, this.py + this.panelH, 0xC0101010);
		Gui.drawRect(this.px, this.py, this.px + this.panelW, this.py + 1, 0xFF606060);
		Gui.drawRect(this.px, this.py + this.panelH - 1, this.px + this.panelW, this.py + this.panelH, 0xFF606060);
		fr.drawString("JEI", this.px + 5, this.py + 3, 0xFFFF55);
		fr.drawString("[x]", this.px + this.panelW - 20, this.py + 3, 0xA0A0A0);
		if (this.search != null) {
			this.search.drawTextBox();
		}
		RenderHelper.enableGUIStandardItemLighting();
		int gx = this.px + 5;
		int gy = gridTop();
		for (int r = 0; r < this.rows; ++r) {
			for (int c = 0; c < COLS; ++c) {
				int idx = (this.scrollRows + r) * COLS + c;
				if (idx >= this.filtered.size()) {
					break;
				}
				ItemStack s = this.filtered.get(idx);
				int x = gx + c * SLOT + 1;
				int y = gy + r * SLOT + 1;
				try {
					ri.renderItemAndEffectIntoGUI(s, x, y);
					ri.renderItemOverlayIntoGUI(fr, s, x, y, null);
				} catch (Throwable t) {
				}
				if (mx >= x && my >= y && mx < x + 16 && my < y + 16) {
					this.hover = s;
					GlStateManager.disableDepth();
					Gui.drawRect(x, y, x + 16, y + 16, 0x80FFFFFF);
					GlStateManager.enableDepth();
				}
			}
		}
		RenderHelper.disableStandardItemLighting();
		int totalRows = (this.filtered.size() + COLS - 1) / COLS;
		fr.drawString(this.filtered.size() + " items", this.px + 5, this.py + this.panelH - 10, 0x808080);
		if (totalRows > this.rows) {
			fr.drawString((this.scrollRows + 1) + "/" + (totalRows - this.rows + 1),
					this.px + this.panelW - 34, this.py + this.panelH - 10, 0x808080);
		}
	}

	public ItemStack getHover() {
		return this.hover;
	}

	/**
	 * @return true if the click was consumed by the panel.
	 */
	public boolean mouseClicked(Minecraft mc, GuiScreen parent, int mx, int my, int btn) {
		if (!visible) {
			int tx = this.screenW - 14;
			if (mx >= tx && my >= this.py && mx < tx + 12 && my < this.py + 40) {
				visible = true;
				return true;
			}
			return false;
		}
		if (mx >= this.px + this.panelW - 22 && my >= this.py && mx < this.px + this.panelW
				&& my < this.py + 12) {
			visible = false;
			unfocus();
			return true;
		}
		if (this.search != null && mx >= this.search.xPosition - 2 && my >= this.search.yPosition - 2
				&& mx < this.search.xPosition + this.panelW - 26 && my < this.search.yPosition + 16) {
			this.search.mouseClicked(mx, my, btn);
			this.search.setFocused(true);
			return true;
		}
		int idx = indexAt(mx, my);
		if (idx < 0) {
			unfocus();
			return false;
		}
		ItemStack s = this.filtered.get(idx);
		if (s == null) {
			return true;
		}
		if (btn == 1) {
			mc.displayGuiScreen(new GuiScreenJei(parent, s.copy(), false));
		} else if (btn == 0) {
			if (mc.playerController.isInCreativeMode()) {
				ItemStack copy = s.copy();
				copy.stackSize = copy.getMaxStackSize();
				mc.thePlayer.inventory.setItemStack(copy);
				try {
					mc.thePlayer.inventoryContainer.detectAndSendChanges();
				} catch (Throwable t) {
				}
			} else {
				mc.displayGuiScreen(new GuiScreenJei(parent, s.copy(), false));
			}
		} else if (btn == 2) {
			mc.displayGuiScreen(new GuiScreenJei(parent, s.copy(), true));
		}
		return true;
	}

	/**
	 * @return true if the key was consumed by the panel.
	 */
	public boolean keyTyped(char c, int key) {
		if (!visible || this.search == null) {
			return false;
		}
		if (key == 1) {
			// Esc: drop focus so the next Esc closes the inventory.
			if (this.search.isFocused()) {
				this.search.setFocused(false);
				return true;
			}
			return false;
		}
		if (this.search.isFocused()) {
			if (this.search.textboxKeyTyped(c, key)) {
				refresh();
			}
			return true;
		}
		return false;
	}

	/**
	 * Wheel scroll while hovering the panel. The parent reads the wheel once
	 * and forwards it (event values must not be read twice).
	 *
	 * @return true if the wheel was consumed by the panel.
	 */
	public boolean handleWheel(int wheel, int mx, int my) {
		if (!visible || this.filtered == null || wheel == 0) {
			return false;
		}
		if (!isOver(mx, my)) {
			return false;
		}
		int totalRows = (this.filtered.size() + COLS - 1) / COLS;
		int maxScroll = Math.max(0, totalRows - this.rows);
		if (wheel > 0) {
			this.scrollRows = Math.max(0, this.scrollRows - 1);
		} else {
			this.scrollRows = Math.min(maxScroll, this.scrollRows + 1);
		}
		return true;
	}
}
