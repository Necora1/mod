package com.necora.aicompanion.build;

import com.necora.aicompanion.util.WorldUtil;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Procedural generators for the structures the companion knows how to build. */
public final class Structures {
	private Structures() {
	}

	public enum RoofStyle {GABLE, FLAT, NONE}

	public static final class HouseSpec {
		public int width = 7;
		public int depth = 7;
		public int height = 4;
		public Block wall = Blocks.OAK_PLANKS;
		public Block corner = Blocks.OAK_LOG;
		public Block floor = Blocks.SPRUCE_PLANKS;
		public Block roof = Blocks.SPRUCE_PLANKS;
		public Block foundation = Blocks.COBBLESTONE;
		public Block window = Blocks.GLASS_PANE;
		public Block door = Blocks.OAK_DOOR;
		public Block bed = Blocks.RED_BED;
		public RoofStyle roofStyle = RoofStyle.GABLE;
		public boolean windows = true;
		public boolean furnish = true;
		public boolean foundationFill = true;
	}

	private static BlockState stair(Block stairs, Direction facing) {
		return stairs.getDefaultState().with(StairsBlock.FACING, facing).with(StairsBlock.HALF, BlockHalf.BOTTOM);
	}

	private static BlockState axisY(Block block) {
		BlockState s = block.getDefaultState();
		return s.contains(PillarBlock.AXIS) ? s.with(PillarBlock.AXIS, Direction.Axis.Y) : s;
	}

	/** Fills air/water/grass below a floor block down to solid ground. */
	private static void foundation(Blueprint bp, World world, BlockPos floorPos, Block block, int maxDepth) {
		for (int dy = 1; dy <= maxDepth; dy++) {
			BlockPos p = floorPos.down(dy);
			BlockState s = world.getBlockState(p);
			if (!WorldUtil.isReplaceable(s) && s.getFluidState().isEmpty()) break;
			bp.set(p, block.getDefaultState(), BlockPlacement.PHASE_FOUNDATION);
		}
	}

	// ------------------------------------------------------------------
	// House
	// ------------------------------------------------------------------

