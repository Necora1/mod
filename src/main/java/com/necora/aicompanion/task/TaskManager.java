package com.necora.aicompanion.task;

import com.necora.aicompanion.AICompanionMod;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.task.behavior.FollowBehavior;
import com.necora.aicompanion.task.behavior.IdleBehavior;
import com.necora.aicompanion.task.behavior.ProtectBehavior;
import com.necora.aicompanion.task.behavior.StayBehavior;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/** Runs queued tasks one after another, falling back to the current behaviour when idle. */
public class TaskManager {
	private final CompanionEntity c;
	private final Deque<Task> queue = new ArrayDeque<>();
	@Nullable
	private Task current;
	@Nullable
	private Task behavior;
	@Nullable
	private NbtCompound pendingBehaviorNbt;

	public TaskManager(CompanionEntity companion) {
		this.c = companion;
	}

	public void tick() {
		if (behavior == null) behavior = restoreBehavior();
		int guard = 0;
		while (current == null && !queue.isEmpty() && guard++ < 16) {
			Task next = queue.poll();
			if (next.isBehavior()) {
				setBehavior(next);
			} else {
				current = next;
			}
		}
		if (current != null) {
			Task.Status status;
			Task running = current;
			try {
				status = running.update();
			} catch (Exception e) {
				AICompanionMod.LOGGER.error("Companion task '{}' crashed", running.describe(), e);
				status = Task.Status.FAILED;
			}
			if (status != Task.Status.RUNNING && current == running) {
				current = null;
				running.stop();
				c.getMover().stop();
				onFinished(running, status == Task.Status.SUCCESS);
			}
		} else if (behavior != null) {
			try {
				behavior.update();
			} catch (Exception e) {
				AICompanionMod.LOGGER.error("Companion behaviour crashed", e);
				behavior = new IdleBehavior(c);
			}
		}
	}

	private void onFinished(Task task, boolean success) {
		CompanionBrain brain = CompanionManager.brainOf(c);
		if (brain != null) brain.onTaskFinished(task, success, task.getResultMessage());
	}

	/** Replace whatever is going on with a new plan. */
	public void replaceAll(List<Task> tasks) {
		clearQueue();
		queue.addAll(tasks);
	}

	public void enqueue(List<Task> tasks) {
		queue.addAll(tasks);
	}

	public void clearQueue() {
		if (current != null) {
			current.stop();
			current = null;
		}
		queue.clear();
		c.getMover().stop();
	}

	/** "stop": drop everything and just stand around. */
	public void stopEverything() {
		clearQueue();
		c.getCombat().stop();
		setBehavior(new IdleBehavior(c));
	}

	public void setBehavior(Task newBehavior) {
		if (behavior != null && behavior != newBehavior) behavior.stop();
		behavior = newBehavior;
	}

	@Nullable
	public Task getBehavior() {
		return behavior;
	}

	@Nullable
	public Task getCurrent() {
		return current;
	}

	public List<Task> getQueue() {
		return new ArrayList<>(queue);
	}

	public boolean isBusy() {
		return current != null || !queue.isEmpty();
	}

	public boolean isFollowing() {
		return behavior instanceof FollowBehavior || behavior instanceof ProtectBehavior;
	}

	public String describe() {
		StringBuilder sb = new StringBuilder();
		if (current != null) {
			sb.append(current.describe());
			if (!queue.isEmpty()) {
				sb.append("; queued: ");
				int i = 0;
				for (Task t : queue) {
					if (i++ > 0) sb.append(", ");
					if (i > 4) {
						sb.append("...");
						break;
					}
					sb.append(t.describe());
				}
			}
			if (behavior != null) sb.append(" (afterwards: ").append(behavior.describe()).append(")");
		} else if (behavior != null) {
			sb.append(behavior.describe());
		} else {
			sb.append("nothing");
		}
		return sb.toString();
	}

	@Nullable
	public LivingEntity getGuardTarget() {
		if (behavior instanceof ProtectBehavior protect) return protect.getGuarded();
		return null;
	}

	public double getGuardRadius() {
		if (behavior instanceof ProtectBehavior protect) return protect.getRadius();
		return 12;
	}

	/** Sneak + right-click: toggles between following the player and waiting. */
	public void toggleFollowStay(ServerPlayerEntity player) {
		if (behavior instanceof StayBehavior || behavior instanceof IdleBehavior) {
			clearQueue();
			setBehavior(new FollowBehavior(c, player.getUuid()));
			c.queueChat(pick("coming", "right behind you", "ok lets go", "following you"), 0);
		} else {
			clearQueue();
			setBehavior(new StayBehavior(c, c.getBlockPos()));
			c.queueChat(pick("ok I'll wait here", "staying put", "alright, waiting here", "k, I'll stay"), 0);
		}
	}

	private String pick(String... options) {
		return options[c.getRandom().nextInt(options.length)];
	}

	private Task restoreBehavior() {
		NbtCompound nbt = pendingBehaviorNbt;
		pendingBehaviorNbt = null;
		if (nbt != null) {
			String type = nbt.getString("Type");
			UUID target = nbt.containsUuid("Target") ? nbt.getUuid("Target") : c.getOwnerUuid();
			BlockPos pos = new BlockPos(nbt.getInt("X"), nbt.getInt("Y"), nbt.getInt("Z"));
			switch (type) {
				case "stay":
					return new StayBehavior(c, pos);
				case "idle":
					return new IdleBehavior(c);
				case "protect":
					if (target != null) return new ProtectBehavior(c, target, nbt.contains("Radius") ? nbt.getDouble("Radius") : 12);
					break;
				case "follow":
					if (target != null) return new FollowBehavior(c, target);
					break;
				default:
					break;
			}
		}
		return c.getOwnerUuid() != null ? new FollowBehavior(c, c.getOwnerUuid()) : new IdleBehavior(c);
	}

	public NbtCompound writeBehavior() {
		NbtCompound nbt = new NbtCompound();
		Task b = behavior;
		if (b == null && pendingBehaviorNbt != null) return pendingBehaviorNbt.copy();
		if (b instanceof StayBehavior stay) {
			nbt.putString("Type", "stay");
			nbt.putInt("X", stay.getPos().getX());
			nbt.putInt("Y", stay.getPos().getY());
			nbt.putInt("Z", stay.getPos().getZ());
		} else if (b instanceof ProtectBehavior protect) {
			nbt.putString("Type", "protect");
			nbt.putUuid("Target", protect.getTargetUuid());
			nbt.putDouble("Radius", protect.getRadius());
		} else if (b instanceof FollowBehavior follow) {
			nbt.putString("Type", "follow");
			nbt.putUuid("Target", follow.getTargetUuid());
		} else if (b instanceof IdleBehavior) {
			nbt.putString("Type", "idle");
		}
		return nbt;
	}

	public void readBehavior(NbtCompound nbt) {
		this.pendingBehaviorNbt = nbt.copy();
		this.behavior = null;
	}
}
