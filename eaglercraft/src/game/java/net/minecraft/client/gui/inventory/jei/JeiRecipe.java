package net.minecraft.client.gui.inventory.jei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

/**
 * JEI recipe overlay data model (ported from minecraft-web-client-next JEI).
 * Covers crafting shaped/shapeless + furnace, with descriptions in the GUI.
 */
public class JeiRecipe {

	public enum Type {
		CRAFTING_SHAPED,
		CRAFTING_SHAPELESS,
		FURNACE
	}

	public final Type type;
	public final ItemStack output;
	/** 9 slots row-major for crafting (null = empty), 1 element for furnace input. */
	public final List<ItemStack> inputs;
	public final int width;
	public final int height;
	public final float furnaceXp;

	public JeiRecipe(Type type, ItemStack output, List<ItemStack> inputs, int width, int height, float furnaceXp) {
		this.type = type;
		this.output = output;
		this.inputs = inputs;
		this.width = width;
		this.height = height;
		this.furnaceXp = furnaceXp;
	}

	public static JeiRecipe shaped(ItemStack output, ItemStack[] items, int width, int height) {
		List<ItemStack> in = new ArrayList<ItemStack>(9);
		for (int i = 0; i < 9; ++i) {
			int x = i % 3;
			int y = i / 3;
			ItemStack s = null;
			if (x < width && y < height && (y * width + x) < items.length) {
				s = items[y * width + x];
			}
			in.add(s);
		}
		return new JeiRecipe(Type.CRAFTING_SHAPED, output, in, width, height, 0.0f);
	}

	public static JeiRecipe shapeless(ItemStack output, List<ItemStack> items) {
		List<ItemStack> in = new ArrayList<ItemStack>(items);
		while (in.size() < 9) {
			in.add(null);
		}
		return new JeiRecipe(Type.CRAFTING_SHAPELESS, output, in, 3, 3, 0.0f);
	}

	public static JeiRecipe furnace(ItemStack input, ItemStack output, float xp) {
		List<ItemStack> in = new ArrayList<ItemStack>();
		in.add(input);
		return new JeiRecipe(Type.FURNACE, output, in, 1, 1, xp);
	}
}
