package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.task.Crafting;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.util.ItemUtil;
import net.minecraft.item.Item;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;

import java.util.List;

/** Crafts items from the inventory, making intermediate items (planks, sticks...) on the way. */
public class CraftTask extends Task {
	private final List<Item> candidates;
	private final int count;
	private List<Crafting.Step> steps;
	private Item chosen;
	private int index;

	public CraftTask(CompanionEntity companion, List<Item> candidates, int count) {
		super(companion);
		this.candidates = candidates;
		this.count = Math.max(1, Math.min(count, 256));
	}

	@Override
	protected Status start() {
		for (Item item : candidates) {
			List<Crafting.Step> plan = Crafting.plan(c, item, count);
			if (plan != null && !plan.isEmpty()) {
				steps = plan;
				chosen = item;
				return Status.RUNNING;
			}
		}
		String name = candidates.isEmpty() ? "that" : ItemUtil.id(candidates.get(0));
		return fail("can't craft " + name + " - missing ingredients (or no recipe)");
	}

	@Override
	protected Status tick() {
		if (ticks % 8 != 0) return Status.RUNNING;
		if (index >= steps.size()) {
			return success("crafted " + count + " " + ItemUtil.id(chosen) + " (now have " + c.countItem(chosen) + ")");
		}
		Crafting.Step step = steps.get(index++);
		if (!Crafting.apply(c, step)) return fail("lost some ingredients while crafting " + ItemUtil.id(chosen));
		c.swingHand(Hand.MAIN_HAND);
		c.getWorld().playSound(null, c.getBlockPos(), SoundEvents.UI_LOOM_TAKE_RESULT, SoundCategory.PLAYERS, 0.3F, 1.2F);
		return Status.RUNNING;
	}

	@Override
	public String describe() {
		return "crafting " + (chosen != null ? ItemUtil.id(chosen) : candidates.isEmpty() ? "something" : ItemUtil.id(candidates.get(0)));
	}
}
