package com.necora.aicompanion.task;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.util.WorldUtil;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class ReachHelper {
	private ReachHelper() {
	}

	/** Finds a walkable spot from which {@code target} is within {@code reach}. */
	@Nullable
	public static BlockPos findStandSpot(CompanionEntity c, BlockPos target, double reach, Set<BlockPos> bad) {
		World world = c.getWorld();
		int r = (int) Math.ceil(reach);
		List<BlockPos> candidates = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -r - 1; dy <= r - 1; dy++) {
					BlockPos p = target.add(dx, dy, dz);
					if (p.equals(target) || p.up().equals(target) || bad.contains(p)) continue;
					Vec3d eye = new Vec3d(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
					if (CompanionEntity.squaredDistanceToBlock(eye, target) > reach * reach) continue;
					if (!WorldUtil.isStandable(world, p)) continue;
					candidates.add(p);
				}
			}
		}
		BlockPos here = c.getBlockPos();
		if (candidates.contains(here)) return here;
		candidates.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(here)));
		int tries = 0;
		for (BlockPos p : candidates) {
			if (tries++ >= 6) break;
			Path path = c.getMovement().findPath(p);
			if (path != null && path.reachesTarget()) return p;
			bad.add(p);
		}
		return null;
	}
}
