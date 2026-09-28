package com.necora.aicompanion.entity;

import com.necora.aicompanion.util.WorldUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Walking (vanilla pathfinding with stuck detection and long-distance waypoints) plus
 * creative-style flight. Tasks call one of the moveTo methods every tick while they want to move.
 */
public class CompanionMovement {
	public enum Status {MOVING, ARRIVED, FAILED}

	private static final double FLY_SPEED = 0.55;

	private final CompanionEntity c;
	private boolean flying;
	@Nullable
	private Vec3d flyTarget;
	private double flySpeedMul = 1.0;

	@Nullable
	private Vec3d requested;
	private int repathCooldown;
	private int pathFailures;
	private int stuckTicks;
	private Vec3d stuckCheckPos = Vec3d.ZERO;
	private int lastMoveCallAge = -100;
	private boolean sprinting;

	public CompanionMovement(CompanionEntity companion) {
		this.c = companion;
	}

	public boolean isFlying() {
		return flying;
	}

	public void startFlying() {
		if (flying) return;
		flying = true;
		c.getNavigation().stop();
		c.setNoGravity(true);
		c.setFlyingTracked(true);
		c.setVelocity(c.getVelocity().add(0, 0.25, 0));
	}

	public void land() {
		flying = false;
		flyTarget = null;
		c.setNoGravity(false);
		c.setFlyingTracked(false);
	}

	/** Called every tick by the entity. */
	public void tick() {
		boolean movedThisTick = c.age - lastMoveCallAge <= 1;
		if (!movedThisTick) {
			// Nobody is asking us to move: stop sprinting, hover or stand.
			if (sprinting) setSprinting(false);
			flyTarget = null;
			stuckTicks = 0;
		}
		if (flying) {
			c.fallDistance = 0;
			if (!c.isCreativeMode()) {
				land();
				return;
			}
			if (flyTarget != null) {
				Vec3d d = flyTarget.subtract(c.getPos());
				double len = d.length();
				double speed = FLY_SPEED * flySpeedMul;
				Vec3d desired = len < 0.05 ? Vec3d.ZERO : d.multiply(Math.min(speed, len * 0.35) / len);
				Vec3d v = c.getVelocity();
				v = v.add(desired.subtract(v).multiply(0.35));
				if (c.horizontalCollision && len > 0.8) v = new Vec3d(v.x, Math.max(v.y, 0.3), v.z);
				c.setVelocity(v);
				if (Math.abs(d.x) + Math.abs(d.z) > 0.3) {
					float yaw = (float) (MathHelper.atan2(d.z, d.x) * (180F / Math.PI)) - 90.0F;
					c.setYaw(yaw);
					c.setBodyYaw(yaw);
				}
			}
		}
	}

	private void markCalled() {
		lastMoveCallAge = c.age;
	}

	public void stop() {
		c.getNavigation().stop();
		requested = null;
		flyTarget = null;
		setSprinting(false);
	}

	private void setSprinting(boolean s) {
		sprinting = s;
		c.setSprinting(s);
	}

