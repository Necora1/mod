package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.util.ItemUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.function.Predicate;

public class EquipTask extends Task {
	private final Predicate<ItemStack> matcher;
	private final String label;

	public EquipTask(CompanionEntity companion, Predicate<ItemStack> matcher, String label) {
		super(companion);
		this.matcher = matcher;
		this.label = label;
	}

	@Override
	protected Status tick() {
		int equippedArmor = 0;
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (s.isEmpty() || !matcher.test(s)) continue;
			if (s.getItem() instanceof ArmorItem armor) {
				EquipmentSlot slot = armor.getSlotType();
				ItemStack old = c.getEquippedStack(slot);
				c.equipStack(slot, s.copy());
				c.getInventory().setStack(i, old.copy());
				equippedArmor++;
			} else if (s.isOf(Items.SHIELD) || s.isOf(Items.TOTEM_OF_UNDYING)) {
				ItemStack old = c.getOffHandStack();
				c.equipStack(EquipmentSlot.OFFHAND, s.copy());
				c.getInventory().setStack(i, old.copy());
				return success("put " + ItemUtil.id(s.getItem()) + " in my off hand");
			} else if (equippedArmor == 0) {
				final ItemStack chosen = s;
				c.selectItem(x -> x == chosen);
				return success("now holding " + ItemUtil.id(c.getMainHandStack().getItem()));
			}
		}
		if (equippedArmor > 0) return success("put on " + equippedArmor + " armor piece(s)");
		if (matcher.test(c.getMainHandStack())) return success("already holding " + ItemUtil.id(c.getMainHandStack().getItem()));
		return fail("I don't have any " + label);
	}

	@Override
	public boolean reportResult() {
		return false;
	}

	@Override
	public String describe() {
		return "equipping " + label;
	}
}
