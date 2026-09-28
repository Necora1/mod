package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.task.Target;
import com.necora.aicompanion.task.Task;
import net.minecraft.util.math.Vec3d;

public class LookTask extends Task {
	private final Target target;
	private final int duration;

	public LookTask(CompanionEntity companion, Target target, int durationTicks) {
		super(companion);
		this.target = target;
		this.duration = durationTicks;
	}

	@Override
	protected Status tick() {
		Vec3d p = target.position();
		if (p == null) return Status.SUCCESS;
		if (target.entity() != null) c.getLookControl().lookAt(target.entity(), 30.0F, 30.0F);
		else c.getLookControl().lookAt(p.x, p.y + 0.5, p.z, 30.0F, 30.0F);
		c.getMover().keepAlive();
		return ticks >= duration ? Status.SUCCESS : Status.RUNNING;
	}

	@Override
	public boolean reportResult() {
		return false;
	}

	@Override
	public String describe() {
		return "looking at " + target.describe();
	}
}
