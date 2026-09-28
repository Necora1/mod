package com.necora.aicompanion.build;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.util.ItemUtil;
import com.necora.aicompanion.util.Names;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/** Picks building materials: resolves names, finds matching stairs/slabs/doors, and falls back to what's in the inventory. */
public final class Materials {
	private Materials() {
	}

	@Nullable
	public static Block byId(String id) {
		Identifier identifier = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (identifier == null || !Registries.BLOCK.containsId(identifier)) return null;
		Block b = Registries.BLOCK.get(identifier);
		return b == Blocks.AIR ? null : b;
	}

	public static String path(Block block) {
		return Registries.BLOCK.getId(block).getPath();
	}

	/**
	 * Resolves a material name. "auto"/empty picks the most plentiful building block in the
	 * inventory (survival) or {@code fallback} (creative).
	 */
	public static Block resolve(@Nullable String name, Block fallback, CompanionEntity c, int needed) {
		if (name == null || name.isBlank() || name.equalsIgnoreCase("auto") || name.equalsIgnoreCase("any") || name.equalsIgnoreCase("default")) {
			return auto(c, fallback, needed);
		}
		Block b = Names.block(name);
		if (b == null) b = Names.block(name + "_planks");
		if (b == null) b = Names.block(name + "_block");
		if (b == null || b == Blocks.AIR) return auto(c, fallback, needed);
		return b;
	}

	/** Most plentiful solid building block the companion carries, or the fallback. */
	public static Block auto(CompanionEntity c, Block fallback, int needed) {
		if (c.isCreativeMode()) return fallback;
		if (c.countItem(fallback.asItem()) >= needed) return fallback;
		Map<Item, Integer> counts = new HashMap<>();
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (isBuildingBlock(s)) counts.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		ItemStack main = c.getMainHandStack();
		if (isBuildingBlock(main)) counts.merge(main.getItem(), main.getCount(), Integer::sum);
		Item best = null;
		int bestCount = 0;
		for (Map.Entry<Item, Integer> e : counts.entrySet()) {
			if (e.getValue() > bestCount) {
				best = e.getKey();
				bestCount = e.getValue();
			}
		}
		if (best instanceof BlockItem bi && bestCount >= Math.min(needed, 16)) return bi.getBlock();
		return fallback;
	}

	public static boolean isBuildingBlock(ItemStack s) {
		if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
		Block block = bi.getBlock();
		if (block instanceof FallingBlock) return false;
		BlockState st = block.getDefaultState();
		if (st.hasBlockEntity() || s.isIn(ItemTags.LEAVES)) return false;
		if (ItemUtil.isValuable(s)) return false;
		String id = path(block);
		if (id.contains("ore") || id.endsWith("_block") && (id.startsWith("iron") || id.startsWith("gold") || id.startsWith("diamond")
				|| id.startsWith("emerald") || id.startsWith("netherite") || id.startsWith("lapis") || id.startsWith("redstone"))) return false;
		return st.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
	}

	/** Wood type or stone type prefix: "spruce_planks" -> "spruce", "stone_bricks" -> "stone_brick". */
	private static String base(Block block) {
		String p = path(block);
		for (String suffix : new String[]{"_planks", "_log", "_wood", "_stem", "_hyphae", "_stairs", "_slab", "_fence_gate", "_fence", "_wall", "_door"}) {
			if (p.endsWith(suffix)) return p.substring(0, p.length() - suffix.length());
		}
		if (p.startsWith("stripped_")) return base(byIdOr(p.substring("stripped_".length()), block));
		if (p.endsWith("bricks")) return p.substring(0, p.length() - 1);
		if (p.endsWith("tiles")) return p.substring(0, p.length() - 1);
		if (p.endsWith("_block")) return p.substring(0, p.length() - "_block".length());
		return p;
	}

	private static Block byIdOr(String id, Block fallback) {
		Block b = byId(id);
		return b == null ? fallback : b;
	}

	@Nullable
	private static Block variant(Block block, String kind) {
		String p = path(block);
		if (p.endsWith("_" + kind)) return block;
		String b = base(block);
		Block v = byId(b + "_" + kind);
		if (v == null) v = byId(p + "_" + kind);
		if (v == null && p.endsWith("s")) v = byId(p.substring(0, p.length() - 1) + "_" + kind);
		if (v == null && kind.equals("wall") && b.equals("cobblestone")) v = Blocks.COBBLESTONE_WALL;
		return v;
	}

	@Nullable
	public static Block stairs(Block block) {
		return variant(block, "stairs");
	}

	@Nullable
	public static Block slab(Block block) {
		return variant(block, "slab");
	}

	/** Planks for wood materials (oak_log -> oak_planks), otherwise the block itself. */
	public static Block fullBlock(Block block) {
		String p = path(block);
		if (p.endsWith("_stairs") || p.endsWith("_slab")) {
			String b = base(block);
			Block full = byId(b + "_planks");
			if (full == null) full = byId(b + "s");
			if (full == null) full = byId(b);
			if (full != null) return full;
		}
		return block;
	}

	/** A log that goes with the material, for corner posts. */
	public static Block log(Block block) {
		String b = base(block);
		Block log = byId(b + "_log");
		if (log == null) log = byId(b + "_stem");
		return log == null ? block : log;
	}

	public static Block door(Block wall, CompanionEntity c) {
		Block d = byId(base(wall) + "_door");
		if (d != null && !path(d).equals("iron_door")) return d;
		Block fromInv = fromInventory(c, ItemTags.WOODEN_DOORS);
		if (fromInv != null && !c.isCreativeMode()) return fromInv;
		return Blocks.OAK_DOOR;
	}

	public static Block fence(Block block) {
		Block f = variant(block, "fence");
		if (f == null) f = variant(block, "wall");
		return f == null ? Blocks.OAK_FENCE : f;
	}

	@Nullable
	public static Block fromInventory(CompanionEntity c, TagKey<Item> tag) {
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (s.isIn(tag) && s.getItem() instanceof BlockItem bi) return bi.getBlock();
		}
		return null;
	}

	public static Block bed(CompanionEntity c) {
		Block b = fromInventory(c, ItemTags.BEDS);
		return b == null ? Blocks.RED_BED : b;
	}

	/** Glass pane for windows (or matching stained pane when building with glass). */
	public static Block pane(Block glass) {
		String p = path(glass);
		if (p.endsWith("glass")) {
			Block pane = byId(p + "_pane");
			if (pane != null) return pane;
		}
		return Blocks.GLASS_PANE;
	}
}
