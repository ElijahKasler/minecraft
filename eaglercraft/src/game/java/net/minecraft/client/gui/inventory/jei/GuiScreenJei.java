package net.minecraft.client.gui.inventory.jei;

import java.io.IOException;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.Keyboard;
import net.lax1dude.eaglercraft.v1_8.Mouse;
import net.lax1dude.eaglercraft.v1_8.opengl.GlStateManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;

/**
 * JEI recipe overlay (ported from minecraft-web-client-next JEI).
 *
 * <p>Item list with search (all creative-tab variants), left-click in creative
 * to take a full stack, R / right-click an item for its crafting + furnace
 * recipes, U for its uses. Recipe view draws the 3x3 grid, shapeless list and
 * furnace input -&gt; output with XP.</p>
 */
public class GuiScreenJei extends GuiScreen {

	private final GuiScreen parent;
	private GuiTextField searchField;
	private List<ItemStack> filtered;
	private int scrollRows = 0;
	private ItemStack hoverStack = null;

	private List<JeiRecipe> recipes = null;
	private int recipeIndex = 0;
	private ItemStack recipeTarget = null;
	private boolean showingUses = false;

	private static final int COLS = 9;
	private static final int SLOT = 18;

	public GuiScreenJei(GuiScreen parent) {
		this.parent = parent;
	}

	public GuiScreenJei(GuiScreen parent, ItemStack target, boolean uses) {
		this.parent = parent;
		this.recipeTarget = target;
		this.showingUses = uses;
		if (target != null) {
			this.recipes = uses ? JeiRegistry.getUsesFor(target) : JeiRegistry.getRecipesFor(target);
		}
	}

	@Override
	public void initGui() {
		super.initGui();
		Keyboard.enableRepeatEvents(true);
		this.buttonList.clear();
		this.buttonList.add(new GuiButton(0, this.width - 60, 8, 50, 20, "Close"));
		this.buttonList.add(new GuiButton(1, this.width - 116, 8, 50, 20, "< Back"));
		if (recipes != null && !recipes.isEmpty()) {
			this.buttonList.add(new GuiButton(2, this.width / 2 - 110, this.height - 28, 50, 20, "< Prev"));
			this.buttonList.add(new GuiButton(3, this.width / 2 + 60, this.height - 28, 50, 20, "Next >"));
		}
		if (searchField == null) {
			searchField = new GuiTextField(10, this.fontRendererObj, 10, 10, 150, 16);
			searchField.setMaxStringLength(32);
		} else {
			searchField.xPosition = 10;
			searchField.yPosition = 10;
			searchField.setFocused(false);
		}
		refreshFilter();
	}

	@Override
	public void onGuiClosed() {
		Keyboard.enableRepeatEvents(false);
	}

	private void refreshFilter() {
		String q = searchField == null ? "" : searchField.getText();
		filtered = JeiRegistry.search(q);
		scrollRows = 0;
	}

	private int gridLeft() {
		return 10;
	}

	private int gridTop() {
		return 34;
	}

	private int visibleRows() {
		if (recipes != null) {
			return 4;
		}
		int h = (this.height - gridTop() - 30) / SLOT;
		return Math.max(3, Math.min(10, h));
	}

	private int indexAt(int mx, int my) {
		int gx = gridLeft();
		int gy = gridTop();
		int dx = mx - gx;
		int dy = my - gy;
		if (dx < 0 || dy < 0) {
			return -1;
		}
		int col = dx / SLOT;
		int row = dy / SLOT;
		if (col < 0 || col >= COLS || row < 0 || row >= visibleRows()) {
			return -1;
		}
		int idx = (scrollRows + row) * COLS + col;
		if (idx < 0 || filtered == null || idx >= filtered.size()) {
			return -1;
		}
		if (dx % SLOT >= 16 || dy % SLOT >= 16) {
			return -1;
		}
		return idx;
	}

	@Override
	protected void actionPerformed(GuiButton btn) {
		if (btn.id == 0) {
			mc.displayGuiScreen(parent);
		} else if (btn.id == 1) {
			if (recipes != null) {
				mc.displayGuiScreen(new GuiScreenJei(parent));
			} else {
				mc.displayGuiScreen(parent);
			}
		} else if (btn.id == 2) {
			if (recipes != null && !recipes.isEmpty()) {
				recipeIndex = (recipeIndex + recipes.size() - 1) % recipes.size();
			}
		} else if (btn.id == 3) {
			if (recipes != null && !recipes.isEmpty()) {
				recipeIndex = (recipeIndex + 1) % recipes.size();
			}
		}
	}

	@Override
	protected void keyTyped(char c, int key) {
		if (key == 1) {
			mc.displayGuiScreen(parent);
			return;
		}
		if (searchField != null && searchField.textboxKeyTyped(c, key)) {
			refreshFilter();
			return;
		}
		// R = recipes for hovered, U = uses.
		if ((c == 'r' || c == 'R') && hoverStack != null) {
			mc.displayGuiScreen(new GuiScreenJei(parent, hoverStack.copy(), false));
			return;
		}
		if ((c == 'u' || c == 'U') && hoverStack != null) {
			mc.displayGuiScreen(new GuiScreenJei(parent, hoverStack.copy(), true));
			return;
		}
		super.keyTyped(c, key);
	}

