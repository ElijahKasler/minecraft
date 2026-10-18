package net.minecraft.client.gui.inventory.jei;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;

/**
 * JEI registry (ported web-client inventoryJei/inventoryNotes feature).
 * All-items list via creative tabs (handles metadata variants), recipes via
 * CraftingManager + FurnaceRecipes. Cached per session, filtered by search.
 */
public class JeiRegistry {

	private static List<ItemStack> allItems = null;
	private static List<JeiRecipe> allRecipes = null;
	private static Map<String, List<JeiRecipe>> byOutput = null;

	public static synchronized List<ItemStack> getAllItems() {
		if (allItems != null) {
			return allItems;
		}
		List<ItemStack> out = new ArrayList<ItemStack>();
		for (int t = 0; t < CreativeTabs.creativeTabArray.length; ++t) {
			CreativeTabs tab = CreativeTabs.creativeTabArray[t];
			if (tab == null) {
				continue;
			}
			try {
				List<ItemStack> tmp = new ArrayList<ItemStack>();
				tab.displayAllReleventItems(tmp);
				for (int i = 0; i < tmp.size(); ++i) {
					ItemStack s = tmp.get(i);
					if (s != null && s.getItem() != null && !contains(out, s)) {
						out.add(s.copy());
					}
				}
			} catch (Throwable th) {
			}
		}
		allItems = out;
		return out;
	}

	private static boolean contains(List<ItemStack> list, ItemStack s) {
		for (int i = 0; i < list.size(); ++i) {
			ItemStack o = list.get(i);
			if (o.getItem() == s.getItem() && o.getMetadata() == s.getMetadata()) {
				return true;
			}
		}
		return false;
	}

	public static synchronized List<JeiRecipe> getAllRecipes() {
		if (allRecipes != null) {
			return allRecipes;
		}
		List<JeiRecipe> out = new ArrayList<JeiRecipe>();
		Map<String, List<JeiRecipe>> map = new HashMap<String, List<JeiRecipe>>();
		try {
			List<IRecipe> recipes = CraftingManager.getInstance().getRecipeList();
			for (int i = 0; i < recipes.size(); ++i) {
				IRecipe r = recipes.get(i);
				try {
					ItemStack output = r.getRecipeOutput();
					if (output == null || output.getItem() == null) {
						continue;
					}
					JeiRecipe jr = null;
					if (r instanceof ShapedRecipes) {
						ShapedRecipes sr = (ShapedRecipes) r;
						jr = JeiRecipe.shaped(output.copy(), sr.getJeiItems(), sr.getJeiWidth(),
								sr.getJeiHeight());
					} else if (r instanceof ShapelessRecipes) {
						ShapelessRecipes sr = (ShapelessRecipes) r;
						List<ItemStack> in = new ArrayList<ItemStack>();
						for (ItemStack s : sr.getJeiItems()) {
							in.add(s == null ? null : s.copy());
						}
						jr = JeiRecipe.shapeless(output.copy(), in);
					}
					if (jr != null) {
						out.add(jr);
						addToMap(map, output, jr);
					}
				} catch (Throwable th) {
				}
			}
		} catch (Throwable th) {
		}
		try {
			Map<ItemStack, ItemStack> smelt = FurnaceRecipes.instance().getSmeltingList();
			if (smelt != null) {
				for (Map.Entry<ItemStack, ItemStack> e : smelt.entrySet()) {
					try {
						ItemStack in = e.getKey();
						ItemStack output = e.getValue();
						if (in == null || output == null) {
							continue;
						}
						float xp = 0.0f;
						try {
							xp = FurnaceRecipes.instance().getSmeltingExperience(in);
						} catch (Throwable th) {
						}
						JeiRecipe jr = JeiRecipe.furnace(in.copy(), output.copy(), xp);
						out.add(jr);
						addToMap(map, output, jr);
					} catch (Throwable th) {
					}
				}
			}
		} catch (Throwable th) {
		}
		allRecipes = out;
		byOutput = map;
		return out;
	}

	private static void addToMap(Map<String, List<JeiRecipe>> map, ItemStack output, JeiRecipe jr) {
		String key = output.getItem().getUnlocalizedName() + ":" + output.getMetadata();
		List<JeiRecipe> l = map.get(key);
		if (l == null) {
			l = new ArrayList<JeiRecipe>();
			map.put(key, l);
		}
		l.add(jr);
	}

	public static List<JeiRecipe> getRecipesFor(ItemStack output) {
		getAllRecipes();
		String key = output.getItem().getUnlocalizedName() + ":" + output.getMetadata();
		List<JeiRecipe> l = byOutput.get(key);
		if (l != null && !l.isEmpty()) {
			return l;
		}
		// Fallback: same item, any metadata (wildcard ingredients use 32767).
		List<JeiRecipe> out = new ArrayList<JeiRecipe>();
		for (JeiRecipe r : allRecipes) {
			if (r.output.getItem() == output.getItem()) {
				out.add(r);
			}
		}
		return out;
	}

	public static List<JeiRecipe> getUsesFor(ItemStack ingredient) {
		getAllRecipes();
		List<JeiRecipe> out = new ArrayList<JeiRecipe>();
		for (JeiRecipe r : allRecipes) {
			for (ItemStack in : r.inputs) {
				if (in != null && in.getItem() == ingredient.getItem()
						&& (in.getMetadata() == 32767 || ingredient.getMetadata() == 32767
								|| in.getMetadata() == ingredient.getMetadata())) {
					out.add(r);
					break;
				}
			}
		}
		return out;
	}

	public static List<ItemStack> search(String query) {
		List<ItemStack> all = getAllItems();
		if (query == null || query.trim().isEmpty()) {
			return all;
		}
		String q = query.trim().toLowerCase();
		List<ItemStack> out = new ArrayList<ItemStack>();
		for (ItemStack s : all) {
			try {
				String name = s.getDisplayName();
				String id = s.getItem().getUnlocalizedName();
				if ((name != null && name.toLowerCase().contains(q))
						|| (id != null && id.toLowerCase().contains(q))) {
					out.add(s);
				}
			} catch (Throwable th) {
			}
		}
		return out;
	}

	public static void clearCache() {
		allItems = null;
		allRecipes = null;
		byOutput = null;
	}
}