	/** Walks (or flies in creative, when useful) toward a point. */
	public Status moveTo(Vec3d target, double speed, double arriveDistance) {
		markCalled();
		Vec3d pos = c.getPos();
		double distSq = pos.squaredDistanceTo(target);
		if (distSq <= arriveDistance * arriveDistance) {
			if (!flying) c.getNavigation().stop();
			flyTarget = null;
			requested = null;
			setSprinting(false);
			return Status.ARRIVED;
		}
		if (flying) {
			return flyTo(target, speed);
		}
		boolean sprint = speed > CompanionEntity.WALK_SPEED + 0.01 && !c.isTouchingWater();
		if (sprint != sprinting) setSprinting(sprint);
		EntityNavigation nav = c.getNavigation();

		boolean newTarget = requested == null || requested.squaredDistanceTo(target) > 2.25;
		if (newTarget) {
			pathFailures = 0;
			stuckTicks = 0;
		}
		if (repathCooldown > 0) repathCooldown--;
		if (newTarget || (nav.isIdle() && repathCooldown <= 0)) {
			Vec3d goal = waypointToward(target);
			Path path = nav.findPathTo(BlockPos.ofFloored(goal), 0);
			requested = target;
			repathCooldown = 10;
			if (path == null || (path.getLength() <= 1 && !path.reachesTarget())) {
				pathFailures++;
				if (c.isCreativeMode() && pathFailures >= 2) {
					startFlying();
					return flyTo(target, speed);
				}
				if (pathFailures > 4) return Status.FAILED;
				return Status.MOVING;
			}
			nav.startMovingAlong(path, speed);
		} else {
			nav.setSpeed(speed);
		}

		// stuck detection
		if (++stuckTicks % 40 == 0) {
			if (stuckCheckPos.squaredDistanceTo(pos) < 0.2) {
				if (c.isOnGround()) c.getJumpControl().setActive();
				nav.stop();
				repathCooldown = 0;
				pathFailures++;
				if (c.isCreativeMode() && pathFailures >= 2) {
					startFlying();
					return flyTo(target, speed);
				}
				if (pathFailures > 5) return Status.FAILED;
			}
			stuckCheckPos = pos;
		}
		return Status.MOVING;
	}

	/** Pathfinding only reaches ~48 blocks: aim at a point on the way for long trips. */
	private Vec3d waypointToward(Vec3d target) {
		Vec3d pos = c.getPos();
		Vec3d flat = new Vec3d(target.x - pos.x, 0, target.z - pos.z);
		double dist = flat.length();
		if (dist <= 36) return target;
		Vec3d step = pos.add(flat.multiply(30.0 / dist));
		int x = MathHelper.floor(step.x);
		int z = MathHelper.floor(step.z);
		int y = WorldUtil.surfaceY(c.getWorld(), x, z);
		return new Vec3d(x + 0.5, y, z + 0.5);
	}

	/** Moves so that the feet end up in the given block. */
	public Status moveToBlock(BlockPos feet, double speed) {
		Vec3d center = Vec3d.ofBottomCenter(feet);
		markCalled();
		BlockPos current = c.getBlockPos();
		if (current.equals(feet) && (c.isOnGround() || flying || c.isTouchingWater())) {
			if (!flying) c.getNavigation().stop();
			flyTarget = null;
			requested = null;
			return Status.ARRIVED;
		}
		if (flying) {
			Status s = flyTo(center.add(0, 0.1, 0), speed);
			if (c.getPos().squaredDistanceTo(center.add(0, 0.1, 0)) < 0.2) return Status.ARRIVED;
			return s;
		}
		return moveTo(center, speed, 0.45);
	}

	public Status moveToEntity(Entity target, double speed, double distance) {
		return moveTo(target.getPos(), speed, distance);
	}

	/** Creative flight toward a point. */
	public Status flyTo(Vec3d target, double speedMul) {
		markCalled();
		if (!c.isCreativeMode()) return Status.FAILED;
		if (!flying) startFlying();
		flyTarget = target;
		flySpeedMul = Math.max(0.5, speedMul);
		if (c.getPos().squaredDistanceTo(target) < 0.35 * 0.35) {
			return Status.ARRIVED;
		}
		return Status.MOVING;
	}

	/** Hover in place (creative) - used while building from the air. */
	public void hover() {
		markCalled();
		flyTarget = null;
	}

	/** Returns true if a walking path to (near) the block exists. Expensive-ish; don't spam it. */
	public boolean canWalkTo(BlockPos feet) {
		Path path = c.getNavigation().findPathTo(feet, 0);
		return path != null && path.reachesTarget();
	}

	@Nullable
	public Path findPath(BlockPos feet) {
		return c.getNavigation().findPathTo(feet, 0);
	}

	public void followPath(Path path, double speed) {
		markCalled();
		requested = Vec3d.ofBottomCenter(path.getTarget());
		c.getNavigation().startMovingAlong(path, speed);
	}

	/** Keep the current path going this tick (counts as "wants to move"). */
	public void keepAlive() {
		markCalled();
	}

	public boolean isIdle() {
		return c.getNavigation().isIdle() && flyTarget == null;
	}
}
