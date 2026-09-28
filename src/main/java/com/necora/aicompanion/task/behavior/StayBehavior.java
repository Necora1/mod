package com.necora.aicompanion.task.behavior;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.task.Task;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Waits at a spot and walks back if pushed away. */
public class StayBehavior extends Task {
	private final BlockPos pos;

	public StayBehavior(CompanionEntity companion, BlockPos pos) {
		super(companion);
		this.pos = pos.toImmutable();
	}

	public BlockPos getPos() {
		return pos;
	}

	@Override
	public boolean isBehavior() {
		return true;
	}

	@Override
	protected Status tick() {
		Vec3d center = Vec3d.ofBottomCenter(pos);
		if (c.getPos().squaredDistanceTo(center) > 2.5 * 2.5) {
			c.getMover().moveTo(center, CompanionEntity.WALK_SPEED, 1.0);
		} else if (c.getMover().isFlying() && !c.getWorld().getBlockState(c.getBlockPos().down()).isAir()) {
			c.getMover().land();
		} else {
			PlayerSocial.idleLook(c, c.getOwner());
		}
		return Status.RUNNING;
	}

	@Override
	public String describe() {
		return "waiting at " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}
}
