package com.necora.aicompanion.build;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An ordered set of block placements plus positions the builder must keep free (e.g. where the player stands). */
public class Blueprint {
	public final String name;
	private final Map<BlockPos, BlockPlacement> placements = new LinkedHashMap<>();
	private final Set<BlockPos> keepFree = new HashSet<>();
	private BlockPos anchor = BlockPos.ORIGIN;
	/** Build from the top down (digging) instead of bottom up. */
	private boolean topDown = false;
	/** Any solid block already in place counts as done (e.g. a shelter next to a stone wall). */
	private boolean acceptExistingSolids = false;

	public Blueprint(String name) {
		this.name = name;
	}

	public Blueprint anchor(BlockPos pos) {
		this.anchor = pos.toImmutable();
		return this;
	}

	public BlockPos getAnchor() {
		return anchor;
	}

	public Blueprint acceptExistingSolids(boolean value) {
		this.acceptExistingSolids = value;
		return this;
	}

	public boolean acceptsExistingSolids() {
		return acceptExistingSolids;
	}

	public Blueprint topDown(boolean value) {
		this.topDown = value;
		return this;
	}

	public void set(BlockPos pos, BlockState state, int phase) {
		placements.put(pos.toImmutable(), new BlockPlacement(pos.toImmutable(), state, phase, false));
	}

	public void setOptional(BlockPos pos, BlockState state, int phase) {
		placements.put(pos.toImmutable(), new BlockPlacement(pos.toImmutable(), state, phase, true));
	}

	/** Make sure this spot ends up empty - unless something solid is planned there. */
	public void clear(BlockPos pos) {
		BlockPos p = pos.toImmutable();
		BlockPlacement existing = placements.get(p);
		if (existing == null || existing.isClear()) {
			placements.put(p, new BlockPlacement(p, Blocks.AIR.getDefaultState(), BlockPlacement.PHASE_CLEAR, false));
		}
	}

	/** Force this spot to be cleared, replacing any planned block. */
	public void forceClear(BlockPos pos) {
		BlockPos p = pos.toImmutable();
		placements.put(p, new BlockPlacement(p, Blocks.AIR.getDefaultState(), BlockPlacement.PHASE_CLEAR, false));
	}

	public void remove(BlockPos pos) {
		placements.remove(pos);
	}

	public void keepFree(BlockPos pos) {
		keepFree.add(pos.toImmutable());
		placements.remove(pos);
	}

	public Set<BlockPos> getKeepFree() {
		return keepFree;
	}

	public int size() {
		return placements.size();
	}

	public boolean isEmpty() {
		return placements.isEmpty();
	}

	public BlockPlacement get(BlockPos pos) {
		return placements.get(pos);
	}

	public List<BlockPlacement> all() {
		return new ArrayList<>(placements.values());
	}

	/** Replaces every planned {@code from} block with {@code to} (used to substitute missing materials). */
	public void substitute(Block from, BlockState to) {
		for (Map.Entry<BlockPos, BlockPlacement> e : placements.entrySet()) {
			BlockPlacement p = e.getValue();
			if (p.state().isOf(from)) {
				e.setValue(new BlockPlacement(p.pos(), to, p.phase(), p.optional()));
			}
		}
	}

	/** Items needed for the non-optional part of the build (ignoring what's already in place). */
	public Map<Item, Integer> materials() {
		Map<Item, Integer> out = new LinkedHashMap<>();
		for (BlockPlacement p : placements.values()) {
			if (p.isClear() || p.optional() || isSecondaryPart(p.state())) continue;
			Item item = p.state().getBlock().asItem();
			if (item == Items.AIR) continue;
			out.merge(item, 1, Integer::sum);
		}
		return out;
	}

	/** The upper half of a door or the head of a bed: created automatically by the other half. */
	public static boolean isSecondaryPart(BlockState state) {
		if (state.contains(Properties.DOUBLE_BLOCK_HALF) && state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
		return state.contains(Properties.BED_PART) && state.get(Properties.BED_PART) == BedPart.HEAD;
	}

	/** Placement order: by phase, then layer (bottom-up, or top-down when digging), then around the build. */
	public List<BlockPlacement> ordered() {
		List<BlockPlacement> list = new ArrayList<>(placements.values());
		list.removeIf(p -> isSecondaryPart(p.state()));
		double cx = 0, cz = 0;
		for (BlockPlacement p : list) {
			cx += p.pos().getX();
			cz += p.pos().getZ();
		}
		if (!list.isEmpty()) {
			cx /= list.size();
			cz /= list.size();
		}
		final Vec3d center = new Vec3d(cx, 0, cz);
		Comparator<BlockPlacement> cmp = Comparator.comparingInt(BlockPlacement::phase);
		cmp = cmp.thenComparingInt(p -> {
			boolean down = topDown || p.phase() == BlockPlacement.PHASE_CLEAR;
			return down ? -p.pos().getY() : p.pos().getY();
		});
		cmp = cmp.thenComparingDouble(p -> Math.atan2(p.pos().getZ() + 0.5 - center.z, p.pos().getX() + 0.5 - center.x));
		list.sort(cmp);
		return list;
	}
}
