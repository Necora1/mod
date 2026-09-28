package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.build.BlockPlacement;
import com.necora.aicompanion.build.Blueprint;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.CompanionMovement;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.task.BlockBreaker;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.util.ItemUtil;
import com.necora.aicompanion.util.WorldUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds (or digs out) a {@link Blueprint} the way a player does: walks to a spot within reach,
 * looks at the block, places it with the item from its inventory, one block at a time. In survival
 * it pillars up with scaffold blocks to reach high spots; in creative it just flies.
 */
public class BuildTask extends Task {
	private enum Pillar {NONE, MOVING_TO_BASE, CLIMBING, ON_TOP, DESCENDING}

	private final Blueprint blueprint;
	private final String label;
	private final boolean dig;
	private List<BlockPlacement> order = List.of();
	private boolean[] done;
	private int[] attempts;
	private int firstOpen;
	private int current = -1;
	private final Set<BlockPos> pendingSolids = new HashSet<>();

	private final BlockBreaker breaker;
	private int cooldown;
	private int placed, broken, skipped;
	private final List<BlockPos> placedPositions = new ArrayList<>();

	// movement toward the current block
	@Nullable
	private BlockPos standTarget;
	private double actReach;
	private int entryTicks;
	private final Set<BlockPos> badStands = new HashSet<>();
	private int occupiedTicks;

	// materials
	private int waitingForItemTicks;
	@Nullable
	private Item missingItem;

	// pillaring (survival)
	private Pillar pillar = Pillar.NONE;
	private final List<BlockPos> pillarBlocks = new ArrayList<>();
	@Nullable
	private BlockPos pillarBase;
	private int pillarGoalY;
	private int pillarTicks;

	private int maxTicks;

	public BuildTask(CompanionEntity companion, Blueprint blueprint, String label, boolean dig) {
		super(companion);
		this.blueprint = blueprint;
		this.label = label;
		this.dig = dig;
		this.breaker = new BlockBreaker(companion);
	}

	@Override
	protected Status start() {
		CompanionConfig cfg = CompanionConfig.get();
		if (blueprint.isEmpty()) return fail("there was nothing to " + (dig ? "dig" : "build") + " there");
		if (blueprint.size() > cfg.maxBuildBlocks) {
			return fail(label + " is too big (" + blueprint.size() + " blocks, the limit is " + cfg.maxBuildBlocks + ")");
		}
		order = blueprint.ordered();
		done = new boolean[order.size()];
		attempts = new int[order.size()];
		for (BlockPlacement p : order) {
			if (!p.isClear()) pendingSolids.add(p.pos());
		}
		if (!dig && needsMaterials()) {
			String missing = missingMaterials();
			if (missing != null) return fail("not enough materials for " + label + ": " + missing);
		}
		maxTicks = 20 * 60 + order.size() * (c.isCreativeMode() ? 10 : 80);
		return Status.RUNNING;
	}

	private boolean needsMaterials() {
		return !c.isCreativeMode() && CompanionConfig.get().requireMaterialsInSurvival;
	}

	/** Returns a description of what's missing, or null if the inventory has enough. */
	@Nullable
	private String missingMaterials() {
		Map<Item, Integer> need = new LinkedHashMap<>();
		World world = c.getWorld();
		for (BlockPlacement p : order) {
			if (p.isClear() || p.optional()) continue;
			BlockState current = world.getBlockState(p.pos());
			if (current.getBlock() == p.state().getBlock()) continue;
			Item item = p.state().getBlock().asItem();
			if (item == Items.AIR) continue;
			need.merge(item, 1, Integer::sum);
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Item, Integer> e : need.entrySet()) {
			int have = c.countItem(e.getKey());
			if (have < e.getValue()) {
				if (!sb.isEmpty()) sb.append(", ");
				sb.append(e.getValue() - have).append(" more ").append(ItemUtil.id(e.getKey()))
						.append(" (need ").append(e.getValue()).append(", have ").append(have).append(")");
			}
		}
		return sb.isEmpty() ? null : sb.toString();
	}