	@Override
	protected void mouseClicked(int mx, int my, int btn) {
		super.mouseClicked(mx, my, btn);
		if (searchField != null) {
			searchField.mouseClicked(mx, my, btn);
		}
		// Clicks inside the recipe panel: jump to ingredient recipes.
		if (recipes != null && !recipes.isEmpty()) {
			ItemStack hit = recipeSlotAt(mx, my);
			if (hit != null) {
				if (btn == 1) {
					mc.displayGuiScreen(new GuiScreenJei(parent, hit.copy(), false));
				} else if (btn == 0 && mc.playerController.isInCreativeMode()) {
					giveStack(hit);
				} else if (btn == 0) {
					mc.displayGuiScreen(new GuiScreenJei(parent, hit.copy(), true));
				}
				return;
			}
		}
		int idx = indexAt(mx, my);
		if (idx < 0) {
			return;
		}
		ItemStack s = filtered.get(idx);
		if (s == null) {
			return;
		}
		if (btn == 1) {
			// Right-click = recipes (R).
			mc.displayGuiScreen(new GuiScreenJei(parent, s.copy(), false));
		} else if (btn == 0) {
			if (mc.playerController.isInCreativeMode()) {
				giveStack(s);
			} else {
				mc.displayGuiScreen(new GuiScreenJei(parent, s.copy(), false));
			}
		} else if (btn == 2) {
			mc.displayGuiScreen(new GuiScreenJei(parent, s.copy(), true));
		}
	}

	private void giveStack(ItemStack s) {
		try {
			ItemStack copy = s.copy();
			copy.stackSize = copy.getMaxStackSize();
			mc.thePlayer.inventory.setItemStack(copy);
			try {
				mc.thePlayer.inventoryContainer.detectAndSendChanges();
			} catch (Throwable th) {
			}
		} catch (Throwable th) {
		}
	}

	@Override
	public void handleMouseInput() throws IOException {
		super.handleMouseInput();
		int wheel = Mouse.getEventDWheel();
		if (wheel != 0 && filtered != null) {
			int totalRows = (filtered.size() + COLS - 1) / COLS;
			int maxScroll = Math.max(0, totalRows - visibleRows());
			if (wheel > 0) {
				scrollRows = Math.max(0, scrollRows - 1);
			} else {
				scrollRows = Math.min(maxScroll, scrollRows + 1);
			}
		}
	}

