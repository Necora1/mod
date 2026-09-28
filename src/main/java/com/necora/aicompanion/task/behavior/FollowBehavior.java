package com.necora.aicompanion.task.behavior;

import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.CompanionMovement;
import com.necora.aicompanion.task.Task;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Follows a player around like a friend would: keeps some distance, sprints to catch up, flies in creative. */
public class FollowBehavior extends Task {
	protected final UUID targetUuid;
	private int failedTicks;
	private final PlayerSocial social = new PlayerSocial();

	public FollowBehavior(CompanionEntity companion, UUID target) {
		super(companion);
		this.targetUuid = target;
	}

	public UUID getTargetUuid() {
		return targetUuid;
	}

	@Override
	public boolean isBehavior() {
		return true;
	}

	@Nullable
	protected ServerPlayerEntity target() {
		if (!(c.getWorld() instanceof ServerWorld sw)) return null;
		return sw.getServer().getPlayerManager().getPlayer(targetUuid);
	}

	/** How close we try to stay. */
	protected double followDistance() {
		return 5.0;
	}

	@Override
	protected Status tick() {
		ServerPlayerEntity p = target();
		CompanionMovement movement = c.getMovement();
		if (p == null || p.isSpectator() || !p.isAlive()) {
			PlayerSocial.idleLook(c, null);
			return Status.RUNNING;
		}
		CompanionConfig cfg = CompanionConfig.get();
		if (p.getWorld() != c.getWorld()) {
			if (cfg.followAcrossDimensions) c.teleportNear(p);
			return Status.RUNNING;
		}
		double dist = c.distanceTo(p);
		if (cfg.teleportToOwnerDistance > 0 && dist > cfg.teleportToOwnerDistance) {
			c.teleportNear(p);
			return Status.RUNNING;
		}

		if (c.isCreativeMode() && (p.getAbilities().flying || (movement.isFlying() && dist > 4))) {
			Vec3d look = p.getRotationVector();
			Vec3d spot = p.getPos().add(-look.x * 2.5 + 1.2, 0.4, -look.z * 2.5);
			if (c.getPos().squaredDistanceTo(spot) > 2.0) {
				movement.flyTo(spot, dist > 10 ? 1.8 : 1.0);
			} else {
				movement.hover();
			}
			c.getLookControl().lookAt(p, 20.0F, 20.0F);
			return Status.RUNNING;
		}
		if (movement.isFlying() && !p.getAbilities().flying && dist < 6) {
			movement.land();
		}

		double keep = followDistance();
		if (dist > keep) {
			double speed = dist > 12 || p.isSprinting() ? CompanionEntity.SPRINT_SPEED : CompanionEntity.WALK_SPEED;
			CompanionMovement.Status st = movement.moveTo(p.getPos(), speed, keep - 1.5);
			if (st == CompanionMovement.Status.FAILED) {
				failedTicks++;
				if (failedTicks > 60 && dist > 16 && cfg.teleportToOwnerDistance > 0) {
					c.teleportNear(p);
					failedTicks = 0;
				}
			} else {
				failedTicks = 0;
			}
		} else {
			social.tick(c, p);
		}
		return Status.RUNNING;
	}

	@Override
	public String describe() {
		ServerPlayerEntity p = target();
		return "following " + (p == null ? "(offline player)" : p.getGameProfile().getName());
	}
}