	@Override
	protected Status tick() {
		if (ticks > maxTicks) return finish(false, "took too long");
		if (cooldown > 0) {
			cooldown--;
			keepPosition();
			return Status.RUNNING;
		}
		switch (pillar) {
			case MOVING_TO_BASE -> {
				return moveToPillarBase();
			}
			case CLIMBING -> {
				return climb();
			}
			case DESCENDING -> {
				return descend();
			}
			default -> {
			}
		}

		if (current < 0 || done[current]) {
			current = pickNext();
			entryTicks = 0;
			standTarget = null;
			actReach = c.getBlockReach();
			occupiedTicks = 0;
			if (current < 0) {
				if (!pillarBlocks.isEmpty()) {
					pillar = Pillar.DESCENDING;
					return Status.RUNNING;
				}
				return finish(true, null);
			}
		}
		BlockPlacement e = order.get(current);
		if (isSatisfied(e)) {
			markDone(current, false);
			return Status.RUNNING;
		}
		entryTicks++;
		if (entryTicks > 400) {
			defer(current);
			return Status.RUNNING;
		}

		if (c.canReachBlock(e.pos(), actReach)) {
			if (pillar == Pillar.ON_TOP) c.getMover().keepAlive();
			return act(e);
		}
		if (pillar == Pillar.ON_TOP) {
			// nothing more to do from up here
			pillar = Pillar.DESCENDING;
			return Status.RUNNING;
		}
		approach(e);
		return Status.RUNNING;
	}

	// ------------------------------------------------------------------
	// choosing what to do next
	// ------------------------------------------------------------------

	private int pickNext() {
		while (firstOpen < order.size() && done[firstOpen]) firstOpen++;
		if (firstOpen >= order.size()) return -1;
		BlockPlacement first = order.get(firstOpen);
		int phase = first.phase();
		int layer = first.pos().getY();
		Vec3d eye = c.getEyePos();
		double reach = c.getBlockReach();
		int best = -1;
		double bestScore = Double.MAX_VALUE;
		int scanned = 0;
		for (int i = firstOpen; i < order.size() && scanned < 400; i++) {
			if (done[i]) continue;
			BlockPlacement p = order.get(i);
			if (p.phase() != phase) break;
			if (p.pos().getY() != layer && !(pillar == Pillar.ON_TOP && Math.abs(p.pos().getY() - layer) <= 1)) break;
			scanned++;
			double distSq = CompanionEntity.squaredDistanceToBlock(eye, p.pos());
			boolean reachable = distSq <= reach * reach;
			if (pillar == Pillar.ON_TOP && !reachable) continue;
			double score = distSq + attempts[i] * 400 + (reachable ? 0 : 50);
			if (score < bestScore) {
				bestScore = score;
				best = i;
			}
		}
		if (best < 0 && pillar == Pillar.ON_TOP) return -2;
		return best < 0 ? firstOpen : best;
	}

	private boolean isSatisfied(BlockPlacement e) {
		World world = c.getWorld();
		BlockState current = world.getBlockState(e.pos());
		if (e.isClear()) {
			if (current.getCollisionShape(world, e.pos()).isEmpty() && !current.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
				return !dig || current.isAir() || WorldUtil.isLiquid(current) || current.isReplaceable();
			}
			return WorldUtil.isProtected(world, e.pos(), current);
		}
		if (current.getBlock() == e.state().getBlock()) return true;
		if (blueprint.acceptsExistingSolids() && current.isSolidBlock(world, e.pos()) && !WorldUtil.isHarmful(current)) return true;
		return Blueprint.isSecondaryPart(e.state());
	}

	private void markDone(int index, boolean skippedIt) {
		if (!done[index]) {
			done[index] = true;
			if (skippedIt) skipped++;
			pendingSolids.remove(order.get(index).pos());
		}
		if (index == current) current = -1;
	}

	private void defer(int index) {
		attempts[index]++;
		if (attempts[index] >= 3) {
			markDone(index, true);
		}
		badStands.clear();
		if (index == current) current = -1;
		breaker.reset();
	}

	// ------------------------------------------------------------------
	// acting on a block within reach
	// ------------------------------------------------------------------