	@Override
	public void drawScreen(int mx, int my, float pt) {
		this.drawDefaultBackground();
		this.drawCenteredString(this.fontRendererObj,
				recipes != null ? "JEI - " + (showingUses ? "Uses" : "Recipes") : "JEI - Items", this.width / 2, 12,
				0xFFFFFF);
		if (searchField != null && recipes == null) {
			searchField.drawTextBox();
		}
		hoverStack = null;

		RenderHelper.enableGUIStandardItemLighting();
		GlStateManager.enableDepth();

		if (recipes == null) {
			drawItemGrid(mx, my);
			String hint = mc.playerController.isInCreativeMode()
					? "Left-click: take stack  |  Right-click / R: recipes  |  Middle / U: uses  |  Type to search"
					: "Click / R: recipes  |  U: uses  |  Type to search (survival: view only)";
			this.drawCenteredString(this.fontRendererObj, hint, this.width / 2, this.height - 16, 0xA0A0A0);
		} else {
			drawItemGrid(mx, my);
			drawRecipePanel(mx, my);
		}

		GlStateManager.disableDepth();
		RenderHelper.disableStandardItemLighting();
		super.drawScreen(mx, my, pt);
		if (hoverStack != null) {
			try {
				List<String> tip = hoverStack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips);
				this.drawHoveringText(tip, mx, my);
			} catch (Throwable th) {
			}
		}
	}

	private void drawItemGrid(int mx, int my) {
		if (filtered == null) {
			return;
		}
		int gx = gridLeft();
		int gy = gridTop();
		int rows = visibleRows();
		for (int r = 0; r < rows; ++r) {
			for (int c = 0; c < COLS; ++c) {
				int idx = (scrollRows + r) * COLS + c;
			if (idx >= filtered.size()) {
				continue;
			}
				ItemStack s = filtered.get(idx);
				int x = gx + c * SLOT + 1;
				int y = gy + r * SLOT + 1;
				try {
					itemRender.renderItemAndEffectIntoGUI(s, x, y);
					itemRender.renderItemOverlayIntoGUI(fontRendererObj, s, x, y, null);
				} catch (Throwable th) {
				}
				if (mx >= x && my >= y && mx < x + 16 && my < y + 16) {
					hoverStack = s;
					GlStateManager.disableDepth();
					GlStateManager.colorMask(true, true, true, false);
					drawGradientRect(x, y, x + 16, y + 16, 0x80FFFFFF, 0x80FFFFFF);
					GlStateManager.colorMask(true, true, true, true);
					GlStateManager.enableDepth();
				}
			}
		}
		int totalRows = (filtered.size() + COLS - 1) / COLS;
		String count = filtered.size() + " items";
		this.drawString(this.fontRendererObj, count, 170, 14, 0x808080);
		if (totalRows > rows) {
			String page = (scrollRows + 1) + "/" + (totalRows - rows + 1);
			this.drawString(this.fontRendererObj, page, gx + COLS * SLOT + 6, gy, 0x808080);
		}
	}

	private int recipePanelLeft() {
		return gridLeft() + COLS * SLOT + 16;
	}

	private int recipePanelTop() {
		return gridTop();
	}

	private void drawRecipePanel(int mx, int my) {
		int px = recipePanelLeft();
		int py = recipePanelTop();
		if (px + 150 > this.width) {
			return;
		}
		if (recipes == null || recipes.isEmpty()) {
			String name = recipeTarget == null ? "?" : recipeTarget.getDisplayName();
			this.drawString(this.fontRendererObj, "No recipes for", px, py, 0xFFFFFF);
			this.drawString(this.fontRendererObj, name, px, py + 12, 0xFFFF55);
			this.drawString(this.fontRendererObj, showingUses ? "Not used in any" : "Try U (uses) or", px, py + 28,
					0x808080);
			this.drawString(this.fontRendererObj, showingUses ? "recipe." : "another item.", px, py + 38, 0x808080);
			return;
		}
		JeiRecipe r = recipes.get(recipeIndex % recipes.size());
		String title = r.type == JeiRecipe.Type.FURNACE ? "Furnace"
				: (r.type == JeiRecipe.Type.CRAFTING_SHAPELESS ? "Shapeless" : "Shaped");
		this.drawString(this.fontRendererObj, title + " " + (recipeIndex + 1) + "/" + recipes.size(), px, py,
				0xFFFF55);
		String outName = r.output.getDisplayName();
		this.drawString(this.fontRendererObj, "= " + outName, px, py + 12, 0xFFFFFF);
		if (r.type == JeiRecipe.Type.FURNACE) {
			drawSlot(r.inputs.get(0), px + 10, py + 30, mx, my);
			this.drawString(this.fontRendererObj, "->", px + 34, py + 34, 0xFFFFFF);
			drawSlot(r.output, px + 52, py + 30, mx, my);
			if (r.furnaceXp > 0.0f) {
				this.drawString(this.fontRendererObj, String.format("%.1f XP", r.furnaceXp), px, py + 52, 0x55FF55);
			}
			this.drawString(this.fontRendererObj, "Click output: uses", px, py + 66, 0x808080);
		} else {
			for (int i = 0; i < 9; ++i) {
				int cx = px + (i % 3) * SLOT;
				int cy = py + 28 + (i / 3) * SLOT;
				drawSlot(r.inputs.get(i), cx, cy, mx, my);
			}
			this.drawString(this.fontRendererObj, "->", px + 58, py + 52, 0xFFFFFF);
			drawSlot(r.output, px + 76, py + 48, mx, my);
			this.drawString(this.fontRendererObj, "R: recipes  U: uses", px, py + 84, 0x808080);
			this.drawString(this.fontRendererObj, "Creative click: take", px, py + 94, 0x808080);
		}
	}

	private void drawSlot(ItemStack s, int x, int y, int mx, int my) {
		drawRect(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
		drawRect(x, y, x + 16, y + 16, 0xFF000000);
		if (s == null) {
			return;
		}
		try {
			itemRender.renderItemAndEffectIntoGUI(s, x, y);
			itemRender.renderItemOverlayIntoGUI(fontRendererObj, s, x, y, null);
		} catch (Throwable th) {
		}
		if (mx >= x && my >= y && mx < x + 16 && my < y + 16) {
			hoverStack = s;
			GlStateManager.disableDepth();
			GlStateManager.colorMask(true, true, true, false);
			drawGradientRect(x, y, x + 16, y + 16, 0x80FFFFFF, 0x80FFFFFF);
			GlStateManager.colorMask(true, true, true, true);
			GlStateManager.enableDepth();
		}
	}

	private ItemStack recipeSlotAt(int mx, int my) {
		if (recipes == null || recipes.isEmpty()) {
			return null;
		}
		JeiRecipe r = recipes.get(recipeIndex % recipes.size());
		int px = recipePanelLeft();
		int py = recipePanelTop();
		if (r.type == JeiRecipe.Type.FURNACE) {
			if (inSlot(mx, my, px + 10, py + 30)) {
				return r.inputs.get(0);
			}
			if (inSlot(mx, my, px + 52, py + 30)) {
				return r.output;
			}
			return null;
		}
		for (int i = 0; i < 9; ++i) {
			int cx = px + (i % 3) * SLOT;
			int cy = py + 28 + (i / 3) * SLOT;
			if (inSlot(mx, my, cx, cy)) {
				return r.inputs.get(i);
			}
		}
		if (inSlot(mx, my, px + 76, py + 48)) {
			return r.output;
		}
		return null;
	}

	private boolean inSlot(int mx, int my, int x, int y) {
		return mx >= x && my >= y && mx < x + 16 && my < y + 16;
	}
}
