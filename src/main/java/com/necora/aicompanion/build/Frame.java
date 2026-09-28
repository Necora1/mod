package com.necora.aicompanion.build;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Local build coordinates: u goes to the right, v goes forward (into the building), y goes up.
 * The origin is the front-left corner of the footprint at floor level.
 */
public record Frame(BlockPos origin, Direction forward) {
	public Direction right() {
		return forward.rotateYClockwise();
	}

	public Direction left() {
		return forward.rotateYCounterclockwise();
	}

	public Direction back() {
		return forward.getOpposite();
	}

	public BlockPos at(int u, int y, int v) {
		return origin.offset(right(), u).offset(forward, v).up(y);
	}

	/** A frame whose footprint of the given width is centered in front of {@code feet}, starting {@code gap} blocks ahead. */
	public static Frame inFrontOf(BlockPos feet, Direction facing, int width, int gap) {
		Direction right = facing.rotateYClockwise();
		BlockPos origin = feet.offset(facing, gap).offset(right, -(width / 2));
		return new Frame(origin, facing);
	}

	/** A frame whose footprint is centered on {@code center}. */
	public static Frame centeredOn(BlockPos center, Direction facing, int width, int depth) {
		Direction right = facing.rotateYClockwise();
		BlockPos origin = center.offset(right, -(width / 2)).offset(facing, -(depth / 2));
		return new Frame(origin, facing);
	}
}
