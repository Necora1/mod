package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.task.Task;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/** Hunts down mobs of a kind (or all hostiles nearby, or one specific entity). */
public class AttackTask extends Task {
	public enum Mode {HOSTILES, TYPE, ENTITY}

	private final Mode mode;
	@Nullable
	private final EntityType<?> type;
	@Nullable
	private final LivingEntity specific;
	private final int count;
	private final int radius;
	private final String label;
	@Nullable
	private LivingEntity current;
	private int kills;
	private int searchesWithoutTarget;
	private final java.util.Set<java.util.UUID> skip = new java.util.HashSet<>();

	public AttackTask(CompanionEntity companion, Mode mode, @Nullable EntityType<?> type, @Nullable LivingEntity specific, int count, int radius, String label) {
		super(companion);
		this.mode = mode;
		this.type = type;
		this.specific = specific;
		this.count = count <= 0 ? 50 : count;
		this.radius = radius;
		this.label = label;
	}

	@Override
	protected Status tick() {
		if (ticks > 20 * 120) return finish();
		if (current != null) {
			if (!current.isAlive()) {
				kills++;
				current = null;
			} else if (c.getCombat().getTarget() != current) {
				// combat gave up on it (unreachable) or switched to self-defence
				skip.add(current.getUuid());
				current = null;
			}
		}
		if (kills >= count) return finish();
		if (current == null) {
			current = findTarget();
			if (current == null) {
				if (++searchesWithoutTarget > 3) return finish();
				return Status.RUNNING;
			}
			searchesWithoutTarget = 0;
			c.getCombat().setForcedTarget(current);
		}
		return Status.RUNNING;
	}

	@Nullable
	private LivingEntity findTarget() {
		if (mode == Mode.ENTITY) {
			return specific != null && specific.isAlive() && kills == 0 && !skip.contains(specific.getUuid()) ? specific : null;
		}
		Box box = c.getBoundingBox().expand(radius, 10, radius);
		List<LivingEntity> list = c.getWorld().getEntitiesByClass(LivingEntity.class, box, e -> {
			if (!e.isAlive() || e == c || c.isFriendly(e) || e instanceof PlayerEntity || skip.contains(e.getUuid())) return false;
			if (mode == Mode.HOSTILES) return e instanceof Monster;
			return e.getType() == type;
		});
		list.sort(Comparator.comparingDouble(c::squaredDistanceTo));
		return list.isEmpty() ? null : list.get(0);
	}

	private Status finish() {
		c.getCombat().setForcedTarget(null);
		if (kills == 0) {
			return mode == Mode.ENTITY && specific != null && !specific.isAlive()
					? success("took care of " + label)
					: fail("couldn't find any " + label + " to fight");
		}
		return success("killed " + kills + " " + label);
	}

	@Override
	public void stop() {
		c.getCombat().setForcedTarget(null);
	}

	@Override
	public String describe() {
		return "fighting " + label + (kills > 0 ? " (" + kills + " down)" : "");
	}
}
