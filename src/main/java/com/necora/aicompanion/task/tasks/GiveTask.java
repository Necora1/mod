package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.CompanionMovement;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.util.ItemUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Walks up to a player and tosses them items (or just drops them when there's no recipient). */
public class GiveTask extends Task {
	private final Predicate<ItemStack> matcher;
	private final String itemLabel;
	private final int count;
	@Nullable
	private final PlayerEntity recipient;
	private int remaining;
	private int throwCooldown;
	private final Map<String, Integer> given = new LinkedHashMap<>();
	private int moveFailures;

	public GiveTask(CompanionEntity companion, Predicate<ItemStack> matcher, String itemLabel, int count, @Nullable PlayerEntity recipient) {
		super(companion);
		this.matcher = matcher;
		this.itemLabel = itemLabel;
		this.count = count <= 0 ? Integer.MAX_VALUE : count;
		this.recipient = recipient;
	}

	@Override
	protected Status start() {
		if (c.countItems(matcher) == 0) return fail("I don't have any " + itemLabel);
		remaining = count;
		return Status.RUNNING;
	}

	@Override
	protected Status tick() {
		if (ticks > 20 * 60) return fail("couldn't get to " + recipientName());
		if (recipient != null) {
			if (!recipient.isAlive() || recipient.getWorld() != c.getWorld()) return fail(recipientName() + " isn't around");
			double dist = c.distanceTo(recipient);
			if (dist > 3.0) {
				CompanionMovement.Status st = c.getMovement().moveTo(recipient.getPos(), dist > 10 ? CompanionEntity.SPRINT_SPEED : CompanionEntity.WALK_SPEED, 2.2);
				if (st == CompanionMovement.Status.FAILED && ++moveFailures > 3 && dist > 8) return fail("couldn't reach " + recipientName());
				return Status.RUNNING;
			}
			c.getLookControl().lookAt(recipient, 30.0F, 30.0F);
		}
		if (throwCooldown > 0) {
			throwCooldown--;
			return Status.RUNNING;
		}
		ItemStack stack = takeNext();
		if (stack.isEmpty()) return finish();
		given.merge(ItemUtil.id(stack.getItem()), stack.getCount(), Integer::sum);
		c.throwStack(stack, recipient);
		throwCooldown = 5;
		if (remaining <= 0) return finish();
		return Status.RUNNING;
	}

	private ItemStack takeNext() {
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (!s.isEmpty() && matcher.test(s)) {
				ItemStack out = s.split(Math.min(remaining, s.getCount()));
				remaining -= out.getCount();
				c.getInventory().markDirty();
				return out;
			}
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack s = c.getEquippedStack(slot);
			if (!s.isEmpty() && matcher.test(s)) {
				ItemStack out = s.split(Math.min(remaining, s.getCount()));
				remaining -= out.getCount();
				if (s.isEmpty()) c.equipStack(slot, ItemStack.EMPTY);
				return out;
			}
		}
		return ItemStack.EMPTY;
	}

	private Status finish() {
		if (given.isEmpty()) return fail("I don't have any " + itemLabel);
		StringBuilder sb = new StringBuilder();
		given.forEach((k, v) -> {
			if (!sb.isEmpty()) sb.append(", ");
			sb.append(v).append(" ").append(k);
		});
		return success((recipient != null ? "gave " + recipientName() + " " : "dropped ") + sb);
	}

	private String recipientName() {
		return recipient == null ? "nobody" : recipient.getGameProfile().getName();
	}

	@Override
	public String describe() {
		return recipient != null ? "giving " + itemLabel + " to " + recipientName() : "dropping " + itemLabel;
	}
}
