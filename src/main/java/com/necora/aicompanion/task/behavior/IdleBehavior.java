package com.necora.aicompanion.task.behavior;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.task.Task;

/** Stands around (after "stop"). Still defends itself and looks at people. */
public class IdleBehavior extends Task {
	public IdleBehavior(CompanionEntity companion) {
		super(companion);
	}

	@Override
	public boolean isBehavior() {
		return true;
	}

	@Override
	protected Status tick() {
		PlayerSocial.idleLook(c, c.getOwner());
		return Status.RUNNING;
	}

	@Override
	public String describe() {
		return "standing around";
	}
}
