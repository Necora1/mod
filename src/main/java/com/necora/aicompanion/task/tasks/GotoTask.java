package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.CompanionMovement;
import com.necora.aicompanion.task.Target;
import com.necora.aicompanion.task.Task;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

public class GotoTask extends Task {
	private final Target target;
	private final double arrive;
	private int failures;
	private int limit;

	public GotoTask(CompanionEntity companion, Target target, double arrive) {
		super(companion);
		this.target = target;
		this.arrive = arrive;
	}

	@Override
	protected Status start() {
		Vec3d p = target.position();
		if (p == null) return fail("couldn't find " + target.describe());
		limit = 20 * (40 + (int) (c.getPos().distanceTo(p) * 0.8));
		return Status.RUNNING;
	}

	@Override
	protected Status tick() {
		Vec3d p = target.position();
		Entity e = target.entity();
		if (p == null || (e != null && e.getWorld() != c.getWorld())) return fail("lost track of " + target.describe());
		if (ticks > limit) return fail("couldn't get to " + target.describe() + " in time");
		double dist = c.getPos().distanceTo(p);
		CompanionMovement.Status st = c.getMovement().moveTo(p, dist > 10 ? CompanionEntity.SPRINT_SPEED : CompanionEntity.WALK_SPEED, arrive);
		if (st == CompanionMovement.Status.ARRIVED) {
			if (e != null) c.getLookControl().lookAt(e, 30.0F, 30.0F);
			return success("arrived at " + target.describe());
		}
		if (st == CompanionMovement.Status.FAILED && ++failures > 3) {
			return fail("couldn't find a way to " + target.describe() + " (" + (int) dist + " blocks away)");
		}
		return Status.RUNNING;
	}

	@Override
	public boolean reportResult() {
		return false;
	}

	@Override
	public String describe() {
		return "going to " + target.describe();
	}
}
