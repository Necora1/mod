package com.necora.aicompanion.task;

import com.necora.aicompanion.entity.CompanionEntity;

/**
 * Something the companion does over several ticks. Finite tasks (build, mine...) end with
 * SUCCESS or FAILED; behaviours (follow, stay, protect) run forever while the queue is empty.
 */
public abstract class Task {
	public enum Status {RUNNING, SUCCESS, FAILED}

	protected final CompanionEntity c;
	private boolean started;
	private String resultMessage = "";
	protected int ticks;

	protected Task(CompanionEntity companion) {
		this.c = companion;
	}

	public final Status update() {
		if (!started) {
			started = true;
			Status s = start();
			if (s != Status.RUNNING) return s;
		}
		ticks++;
		return tick();
	}

	protected Status start() {
		return Status.RUNNING;
	}

	protected abstract Status tick();

	/** Called when the task ends or is interrupted. Clean up movement, break progress, etc. */
	public void stop() {
	}

	/** Present-tense description for status lines: "building a house (40/120 blocks)". */
	public abstract String describe();

	public boolean isBehavior() {
		return false;
	}

	/** Whether the language model should hear about this task finishing. */
	public boolean reportResult() {
		return true;
	}

	public String getResultMessage() {
		return resultMessage;
	}

	protected Status success(String message) {
		this.resultMessage = message;
		return Status.SUCCESS;
	}

	protected Status fail(String message) {
		this.resultMessage = message;
		return Status.FAILED;
	}
}
