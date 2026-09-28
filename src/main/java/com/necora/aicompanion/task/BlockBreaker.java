package com.necora.aicompanion.task;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.util.WorldUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Breaks one block at a time exactly like a survival player: the right tool, the same break
 * speed formula, crack animation, hit sounds and tool wear. Instant-ish in creative.
 */
public class BlockBreaker {
	public enum Result {WORKING, BROKEN, FAILED}

	private final CompanionEntity c;
	@Nullable
	private BlockPos target;
	private float progress;
	private int lastStage = -1;
	private int ticks;

	public BlockBreaker(CompanionEntity companion) {
		this.c = companion;
	}

	public void reset() {
		if (target != null && lastStage >= 0) {
			c.getWorld().setBlockBreakingInfo(c.getId(), target, -1);
		}
		target = null;
		progress = 0;
		lastStage = -1;
		ticks = 0;
	}

	@Nullable
	public BlockPos getTarget() {
		return target;
	}

	public Result tick(BlockPos pos) {
		World world = c.getWorld();
		if (!pos.equals(target)) {
			reset();
			target = pos.toImmutable();
		}
		BlockState state = world.getBlockState(pos);
		if (state.isAir() || WorldUtil.isLiquid(state)) {
			reset();
			return Result.BROKEN;
		}
		if (WorldUtil.isProtected(world, pos, state)) {
			reset();
			return Result.FAILED;
		}
		ticks++;
		c.lookAtBlock(pos);
		if (c.isCreativeMode()) {
			if (ticks == 1) c.swingHand(Hand.MAIN_HAND);
			if (ticks >= 3) {
				finish(pos, state);
				return Result.BROKEN;
			}
			return Result.WORKING;
		}
		if (ticks == 1) selectBestTool(state);
		float delta = breakingDelta(state, pos);
		if (delta <= 0) {
			reset();
			return Result.FAILED;
		}
		progress += delta;
		if (ticks % 4 == 1) {
			c.swingHand(Hand.MAIN_HAND);
			BlockSoundGroup sounds = state.getSoundGroup();
			world.playSound(null, pos, sounds.getHitSound(), SoundCategory.BLOCKS, (sounds.getVolume() + 1.0F) / 8.0F, sounds.getPitch() * 0.5F);
		}
		int stage = Math.min(9, (int) (progress * 10.0F));
		if (stage != lastStage) {
			world.setBlockBreakingInfo(c.getId(), pos, stage);
			lastStage = stage;
		}
		if (progress >= 1.0F) {
			finish(pos, state);
			return Result.BROKEN;
		}
		return Result.WORKING;
	}

	public float breakingDelta(BlockState state, BlockPos pos) {
		World world = c.getWorld();
		float hardness = state.getHardness(world, pos);
		if (hardness < 0) return 0;
		if (hardness == 0) return 1.0F;
		ItemStack tool = c.getMainHandStack();
		float speed = tool.getMiningSpeedMultiplier(state);
		if (speed > 1.0F) speed += (float) c.getAttributeValue(EntityAttributes.PLAYER_MINING_EFFICIENCY);
		if (StatusEffectUtil.hasHaste(c)) speed *= 1.0F + (StatusEffectUtil.getHasteAmplifier(c) + 1) * 0.2F;
		speed *= (float) c.getAttributeValue(EntityAttributes.PLAYER_BLOCK_BREAK_SPEED);
		if (c.isSubmergedInWater()) speed *= (float) c.getAttributeValue(EntityAttributes.PLAYER_SUBMERGED_MINING_SPEED);
		if (!c.isOnGround() && !c.getMover().isFlying() && !c.isTouchingWater()) speed /= 5.0F;
		boolean canHarvest = !state.isToolRequired() || tool.isSuitableFor(state);
		return speed / hardness / (canHarvest ? 30.0F : 100.0F);
	}

	private void finish(BlockPos pos, BlockState state) {
		World world = c.getWorld();
		world.setBlockBreakingInfo(c.getId(), pos, -1);
		ItemStack tool = c.getMainHandStack();
		boolean creative = c.isCreativeMode();
		boolean canHarvest = !state.isToolRequired() || tool.isSuitableFor(state);
		BlockEntity blockEntity = world.getBlockEntity(pos);
		boolean removed = world.breakBlock(pos, false, c);
		if (removed && !creative && world instanceof ServerWorld sw) {
			if (canHarvest) {
				Block.dropStacks(state, world, pos, blockEntity, c, tool);
				state.onStacksDropped(sw, pos, tool, true);
			}
			if (!tool.isEmpty()) {
				tool.getItem().postMine(tool, world, state, pos, c);
			}
		}
		c.onBlockBroken();
		target = null;
		progress = 0;
		lastStage = -1;
		ticks = 0;
	}

	/** Picks the fastest suitable tool from the inventory. */
	public void selectBestTool(BlockState state) {
		ItemStack held = c.getMainHandStack();
		float bestSpeed = held.getMiningSpeedMultiplier(state);
		boolean bestOk = !state.isToolRequired() || held.isSuitableFor(state);
		ItemStack best = null;
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (s.isEmpty()) continue;
			float speed = s.getMiningSpeedMultiplier(state);
			boolean ok = !state.isToolRequired() || s.isSuitableFor(state);
			if ((ok && !bestOk) || (ok == bestOk && speed > bestSpeed + 0.01F)) {
				best = s;
				bestSpeed = speed;
				bestOk = ok;
			}
		}
		if (best != null) {
			final ItemStack chosen = best;
			c.selectItem(s -> s == chosen);
		} else if (bestSpeed <= 1.0F && !held.isEmpty() && held.isDamageable()) {
			// Don't wear out a sword or tool on something it isn't good for: use the hand.
			c.emptyMainHand();
		}
	}

	/** True if the block can be harvested (dropped) with something the companion owns or holds. */
	public static boolean canHarvest(CompanionEntity c, BlockState state) {
		if (c.isCreativeMode() || !state.isToolRequired()) return true;
		if (c.getMainHandStack().isSuitableFor(state)) return true;
		for (int i = 0; i < c.getInventory().size(); i++) {
			if (c.getInventory().getStack(i).isSuitableFor(state)) return true;
		}
		return false;
	}
}
