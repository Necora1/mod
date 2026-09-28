package com.necora.aicompanion.task;

import com.necora.aicompanion.AICompanionMod;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.CompanionMovement;
import com.necora.aicompanion.entity.Stance;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.util.ItemUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reflex layer: self-defence, defending the owner, bodyguarding and explicit attack orders.
 * While it has a target it controls the body and normal tasks are paused.
 */
public class CombatController {
	private static final Identifier CRIT_MODIFIER = Identifier.of(AICompanionMod.MOD_ID, "critical_hit");

	private final CompanionEntity c;
	@Nullable
	private LivingEntity target;
	@Nullable
	private LivingEntity forcedTarget;
	@Nullable
	private LivingEntity lastAttacker;
	private int lastAttackerAge = -1000;
	private int attackCooldown;
	private int scanCooldown;
	private boolean pendingCrit;
	private int critTicks;
	private int retreatTicks;
	private int noProgressTicks;
	private double bestDistance = Double.MAX_VALUE;
	private final Map<UUID, Integer> ignoreUntil = new HashMap<>();

	public CombatController(CompanionEntity companion) {
		this.c = companion;
	}

	@Nullable
	public LivingEntity getTarget() {
		return target;
	}

	public boolean isFighting() {
		return target != null;
	}

	public void setForcedTarget(@Nullable LivingEntity entity) {
		this.forcedTarget = entity;
		if (entity != null) {
			this.target = entity;
			resetProgress();
		}
	}

	@Nullable
	public LivingEntity getForcedTarget() {
		return forcedTarget;
	}

	public void stop() {
		forcedTarget = null;
		target = null;
		lastAttacker = null;
		pendingCrit = false;
	}

	public void onDamaged(DamageSource source) {
		Entity attacker = source.getAttacker();
		if (attacker instanceof LivingEntity living && living != c && !c.isFriendly(living)) {
			lastAttacker = living;
			lastAttackerAge = c.age;
			scanCooldown = 0;
		}
	}

	/** Returns true if combat is controlling the companion this tick. */
	public boolean tick() {
		if (attackCooldown > 0) attackCooldown--;
		if (--scanCooldown <= 0) {
			scanCooldown = 10;
			LivingEntity next = pickTarget();
			if (next != target) {
				target = next;
				resetProgress();
				if (target != null) equipBestWeapon();
			}
		}
		if (target != null && !isValid(target)) {
			if (target == forcedTarget) forcedTarget = null;
			target = null;
			pendingCrit = false;
		}
		if (target == null) return false;
		if (c.isUsingItem() && retreatTicks <= 0) c.stopUsingItem();
		engage(target);
		return true;
	}

	private void resetProgress() {
		noProgressTicks = 0;
		bestDistance = Double.MAX_VALUE;
		pendingCrit = false;
	}

	private boolean isValid(LivingEntity e) {
		if (e == null || !e.isAlive() || e.isRemoved() || e.getWorld() != c.getWorld()) return false;
		if (e == c || c.isFriendly(e)) return false;
		if (e.isInvulnerable()) return false;
		if (e instanceof PlayerEntity p && (p.isCreative() || p.isSpectator())) return false;
		if (c.squaredDistanceTo(e) > 40 * 40) return false;
		Integer until = ignoreUntil.get(e.getUuid());
		return until == null || until <= c.age;
	}

	private boolean isHostile(LivingEntity e) {
		if (!(e instanceof Monster)) return false;
		if (e instanceof Angerable angerable && !angerable.hasAngerTime()) {
			// neutral mobs (endermen, zombified piglins...) only when they're angry
			return false;
		}
		return true;
	}

