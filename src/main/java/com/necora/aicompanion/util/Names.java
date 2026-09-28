package com.necora.aicompanion.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns the loose names a language model (or a player) uses into Minecraft blocks and items.
 * Handles ids ("oak_planks"), spaces ("oak planks"), plurals ("logs"), namespaces and common slang.
 */
public final class Names {
	private static final Map<String, String> BLOCK_ALIASES = new HashMap<>();
	private static final Map<String, String> ITEM_ALIASES = new HashMap<>();

	static {
		String[][] blocks = {
				{"wood", "oak_planks"}, {"planks", "oak_planks"}, {"plank", "oak_planks"}, {"wooden_planks", "oak_planks"},
				{"log", "oak_log"}, {"logs", "oak_log"}, {"cobble", "cobblestone"}, {"stonebrick", "stone_bricks"},
				{"stone_brick", "stone_bricks"}, {"brick", "bricks"}, {"glass_block", "glass"}, {"window", "glass_pane"},
				{"windows", "glass_pane"}, {"pane", "glass_pane"}, {"wool", "white_wool"}, {"concrete", "white_concrete"},
				{"terracotta", "terracotta"}, {"door", "oak_door"}, {"wooden_door", "oak_door"}, {"fence", "oak_fence"},
				{"gate", "oak_fence_gate"}, {"fence_gate", "oak_fence_gate"}, {"slab", "oak_slab"}, {"stairs", "oak_stairs"},
				{"torch", "torch"}, {"torches", "torch"}, {"lantern", "lantern"}, {"bed", "red_bed"}, {"chest", "chest"},
				{"table", "crafting_table"}, {"workbench", "crafting_table"}, {"crafting", "crafting_table"},
				{"furnace", "furnace"}, {"ladder", "ladder"}, {"quartz", "quartz_block"}, {"iron", "iron_block"},
				{"gold", "gold_block"}, {"diamond", "diamond_block"}, {"emerald", "emerald_block"}, {"obby", "obsidian"},
				{"leaves", "oak_leaves"}, {"grass", "grass_block"}, {"water", "water"}, {"lava", "lava"},
				{"sandstone_block", "sandstone"}, {"nether_brick", "nether_bricks"}, {"end_brick", "end_stone_bricks"},
				{"deepslate_brick", "deepslate_bricks"}, {"mud_brick", "mud_bricks"}, {"prismarine_brick", "prismarine_bricks"},
				{"snow", "snow_block"}, {"ice", "ice"}, {"hay", "hay_block"}, {"glowstone", "glowstone"},
				{"sea_lantern", "sea_lantern"}, {"stone_bricks_block", "stone_bricks"}, {"smooth", "smooth_stone"},
				{"log_cabin", "spruce_log"}, {"dirt_block", "dirt"}, {"iron_bar", "iron_bars"}, {"bars", "iron_bars"},
		};
		for (String[] a : blocks) BLOCK_ALIASES.put(a[0], a[1]);
		String[][] items = {
				{"pick", "iron_pickaxe"}, {"pickaxe", "iron_pickaxe"}, {"sword", "iron_sword"}, {"axe", "iron_axe"},
				{"shovel", "iron_shovel"}, {"hoe", "iron_hoe"}, {"steak", "cooked_beef"}, {"pork", "cooked_porkchop"},
				{"porkchop", "cooked_porkchop"}, {"chicken", "cooked_chicken"}, {"mutton", "cooked_mutton"},
				{"fish", "cooked_cod"}, {"sticks", "stick"}, {"coal", "coal"}, {"iron", "iron_ingot"}, {"gold", "gold_ingot"},
				{"diamonds", "diamond"}, {"emeralds", "emerald"}, {"pearl", "ender_pearl"}, {"pearls", "ender_pearl"},
				{"arrows", "arrow"}, {"bucket_of_water", "water_bucket"}, {"shield", "shield"}, {"bow", "bow"},
				{"table", "crafting_table"}, {"workbench", "crafting_table"}, {"planks", "oak_planks"}, {"wood", "oak_planks"},
				{"torches", "torch"}, {"bread_loaf", "bread"},
		};
		for (String[] a : items) ITEM_ALIASES.put(a[0], a[1]);
	}

	private Names() {
	}

