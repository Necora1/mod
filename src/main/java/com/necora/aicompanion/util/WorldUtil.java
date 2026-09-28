package com.necora.aicompanion.util;

import com.necora.aicompanion.config.CompanionConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

public final class WorldUtil {
	private WorldUtil() {
	}

	public static boolean isPassable(World world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.getCollisionShape(world, pos).isEmpty() && !isHarmful(state);
	}

	public static boolean isHarmful(BlockState state) {
		FluidState fluid = state.getFluidState();
		if (fluid.isIn(FluidTags.LAVA)) return true;
		return state.isIn(BlockTags.FIRE) || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.SWEET_BERRY_BUSH)
				|| state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.WITHER_ROSE) || state.isOf(Blocks.MAGMA_BLOCK)
				|| state.isIn(BlockTags.CAMPFIRES);
	}

	/** Something you can stand on: a block with a collision top at least half a block high. */
	public static boolean isSolidFloor(World world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		if (isHarmful(state)) return false;
		VoxelShape shape = state.getCollisionShape(world, pos);
		if (shape.isEmpty()) return false;
		return shape.getMax(Direction.Axis.Y) >= 0.5;
	}

	/** A 2-high air gap with solid ground under it. */
	public static boolean isStandable(World world, BlockPos feet) {
		if (!world.isInBuildLimit(feet)) return false;
		if (!isSolidFloor(world, feet.down())) return false;
		if (!isPassable(world, feet) || !isPassable(world, feet.up())) return false;
		FluidState fluid = world.getFluidState(feet);
		return fluid.isEmpty() || fluid.isIn(FluidTags.WATER) && world.getFluidState(feet.up()).isEmpty();
	}

	public static int surfaceY(World world, int x, int z) {
		return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	/** Finds the nearest standable position in the column around {@code pos} (searching up and down). */
	public static BlockPos findStandableNear(World world, BlockPos pos, int maxDy) {
		if (isStandable(world, pos)) return pos;
		for (int d = 1; d <= maxDy; d++) {
			if (isStandable(world, pos.up(d))) return pos.up(d);
			if (isStandable(world, pos.down(d))) return pos.down(d);
		}
		return null;
	}

	/**
	 * Blocks the companion should never break on its own: unbreakable blocks, anything holding
	 * items/data (chests, furnaces, signs...) unless allowed, and spawners.
	 */
	public static boolean isProtected(World world, BlockPos pos, BlockState state) {
		if (state.isAir()) return false;
		if (state.getHardness(world, pos) < 0) return true;
		if (state.isOf(Blocks.SPAWNER) || state.isOf(Blocks.END_PORTAL_FRAME) || state.isOf(Blocks.NETHER_PORTAL)
				|| state.isOf(Blocks.END_PORTAL) || state.isOf(Blocks.END_GATEWAY)) return true;
		return state.hasBlockEntity() && !CompanionConfig.get().allowBreakingContainers;
	}

	public static boolean isLiquid(BlockState state) {
		return !state.getFluidState().isEmpty() && state.getCollisionShape(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty();
	}

	/** Air, grass, flowers, snow layers, water... things you can place a block into. */
	public static boolean isReplaceable(BlockState state) {
		return state.isAir() || state.isReplaceable();
	}

	/** Exposed = at least one neighbour you could stand in / look through. */
	public static boolean isExposed(World world, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockPos n = pos.offset(d);
			BlockState s = world.getBlockState(n);
			if (s.getCollisionShape(world, n).isEmpty() && s.getFluidState().isEmpty()) return true;
		}
		return false;
	}

	public static String directionName(double dx, double dz) {
		double angle = Math.toDegrees(Math.atan2(-dx, dz)); // 0 = south, 90 = west, 180 = north, -90 = east
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		int idx = (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
		return names[idx];
	}

	public static String facingName(Direction d) {
		return switch (d) {
			case NORTH -> "north (-z)";
			case SOUTH -> "south (+z)";
			case EAST -> "east (+x)";
			case WEST -> "west (-x)";
			default -> d.getName();
		};
	}
}
