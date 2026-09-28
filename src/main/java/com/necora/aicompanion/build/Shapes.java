package com.necora.aicompanion.build;

import com.google.gson.JsonObject;
import com.necora.aicompanion.util.Json;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;

/**
 * Geometric primitives for "build_custom": lets the model design its own builds out of boxes,
 * spheres, cylinders, pyramids and lines. Coordinates are offsets from an origin (x=east, y=up, z=south).
 */
public final class Shapes {
	private Shapes() {
	}

	/** Adds one shape to the blueprint. Returns an error message or null. */
	public static String apply(Blueprint bp, BlockPos origin, JsonObject part, BlockState block, int limit) {
		String shape = Json.strOr(part, "block", "shape", "type", "kind").toLowerCase(Locale.ROOT).trim();
		BlockPos from = Json.vec(Json.get(part, "from", "start", "pos", "at", "position", "offset"));
		BlockPos to = Json.vec(Json.get(part, "to", "end"));
		BlockPos center = Json.vec(Json.get(part, "center", "centre", "from", "at", "pos", "position"));
		int radius = MathHelper.clamp(Json.integer(part, 3, "radius", "r", "size"), 1, 32);
		int height = MathHelper.clamp(Json.integer(part, 1, "height", "h"), 1, 64);
		boolean hollow = Json.bool(part, shape.contains("hollow"), "hollow");
		boolean clear = block.isAir() || shape.equals("clear") || shape.equals("air") || shape.equals("remove") || shape.equals("dig");
		if (center == null) center = BlockPos.ORIGIN;
		switch (shape) {
			case "block", "single", "point" -> put(bp, origin.add(from == null ? BlockPos.ORIGIN : from), block, clear);
			case "box", "fill", "cube", "cuboid", "floor", "platform", "hollow_box", "shell", "room", "walls", "clear", "air", "remove", "dig", "roof" -> {
				if (from == null || to == null) return "'" + shape + "' needs from and to";
				int minX = Math.min(from.getX(), to.getX()), maxX = Math.max(from.getX(), to.getX());
				int minY = Math.min(from.getY(), to.getY()), maxY = Math.max(from.getY(), to.getY());
				int minZ = Math.min(from.getZ(), to.getZ()), maxZ = Math.max(from.getZ(), to.getZ());
				long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
				if (volume > limit * 4L) return "shape too big (" + volume + " blocks)";
				boolean shell = shape.equals("hollow_box") || shape.equals("shell") || shape.equals("room") || (hollow && !shape.equals("walls"));
				boolean wallsOnly = shape.equals("walls");
				for (int x = minX; x <= maxX; x++) {
					for (int y = minY; y <= maxY; y++) {
						for (int z = minZ; z <= maxZ; z++) {
							boolean edgeXZ = x == minX || x == maxX || z == minZ || z == maxZ;
							boolean edgeY = y == minY || y == maxY;
							BlockPos p = origin.add(x, y, z);
							if (wallsOnly && !edgeXZ) {
								continue;
							}
							if (shell && !edgeXZ && !edgeY) {
								bp.clear(p);
								continue;
							}
							put(bp, p, block, clear);
						}
					}
				}
			}
			case "line", "beam" -> {
				if (from == null || to == null) return "'line' needs from and to";
				for (BlockPos p : Structures.line3d(from, to)) put(bp, origin.add(p), block, clear);
			}
			case "cylinder", "tube", "circle", "ring", "disc", "disk" -> {
				boolean flat = shape.equals("circle") || shape.equals("ring") || shape.equals("disc") || shape.equals("disk");
				int hh = flat ? 1 : height;
				boolean ringOnly = hollow || shape.equals("ring") || shape.equals("tube");
				for (int y = 0; y < hh; y++) {
					for (int x = -radius; x <= radius; x++) {
						for (int z = -radius; z <= radius; z++) {
							double d = Math.sqrt(x * x + z * z);
							if (d > radius + 0.5) continue;
							boolean edge = d > radius - 0.5;
							BlockPos p = origin.add(center).add(x, y, z);
							if (ringOnly && !edge) {
								if (!flat) bp.clear(p);
								continue;
							}
							put(bp, p, block, clear);
						}
					}
				}
			}
			case "sphere", "ball", "dome", "hemisphere", "hollow_sphere" -> {
				boolean dome = shape.equals("dome") || shape.equals("hemisphere");
				for (int x = -radius; x <= radius; x++) {
					for (int y = dome ? 0 : -radius; y <= radius; y++) {
						for (int z = -radius; z <= radius; z++) {
							double d = Math.sqrt(x * x + y * y + z * z);
							if (d > radius + 0.5) continue;
							BlockPos p = origin.add(center).add(x, y, z);
							if (hollow && d < radius - 0.5) {
								bp.clear(p);
								continue;
							}
							put(bp, p, block, clear);
						}
					}
				}
			}
			case "pyramid", "cone" -> {
				for (int layer = 0; layer <= radius; layer++) {
					int r = radius - layer;
					for (int x = -r; x <= r; x++) {
						for (int z = -r; z <= r; z++) {
							boolean edge = Math.abs(x) == r || Math.abs(z) == r;
							BlockPos p = origin.add(center).add(x, layer, z);
							if (hollow && !edge && layer < radius) {
								bp.clear(p);
								continue;
							}
							put(bp, p, block, clear);
						}
					}
				}
			}
			default -> {
				return "unknown shape '" + shape + "'";
			}
		}
		if (bp.size() > limit) return "build too big (" + bp.size() + " blocks, limit " + limit + ")";
		return null;
	}

	private static void put(Blueprint bp, BlockPos p, BlockState block, boolean clear) {
		if (clear) bp.forceClear(p);
		else bp.set(p, block, block.getCollisionShape(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty() ? BlockPlacement.PHASE_DETAIL : BlockPlacement.PHASE_STRUCTURE);
	}
}
