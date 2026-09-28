package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.util.ItemUtil;

public class EatTask extends Task {
	private String food = "";

	public EatTask(CompanionEntity companion) {
		super(companion);
	}

	@Override
	protected Status start() {
		if (c.isCreativeMode()) return success("I'm not hungry (creative)");
		if (c.getFoodLevel() >= 20) return success("I'm not hungry");
		if (c.countItems(ItemUtil::isGoodFood) == 0) return fail("I don't have any food");
		if (!c.startEating()) return fail("I don't have any food");
		food = ItemUtil.id(c.getMainHandStack().getItem());
		return Status.RUNNING;
	}

	@Override
	protected Status tick() {
		if (c.isUsingItem()) return Status.RUNNING;
		if (ticks > 200) return fail("got interrupted while eating");
		return success("ate " + food + " (hunger " + c.getFoodLevel() + "/20)");
	}

	@Override
	public boolean reportResult() {
		return false;
	}

	@Override
	public String describe() {
		return "eating";
	}
}