	public static String normalize(String name) {
		String s = name.toLowerCase(Locale.ROOT).trim();
		if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
		s = s.replaceAll("^(a|an|some|the|my|your)\\s+", "");
		s = s.replaceAll("[\\s\\-]+", "_").replaceAll("[^a-z0-9_:\\[\\]=,.]", "");
		return s;
	}

	private static List<String> candidates(String n) {
		List<String> out = new ArrayList<>();
		out.add(n);
		if (n.endsWith("ies")) out.add(n.substring(0, n.length() - 3) + "y");
		if (n.endsWith("es")) out.add(n.substring(0, n.length() - 2));
		if (n.endsWith("s")) out.add(n.substring(0, n.length() - 1));
		if (!n.endsWith("s")) out.add(n + "s");
		return out;
	}

	@Nullable
	public static Block block(String name) {
		if (name == null || name.isBlank()) return null;
		String n = normalize(name);
		int bracket = n.indexOf('[');
		if (bracket > 0) n = n.substring(0, bracket);
		for (String c : candidates(n)) {
			Block b = exactBlock(c);
			if (b != null) return b;
			String alias = BLOCK_ALIASES.get(c);
			if (alias != null && (b = exactBlock(alias)) != null) return b;
		}
		// "oak wood planks" -> try removing "wood"
		if (n.contains("_wood_")) return block(n.replace("_wood_", "_"));
		if (n.contains("wooden_")) return block(n.replace("wooden_", "oak_"));
		return null;
	}

	@Nullable
	private static Block exactBlock(String id) {
		Identifier identifier = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (identifier == null || !Registries.BLOCK.containsId(identifier)) return null;
		Block b = Registries.BLOCK.get(identifier);
		return b == Blocks.AIR && !id.endsWith("air") ? null : b;
	}

