package com.necora.aicompanion.task.behavior;

import com.necora.aicompanion.entity.CompanionEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** Little idle things players do: look at you when you look at them, crouch back when you crouch at them. */
public class PlayerSocial {
	private boolean lastSneaking;
	private int toggles;
	private int windowStart;
	private int mimicCooldown;

	public void tick(CompanionEntity c, PlayerEntity p) {
		boolean looking = isLookingAt(p, c);
		if (looking || c.age % 60 < 25) {
			c.getLookControl().lookAt(p, 20.0F, 20.0F);
		}
		if (mimicCooldown > 0) mimicCooldown--;
		boolean sneaking = p.isSneaking();
		if (sneaking != lastSneaking) {
			if (c.age - windowStart > 40) {
				windowStart = c.age;
				toggles = 0;
			}
			toggles++;
			lastSneaking = sneaking;
		}
		if (toggles >= 4 && looking && mimicCooldown == 0 && c.distanceTo(p) < 8) {
			// the classic crouch-spam greeting
			c.crouchFor(4 + c.getRandom().nextInt(4));
			if (toggles >= 6) {
				toggles = 0;
				mimicCooldown = 10;
			}
		}
	}

	public static void idleLook(CompanionEntity c, @Nullable PlayerEntity owner) {
		if (owner != null && owner.getWorld() == c.getWorld() && c.squaredDistanceTo(owner) < 12 * 12 && isLookingAt(owner, c)) {
			c.getLookControl().lookAt(owner, 20.0F, 20.0F);
		}
	}

	public static boolean isLookingAt(PlayerEntity p, CompanionEntity c) {
		Vec3d toC = c.getEyePos().subtract(p.getEyePos());
		double len = toC.length();
		if (len < 0.01 || len > 24) return false;
		return p.getRotationVector().dotProduct(toC.multiply(1.0 / len)) > 0.96;
	}
}
