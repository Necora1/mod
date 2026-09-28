package com.necora.aicompanion.task.behavior;

import com.necora.aicompanion.entity.CompanionEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Bodyguard mode: stays close to a player and kills hostile mobs that come near them. */
public class ProtectBehavior extends FollowBehavior {
	private final double radius;

	public ProtectBehavior(CompanionEntity companion, UUID target, double radius) {
		super(companion, target);
		this.radius = Math.max(4, Math.min(32, radius));
	}

	public double getRadius() {
		return radius;
	}

	@Nullable
	public LivingEntity getGuarded() {
		return target();
	}

	@Override
	protected double followDistance() {
		return 3.5;
	}

	@Override
	public String describe() {
		ServerPlayerEntity p = target();
		return "guarding " + (p == null ? "(offline player)" : p.getGameProfile().getName()) + " (radius " + (int) radius + ")";
	}
}