	@Nullable
	private LivingEntity pickTarget() {
		if (forcedTarget != null && isValid(forcedTarget)) return forcedTarget;
		forcedTarget = null;
		Stance stance = c.getStance();
		if (stance == Stance.PASSIVE) return null;
		if (target != null && isValid(target) && c.squaredDistanceTo(target) < 24 * 24) return target;

		if (lastAttacker != null && c.age - lastAttackerAge < 400 && isValid(lastAttacker) && c.squaredDistanceTo(lastAttacker) < 24 * 24) {
			return lastAttacker;
		}
		ServerPlayerEntity owner = c.getOwner();
		if (owner != null && owner.getWorld() == c.getWorld() && owner.squaredDistanceTo(c) < 32 * 32) {
			LivingEntity ownerAttacker = owner.getAttacker();
			if (ownerAttacker != null && isValid(ownerAttacker) && ownerAttacker.squaredDistanceTo(owner) < 24 * 24) {
				return ownerAttacker;
			}
		}

		// Mobs going after us or the owner.
		Box box = c.getBoundingBox().expand(16, 8, 16);
		LivingEntity best = null;
		double bestSq = Double.MAX_VALUE;
		List<MobEntity> mobs = c.getWorld().getEntitiesByClass(MobEntity.class, box, m -> m.isAlive() && isValid(m));
		for (MobEntity mob : mobs) {
			LivingEntity mobTarget = mob.getTarget();
			if (mobTarget != null && (mobTarget == c || c.isFriendly(mobTarget)) && isHostileOrAngry(mob)) {
				double d = c.squaredDistanceTo(mob);
				if (d < bestSq) {
					bestSq = d;
					best = mob;
				}
			}
		}
		if (best != null) return best;

		// Bodyguard duty.
		LivingEntity guarded = c.getTaskManager().getGuardTarget();
		if (guarded != null && guarded.getWorld() == c.getWorld()) {
			double radius = c.getTaskManager().getGuardRadius();
			Box guardBox = guarded.getBoundingBox().expand(radius, 6, radius);
			for (MobEntity mob : c.getWorld().getEntitiesByClass(MobEntity.class, guardBox, m -> m.isAlive() && isValid(m) && isHostile(m))) {
				double d = mob.squaredDistanceTo(guarded);
				if (d < bestSq && (c.canSee(mob) || d < 36)) {
					bestSq = d;
					best = mob;
				}
			}
			if (best != null) return best;
		}

		if (stance == Stance.AGGRESSIVE) {
			for (MobEntity mob : c.getWorld().getEntitiesByClass(MobEntity.class, c.getBoundingBox().expand(12, 6, 12), m -> m.isAlive() && isValid(m) && isHostile(m))) {
				double d = c.squaredDistanceTo(mob);
				if (d < bestSq && c.canSee(mob)) {
					bestSq = d;
					best = mob;
				}
			}
		}
		return best;
	}

	private boolean isHostileOrAngry(MobEntity mob) {
		return mob instanceof Monster || (mob instanceof Angerable a && a.hasAngerTime());
	}

	private void engage(LivingEntity t) {
		CompanionMovement movement = c.getMovement();
		c.getLookControl().lookAt(t, 30.0F, 30.0F);
		double distSq = c.squaredDistanceTo(t);

		// Low health: back off and eat, like a player would.
		if (!c.isCreativeMode() && c.getHealth() <= 6.0F && retreatTicks <= 0 && distSq < 64 && c.countItems(ItemUtil::isGoodFood) > 0) {
			retreatTicks = 80;
		}
		if (retreatTicks > 0) {
			retreatTicks--;
			Vec3d away = c.getPos().subtract(t.getPos());
			if (away.lengthSquared() < 1.0E-4) away = new Vec3d(1, 0, 0);
			Vec3d dest = c.getPos().add(away.normalize().multiply(8));
			movement.moveTo(dest, CompanionEntity.SPRINT_SPEED, 1.0);
			if (retreatTicks < 60 && !c.isUsingItem()) c.startEating();
			return;
		}

		// Creepers: hit them, then back off when they start hissing.
		if (t instanceof CreeperEntity creeper && (creeper.getFuseSpeed() > 0 || creeper.isIgnited()) && distSq < 36) {
			Vec3d away = c.getPos().subtract(t.getPos()).normalize();
			movement.moveTo(c.getPos().add(away.multiply(7)), CompanionEntity.SPRINT_SPEED, 1.0);
			return;
		}

		double reach = c.getEntityReach();
		Vec3d eye = c.getEyePos();
		Vec3d closest = closestPoint(t.getBoundingBox(), eye);
		double reachDist = eye.distanceTo(closest);
		boolean inRange = reachDist <= reach;

		if (!inRange) {
			CompanionMovement.Status st;
			if (c.isCreativeMode() && (Math.abs(t.getY() - c.getY()) > 3 || movement.isFlying())) {
				st = movement.flyTo(t.getPos().add(0, 0.5, 0), 1.2);
			} else {
				st = movement.moveTo(t.getPos(), CompanionEntity.SPRINT_SPEED, Math.max(1.0, reach - 1.0));
			}
			if (distSq < bestDistance - 0.5) {
				bestDistance = distSq;
				noProgressTicks = 0;
			} else if (++noProgressTicks > 100 || st == CompanionMovement.Status.FAILED) {
				// can't reach it (flying, across a ravine...) - give up on it for a while
				ignoreUntil.put(t.getUuid(), c.age + 300);
				if (t == forcedTarget) forcedTarget = null;
				target = null;
				return;
			}
		} else {
			// close in a little like players do, but don't hug the target
			if (distSq > 4.0) movement.moveTo(t.getPos(), CompanionEntity.WALK_SPEED, 2.0);
			else movement.keepAlive();
		}

		if (pendingCrit) {
			critTicks++;
			boolean falling = !c.isOnGround() && c.getVelocity().y < -0.02;
			if (falling && inRange) {
				attack(t, true);
			} else if ((c.isOnGround() && critTicks > 4) || critTicks > 20) {
				pendingCrit = false;
				if (inRange && attackCooldown <= 0) attack(t, false);
			}
			return;
		}
		if (inRange && attackCooldown <= 0 && c.canSee(t)) {
			if (c.isOnGround() && !c.isTouchingWater() && !c.getMovement().isFlying() && c.getRandom().nextFloat() < 0.35F) {
				c.getJumpControl().setActive();
				pendingCrit = true;
				critTicks = 0;
			} else {
				attack(t, false);
			}
		}
	}

