package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.task.Task;

public class WaitTask extends Task {
	private final int duration;

	public WaitTask(CompanionEntity companion, int durationTicks) {
		super(companion);
		this.duration = Math.max(1, Math.min(durationTicks, 20 * 600));
	}

	@Override
	protected Status tick() {
		return ticks >= duration ? Status.SUCCESS : Status.RUNNING;
	}

	@Override
	public boolean reportResult() {
		return false;
	}

	@Override
	public String describe() {
		return "waiting " + (duration / 20) + "s";
	}
}
