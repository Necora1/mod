package com.necora.aicompanion.entity;

import com.necora.aicompanion.AICompanionMod;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.net.GuiServer;
import com.necora.aicompanion.task.CombatController;
import com.necora.aicompanion.task.TaskManager;
import com.necora.aicompanion.util.ItemUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.goal.LongDoorInteractGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The companion's body. It looks, sounds and moves like a player; the "mind" lives in
 * {@link CompanionBrain} (LLM + memory) and tasks are run by {@link TaskManager}.
 */
public class CompanionEntity extends PathAwareEntity {
	public static final int INVENTORY_SIZE = 39;
	public static final double WALK_SPEED = 1.0;
	public static final double SPRINT_SPEED = 1.35;

	private static final TrackedData<String> SKIN_NAME = DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<String> SKIN_VALUE = DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<String> SKIN_SIGNATURE = DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.STRING);
	private static final TrackedData<Boolean> CREATIVE = DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<Boolean> FLYING = DataTracker.registerData(CompanionEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

	private static final ChunkTicketType<ChunkPos> CHUNK_TICKET = ChunkTicketType.create("aicompanion", Comparator.comparingLong(ChunkPos::toLong), 60);
	private static final EntityDimensions CROUCHING_DIMENSIONS = EntityDimensions.changing(0.6F, 1.5F).withEyeHeight(1.27F);

	private final SimpleInventory inventory = new SimpleInventory(INVENTORY_SIZE);
	@Nullable
	private UUID ownerUuid;
	private String companionName = "Companion";
	private GameModeSetting gameModeSetting = GameModeSetting.AUTO;
	private Stance stance = Stance.DEFENSIVE;

	private final CompanionMovement movement;
	private final TaskManager taskManager;
	private final CombatController combat;

	// Player-like hunger
	private int foodLevel = 20;
	private float saturation = 5.0F;
	private float exhaustion = 0.0F;
	private int foodTickTimer = 0;
	private Vec3d lastTickPos = Vec3d.ZERO;

	// Chat waiting to be "typed"
	private final Deque<PendingChat> pendingChat = new ArrayDeque<>();
	private int chatCooldown = 0;

	private int sneakTicks = 0;
	@Nullable
	private Entity focus;
	private int focusTicks;
	/** Item conjured into the hand while building in creative (not a real inventory item). */
	@Nullable
	private ItemStack displayStack;
	private int lastBlockActionTick = 0;
	private boolean deathHandled = false;

	private record PendingChat(String message, int delay) {
	}

	public CompanionEntity(EntityType<? extends CompanionEntity> type, World world) {
		super(type, world);
		this.setPersistent();
		this.setCanPickUpLoot(false);
		this.setCustomNameVisible(true);
		this.experiencePoints = 0;
		this.movement = new CompanionMovement(this);
		this.taskManager = new TaskManager(this);
		this.combat = new CombatController(this);
	}

	public static DefaultAttributeContainer.Builder createCompanionAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
				.add(EntityAttributes.GENERIC_ATTACK_SPEED, 4.0)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0)
				.add(EntityAttributes.GENERIC_LUCK)
				.add(EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE, 4.5)
				.add(EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE, 3.0)
				.add(EntityAttributes.PLAYER_BLOCK_BREAK_SPEED)
				.add(EntityAttributes.PLAYER_MINING_EFFICIENCY)
				.add(EntityAttributes.PLAYER_SUBMERGED_MINING_SPEED);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(SKIN_NAME, "");
		builder.add(SKIN_VALUE, "");
		builder.add(SKIN_SIGNATURE, "");
		builder.add(CREATIVE, false);
		builder.add(FLYING, false);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(1, new LongDoorInteractGoal(this, true));
		this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
		this.goalSelector.add(9, new LookAroundGoal(this));
	}

	@Override
	protected EntityNavigation createNavigation(World world) {
		MobNavigation nav = new MobNavigation(this, world);
		nav.setCanPathThroughDoors(true);
		nav.setCanEnterOpenDoors(true);
		nav.setCanSwim(true);
		return nav;
	}

	// ------------------------------------------------------------------
	// Accessors
	// ------------------------------------------------------------------

	public SimpleInventory getInventory() {
		return inventory;
	}

	public CompanionMovement getMover() {
		return movement;
	}

	public TaskManager getTaskManager() {
		return taskManager;
	}

	public CombatController getCombat() {
		return combat;
	}

	public String getCompanionName() {
		return companionName;
	}

	public void setCompanionName(String name) {
		this.companionName = name;
		this.setCustomName(Text.literal(name));
		this.setCustomNameVisible(true);
	}

	@Nullable
	public UUID getOwnerUuid() {
		return ownerUuid;
	}

	public void setOwnerUuid(@Nullable UUID uuid) {
		this.ownerUuid = uuid;
	}

	@Nullable
	public ServerPlayerEntity getOwner() {
		if (ownerUuid == null || !(getWorld() instanceof ServerWorld sw)) return null;
		return sw.getServer().getPlayerManager().getPlayer(ownerUuid);
	}

	public boolean isOwner(Entity entity) {
		return entity != null && ownerUuid != null && ownerUuid.equals(entity.getUuid());
	}

	/** Owner, trusted players, or anyone the owner allowed. */
	public boolean canTakeOrdersFrom(PlayerEntity player) {
		if (isOwner(player)) return true;
		CompanionBrain brain = CompanionManager.brainOf(this);
		return brain != null && brain.getMemory().isTrusted(player.getGameProfile().getName());
	}

	public boolean isFriendly(Entity entity) {
		if (entity == null) return false;
		if (entity == this || isOwner(entity)) return true;
		if (entity instanceof CompanionEntity other) {
			return other.ownerUuid != null && other.ownerUuid.equals(this.ownerUuid);
		}
		if (entity instanceof net.minecraft.entity.passive.TameableEntity tame) {
			return tame.getOwnerUuid() != null && tame.getOwnerUuid().equals(this.ownerUuid);
		}
		if (entity instanceof PlayerEntity p) {
			CompanionBrain brain = CompanionManager.brainOf(this);
			return brain != null && brain.getMemory().isTrusted(p.getGameProfile().getName());
		}
		return false;
	}

	public Stance getStance() {
		return stance;
	}

	public void setStance(Stance stance) {
		this.stance = stance;
	}

	public GameModeSetting getGameModeSetting() {
		return gameModeSetting;
	}

	public void setGameModeSetting(GameModeSetting setting) {
		this.gameModeSetting = setting;
		refreshGameMode();
	}

	public boolean isCreativeMode() {
		return this.dataTracker.get(CREATIVE);
	}

	public boolean isFlyingTracked() {
		return this.dataTracker.get(FLYING);
	}

	void setFlyingTracked(boolean flying) {
		this.dataTracker.set(FLYING, flying);
	}

	public String getSkinName() {
		return this.dataTracker.get(SKIN_NAME);
	}

	public String getSkinValue() {
		return this.dataTracker.get(SKIN_VALUE);
	}

	public String getSkinSignature() {
		return this.dataTracker.get(SKIN_SIGNATURE);
	}

	public void setSkin(String name, String value, String signature) {
		this.dataTracker.set(SKIN_NAME, name == null ? "" : name);
		this.dataTracker.set(SKIN_VALUE, value == null ? "" : value);
		this.dataTracker.set(SKIN_SIGNATURE, signature == null ? "" : signature);
	}

	public int getFoodLevel() {
		return foodLevel;
	}

	public void addExhaustion(float amount) {
		if (!isCreativeMode()) exhaustion = Math.min(40.0F, exhaustion + amount);
	}

	public double getBlockReach() {
		double base = this.getAttributeValue(EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE);
		return isCreativeMode() ? base + 0.5 : base;
	}

	public double getEntityReach() {
		double base = this.getAttributeValue(EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE);
		return isCreativeMode() ? base + 2.0 : base;
	}

	// ------------------------------------------------------------------
	// Ticking
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (!this.getWorld().isClient && this.isAlive()) {
			if (this.age % 20 == 0) refreshGameMode();
			tickHunger();
			tickChat();
			tickSneak();
			if (this.age % 20 == 0 && this.getWorld() instanceof ServerWorld sw) {
				keepChunkLoaded(sw);
			}
		}
	}

	@Override
	protected void mobTick() {
		super.mobTick();
		if (!(this.getWorld() instanceof ServerWorld)) return;
		movement.tick();
		boolean fighting = combat.tick();
		if (!fighting) {
			if (!this.isUsingItem()) {
				taskManager.tick();
			}
			tickAutoEat();
		}
		if (focusTicks > 0) {
			focusTicks--;
			if (focus != null && focus.isAlive() && !fighting && !taskManager.isBusy() && focus.getWorld() == this.getWorld()) {
				this.getLookControl().lookAt(focus, 30.0F, 30.0F);
			}
		}
	}

	/** Turn toward someone for a moment (e.g. when replying to them), like a player would. */
	public void focusOn(@Nullable Entity entity, int ticks) {
		this.focus = entity;
		this.focusTicks = entity == null ? 0 : ticks;
	}

	@Override
	public void tickMovement() {
		this.tickHandSwing();
		super.tickMovement();
		if (!this.getWorld().isClient && this.isAlive() && !this.isSpectatorLike()) {
			pickUpNearbyItems();
		}
	}

	private boolean isSpectatorLike() {
		return this.deathHandled || this.isRemoved();
	}

	@Override
	public void travel(Vec3d movementInput) {
		if (movement.isFlying()) {
			// Creative flight: velocity is driven by CompanionMovement, we just integrate it.
			this.move(MovementType.SELF, this.getVelocity());
			this.setVelocity(this.getVelocity().multiply(0.91));
			this.fallDistance = 0;
			this.updateLimbs(false);
			return;
		}
		super.travel(movementInput);
	}

	private void refreshGameMode() {
		boolean creative = switch (gameModeSetting) {
			case CREATIVE -> true;
			case SURVIVAL -> false;
			case AUTO -> {
				ServerPlayerEntity owner = getOwner();
				yield owner != null ? owner.isCreative() : isCreativeMode();
			}
		};
		if (creative != isCreativeMode()) {
			this.dataTracker.set(CREATIVE, creative);
			if (creative) {
				this.setHealth(this.getMaxHealth());
				this.foodLevel = 20;
				this.extinguish();
			} else if (movement.isFlying()) {
				movement.land();
			}
		}
	}

	private void keepChunkLoaded(ServerWorld world) {
		if (!CompanionConfig.get().keepChunksLoaded) return;
		ChunkPos pos = new ChunkPos(this.getBlockPos());
		world.getChunkManager().addTicket(CHUNK_TICKET, pos, 3, pos);
	}

	// ------------------------------------------------------------------
	// Hunger & health (simplified player rules)
	// ------------------------------------------------------------------

	private void tickHunger() {
		CompanionConfig cfg = CompanionConfig.get();
		Vec3d pos = this.getPos();
		double moved = Math.sqrt(MathHelper.square(pos.x - lastTickPos.x) + MathHelper.square(pos.z - lastTickPos.z));
		lastTickPos = pos;
		if (isCreativeMode() || !cfg.hunger) {
			foodLevel = 20;
			if (this.age % 40 == 0 && this.getHealth() < this.getMaxHealth()) this.heal(1.0F);
			return;
		}
		if (this.isSprinting() && moved < 1.0) addExhaustion((float) (0.1 * moved));
		if (this.isTouchingWater() && moved < 1.0) addExhaustion((float) (0.01 * moved));

		if (exhaustion > 4.0F) {
			exhaustion -= 4.0F;
			if (saturation > 0) saturation = Math.max(0, saturation - 1.0F);
			else foodLevel = Math.max(0, foodLevel - 1);
		}
		boolean peaceful = this.getWorld().getDifficulty() == Difficulty.PEACEFUL;
		if (peaceful && this.age % 20 == 0) {
			if (this.getHealth() < this.getMaxHealth()) this.heal(1.0F);
			if (foodLevel < 20) foodLevel++;
		}
		foodTickTimer++;
		if (saturation > 0 && foodLevel >= 20 && this.getHealth() < this.getMaxHealth()) {
			if (foodTickTimer >= 10) {
				float amount = Math.min(saturation, 6.0F);
				this.heal(amount / 6.0F);
				addExhaustion(amount);
				foodTickTimer = 0;
			}
		} else if (foodLevel >= 18 && this.getHealth() < this.getMaxHealth()) {
			if (foodTickTimer >= 80) {
				this.heal(1.0F);
				addExhaustion(6.0F);
				foodTickTimer = 0;
			}
		} else if (foodLevel <= 0) {
			if (foodTickTimer >= 80) {
				Difficulty d = this.getWorld().getDifficulty();
				if (this.getHealth() > 10.0F || d == Difficulty.HARD || (this.getHealth() > 1.0F && d == Difficulty.NORMAL)) {
					this.damage(this.getDamageSources().starve(), 1.0F);
				}
				foodTickTimer = 0;
			}
		} else {
			foodTickTimer = 0;
		}
	}

	private void tickAutoEat() {
		if (isCreativeMode() || this.isUsingItem() || this.age % 20 != 0) return;
		boolean hungry = foodLevel <= 14 || (foodLevel < 20 && this.getHealth() < this.getMaxHealth() - 6);
		if (hungry) startEating();
	}

	/** Starts eating the best food in the inventory. Returns false if there's nothing to eat. */
	public boolean startEating() {
		if (this.isUsingItem()) return true;
		if (!selectItem(ItemUtil::isGoodFood)) return false;
		this.setCurrentHand(Hand.MAIN_HAND);
		return true;
	}

	@Override
	public ItemStack eatFood(World world, ItemStack stack, FoodComponent food) {
		if (!world.isClient) {
			foodLevel = Math.min(20, foodLevel + food.nutrition());
			saturation = Math.min(foodLevel, saturation + food.saturation());
			world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5F, world.random.nextFloat() * 0.1F + 0.9F);
		}
		return super.eatFood(world, stack, food);
	}

	// ------------------------------------------------------------------
	// Chat
	// ------------------------------------------------------------------

	/** Queues a chat message. With typing delay enabled, it appears after a short, human-like pause. */
	public void queueChat(String message, int extraDelayTicks) {
		if (message == null) return;
		String msg = message.replace('\n', ' ').replaceAll("\\s+", " ").trim();
		if (msg.isEmpty()) return;
		for (String part : splitChat(msg)) {
			int delay = extraDelayTicks;
			if (CompanionConfig.get().typingDelay) {
				delay += MathHelper.clamp(part.length() / 3, 4, 50);
			}
			pendingChat.add(new PendingChat(part, delay));
			extraDelayTicks = 0;
		}
	}

	private static java.util.List<String> splitChat(String msg) {
		java.util.List<String> out = new java.util.ArrayList<>();
		while (msg.length() > 240) {
			int cut = msg.lastIndexOf(". ", 240);
			if (cut < 80) cut = msg.lastIndexOf(' ', 240);
			if (cut < 80) cut = 240;
			out.add(msg.substring(0, cut + (msg.charAt(cut) == '.' ? 1 : 0)).trim());
			msg = msg.substring(cut + 1).trim();
		}
		if (!msg.isEmpty()) out.add(msg);
		return out;
	}

	private void tickChat() {
		if (chatCooldown > 0) {
			chatCooldown--;
			return;
		}
		PendingChat next = pendingChat.peek();
		if (next == null) return;
		pendingChat.poll();
		if (next.delay() > 0) {
			chatCooldown = next.delay();
			pendingChat.addFirst(new PendingChat(next.message(), 0));
			return;
		}
		sayNow(next.message());
		chatCooldown = 6;
	}

	/** Sends a chat line formatted exactly like a player's: {@code <Name> message}. */
	public void sayNow(String message) {
		if (!(this.getWorld() instanceof ServerWorld sw)) return;
		Text line = Text.translatable("chat.type.text", Text.literal(companionName), Text.literal(message));
		MinecraftServer server = sw.getServer();
		int range = CompanionConfig.get().chatRange;
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (range <= 0 || isOwner(player) || (player.getWorld() == this.getWorld() && player.squaredDistanceTo(this) <= (double) range * range)) {
				player.sendMessage(line, false);
			}
		}
		AICompanionMod.LOGGER.info("<{}> {}", companionName, message);
	}

	/** Grey system-style message to the owner only (errors, hints). */
	public void notifyOwner(String message) {
		ServerPlayerEntity owner = getOwner();
		if (owner != null) {
			owner.sendMessage(Text.literal("[" + companionName + "] " + message).formatted(Formatting.GRAY, Formatting.ITALIC), false);
		}
	}

	// ------------------------------------------------------------------
	// Emotes / poses
	// ------------------------------------------------------------------

	public void setCrouching(boolean crouch) {
		this.setSneaking(crouch);
		this.setPose(crouch ? EntityPose.CROUCHING : EntityPose.STANDING);
	}

	/** Crouches for a few ticks (used by emotes). */
	public void crouchFor(int ticks) {
		setCrouching(true);
		sneakTicks = ticks;
	}

	private void tickSneak() {
		if (sneakTicks > 0 && --sneakTicks == 0) setCrouching(false);
	}

	@Override
	public EntityDimensions getBaseDimensions(EntityPose pose) {
		if (pose == EntityPose.CROUCHING) return CROUCHING_DIMENSIONS.scaled(this.getScaleFactor());
		return super.getBaseDimensions(pose);
	}

	@Override
	public void jump() {
		super.jump();
		addExhaustion(this.isSprinting() ? 0.2F : 0.05F);
	}

	// ------------------------------------------------------------------
	// Inventory helpers
	// ------------------------------------------------------------------

	/** Counts matching items in inventory, hands and armor. */
	public int countItems(Predicate<ItemStack> predicate) {
		int n = 0;
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack s = inventory.getStack(i);
			if (!s.isEmpty() && predicate.test(s)) n += s.getCount();
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack s = this.getEquippedStack(slot);
			if (!s.isEmpty() && predicate.test(s)) n += s.getCount();
		}
		return n;
	}

	public int countItem(Item item) {
		return countItems(s -> s.isOf(item));
	}

	/**
	 * Puts a matching stack into the main hand, like choosing a hotbar slot. The previously held item
	 * goes back into the inventory. Returns false if nothing matches.
	 */
	public boolean selectItem(Predicate<ItemStack> predicate) {
		ItemStack held = this.getMainHandStack();
		if (!held.isEmpty() && predicate.test(held)) return true;
		boolean fake = held == displayStack;
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack s = inventory.getStack(i);
			if (!s.isEmpty() && predicate.test(s)) {
				inventory.setStack(i, fake ? ItemStack.EMPTY : held.copy());
				this.equipStack(EquipmentSlot.MAINHAND, s);
				return true;
			}
		}
		ItemStack off = this.getOffHandStack();
		if (!off.isEmpty() && predicate.test(off)) {
			this.equipStack(EquipmentSlot.OFFHAND, fake ? ItemStack.EMPTY : held.copy());
			this.equipStack(EquipmentSlot.MAINHAND, off);
			return true;
		}
		return false;
	}

	/** Holds nothing (puts the main hand item away). */
	public void emptyMainHand() {
		ItemStack held = this.getMainHandStack();
		if (held.isEmpty()) return;
		if (held == displayStack) {
			this.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
			return;
		}
		ItemStack rest = inventory.addStack(held.copy());
		this.equipStack(EquipmentSlot.MAINHAND, rest);
	}

	/** Shows an item in hand without taking it from the inventory (creative building). */
	public void holdForDisplay(Item item) {
		ItemStack held = this.getMainHandStack();
		if (held.isOf(item)) return;
		if (!held.isEmpty() && held != displayStack) {
			// a real item: put it back in the inventory
			ItemStack rest = inventory.addStack(held.copy());
			if (!rest.isEmpty()) this.dropStack(rest);
		}
		displayStack = new ItemStack(item);
		this.equipStack(EquipmentSlot.MAINHAND, displayStack);
	}

	/**
	 * Adds a stack to the inventory (auto-equipping better armor). Returns what didn't fit.
	 */
	public ItemStack insertStack(ItemStack stack) {
		if (stack.isEmpty()) return ItemStack.EMPTY;
		if (stack.getItem() instanceof ArmorItem armor) {
			EquipmentSlot slot = armor.getSlotType();
			ItemStack current = this.getEquippedStack(slot);
			if (current.isEmpty() || ItemUtil.armorValue(stack) > ItemUtil.armorValue(current)) {
				this.equipStack(slot, stack.copy());
				if (!current.isEmpty()) {
					ItemStack rest = inventory.addStack(current);
					if (!rest.isEmpty()) this.dropStack(rest);
				}
				return ItemStack.EMPTY;
			}
		}
		return inventory.addStack(stack);
	}

	/** Removes up to {@code count} matching items from inventory and hands. Returns the removed amount. */
	public int removeItems(Predicate<ItemStack> predicate, int count) {
		int remaining = count;
		for (int i = 0; i < inventory.size() && remaining > 0; i++) {
			ItemStack s = inventory.getStack(i);
			if (!s.isEmpty() && predicate.test(s)) {
				int take = Math.min(remaining, s.getCount());
				s.decrement(take);
				remaining -= take;
			}
		}
		for (Hand hand : Hand.values()) {
			ItemStack s = this.getStackInHand(hand);
			if (remaining > 0 && !s.isEmpty() && predicate.test(s)) {
				int take = Math.min(remaining, s.getCount());
				s.decrement(take);
				remaining -= take;
			}
		}
		inventory.markDirty();
		return count - remaining;
	}

	public boolean hasFreeSpaceFor(ItemStack stack) {
		return inventory.canInsert(stack);
	}

	public boolean isInventoryFull() {
		for (int i = 0; i < inventory.size(); i++) {
			if (inventory.getStack(i).isEmpty()) return false;
		}
		return true;
	}

	private void pickUpNearbyItems() {
		Box box = this.getBoundingBox().expand(1.0, 0.5, 1.0);
		for (ItemEntity item : this.getWorld().getEntitiesByClass(ItemEntity.class, box, e -> !e.isRemoved() && !e.getStack().isEmpty() && !e.cannotPickup())) {
			if (item.owner != null && !item.owner.equals(this.getUuid())) continue;
			Entity thrower = item.getOwner();
			if (thrower == this && item.getItemAge() < 200) continue;
			ItemStack stack = item.getStack();
			int before = stack.getCount();
			ItemStack rest = insertStack(stack.copy());
			int taken = before - rest.getCount();
			if (taken <= 0) continue;
			ItemStack takenStack = stack.copyWithCount(taken);
			this.sendPickup(item, taken);
			if (rest.isEmpty()) item.discard();
			else item.setStack(rest);
			if (thrower instanceof PlayerEntity giver && thrower != this) {
				CompanionBrain brain = CompanionManager.brainOf(this);
				if (brain != null) brain.onItemReceived(giver, takenStack);
			}
		}
	}

	/** Throws a stack toward a target position, like pressing Q while looking at someone. */
	public void throwStack(ItemStack stack, @Nullable Entity toward) {
		if (stack.isEmpty()) return;
		this.swingHand(Hand.MAIN_HAND);
		ItemEntity entity = new ItemEntity(this.getWorld(), this.getX(), this.getEyeY() - 0.3, this.getZ(), stack);
		entity.setPickupDelay(30);
		entity.setThrower(this);
		Vec3d dir;
		if (toward != null) {
			dir = toward.getEyePos().subtract(this.getEyePos());
			if (toward instanceof PlayerEntity) entity.setOwner(toward.getUuid());
		} else {
			dir = this.getRotationVector();
		}
		double len = Math.max(0.001, dir.length());
		double speed = Math.min(0.45, 0.12 + len * 0.06);
		entity.setVelocity(dir.x / len * speed, dir.y / len * speed + 0.15, dir.z / len * speed);
		this.getWorld().spawnEntity(entity);
	}

	// ------------------------------------------------------------------
	// Block interaction
	// ------------------------------------------------------------------

	public enum PlaceResult {PLACED, ALREADY_THERE, OBSTRUCTED, OCCUPIED, NO_ITEM, INVALID}

	public Vec3d getInteractionEyePos() {
		return this.getEyePos();
	}

	/** Distance from the eyes to the closest point of the block, like vanilla's reach check. */
	public double distanceToBlock(BlockPos pos) {
		return Math.sqrt(squaredDistanceToBlock(this.getEyePos(), pos));
	}

	public static double squaredDistanceToBlock(Vec3d eye, BlockPos pos) {
		double dx = Math.max(Math.max(pos.getX() - eye.x, eye.x - (pos.getX() + 1)), 0);
		double dy = Math.max(Math.max(pos.getY() - eye.y, eye.y - (pos.getY() + 1)), 0);
		double dz = Math.max(Math.max(pos.getZ() - eye.z, eye.z - (pos.getZ() + 1)), 0);
		return dx * dx + dy * dy + dz * dz;
	}

	public boolean canReachBlock(BlockPos pos, double reach) {
		return squaredDistanceToBlock(this.getEyePos(), pos) <= reach * reach;
	}

	public void lookAtBlock(BlockPos pos) {
		this.getLookControl().lookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30.0F, 30.0F);
	}

	public int ticksSinceLastBlockAction() {
		return this.age - lastBlockActionTick;
	}

	/**
	 * Places a block like a player would: needs the item (unless creative), swings the arm and
	 * plays the sound. Multi-block things (doors, beds, tall plants) are completed by the block itself.
	 */
	public PlaceResult placeBlock(BlockPos pos, BlockState state, boolean consumeItem) {
		World world = this.getWorld();
		if (!(world instanceof ServerWorld)) return PlaceResult.INVALID;
		BlockState current = world.getBlockState(pos);
		if (current.equals(state)) return PlaceResult.ALREADY_THERE;
		if (!current.isAir() && !current.isReplaceable()) return PlaceResult.OBSTRUCTED;
		if (!world.canPlace(state, pos, ShapeContext.absent())) return PlaceResult.OCCUPIED;
		if (!world.isInBuildLimit(pos)) return PlaceResult.INVALID;

		Item item = state.getBlock().asItem();
		boolean creative = isCreativeMode();
		ItemStack used;
		if (creative || !consumeItem) {
			if (item != Items.AIR) holdForDisplay(item);
			used = new ItemStack(item);
		} else {
			if (item == Items.AIR || !selectItem(s -> s.isOf(item))) return PlaceResult.NO_ITEM;
			used = this.getMainHandStack();
		}

		BlockState toPlace = state;
		Block block = state.getBlock();
		if (block instanceof PaneBlock || block instanceof FenceBlock || block instanceof WallBlock || block instanceof StairsBlock || block instanceof FenceGateBlock) {
			toPlace = Block.postProcessState(state, world, pos);
		}
		lookAtBlock(pos);
		if (!world.setBlockState(pos, toPlace, Block.NOTIFY_ALL)) return PlaceResult.INVALID;
		block.onPlaced(world, pos, toPlace, this, used.copy());
		this.swingHand(Hand.MAIN_HAND);
		BlockSoundGroup sounds = toPlace.getSoundGroup();
		world.playSound(null, pos, sounds.getPlaceSound(), SoundCategory.BLOCKS, (sounds.getVolume() + 1.0F) / 2.0F, sounds.getPitch() * 0.8F);
		world.emitGameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Emitter.of(this, toPlace));
		if (!creative && consumeItem) {
			this.getMainHandStack().decrement(1);
		}
		lastBlockActionTick = this.age;
		CompanionBrain brain = CompanionManager.brainOf(this);
		if (brain != null) brain.getMemory().stats.blocksPlaced++;
		return PlaceResult.PLACED;
	}

	/** Called by the block breaker when a block is finished. */
	public void onBlockBroken() {
		lastBlockActionTick = this.age;
		addExhaustion(0.005F);
		CompanionBrain brain = CompanionManager.brainOf(this);
		if (brain != null) brain.getMemory().stats.blocksBroken++;
	}

	/** Teleports next to a player (used when left far behind or across dimensions). */
	public boolean teleportNear(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();
		BlockPos base = player.getBlockPos();
		BlockPos spot = null;
		for (int attempt = 0; attempt < 24 && spot == null; attempt++) {
			int dx = this.random.nextInt(5) - 2;
			int dz = this.random.nextInt(5) - 2;
			if (Math.abs(dx) + Math.abs(dz) < 2) continue;
			BlockPos p = com.necora.aicompanion.util.WorldUtil.findStandableNear(world, base.add(dx, 0, dz), 3);
			if (p != null) spot = p;
		}
		if (spot == null) spot = base;
		movement.stop();
		if (movement.isFlying() && !player.getAbilities().flying) movement.land();
		Vec3d dest = Vec3d.ofBottomCenter(spot);
		this.fallDistance = 0;
		if (world == this.getWorld()) {
			this.refreshPositionAndAngles(dest.x, dest.y, dest.z, player.getYaw(), 0.0F);
			this.getNavigation().stop();
			return true;
		}
		return this.teleport(world, dest.x, dest.y, dest.z, java.util.Set.of(), player.getYaw(), 0.0F);
	}

	// ------------------------------------------------------------------
	// Interaction, damage & death
	// ------------------------------------------------------------------

	@Override
	protected ActionResult interactMob(PlayerEntity player, Hand hand) {
		if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
		if (this.getWorld().isClient) return ActionResult.SUCCESS;
		if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
		if (!canTakeOrdersFrom(sp)) {
			sp.sendMessage(Text.literal(companionName + " isn't your companion.").formatted(Formatting.GRAY), true);
			return ActionResult.CONSUME;
		}
		if (sp.isSneaking()) {
			taskManager.toggleFollowStay(sp);
		} else {
			CompanionBrain brain = CompanionManager.brainOf(this);
			// players with the mod installed get the companion menu, vanilla clients the inventory
			if (brain != null && GuiServer.hasGui(sp)) GuiServer.openFor(sp, brain);
			else openInventory(sp);
		}
		return ActionResult.SUCCESS;
	}

	public void openInventory(ServerPlayerEntity player) {
		CompanionInventoryView view = new CompanionInventoryView(this);
		player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
				(syncId, playerInventory, p) -> new GenericContainerScreenHandler(ScreenHandlerType.GENERIC_9X5, syncId, playerInventory, view, 5),
				Text.literal(companionName + "'s inventory")));
	}

	@Override
	public boolean canBeLeashed() {
		return false;
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		if (this.getWorld().isClient) return false;
		Entity attacker = source.getAttacker();
		if (attacker != null && isOwner(attacker) && !CompanionConfig.get().ownerCanHurtCompanion
				&& !source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			CompanionBrain brain = CompanionManager.brainOf(this);
			if (brain != null) brain.onPunchedByOwner();
			return false;
		}
		if (attacker instanceof CompanionEntity other && other != this && isFriendly(other)) return false;
		if (this.isUsingItem() && attacker != null) this.stopUsingItem();
		boolean hurt = super.damage(source, amount);
		if (hurt) {
			addExhaustion(0.1F);
			combat.onDamaged(source);
			CompanionBrain brain = CompanionManager.brainOf(this);
			if (brain != null) brain.onHurt(source, amount);
		}
		return hurt;
	}

	@Override
	public boolean isInvulnerableTo(DamageSource source) {
		if (isCreativeMode() && !source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
		return super.isInvulnerableTo(source);
	}

	@Override
	public void onDeath(DamageSource source) {
		if (!this.getWorld().isClient && !deathHandled) {
			deathHandled = true;
			Text message = this.getDamageTracker().getDeathMessage();
			CompanionManager.onCompanionDied(this, message);
		}
		super.onDeath(source);
	}

	@Override
	protected void dropInventory() {
		super.dropInventory();
		if (this.getWorld().getGameRules().getBoolean(GameRules.KEEP_INVENTORY)) {
			return; // kept in memory by CompanionManager.onCompanionDied
		}
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack s = inventory.removeStack(i);
			if (!s.isEmpty()) this.dropStack(s);
		}
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack s = this.getEquippedStack(slot);
			if (!s.isEmpty()) {
				if (s != displayStack) this.dropStack(s.copy());
				this.equipStack(slot, ItemStack.EMPTY);
			}
		}
	}

	@Override
	protected float getDropChance(EquipmentSlot slot) {
		// Everything is dropped (or kept) by dropInventory, like a player's inventory.
		return 0.0F;
	}

	@Override
	public boolean cannotDespawn() {
		return true;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	@Override
	@Nullable
	protected SoundEvent getAmbientSound() {
		return null;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.ENTITY_PLAYER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.ENTITY_PLAYER_DEATH;
	}

	@Override
	public LivingEntity.FallSounds getFallSounds() {
		return new LivingEntity.FallSounds(SoundEvents.ENTITY_PLAYER_SMALL_FALL, SoundEvents.ENTITY_PLAYER_BIG_FALL);
	}

	@Override
	protected SoundEvent getSwimSound() {
		return SoundEvents.ENTITY_PLAYER_SWIM;
	}

	@Override
	protected SoundEvent getSplashSound() {
		return SoundEvents.ENTITY_PLAYER_SPLASH;
	}

	@Override
	protected SoundEvent getHighSpeedSplashSound() {
		return SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED;
	}

	// ------------------------------------------------------------------
	// Saving
	// ------------------------------------------------------------------

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		if (ownerUuid != null) nbt.putUuid("CompanionOwner", ownerUuid);
		nbt.putString("CompanionName", companionName);
		nbt.put("CompanionInventory", inventory.toNbtList(this.getRegistryManager()));
		nbt.putString("GameModeSetting", gameModeSetting.id());
		nbt.putBoolean("CreativeNow", isCreativeMode());
		nbt.putString("Stance", stance.id());
		nbt.putInt("FoodLevel", foodLevel);
		nbt.putFloat("Saturation", saturation);
		nbt.putString("SkinName", getSkinName());
		nbt.putString("SkinValue", getSkinValue());
		nbt.putString("SkinSignature", getSkinSignature());
		nbt.put("Behavior", taskManager.writeBehavior());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.containsUuid("CompanionOwner")) ownerUuid = nbt.getUuid("CompanionOwner");
		if (nbt.contains("CompanionName")) setCompanionName(nbt.getString("CompanionName"));
		if (nbt.contains("CompanionInventory", NbtElement.LIST_TYPE)) {
			inventory.readNbtList(nbt.getList("CompanionInventory", NbtElement.COMPOUND_TYPE), this.getRegistryManager());
		}
		gameModeSetting = GameModeSetting.parse(nbt.getString("GameModeSetting"), GameModeSetting.AUTO);
		this.dataTracker.set(CREATIVE, nbt.getBoolean("CreativeNow"));
		stance = Stance.parse(nbt.getString("Stance"), Stance.DEFENSIVE);
		if (nbt.contains("FoodLevel")) foodLevel = nbt.getInt("FoodLevel");
		if (nbt.contains("Saturation")) saturation = nbt.getFloat("Saturation");
		setSkin(nbt.getString("SkinName"), nbt.getString("SkinValue"), nbt.getString("SkinSignature"));
		if (nbt.contains("Behavior", NbtElement.COMPOUND_TYPE)) taskManager.readBehavior(nbt.getCompound("Behavior"));
	}

	/** Inventory + equipment only; used to carry items over while the companion is away. */
	public NbtCompound writeItemsNbt() {
		NbtCompound nbt = new NbtCompound();
		nbt.put("Inventory", inventory.toNbtList(this.getRegistryManager()));
		NbtCompound equipment = new NbtCompound();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack s = this.getEquippedStack(slot);
			if (!s.isEmpty() && s != displayStack) equipment.put(slot.getName(), s.encode(this.getRegistryManager()));
		}
		nbt.put("Equipment", equipment);
		nbt.putInt("FoodLevel", foodLevel);
		nbt.putFloat("Health", this.getHealth());
		return nbt;
	}

	public void readItemsNbt(NbtCompound nbt, boolean restoreHealth) {
		if (nbt.contains("Inventory", NbtElement.LIST_TYPE)) {
			inventory.readNbtList(nbt.getList("Inventory", NbtElement.COMPOUND_TYPE), this.getRegistryManager());
		}
		NbtCompound equipment = nbt.getCompound("Equipment");
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (equipment.contains(slot.getName())) {
				ItemStack.fromNbt(this.getRegistryManager(), equipment.get(slot.getName())).ifPresent(s -> this.equipStack(slot, s));
			}
		}
		if (restoreHealth) {
			if (nbt.contains("FoodLevel")) foodLevel = nbt.getInt("FoodLevel");
			if (nbt.contains("Health")) this.setHealth(Math.max(1.0F, nbt.getFloat("Health")));
		}
	}
}