	private static Vec3d closestPoint(Box box, Vec3d p) {
		return new Vec3d(
				Math.max(box.minX, Math.min(p.x, box.maxX)),
				Math.max(box.minY, Math.min(p.y, box.maxY)),
				Math.max(box.minZ, Math.min(p.z, box.maxZ)));
	}

	private void attack(LivingEntity t, boolean crit) {
		pendingCrit = false;
		c.swingHand(Hand.MAIN_HAND);
		EntityAttributeInstance dmg = c.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
		if (crit && dmg != null && !dmg.hasModifier(CRIT_MODIFIER)) {
			dmg.addTemporaryModifier(new EntityAttributeModifier(CRIT_MODIFIER, 0.5, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		boolean hit;
		try {
			hit = c.tryAttack(t);
		} finally {
			if (dmg != null) dmg.removeModifier(CRIT_MODIFIER);
		}
		SoundEvent sound;
		if (!hit) {
			sound = SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE;
		} else if (crit) {
			sound = SoundEvents.ENTITY_PLAYER_ATTACK_CRIT;
			if (c.getWorld() instanceof ServerWorld sw) {
				sw.spawnParticles(ParticleTypes.CRIT, t.getX(), t.getBodyY(0.5), t.getZ(), 12, 0.3, 0.4, 0.3, 0.25);
			}
		} else {
			sound = ItemUtil.attackDamage(c.getMainHandStack()) > 2 ? SoundEvents.ENTITY_PLAYER_ATTACK_STRONG : SoundEvents.ENTITY_PLAYER_ATTACK_WEAK;
		}
		c.getWorld().playSound(null, c.getX(), c.getY(), c.getZ(), sound, SoundCategory.PLAYERS, 1.0F, 1.0F);
		if (hit) {
			ItemStack weapon = c.getMainHandStack();
			if (!c.isCreativeMode() && weapon.isDamageable()) {
				weapon.damage(ItemUtil.isWeapon(weapon) && !ItemUtil.isTool(weapon) ? 1 : 2, c, EquipmentSlot.MAINHAND);
			}
			if (!t.isAlive()) onKill(t);
		}
		c.addExhaustion(0.1F);
		double speed = Math.max(0.5, c.getAttributeValue(EntityAttributes.GENERIC_ATTACK_SPEED));
		attackCooldown = (int) Math.ceil(20.0 / speed);
	}

	private void onKill(LivingEntity t) {
		CompanionBrain brain = CompanionManager.brainOf(c);
		if (brain != null) brain.onKilled(t);
		if (t == forcedTarget) forcedTarget = null;
		if (t == lastAttacker) lastAttacker = null;
	}

	/** Holds the best weapon from the inventory. */
	public void equipBestWeapon() {
		ItemStack held = c.getMainHandStack();
		double best = ItemUtil.attackDamage(held) + (ItemUtil.isWeapon(held) ? 0.5 : 0);
		ItemStack pick = null;
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (s.isEmpty()) continue;
			double v = ItemUtil.attackDamage(s) + (ItemUtil.isWeapon(s) ? 0.5 : 0);
			if (v > best + 0.01) {
				best = v;
				pick = s;
			}
		}
		if (pick != null) {
			final ItemStack chosen = pick;
			c.selectItem(s -> s == chosen);
		}
	}
}