	private Status act(BlockPlacement e) {
		World world = c.getWorld();
		BlockState present = world.getBlockState(e.pos());
		c.lookAtBlock(e.pos());
		if (e.isClear() || (!WorldUtil.isReplaceable(present) && present.getBlock() != e.state().getBlock())) {
			if (WorldUtil.isProtected(world, e.pos(), present)) {
				markDone(current, true);
				return Status.RUNNING;
			}
			if (!e.isClear() && !dig && !BlockBreaker.canHarvest(c, present) && present.getHardness(world, e.pos()) > 2.0F) {
				// e.g. obsidian in the way without a pickaxe
				markDone(current, true);
				return Status.RUNNING;
			}
			BlockBreaker.Result r = breaker.tick(e.pos());
			if (r == BlockBreaker.Result.BROKEN) {
				broken++;
				if (e.isClear()) markDone(current, false);
				cooldown = c.isCreativeMode() ? 1 : 2;
			} else if (r == BlockBreaker.Result.FAILED) {
				markDone(current, true);
			}
			return Status.RUNNING;
		}

		// placing: don't put a block inside ourselves or someone else
		Box blockBox = new Box(e.pos());
		boolean hasCollision = !e.state().getCollisionShape(world, e.pos()).isEmpty();
		if (hasCollision && c.getBoundingBox().intersects(blockBox)) {
			stepAside(e.pos());
			return Status.RUNNING;
		}
		boolean consume = needsMaterials();
		CompanionEntity.PlaceResult result = c.placeBlock(e.pos(), e.state(), consume);
		switch (result) {
			case PLACED -> {
				placed++;
				placedPositions.add(e.pos());
				markDone(current, false);
				waitingForItemTicks = 0;
				missingItem = null;
				cooldown = c.isCreativeMode() ? 1 : 3 + c.getRandom().nextInt(3);
			}
			case ALREADY_THERE -> markDone(current, false);
			case OCCUPIED -> {
				if (++occupiedTicks > 60) defer(current);
			}
			case OBSTRUCTED, INVALID -> defer(current);
			case NO_ITEM -> {
				if (e.optional()) {
					markDone(current, true);
				} else {
					return waitForItem(e);
				}
			}
		}
		return Status.RUNNING;
	}

	private Status waitForItem(BlockPlacement e) {
		Item item = e.state().getBlock().asItem();
		if (missingItem != item) {
			missingItem = item;
			waitingForItemTicks = 0;
			CompanionBrain brain = CompanionManager.brainOf(c);
			if (brain != null) {
				brain.onTaskProblem("ran out of " + ItemUtil.id(item) + " while building " + label + " (" + placed + " blocks placed so far). Waiting up to a minute for more.");
			}
		}
		if (++waitingForItemTicks > 20 * 60) {
			return finish(false, "ran out of " + ItemUtil.id(item));
		}
		c.getMover().keepAlive();
		return Status.RUNNING;
	}

