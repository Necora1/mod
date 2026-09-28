package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.CompanionMovement;
import com.necora.aicompanion.task.BlockBreaker;
import com.necora.aicompanion.task.BlockScanner;
import com.necora.aicompanion.task.ReachHelper;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.util.Names;
import com.necora.aicompanion.util.WorldUtil;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds and mines blocks nearby (trees, stone, ores...) and picks up what drops. */
public class MineTask extends Task {
	private final Names.BlockMatcher matcher;
	private final int count;
	private final int radius;
	private final BlockBreaker breaker;
	private BlockScanner scanner;
	@Nullable
	private BlockPos target;
	@Nullable
	private BlockPos stand;
	private double reach;
	private final Set<BlockPos> blacklist = new HashSet<>();
	private final Set<BlockPos> badStands = new HashSet<>();
	private int mined;
	private int targetTicks;
	private int collectTicks;
	private int idleScanTicks;
	private int finalCollectTicks;

	public MineTask(CompanionEntity companion, Names.BlockMatcher matcher, int count, int radius) {
		super(companion);
		this.matcher = matcher;
		this.count = Math.max(1, Math.min(count, 512));
		this.radius = Math.max(4, Math.min(radius, 40));
		this.breaker = new BlockBreaker(companion);
	}

	@Override
	protected Status start() {
		scanner = new BlockScanner(c.getBlockPos(), radius);
		return Status.RUNNING;
	}

	@Override
	protected Status tick() {
		if (ticks > 20 * 60 * 8) return finish("ran out of time");
		if (collectTicks > 0) {
			collectTicks--;
			if (collectNearbyDrops()) return Status.RUNNING;
			collectTicks = 0;
		}
		if (mined >= count) {
			// pick up what just dropped before calling it done
			if (finalCollectTicks++ < 100 && collectNearbyDrops()) return Status.RUNNING;
			return finish(null);
		}
		if (c.isInventoryFull()) return finish("my inventory is full");

		if (target == null) {
			target = scanner.next(c.getWorld(), 12000, (pos, state) -> matcher.predicate().test(state)
					&& !blacklist.contains(pos) && WorldUtil.isExposed(c.getWorld(), pos) && !WorldUtil.isProtected(c.getWorld(), pos, state));
			if (target == null) {
				if (scanner.isFinished()) {
					if (++idleScanTicks > 1) return finish(mined > 0 ? "there's no more " + matcher.label() + " nearby" : null);
					scanner = new BlockScanner(c.getBlockPos(), radius);
				}
				return Status.RUNNING;
			}
			BlockState state = c.getWorld().getBlockState(target);
			if (!BlockBreaker.canHarvest(c, state)) {
				return fail("I need a " + toolName(state) + " to mine " + matcher.label());
			}
			stand = null;
			targetTicks = 0;
			reach = c.getBlockReach();
		}
		World world = c.getWorld();
		BlockState state = world.getBlockState(target);
		if (!matcher.predicate().test(state)) {
			target = null;
			breaker.reset();
			return Status.RUNNING;
		}
		if (++targetTicks > 400) {
			giveUpOnTarget();
			return Status.RUNNING;
		}
		if (c.canReachBlock(target, reach)) {
			BlockBreaker.Result r = breaker.tick(target);
			if (r == BlockBreaker.Result.BROKEN) {
				mined++;
				target = null;
				collectTicks = 60;
				scanner = new BlockScanner(c.getBlockPos(), radius);
			} else if (r == BlockBreaker.Result.FAILED) {
				giveUpOnTarget();
			}
			return Status.RUNNING;
		}
		if (stand == null) {
			stand = ReachHelper.findStandSpot(c, target, c.getBlockReach() - 0.25, badStands);
			reach = c.getBlockReach();
			if (stand == null && com.necora.aicompanion.config.CompanionConfig.get().reachAssist) {
				stand = ReachHelper.findStandSpot(c, target, 6.0, badStands);
				reach = 6.3;
			}
			if (stand == null) {
				if (c.isCreativeMode()) {
					c.getMover().flyTo(target.toCenterPos().add(0, 1.5, 0), 1.2);
					reach = c.getBlockReach();
					return Status.RUNNING;
				}
				giveUpOnTarget();
				return Status.RUNNING;
			}
		}
		CompanionMovement.Status st = c.getMover().moveToBlock(stand, CompanionEntity.WALK_SPEED);
		if (st == CompanionMovement.Status.FAILED) {
			badStands.add(stand);
			stand = null;
		} else if (st == CompanionMovement.Status.ARRIVED && !c.canReachBlock(target, reach)) {
			badStands.add(stand);
			stand = null;
		}
		return Status.RUNNING;
	}

	private void giveUpOnTarget() {
		if (target != null) blacklist.add(target);
		target = null;
		stand = null;
		breaker.reset();
	}

	private boolean collectNearbyDrops() {
		Box box = c.getBoundingBox().expand(6, 3, 6);
		// includes items still on their short pickup delay, so we walk over and grab them
		List<ItemEntity> items = c.getWorld().getEntitiesByClass(ItemEntity.class, box, i -> i.isAlive() && i.getOwner() != c
				&& (i.owner == null || i.owner.equals(c.getUuid())));
		if (items.isEmpty() || c.isInventoryFull()) return false;
		items.sort(Comparator.comparingDouble(c::squaredDistanceTo));
		c.getMover().moveTo(items.get(0).getPos(), CompanionEntity.WALK_SPEED, 0.5);
		return true;
	}

	private static String toolName(BlockState state) {
		if (state.isIn(BlockTags.NEEDS_DIAMOND_TOOL)) return "diamond pickaxe";
		if (state.isIn(BlockTags.NEEDS_IRON_TOOL)) return "iron pickaxe";
		if (state.isIn(BlockTags.NEEDS_STONE_TOOL)) return "stone pickaxe or better";
		if (state.isIn(BlockTags.PICKAXE_MINEABLE)) return "pickaxe";
		if (state.isIn(BlockTags.SHOVEL_MINEABLE)) return "shovel";
		if (state.isIn(BlockTags.AXE_MINEABLE)) return "axe";
		return "better tool";
	}

	private Status finish(@Nullable String note) {
		breaker.reset();
		if (mined == 0) return fail("couldn't find any " + matcher.label() + " within " + radius + " blocks" + (note == null ? "" : " (" + note + ")"));
		return success("mined " + mined + " " + matcher.label() + (note == null ? "" : " (" + note + ")"));
	}

	@Override
	public void stop() {
		breaker.reset();
	}

	@Override
	public String describe() {
		return "mining " + matcher.label() + " (" + mined + "/" + count + ")";
	}
}
