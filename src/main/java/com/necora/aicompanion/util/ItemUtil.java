package com.necora.aicompanion.util;

import net.minecraft.block.Block;
import net.minecraft.block.FallingBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;

import java.util.Set;

public final class ItemUtil {
	private static final Set<Item> BAD_FOOD = Set.of(
			Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
			Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE,
			Items.CHICKEN);
	private static final Set<Item> SCAFFOLD = Set.of(
			Items.DIRT, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK, Items.STONE,
			Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.DEEPSLATE, Items.BLACKSTONE,
			Items.END_STONE, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.MOSS_BLOCK, Items.BASALT);

	private ItemUtil() {
	}

	public static String id(Item item) {
		return Registries.ITEM.getId(item).getPath();
	}

	public static String describe(ItemStack stack) {
		if (stack.isEmpty()) return "nothing";
		return stack.getCount() > 1 ? stack.getCount() + "x " + id(stack.getItem()) : id(stack.getItem());
	}

	public static boolean isFood(ItemStack stack) {
		return stack.get(DataComponentTypes.FOOD) != null;
	}

	public static boolean isGoodFood(ItemStack stack) {
		return isFood(stack) && !BAD_FOOD.contains(stack.getItem());
	}

	public static int foodValue(ItemStack stack) {
		var food = stack.get(DataComponentTypes.FOOD);
		return food == null ? 0 : food.nutrition();
	}

	public static double armorValue(ItemStack stack) {
		if (stack.getItem() instanceof ArmorItem armor) {
			return armor.getProtection() + armor.getToughness() * 0.5 + (stack.hasEnchantments() ? 0.5 : 0);
		}
		return 0;
	}

	/** Attack damage the item adds when held in the main hand. */
	public static double attackDamage(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		double[] total = {0};
		stack.applyAttributeModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if (attribute.equals(EntityAttributes.GENERIC_ATTACK_DAMAGE) && modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
				total[0] += modifier.value();
			}
		});
		return total[0];
	}

	public static boolean isWeapon(ItemStack stack) {
		return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES) || stack.isOf(Items.TRIDENT) || stack.isOf(Items.MACE);
	}

	public static boolean isTool(ItemStack stack) {
		return stack.isIn(ItemTags.PICKAXES) || stack.isIn(ItemTags.AXES) || stack.isIn(ItemTags.SHOVELS) || stack.isIn(ItemTags.HOES) || stack.isOf(Items.SHEARS);
	}

	public static boolean isValuable(ItemStack stack) {
		String id = id(stack.getItem());
		return stack.hasEnchantments() || id.contains("diamond") || id.contains("netherite") || id.contains("emerald")
				|| stack.isOf(Items.ELYTRA) || stack.isOf(Items.TOTEM_OF_UNDYING) || stack.isOf(Items.BEACON);
	}

	/** Cheap full blocks that are fine to pillar up with and throw away later. */
	public static boolean isScaffoldBlock(ItemStack stack) {
		return SCAFFOLD.contains(stack.getItem());
	}

	/** Any solid, non-falling block item (fallback scaffolding). */
	public static boolean isPlaceableSolid(ItemStack stack) {
		if (!(stack.getItem() instanceof BlockItem bi)) return false;
		Block block = bi.getBlock();
		if (block instanceof FallingBlock) return false;
		return block.getDefaultState().isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, net.minecraft.util.math.BlockPos.ORIGIN)
				&& !block.getDefaultState().hasBlockEntity();
	}
}