	private void stepAside(BlockPos avoid) {
		BlockPos here = c.getBlockPos();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos p = here.add(dx, dy, dz);
					if (p.equals(avoid) || p.up().equals(avoid) || isReserved(p)) continue;
					if (!WorldUtil.isStandable(c.getWorld(), p)) continue;
					double d = p.getSquaredDistance(here) + (p.getSquaredDistance(avoid) < 2 ? 10 : 0);
					if (d < bestD && d > 0) {
						bestD = d;
						best = p;
					}
				}
			}
		}
		if (best != null) {
			c.getMover().moveToBlock(best, CompanionEntity.WALK_SPEED);
		} else if (c.isCreativeMode()) {
			c.getMover().flyTo(c.getPos().add(0, 1.5, 0), 1.0);
		} else {
			defer(current);
		}
	}

	private boolean isReserved(BlockPos p) {
		return pendingSolids.contains(p) || blueprint.getKeepFree().contains(p);
	}

	private void keepPosition() {
		if (pillar == Pillar.ON_TOP || pillar == Pillar.CLIMBING || c.getMover().isFlying()) {
			c.getMover().keepAlive();
			centerOnPillar();
		}
	}

	// ------------------------------------------------------------------
	// getting within reach
	// ------------------------------------------------------------------

	private void approach(BlockPlacement e) {
		CompanionMovement movement = c.getMover();
		if (c.isCreativeMode()) {
			BlockPos hover = standTarget != null ? standTarget : findHoverSpot(e.pos());
			if (hover == null) {
				defer(current);
				return;
			}
			standTarget = hover;
			Vec3d target = Vec3d.ofBottomCenter(hover).add(0, 0.1, 0);
			CompanionMovement.Status st = c.getMover().isFlying() || needsFlight(hover)
					? movement.flyTo(target, 1.2)
					: movement.moveTo(target, CompanionEntity.SPRINT_SPEED, 0.6);
			if (st == CompanionMovement.Status.FAILED) {
				movement.flyTo(target, 1.2);
			}
			return;
		}

		if (standTarget == null) {
			standTarget = findStandSpot(e.pos(), c.getBlockReach() - 0.25);
			actReach = c.getBlockReach();
			if (standTarget == null && canPillarFor(e)) {
				return;
			}
			if (standTarget == null && CompanionConfig.get().reachAssist) {
				standTarget = findStandSpot(e.pos(), 6.25);
				actReach = 6.5;
			}
			if (standTarget == null) {
				defer(current);
				return;
			}
		}
		CompanionMovement.Status st = movement.moveToBlock(standTarget, distanceTo(standTarget) > 10 ? CompanionEntity.SPRINT_SPEED : CompanionEntity.WALK_SPEED);
		if (st == CompanionMovement.Status.FAILED) {
			badStands.add(standTarget);
			standTarget = null;
			if (badStands.size() > 6) defer(current);
		} else if (st == CompanionMovement.Status.ARRIVED && !c.canReachBlock(e.pos(), actReach)) {
			badStands.add(standTarget);
			standTarget = null;
		}
	}

	private double distanceTo(BlockPos p) {
		return Math.sqrt(c.getBlockPos().getSquaredDistance(p));
	}

	private boolean needsFlight(BlockPos hover) {
		return !WorldUtil.isStandable(c.getWorld(), hover) || Math.abs(hover.getY() - c.getBlockY()) > 1;
	}

	@Nullable
	private BlockPos findStandSpot(BlockPos target, double reach) {
		World world = c.getWorld();
		int r = (int) Math.ceil(reach);
		List<BlockPos> candidates = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				for (int dy = -r - 1; dy <= r - 1; dy++) {
					BlockPos p = target.add(dx, dy, dz);
					if (p.equals(target) || p.up().equals(target) || badStands.contains(p)) continue;
					Vec3d eye = new Vec3d(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
					if (CompanionEntity.squaredDistanceToBlock(eye, target) > reach * reach) continue;
					if (isReserved(p) || isReserved(p.up())) continue;
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
			Path path = c.getMover().findPath(p);
			if (path != null && path.reachesTarget()) return p;
			badStands.add(p);
		}
		return null;
	}

	@Nullable
	private BlockPos findHoverSpot(BlockPos target) {
		World world = c.getWorld();
		double reach = c.getBlockReach() - 0.5;
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos here = c.getBlockPos();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = -1; dy <= 3; dy++) {
					BlockPos p = target.add(dx, dy, dz);
					if (p.equals(target) || p.up().equals(target) || badStands.contains(p)) continue;
					if (isReserved(p) || isReserved(p.up())) continue;
					if (!WorldUtil.isPassable(world, p) || !WorldUtil.isPassable(world, p.up())) continue;
					Vec3d eye = new Vec3d(p.getX() + 0.5, p.getY() + 1.62, p.getZ() + 0.5);
					if (CompanionEntity.squaredDistanceToBlock(eye, target) > reach * reach) continue;
					// prefer spots above/outside the build
					double d = p.getSquaredDistance(here) - dy * 2;
					if (d < bestD) {
						bestD = d;
						best = p;
					}
				}
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// pillaring (survival only)
	// ------------------------------------------------------------------

	private boolean canPillarFor(BlockPlacement e) {
		if (c.isCreativeMode() || e.isClear() && dig) return false;
		if (e.pos().getY() < c.getBlockY() + 3) return false;
		if (!hasScaffold()) return false;
		World world = c.getWorld();
		int goal = e.pos().getY() - 2;
		BlockPos here = c.getBlockPos();
		List<BlockPos> bases = new ArrayList<>();
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				for (int dy = -3; dy <= 1; dy++) {
					BlockPos base = new BlockPos(e.pos().getX() + dx, here.getY() + dy, e.pos().getZ() + dz);
					if (Math.abs(dx) + Math.abs(dz) == 0) continue;
					if (!WorldUtil.isStandable(world, base) || isReserved(base) || badStands.contains(base)) continue;
					if (goal - base.getY() > 20) continue;
					boolean clearColumn = true;
					for (int y = base.getY(); y <= goal + 2; y++) {
						BlockPos p = new BlockPos(base.getX(), y, base.getZ());
						if (isReserved(p) || (y > base.getY() + 1 && !WorldUtil.isPassable(world, p))) {
							clearColumn = false;
							break;
						}
					}
					if (!clearColumn) continue;
					Vec3d topEye = new Vec3d(base.getX() + 0.5, Math.max(goal, base.getY()) + 1.62, base.getZ() + 0.5);
					if (CompanionEntity.squaredDistanceToBlock(topEye, e.pos()) > 4.2 * 4.2) continue;
					bases.add(base);
				}
			}
		}
		bases.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(here)));
		int tries = 0;
		for (BlockPos base : bases) {
			if (tries++ >= 4) break;
			if (base.equals(here) || c.getMover().canWalkTo(base)) {
				pillarBase = base;
				pillarGoalY = Math.max(base.getY() + 1, goal);
				pillar = Pillar.MOVING_TO_BASE;
				pillarTicks = 0;
				return true;
			}
			badStands.add(base);
		}
		return false;
	}

	private boolean hasScaffold() {
		return c.countItems(ItemUtil::isScaffoldBlock) > 0 || c.countItems(ItemUtil::isPlaceableSolid) > 0;
	}

	private Status moveToPillarBase() {
		if (pillarBase == null || ++pillarTicks > 300) {
			pillar = pillarBlocks.isEmpty() ? Pillar.NONE : Pillar.DESCENDING;
			if (current >= 0) defer(current);
			return Status.RUNNING;
		}
		CompanionMovement.Status st = c.getMover().moveToBlock(pillarBase, CompanionEntity.WALK_SPEED);
		if (st == CompanionMovement.Status.ARRIVED) {
			pillar = Pillar.CLIMBING;
			pillarTicks = 0;
		} else if (st == CompanionMovement.Status.FAILED) {
			badStands.add(pillarBase);
			pillar = Pillar.NONE;
		}
		return Status.RUNNING;
	}

	private void centerOnPillar() {
		if (pillarBase == null) return;
		double cx = pillarBase.getX() + 0.5;
		double cz = pillarBase.getZ() + 0.5;
		Vec3d v = c.getVelocity();
		c.setVelocity((cx - c.getX()) * 0.35, v.y, (cz - c.getZ()) * 0.35);
		c.getNavigation().stop();
	}

	private Status climb() {
		c.getMover().keepAlive();
		centerOnPillar();
		if (++pillarTicks > 400 || pillarBase == null) {
			pillar = pillarBlocks.isEmpty() ? Pillar.NONE : Pillar.DESCENDING;
			return Status.RUNNING;
		}
		int nextY = pillarBase.getY() + pillarBlocks.size();
		if (nextY >= pillarGoalY && c.isOnGround()) {
			pillar = Pillar.ON_TOP;
			current = -1;
			return Status.RUNNING;
		}
		World world = c.getWorld();
		BlockPos head = new BlockPos(pillarBase.getX(), nextY + 2, pillarBase.getZ());
		if (!WorldUtil.isPassable(world, head)) {
			pillar = Pillar.ON_TOP;
			current = -1;
			return Status.RUNNING;
		}
		if (c.isOnGround()) {
			c.getJumpControl().setActive();
			return Status.RUNNING;
		}
		BlockPos slot = new BlockPos(pillarBase.getX(), nextY, pillarBase.getZ());
		if (c.getY() >= nextY + 1.0 && WorldUtil.isReplaceable(world.getBlockState(slot))) {
			BlockState scaffold = scaffoldState();
			if (scaffold == null) {
				pillar = pillarBlocks.isEmpty() ? Pillar.NONE : Pillar.ON_TOP;
				return Status.RUNNING;
			}
			c.getLookControl().lookAt(slot.getX() + 0.5, slot.getY(), slot.getZ() + 0.5, 90.0F, 90.0F);
			if (c.placeBlock(slot, scaffold, true) == CompanionEntity.PlaceResult.PLACED) {
				pillarBlocks.add(slot);
			}
		}
		return Status.RUNNING;
	}

	@Nullable
	private BlockState scaffoldState() {
		ItemStack pick = null;
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (ItemUtil.isScaffoldBlock(s)) {
				pick = s;
				break;
			}
		}
		if (pick == null && ItemUtil.isScaffoldBlock(c.getMainHandStack())) pick = c.getMainHandStack();
		if (pick == null) {
			for (int i = 0; i < c.getInventory().size(); i++) {
				ItemStack s = c.getInventory().getStack(i);
				if (ItemUtil.isPlaceableSolid(s)) {
					pick = s;
					break;
				}
			}
		}
		if (pick == null || !(pick.getItem() instanceof BlockItem bi)) return null;
		Block block = bi.getBlock();
		return block.getDefaultState();
	}

	private Status descend() {
		c.getMover().keepAlive();
		if (pillarBlocks.isEmpty()) {
			pillar = Pillar.NONE;
			breaker.reset();
			return Status.RUNNING;
		}
		centerOnPillar();
		BlockPos top = pillarBlocks.get(pillarBlocks.size() - 1);
		World world = c.getWorld();
		if (world.getBlockState(top).isAir()) {
			pillarBlocks.remove(pillarBlocks.size() - 1);
			return Status.RUNNING;
		}
		boolean standingOnIt = c.getBlockPos().down().equals(top) || c.getBlockPos().equals(top.up());
		if (!standingOnIt) {
			// knocked off the pillar: tear the rest down from the ground (as clearing jobs)
			if (c.isOnGround() && c.getBlockY() <= pillarBase.getY()) {
				for (BlockPos p : pillarBlocks) extraClear(p);
				pillarBlocks.clear();
				pillar = Pillar.NONE;
			}
			return Status.RUNNING;
		}
		if (!c.isOnGround()) return Status.RUNNING;
		BlockBreaker.Result r = breaker.tick(top);
		if (r != BlockBreaker.Result.WORKING) {
			pillarBlocks.remove(pillarBlocks.size() - 1);
			cooldown = 2;
		}
		return Status.RUNNING;
	}

	private void extraClear(BlockPos p) {
		List<BlockPlacement> bigger = new ArrayList<>(order);
		bigger.add(new BlockPlacement(p, net.minecraft.block.Blocks.AIR.getDefaultState(), BlockPlacement.PHASE_DETAIL + 1, false));
		boolean[] d2 = new boolean[bigger.size()];
		System.arraycopy(done, 0, d2, 0, done.length);
		int[] a2 = new int[bigger.size()];
		System.arraycopy(attempts, 0, a2, 0, attempts.length);
		order = bigger;
		done = d2;
		attempts = a2;
	}

	// ------------------------------------------------------------------

	private Status finish(boolean ok, @Nullable String problem) {
		breaker.reset();
		if (c.getMover().isFlying() && c.isCreativeMode()) {
			// stay in the air; the next behaviour decides whether to land
		}
		CompanionBrain brain = CompanionManager.brainOf(c);
		if (brain != null && !placedPositions.isEmpty()) {
			brain.setLastBuild(new ArrayList<>(placedPositions), label);
			if (ok) {
				BlockPos a = blueprint.getAnchor();
				brain.getMemory().addEvent("built " + label + " at " + a.getX() + " " + a.getY() + " " + a.getZ(), brain.timeStamp());
			}
		}
		StringBuilder sb = new StringBuilder();
		if (dig) sb.append("removed ").append(broken).append(" blocks");
		else sb.append("placed ").append(placed).append(" blocks");
		if (!dig && broken > 0) sb.append(", cleared ").append(broken);
		if (skipped > 0) sb.append(", skipped ").append(skipped).append(" I couldn't do");
		if (ok) return success("finished " + label + " (" + sb + ")");
		return fail(label + " stopped: " + problem + " (" + sb + ")");
	}

	@Override
	public void stop() {
		breaker.reset();
	}

	@Override
	public String describe() {
		int total = order.size();
		int finished = 0;
		if (done != null) for (boolean b : done) if (b) finished++;
		String verb = dig ? "digging out " : "building ";
		return verb + label + (total > 0 ? " (" + finished + "/" + total + ")" : "");
	}
}
