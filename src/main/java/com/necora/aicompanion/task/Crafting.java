package com.necora.aicompanion.task;

import com.necora.aicompanion.entity.CompanionEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans crafting from the companion's inventory using the server's real recipes, including
 * intermediate steps (logs -> planks -> sticks -> pickaxe).
 */
public final class Crafting {
	private Crafting() {
	}

	public record Step(ItemStack result, Map<Item, Integer> consumed, Map<Item, Integer> remainders) {
	}

	/** Returns the steps to craft {@code amount} more of {@code target}, or null if impossible. */
	@Nullable
	public static List<Step> plan(CompanionEntity c, Item target, int amount) {
		World world = c.getWorld();
		Map<Item, Integer> inv = inventoryCounts(c);
		List<Step> steps = new ArrayList<>();
		int want = inv.getOrDefault(target, 0) + amount;
		if (ensure(world, inv, target, want, 0, new HashSet<>(), steps)) return steps;
		return null;
	}

	public static Map<Item, Integer> inventoryCounts(CompanionEntity c) {
		Map<Item, Integer> inv = new HashMap<>();
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (!s.isEmpty()) inv.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		ItemStack main = c.getMainHandStack();
		if (!main.isEmpty()) inv.merge(main.getItem(), main.getCount(), Integer::sum);
		ItemStack off = c.getOffHandStack();
		if (!off.isEmpty()) inv.merge(off.getItem(), off.getCount(), Integer::sum);
		return inv;
	}

	private static List<RecipeEntry<CraftingRecipe>> recipesFor(World world, Item item) {
		List<RecipeEntry<CraftingRecipe>> out = new ArrayList<>();
		for (RecipeEntry<CraftingRecipe> entry : world.getRecipeManager().listAllOfType(RecipeType.CRAFTING)) {
			ItemStack result = entry.value().getResult(world.getRegistryManager());
			if (result.isEmpty() || !result.isOf(item)) continue;
			boolean hasIngredients = false;
			for (Ingredient ing : entry.value().getIngredients()) {
				if (!ing.isEmpty()) {
					hasIngredients = true;
					break;
				}
			}
			if (hasIngredients) out.add(entry);
		}
		out.sort(Comparator.comparingInt(e -> (int) e.value().getIngredients().stream().filter(i -> !i.isEmpty()).count()));
		return out;
	}

	private static boolean ensure(World world, Map<Item, Integer> inv, Item item, int amount, int depth,
								  Set<Item> visiting, List<Step> steps) {
		int have = inv.getOrDefault(item, 0);
		if (have >= amount) return true;
		if (depth > 4 || visiting.contains(item)) return false;
		visiting.add(item);
		try {
			for (RecipeEntry<CraftingRecipe> entry : recipesFor(world, item)) {
				CraftingRecipe recipe = entry.value();
				ItemStack result = recipe.getResult(world.getRegistryManager());
				int perCraft = Math.max(1, result.getCount());
				int crafts = (int) Math.ceil((amount - have) / (double) perCraft);
				if (crafts > 64) continue;
				Map<Item, Integer> trial = new HashMap<>(inv);
				List<Step> trialSteps = new ArrayList<>();
				boolean ok = true;
				for (int k = 0; k < crafts && ok; k++) {
					Map<Item, Integer> consumed = new LinkedHashMap<>();
					Map<Item, Integer> remainders = new LinkedHashMap<>();
					for (Ingredient ing : recipe.getIngredients()) {
						if (ing.isEmpty()) continue;
						Item pick = pickFrom(trial, ing);
						if (pick == null) {
							for (ItemStack option : ing.getMatchingStacks()) {
								if (ensure(world, trial, option.getItem(), 1, depth + 1, visiting, trialSteps)) {
									pick = option.getItem();
									break;
								}
							}
						}
						if (pick == null) {
							ok = false;
							break;
						}
						trial.merge(pick, -1, Integer::sum);
						consumed.merge(pick, 1, Integer::sum);
						if (pick.hasRecipeRemainder() && pick.getRecipeRemainder() != null && pick.getRecipeRemainder() != Items.AIR) {
							Item rem = pick.getRecipeRemainder();
							trial.merge(rem, 1, Integer::sum);
							remainders.merge(rem, 1, Integer::sum);
						}
					}
					if (ok) {
						trial.merge(item, perCraft, Integer::sum);
						trialSteps.add(new Step(result.copy(), consumed, remainders));
					}
				}
				if (ok) {
					inv.clear();
					inv.putAll(trial);
					steps.addAll(trialSteps);
					return true;
				}
			}
			return false;
		} finally {
			visiting.remove(item);
		}
	}

	@Nullable
	private static Item pickFrom(Map<Item, Integer> inv, Ingredient ing) {
		Item best = null;
		int bestCount = 0;
		for (Map.Entry<Item, Integer> e : inv.entrySet()) {
			if (e.getValue() <= 0) continue;
			if (ing.test(new ItemStack(e.getKey()))) {
				if (e.getValue() > bestCount) {
					best = e.getKey();
					bestCount = e.getValue();
				}
			}
		}
		return best;
	}

	/** Applies one crafting step to the real inventory. */
	public static boolean apply(CompanionEntity c, Step step) {
		for (Map.Entry<Item, Integer> e : step.consumed().entrySet()) {
			if (c.countItem(e.getKey()) < e.getValue()) return false;
		}
		for (Map.Entry<Item, Integer> e : step.consumed().entrySet()) {
			final Item item = e.getKey();
			c.removeItems(s -> s.isOf(item), e.getValue());
		}
		giveOrDrop(c, step.result().copy());
		for (Map.Entry<Item, Integer> e : step.remainders().entrySet()) {
			giveOrDrop(c, new ItemStack(e.getKey(), e.getValue()));
		}
		return true;
	}

	private static void giveOrDrop(CompanionEntity c, ItemStack stack) {
		ItemStack rest = c.insertStack(stack);
		if (!rest.isEmpty()) c.dropStack(rest);
	}
}