	public static Blueprint house(World world, Frame f, HouseSpec s) {
		int w = MathHelper.clamp(s.width, 5, 21);
		int d = MathHelper.clamp(s.depth, 5, 21);
		int h = MathHelper.clamp(s.height, 3, 8);
		Blueprint bp = new Blueprint("house").anchor(f.at(w / 2, 0, d / 2));
		Direction right = f.right();

		// Floor + foundation
		for (int u = 0; u < w; u++) {
			for (int v = 0; v < d; v++) {
				BlockPos p = f.at(u, 0, v);
				bp.set(p, s.floor.getDefaultState(), BlockPlacement.PHASE_FOUNDATION);
				if (s.foundationFill) foundation(bp, world, p, s.foundation, 6);
			}
		}
		// Clear the inside (and whatever terrain is in the way)
		int roofRows = s.roofStyle == RoofStyle.GABLE ? (w + 2) / 2 : 1;
		for (int u = 0; u < w; u++) {
			for (int v = 0; v < d; v++) {
				for (int y = 1; y <= h + roofRows; y++) bp.clear(f.at(u, y, v));
			}
		}
		// Walls with log corners
		for (int y = 1; y <= h; y++) {
			for (int u = 0; u < w; u++) {
				for (int v = 0; v < d; v++) {
					boolean edgeU = u == 0 || u == w - 1;
					boolean edgeV = v == 0 || v == d - 1;
					if (!edgeU && !edgeV) continue;
					BlockState state = edgeU && edgeV ? axisY(s.corner) : s.wall.getDefaultState();
					bp.set(f.at(u, y, v), state, BlockPlacement.PHASE_STRUCTURE);
				}
			}
		}
		// Door opening in the middle of the front wall
		int doorU = w / 2;
		bp.forceClear(f.at(doorU, 1, 0));
		bp.forceClear(f.at(doorU, 2, 0));
		// Windows
		if (s.windows) {
			BlockState pane = s.window.getDefaultState();
			int top = h >= 4 ? 3 : 2;
			for (int y = 2; y <= top; y++) {
				bp.setOptional(f.at(0, y, d / 2), pane, BlockPlacement.PHASE_STRUCTURE);
				bp.setOptional(f.at(w - 1, y, d / 2), pane, BlockPlacement.PHASE_STRUCTURE);
				bp.setOptional(f.at(w / 2, y, d - 1), pane, BlockPlacement.PHASE_STRUCTURE);
				if (d >= 9) {
					bp.setOptional(f.at(0, y, d / 2 - 2), pane, BlockPlacement.PHASE_STRUCTURE);
					bp.setOptional(f.at(w - 1, y, d / 2 + 2), pane, BlockPlacement.PHASE_STRUCTURE);
				}
				if (w >= 7) {
					bp.setOptional(f.at(doorU - 2, y, 0), pane, BlockPlacement.PHASE_STRUCTURE);
					bp.setOptional(f.at(doorU + 2, y, 0), pane, BlockPlacement.PHASE_STRUCTURE);
				}
			}
		}
		// Roof
		Block stairs = Materials.stairs(s.roof);
		Block roofFull = Materials.fullBlock(s.roof);
		switch (s.roofStyle) {
			case FLAT -> {
				Block slab = Materials.slab(roofFull);
				BlockState top = slab != null ? slab.getDefaultState() : roofFull.getDefaultState();
				for (int u = -1; u <= w; u++) {
					for (int v = -1; v <= d; v++) {
						boolean overhang = u < 0 || u >= w || v < 0 || v >= d;
						bp.set(f.at(u, h + 1, v), overhang ? top : roofFull.getDefaultState(), BlockPlacement.PHASE_ROOF);
					}
				}
			}
			case GABLE -> {
				int span = w + 2;
				int rows = span / 2;
				for (int i = 0; i < rows; i++) {
					int y = h + 1 + i;
					int uL = -1 + i;
					int uR = w - i;
					for (int v = -1; v <= d; v++) {
						bp.set(f.at(uL, y, v), stairs != null ? stair(stairs, right) : roofFull.getDefaultState(), BlockPlacement.PHASE_ROOF);
						bp.set(f.at(uR, y, v), stairs != null ? stair(stairs, right.getOpposite()) : roofFull.getDefaultState(), BlockPlacement.PHASE_ROOF);
					}
					// gable ends: fill the triangle on the front and back walls
					for (int u = Math.max(0, uL + 1); u <= Math.min(w - 1, uR - 1); u++) {
						bp.set(f.at(u, y, 0), s.wall.getDefaultState(), BlockPlacement.PHASE_ROOF);
						bp.set(f.at(u, y, d - 1), s.wall.getDefaultState(), BlockPlacement.PHASE_ROOF);
					}
				}
				if (span % 2 == 1) {
					int uC = -1 + rows;
					for (int v = -1; v <= d; v++) {
						bp.set(f.at(uC, h + rows, v), roofFull.getDefaultState(), BlockPlacement.PHASE_ROOF);
					}
				}
			}
			default -> {
			}
		}
		// Details: door, lights, furniture
		BlockState door = s.door.getDefaultState();
		if (door.contains(DoorBlock.FACING)) {
			door = door.with(DoorBlock.FACING, f.forward()).with(DoorBlock.HALF, DoubleBlockHalf.LOWER).with(DoorBlock.HINGE, DoorHinge.LEFT);
		}
		// optional: without a door in the inventory the doorway just stays open
		bp.setOptional(f.at(doorU, 1, 0), door, BlockPlacement.PHASE_DETAIL);
		bp.setOptional(f.at(doorU + 1, 2, -1), Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, f.back()), BlockPlacement.PHASE_DETAIL);
		bp.setOptional(f.at(1, 1, 1), Blocks.TORCH.getDefaultState(), BlockPlacement.PHASE_DETAIL);
		if (s.furnish) {
			BlockState bed = s.bed.getDefaultState();
			if (bed.contains(BedBlock.FACING)) bed = bed.with(BedBlock.FACING, f.forward());
			bp.setOptional(f.at(1, 1, d - 3), bed, BlockPlacement.PHASE_DETAIL);
			bp.setOptional(f.at(w - 2, 1, d - 2), Blocks.CRAFTING_TABLE.getDefaultState(), BlockPlacement.PHASE_DETAIL);
			BlockState chest = Blocks.CHEST.getDefaultState().with(Properties.HORIZONTAL_FACING, f.left());
			bp.setOptional(f.at(w - 2, 1, d - 3), chest, BlockPlacement.PHASE_DETAIL);
			if (w >= 7) {
				BlockState furnace = Blocks.FURNACE.getDefaultState().with(Properties.HORIZONTAL_FACING, f.left());
				bp.setOptional(f.at(w - 2, 1, d - 4), furnace, BlockPlacement.PHASE_DETAIL);
			}
			if (w >= 7 && d >= 7) {
				bp.setOptional(f.at(w - 2, 1, 1), Blocks.TORCH.getDefaultState(), BlockPlacement.PHASE_DETAIL);
			}
		}
		return bp;
	}

	// ------------------------------------------------------------------
	// Tower
	// ------------------------------------------------------------------

	public static Blueprint tower(World world, Frame f, int width, int height, Block wall, Block floor) {
		int w = MathHelper.clamp(width | 1, 5, 11);
		int h = MathHelper.clamp(height, 6, 40);
		Blueprint bp = new Blueprint("tower").anchor(f.at(w / 2, 0, w / 2));
		for (int u = 0; u < w; u++) {
			for (int v = 0; v < w; v++) {
				BlockPos p = f.at(u, 0, v);
				bp.set(p, floor.getDefaultState(), BlockPlacement.PHASE_FOUNDATION);
				foundation(bp, world, p, wall, 6);
				for (int y = 1; y <= h + 3; y++) bp.clear(f.at(u, y, v));
			}
		}
		for (int y = 1; y <= h; y++) {
			for (int u = 0; u < w; u++) {
				for (int v = 0; v < w; v++) {
					if (u == 0 || u == w - 1 || v == 0 || v == w - 1) {
						bp.set(f.at(u, y, v), wall.getDefaultState(), BlockPlacement.PHASE_STRUCTURE);
					}
				}
			}
			// arrow slits every 3 blocks
			if (y % 3 == 0 && y < h) {
				bp.forceClear(f.at(0, y, w / 2));
				bp.forceClear(f.at(w - 1, y, w / 2));
				bp.forceClear(f.at(w / 2, y, w - 1));
			}
		}
		int mid = w / 2;
		bp.forceClear(f.at(mid, 1, 0));
		bp.forceClear(f.at(mid, 2, 0));
		BlockState door = Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, f.forward()).with(DoorBlock.HALF, DoubleBlockHalf.LOWER);
		bp.setOptional(f.at(mid, 1, 0), door, BlockPlacement.PHASE_DETAIL);
		// top floor with a ladder hole
		for (int u = 0; u < w; u++) {
			for (int v = 0; v < w; v++) {
				bp.set(f.at(u, h + 1, v), floor.getDefaultState(), BlockPlacement.PHASE_ROOF);
			}
		}
		BlockState ladder = Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, f.back());
		for (int y = 1; y <= h + 1; y++) {
			bp.set(f.at(mid, y, w - 2), ladder, BlockPlacement.PHASE_DETAIL);
		}
		// battlements
		for (int u = 0; u < w; u++) {
			for (int v = 0; v < w; v++) {
				boolean edge = u == 0 || u == w - 1 || v == 0 || v == w - 1;
				if (edge && (u + v) % 2 == 0) bp.set(f.at(u, h + 2, v), wall.getDefaultState(), BlockPlacement.PHASE_ROOF);
			}
		}
		bp.setOptional(f.at(1, h + 2, 1), Blocks.TORCH.getDefaultState(), BlockPlacement.PHASE_DETAIL);
		bp.setOptional(f.at(w - 2, h + 2, w - 2), Blocks.TORCH.getDefaultState(), BlockPlacement.PHASE_DETAIL);
		bp.setOptional(f.at(1, 1, 1), Blocks.TORCH.getDefaultState(), BlockPlacement.PHASE_DETAIL);
		return bp;
	}

	// ------------------------------------------------------------------
	// Walls, fences, platforms, bridges, pillars
	// ------------------------------------------------------------------

	/** Straight wall between two points, following the terrain. */
	public static Blueprint wall(World world, BlockPos from, BlockPos to, int height, BlockState block) {
		Blueprint bp = new Blueprint("wall").anchor(from);
		int h = MathHelper.clamp(height, 1, 16);
		for (BlockPos col : line2d(from, to)) {
			int base = groundLevel(world, col);
			for (int y = 0; y < h; y++) {
				bp.set(new BlockPos(col.getX(), base + y, col.getZ()), block, BlockPlacement.PHASE_STRUCTURE);
			}
		}
		return bp;
	}

	/** Fence (or wall) around a rectangle with a gate on the front side. */
	public static Blueprint fence(World world, Frame f, int width, int depth, Block fence) {
		int w = MathHelper.clamp(width, 3, 64);
		int d = MathHelper.clamp(depth, 3, 64);
		Blueprint bp = new Blueprint("fence").anchor(f.at(w / 2, 0, d / 2));
		Block gate = Materials.byId(Materials.path(fence).replace("_fence", "_fence_gate"));
		for (int u = 0; u < w; u++) {
			for (int v = 0; v < d; v++) {
				if (u != 0 && u != w - 1 && v != 0 && v != d - 1) continue;
				BlockPos col = f.at(u, 0, v);
				BlockPos p = new BlockPos(col.getX(), groundLevel(world, col), col.getZ());
				if (v == 0 && u == w / 2 && gate != null && gate.getDefaultState().contains(FenceGateBlock.FACING)) {
					bp.set(p, gate.getDefaultState().with(FenceGateBlock.FACING, f.forward()), BlockPlacement.PHASE_STRUCTURE);
				} else {
					bp.set(p, fence.getDefaultState(), BlockPlacement.PHASE_STRUCTURE);
				}
			}
		}
		return bp;
	}

	public static Blueprint platform(World world, Frame f, int width, int depth, int y, BlockState block) {
		int w = MathHelper.clamp(width, 1, 64);
		int d = MathHelper.clamp(depth, 1, 64);
		Blueprint bp = new Blueprint("platform").anchor(f.at(w / 2, 0, d / 2));
		for (int u = 0; u < w; u++) {
			for (int v = 0; v < d; v++) {
				BlockPos col = f.at(u, 0, v);
				BlockPos p = new BlockPos(col.getX(), y, col.getZ());
				bp.set(p, block, BlockPlacement.PHASE_STRUCTURE);
				bp.clear(p.up());
				bp.clear(p.up(2));
			}
		}
		return bp;
	}

	/** A bridge straight ahead of {@code feet} at walking height. */
	public static Blueprint bridge(BlockPos feet, Direction forward, int length, int width, BlockState deck, @Nullable BlockState rail) {
		int len = MathHelper.clamp(length, 2, 128);
		int w = MathHelper.clamp(width, 1, 7);
		Direction right = forward.rotateYClockwise();
		Blueprint bp = new Blueprint("bridge").anchor(feet.offset(forward, len / 2));
		int half = w / 2;
		for (int v = 1; v <= len; v++) {
			for (int u = -half; u <= half; u++) {
				BlockPos p = feet.down().offset(forward, v).offset(right, u);
				bp.set(p, deck, BlockPlacement.PHASE_STRUCTURE);
				bp.clear(p.up());
				bp.clear(p.up(2));
			}
			if (rail != null) {
				bp.set(feet.offset(forward, v).offset(right, -half - 1), rail, BlockPlacement.PHASE_ROOF);
				bp.set(feet.offset(forward, v).offset(right, half + 1), rail, BlockPlacement.PHASE_ROOF);
				bp.set(feet.down().offset(forward, v).offset(right, -half - 1), deck, BlockPlacement.PHASE_STRUCTURE);
				bp.set(feet.down().offset(forward, v).offset(right, half + 1), deck, BlockPlacement.PHASE_STRUCTURE);
			}
		}
		return bp;
	}

	public static Blueprint pillar(BlockPos base, int height, BlockState block) {
		Blueprint bp = new Blueprint("pillar").anchor(base);
		int h = MathHelper.clamp(height, 1, 64);
		for (int y = 0; y < h; y++) bp.set(base.up(y), block, BlockPlacement.PHASE_STRUCTURE);
		return bp;
	}

	/**
	 * A protective shell around a player standing at {@code feet}: walls, roof and floor, with the
	 * inside kept free. radius 1 = the classic 1x2 "panic box".
	 */
	public static Blueprint surround(World world, BlockPos feet, int radius, BlockState block, boolean roof, boolean floor, @Nullable BlockState windowBlock) {
		int r = MathHelper.clamp(radius, 1, 4);
		int innerTop = 1 + (r - 1); // interior from y=0 to innerTop
		Blueprint bp = new Blueprint("shelter").anchor(feet).acceptExistingSolids(true);
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -1; dy <= innerTop + 1; dy++) {
					BlockPos p = feet.add(dx, dy, dz);
					boolean inside = Math.abs(dx) < r && Math.abs(dz) < r && dy >= 0 && dy <= innerTop;
					if (inside) {
						bp.keepFree(p);
						continue;
					}
					if (dy == -1) {
						if (!floor && !WorldUtil.isReplaceable(world.getBlockState(p))) continue;
						bp.set(p, block, BlockPlacement.PHASE_FOUNDATION);
					} else if (dy == innerTop + 1) {
						if (roof) bp.set(p, block, BlockPlacement.PHASE_ROOF);
					} else {
						boolean eyeLevel = dy == 1 && windowBlock != null && (dx == 0 || dz == 0);
						bp.set(p, eyeLevel ? windowBlock : block, BlockPlacement.PHASE_STRUCTURE);
					}
				}
			}
		}
		return bp;
	}

	/** Torches spread around an area on dark, standable spots. */
	public static Blueprint lights(World world, BlockPos center, int radius) {
		Blueprint bp = new Blueprint("torches").anchor(center);
		int r = MathHelper.clamp(radius, 3, 24);
		int step = 5;
		for (int dx = -r; dx <= r; dx += step) {
			for (int dz = -r; dz <= r; dz += step) {
				BlockPos col = center.add(dx, 0, dz);
				BlockPos spot = WorldUtil.findStandableNear(world, col, 4);
				if (spot == null) continue;
				if (world.getLightLevel(spot) >= 9) continue;
				if (!world.getBlockState(spot).isAir()) continue;
				bp.set(spot, Blocks.TORCH.getDefaultState(), BlockPlacement.PHASE_DETAIL);
			}
		}
		return bp;
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** The first free Y above the ground at this column (near the reference height). */
	public static int groundLevel(World world, BlockPos near) {
		BlockPos p = WorldUtil.findStandableNear(world, near, 6);
		if (p != null) return p.getY();
		return WorldUtil.surfaceY(world, near.getX(), near.getZ());
	}

	public static List<BlockPos> line2d(BlockPos a, BlockPos b) {
		List<BlockPos> out = new ArrayList<>();
		int x0 = a.getX(), z0 = a.getZ(), x1 = b.getX(), z1 = b.getZ();
		int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
		int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
		int err = dx - dz;
		int y = a.getY();
		int guard = 0;
		while (guard++ < 512) {
			out.add(new BlockPos(x0, y, z0));
			if (x0 == x1 && z0 == z1) break;
			int e2 = 2 * err;
			if (e2 > -dz) {
				err -= dz;
				x0 += sx;
			}
			if (e2 < dx) {
				err += dx;
				z0 += sz;
			}
		}
		return out;
	}

	public static List<BlockPos> line3d(BlockPos a, BlockPos b) {
		List<BlockPos> out = new ArrayList<>();
		int n = Math.max(Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getY() - a.getY())), Math.abs(b.getZ() - a.getZ()));
		for (int i = 0; i <= n; i++) {
			double t = n == 0 ? 0 : (double) i / n;
			out.add(BlockPos.ofFloored(
					a.getX() + (b.getX() - a.getX()) * t + 0.5,
					a.getY() + (b.getY() - a.getY()) * t + 0.5,
					a.getZ() + (b.getZ() - a.getZ()) * t + 0.5));
		}
		return out;
	}
}
