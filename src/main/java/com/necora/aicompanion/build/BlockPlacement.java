package com.necora.aicompanion.build;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * One step of a build: put {@code state} at {@code pos}, or clear the spot if the state is air.
 *
 * @param phase    0 = clearing, 1 = foundation/floor, 2 = walls, 3 = roof, 4 = details (doors, torches, furniture)
 * @param optional skipped silently if the companion doesn't have the item (furniture, windows)
 */
public record BlockPlacement(BlockPos pos, BlockState state, int phase, boolean optional) {
	public static final int PHASE_CLEAR = 0;
	public static final int PHASE_FOUNDATION = 1;
	public static final int PHASE_STRUCTURE = 2;
	public static final int PHASE_ROOF = 3;
	public static final int PHASE_DETAIL = 4;

	public boolean isClear() {
		return state.isAir();
	}
}
