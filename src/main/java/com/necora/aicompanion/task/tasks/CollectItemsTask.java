package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.CompanionMovement;
import com.necora.aicompanion.task.Task;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Walks over dropped items nearby to pick them up. */
public class CollectItemsTask extends Task {
	private final int radius;
	private final Set<UUID> unreachable = new HashSet<>();
	private int collectedStart = -1;
	private ItemEntity current;
	private int currentTicks;

	public CollectItemsTask(CompanionEntity companion, int radius) {
		super(companion);
		this.radius = Math.max(3, Math.min(radius, 32));
	}

	@Override
	protected Status start() {
		collectedStart = countAll();
		return Status.RUNNING;
	}

	private int countAll() {
		return c.countItems(s -> true);
	}

	@Override
	protected Status tick() {
		if (ticks > 20 * 90) return done();
		if (c.isInventoryFull()) return done();
		if (current == null || !current.isAlive() || ++currentTicks > 200) {
			if (current != null && current.isAlive()) unreachable.add(current.getUuid());
			Box box = c.getBoundingBox().expand(radius, 6, radius);
			List<ItemEntity> items = c.getWorld().getEntitiesByClass(ItemEntity.class, box,
					i -> i.isAlive() && !unreachable.contains(i.getUuid()) && (i.owner == null || i.owner.equals(c.getUuid())) && i.getOwner() != c);
			if (items.isEmpty()) return done();
			items.sort(Comparator.comparingDouble(c::squaredDistanceTo));
			current = items.get(0);
			currentTicks = 0;
		}
		CompanionMovement.Status st = c.getMover().moveTo(current.getPos(), CompanionEntity.WALK_SPEED, 0.4);
		if (st == CompanionMovement.Status.FAILED) {
			unreachable.add(current.getUuid());
			current = null;
		}
		return Status.RUNNING;
	}

	private Status done() {
		int got = Math.max(0, countAll() - collectedStart);
		return success(got > 0 ? "picked up " + got + " items" : "there was nothing to pick up");
	}

	@Override
	public String describe() {
		return "picking up items";
	}
}
