package com.necora.aicompanion.entity;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

/**
 * 45-slot view used for the companion's inventory screen:
 * row 1 = helmet, chestplate, leggings, boots, main hand, off hand, then 3 extra storage slots;
 * rows 2-5 = the 36 main inventory slots.
 */
public class CompanionInventoryView implements Inventory {
	private static final EquipmentSlot[] EQUIPMENT = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
			EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
	};
	private final CompanionEntity companion;

	public CompanionInventoryView(CompanionEntity companion) {
		this.companion = companion;
	}

	private int inventoryIndex(int slot) {
		// slots 6..8 -> inventory 36..38, slots 9..44 -> inventory 0..35
		return slot < 9 ? 36 + (slot - 6) : slot - 9;
	}

	@Override
	public int size() {
		return 45;
	}

	@Override
	public boolean isEmpty() {
		for (int i = 0; i < size(); i++) {
			if (!getStack(i).isEmpty()) return false;
		}
		return true;
	}

	@Override
	public ItemStack getStack(int slot) {
		if (slot < 0 || slot >= size()) return ItemStack.EMPTY;
		if (slot < EQUIPMENT.length) return companion.getEquippedStack(EQUIPMENT[slot]);
		return companion.getInventory().getStack(inventoryIndex(slot));
	}

	@Override
	public ItemStack removeStack(int slot, int amount) {
		ItemStack stack = getStack(slot);
		if (stack.isEmpty() || amount <= 0) return ItemStack.EMPTY;
		ItemStack split = stack.split(amount);
		if (slot < EQUIPMENT.length) {
			companion.equipStack(EQUIPMENT[slot], stack.isEmpty() ? ItemStack.EMPTY : stack);
		} else {
			companion.getInventory().markDirty();
		}
		return split;
	}

	@Override
	public ItemStack removeStack(int slot) {
		ItemStack stack = getStack(slot);
		if (stack.isEmpty()) return ItemStack.EMPTY;
		setStack(slot, ItemStack.EMPTY);
		return stack;
	}

	@Override
	public void setStack(int slot, ItemStack stack) {
		if (slot < 0 || slot >= size()) return;
		if (slot < EQUIPMENT.length) {
			companion.equipStack(EQUIPMENT[slot], stack);
		} else {
			companion.getInventory().setStack(inventoryIndex(slot), stack);
		}
	}

	@Override
	public void markDirty() {
		companion.getInventory().markDirty();
	}

	@Override
	public boolean canPlayerUse(PlayerEntity player) {
		return companion.isAlive() && !companion.isRemoved() && player.squaredDistanceTo(companion) < 64.0;
	}

	@Override
	public void clear() {
		for (int i = 0; i < size(); i++) setStack(i, ItemStack.EMPTY);
	}
}