	/**
	 * Parses a block with optional state, e.g. "oak_stairs[facing=north,half=top]". Falls back to
	 * {@link #block(String)} for loose names.
	 */
	@Nullable
	public static BlockState blockState(String name) {
		if (name == null || name.isBlank()) return null;
		String n = normalize(name);
		if (n.contains("[")) {
			try {
				return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), n, false).blockState();
			} catch (Exception ignored) {
			}
		}
		Block b = block(n);
		return b == null ? null : b.getDefaultState();
	}

	@Nullable
	public static Item item(String name) {
		if (name == null || name.isBlank()) return null;
		String n = normalize(name);
		for (String c : candidates(n)) {
			Item it = exactItem(c);
			if (it != null) return it;
			String alias = ITEM_ALIASES.get(c);
			if (alias != null && (it = exactItem(alias)) != null) return it;
			alias = BLOCK_ALIASES.get(c);
			if (alias != null && (it = exactItem(alias)) != null) return it;
		}
		return null;
	}

	@Nullable
	private static Item exactItem(String id) {
		Identifier identifier = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
		if (identifier == null || !Registries.ITEM.containsId(identifier)) return null;
		Item it = Registries.ITEM.get(identifier);
		return it == Items.AIR ? null : it;
	}

	@Nullable
	public static EntityType<?> entityType(String name) {
		if (name == null || name.isBlank()) return null;
		String n = normalize(name);
		for (String c : candidates(n)) {
			Identifier identifier = Identifier.tryParse(c.contains(":") ? c : "minecraft:" + c);
			if (identifier != null && Registries.ENTITY_TYPE.containsId(identifier)) {
				return Registries.ENTITY_TYPE.get(identifier);
			}
		}
		return null;
	}

	/** A description of what to look for in the world when mining. */
	public record BlockMatcher(String label, Predicate<BlockState> predicate) {
	}

	@Nullable
	public static BlockMatcher blockMatcher(String name) {
		if (name == null || name.isBlank()) return null;
		String n = normalize(name);
		TagKey<Block> tag = switch (n) {
			case "log", "logs", "wood", "woods", "tree", "trees", "timber" -> BlockTags.LOGS;
			case "leaves", "leaf" -> BlockTags.LEAVES;
			case "stone", "stones", "rock", "rocks" -> BlockTags.BASE_STONE_OVERWORLD;
			case "dirt", "soil" -> BlockTags.DIRT;
			case "sand" -> BlockTags.SAND;
			case "iron", "iron_ore", "iron_ores" -> BlockTags.IRON_ORES;
			case "coal", "coal_ore", "coal_ores" -> BlockTags.COAL_ORES;
			case "gold", "gold_ore", "gold_ores" -> BlockTags.GOLD_ORES;
			case "diamond", "diamonds", "diamond_ore", "diamond_ores" -> BlockTags.DIAMOND_ORES;
			case "copper", "copper_ore", "copper_ores" -> BlockTags.COPPER_ORES;
			case "redstone", "redstone_ore", "redstone_ores" -> BlockTags.REDSTONE_ORES;
			case "lapis", "lapis_ore", "lapis_ores", "lapis_lazuli" -> BlockTags.LAPIS_ORES;
			case "emerald", "emeralds", "emerald_ore", "emerald_ores" -> BlockTags.EMERALD_ORES;
			case "wool" -> BlockTags.WOOL;
			case "flower", "flowers" -> BlockTags.FLOWERS;
			default -> null;
		};
		if (tag != null) {
			final TagKey<Block> t = tag;
			return new BlockMatcher(n, s -> s.isIn(t));
		}
		if (n.equals("ore") || n.equals("ores")) {
			return new BlockMatcher("ores", s -> s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES) || s.isIn(BlockTags.GOLD_ORES)
					|| s.isIn(BlockTags.DIAMOND_ORES) || s.isIn(BlockTags.COPPER_ORES) || s.isIn(BlockTags.REDSTONE_ORES)
					|| s.isIn(BlockTags.LAPIS_ORES) || s.isIn(BlockTags.EMERALD_ORES));
		}
		Block b = block(n);
		if (b == null || b == Blocks.AIR) return null;
		// Include deepslate variants of ores automatically.
		Block deepslate = b.getDefaultState().isIn(BlockTags.IRON_ORES) ? null : exactBlock("deepslate_" + Registries.BLOCK.getId(b).getPath());
		final Block fb = b;
		final Block fd = deepslate;
		return new BlockMatcher(Registries.BLOCK.getId(b).getPath(), s -> s.isOf(fb) || (fd != null && s.isOf(fd)));
	}

	/**
	 * A predicate over item stacks for "give me the sword", "drop the dirt", "all your food"...
	 * Returns null if the name makes no sense at all.
	 */
	@Nullable
	public static Predicate<ItemStack> itemMatcher(String name) {
		if (name == null || name.isBlank()) return null;
		String n = normalize(name);
		switch (n) {
			case "all", "everything", "anything", "items", "stuff", "inventory", "all_items":
				return s -> !s.isEmpty();
			case "food", "foods", "something_to_eat":
				return ItemUtil::isGoodFood;
			case "sword", "swords":
				return s -> s.isIn(ItemTags.SWORDS);
			case "pickaxe", "pickaxes", "pick":
				return s -> s.isIn(ItemTags.PICKAXES);
			case "axe", "axes":
				return s -> s.isIn(ItemTags.AXES);
			case "shovel", "shovels", "spade":
				return s -> s.isIn(ItemTags.SHOVELS);
			case "hoe", "hoes":
				return s -> s.isIn(ItemTags.HOES);
			case "tool", "tools":
				return ItemUtil::isTool;
			case "weapon", "weapons":
				return ItemUtil::isWeapon;
			case "armor", "armour":
				return s -> s.getItem() instanceof ArmorItem;
			case "log", "logs", "wood":
				return s -> s.isIn(ItemTags.LOGS);
			case "plank", "planks":
				return s -> s.isIn(ItemTags.PLANKS);
			case "block", "blocks", "building_blocks":
				return s -> s.getItem() instanceof BlockItem;
			case "wool":
				return s -> s.isIn(ItemTags.WOOL);
			case "ore", "ores", "ingots", "valuables":
				return s -> { String id = ItemUtil.id(s.getItem()); return id.contains("ingot") || id.contains("raw_") || id.equals("diamond") || id.equals("emerald") || id.equals("coal"); };
			default:
				break;
		}
		Item exact = item(n);
		if (exact != null) {
			final Item e = exact;
			// "iron_sword" should match exactly, but "iron" alias -> iron_ingot shouldn't block matching raw iron etc.
			return s -> s.isOf(e);
		}
		// loose word match against item ids ("diamond pick" -> diamond_pickaxe)
		String[] words = n.split("_");
		return s -> {
			if (s.isEmpty()) return false;
			String id = ItemUtil.id(s.getItem());
			for (String w : words) {
				if (w.length() < 3) continue;
				String stem = w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
				if (!id.contains(stem)) return false;
			}
			return true;
		};
	}
}
