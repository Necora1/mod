package com.necora.aicompanion.task;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.BiPredicate;

/** Finds the nearest matching block around a point, spreading the work over several ticks. */
public class BlockScanner {
	private static final int MAX_RADIUS = 40;
	private static final int MAX_DY = 20;
	private static long[] offsets;

	private final BlockPos origin;
	private final int radiusSq;
	private int index;

	public BlockScanner(BlockPos origin, int radius) {
		this.origin = origin.toImmutable();
		int r = Math.min(radius, MAX_RADIUS);
		this.radiusSq = r * r;
		ensureOffsets();
	}

	private static synchronized void ensureOffsets() {
		if (offsets != null) return;
		int r = MAX_RADIUS;
		long[] tmp = new long[(2 * r + 1) * (2 * r + 1) * (2 * MAX_DY + 1)];
		int n = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -MAX_DY; dy <= MAX_DY; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					int d2 = dx * dx + dy * dy + dz * dz;
					if (d2 > r * r) continue;
					long packed = ((long) (dx + 128) << 16) | ((long) (dy + 128) << 8) | (dz + 128);
					tmp[n++] = ((long) d2 << 24) | packed;
				}
			}
		}
		long[] arr = Arrays.copyOf(tmp, n);
		Arrays.sort(arr);
		offsets = arr;
	}

	public boolean isFinished() {
		return index >= offsets.length || (offsets[index] >>> 24) > radiusSq;
	}

	/** Checks up to {@code budget} positions. Returns the first match, or null (call again next tick). */
	@Nullable
	public BlockPos next(World world, int budget, BiPredicate<BlockPos, BlockState> test) {
		BlockPos.Mutable m = new BlockPos.Mutable();
		int end = Math.min(offsets.length, index + budget);
		while (index < end) {
			long v = offsets[index++];
			if ((v >>> 24) > radiusSq) {
				index = offsets.length;
				return null;
			}
			int dx = (int) ((v >> 16) & 0xFF) - 128;
			int dy = (int) ((v >> 8) & 0xFF) - 128;
			int dz = (int) (v & 0xFF) - 128;
			m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
			if (!world.isInBuildLimit(m) || !world.isChunkLoaded(m.getX() >> 4, m.getZ() >> 4)) continue;
			BlockState state = world.getBlockState(m);
			if (state.isAir()) continue;
			if (test.test(m, state)) return m.toImmutable();
		}
		return null;
	}
}
