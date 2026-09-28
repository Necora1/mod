package com.necora.aicompanion.manager;

import com.necora.aicompanion.AICompanionMod;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.GameModeSetting;
import com.necora.aicompanion.entity.Stance;
import com.necora.aicompanion.memory.CompanionMemory;
import com.necora.aicompanion.memory.MemoryStore;
import com.necora.aicompanion.registry.ModEntities;
import com.necora.aicompanion.util.SkinFetcher;
import com.necora.aicompanion.util.WorldUtil;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermiteEntity;
import net.minecraft.entity.mob.IllagerEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SilverfishEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.mob.WitchEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keeps track of every companion in the world: their brains (by name), their bodies (by entity
 * UUID), joining/leaving with their owner, respawning, and routing chat to them.
 */
public final class CompanionManager {
	private static CompanionManager instance;

	private final MinecraftServer server;
	private final Map<String, CompanionBrain> brains = new LinkedHashMap<>();
	private final Map<UUID, CompanionEntity> loaded = new HashMap<>();
	private final List<PendingSpawn> pendingSpawns = new ArrayList<>();
	private int ticks;

	private record PendingSpawn(String name, int atTick, boolean respawn, @Nullable String deathMessage) {
	}

	private CompanionManager(MinecraftServer server) {
		this.server = server;
	}

	@Nullable
	public static CompanionManager get() {
		return instance;
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			instance = new CompanionManager(server);
			instance.loadAll();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (instance != null) instance.saveAll();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> instance = null);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (instance != null) instance.tick();
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof CompanionEntity companion) {
				if (instance != null) instance.onLoad(companion);
			} else if (entity instanceof MobEntity mob) {
				addCompanionTargeting(mob);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof CompanionEntity companion && instance != null) {
				instance.loaded.remove(companion.getUuid(), companion);
			}
		});
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			if (instance != null) instance.onChat(sender, message.getSignedContent());
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (instance != null) instance.onPlayerJoin(handler.player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (instance != null) instance.onPlayerLeave(handler.player);
		});
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			if (instance != null) instance.onOwnerChangedWorld(player, origin);
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
			if (instance != null && entity instanceof ServerPlayerEntity player && damage > 0) {
				for (CompanionBrain b : instance.brainsOwnedBy(player.getUuid())) b.onOwnerHurt(player, source);
			}
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (instance != null && entity instanceof ServerPlayerEntity player) {
				String msg = player.getDamageTracker().getDeathMessage().getString();
				for (CompanionBrain b : instance.brainsOwnedBy(player.getUuid())) b.onOwnerDied(msg);
			}
		});
	}

	/** Hostile mobs go after companions like they go after players. */
	private static void addCompanionTargeting(MobEntity mob) {
		if (!CompanionConfig.get().mobsTargetCompanion) return;
		if (mob instanceof ZombieEntity || mob instanceof AbstractSkeletonEntity || mob instanceof CreeperEntity || mob instanceof IllagerEntity
				|| mob instanceof WitchEntity || mob instanceof BlazeEntity || mob instanceof EndermiteEntity || mob instanceof SilverfishEntity
				|| mob instanceof VexEntity) {
			mob.targetSelector.add(2, new ActiveTargetGoal<>(mob, CompanionEntity.class, 10, true, false,
					target -> target instanceof CompanionEntity c && !c.isCreativeMode()));
		}
	}

	// ------------------------------------------------------------------
	// static helpers used by entities / tasks
	// ------------------------------------------------------------------

	@Nullable
	public static CompanionBrain brainOf(CompanionEntity entity) {
		if (instance == null) return null;
		return instance.brains.get(key(entity.getCompanionName()));
	}

	@Nullable
	public static CompanionEntity findEntity(MinecraftServer server, CompanionBrain brain) {
		if (instance == null) return null;
		UUID id = brain.getMemory().entityUuid();
		if (id == null) return null;
		CompanionEntity e = instance.loaded.get(id);
		return e == null || e.isRemoved() ? null : e;
	}

	public static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	public Collection<CompanionBrain> brains() {
		return brains.values();
	}

	@Nullable
	public CompanionBrain brain(String name) {
		return brains.get(key(name));
	}

	public List<CompanionBrain> brainsOwnedBy(UUID owner) {
		List<CompanionBrain> out = new ArrayList<>();
		for (CompanionBrain b : brains.values()) {
			if (owner.equals(b.getMemory().ownerUuid())) out.add(b);
		}
		return out;
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	private void loadAll() {
		for (CompanionMemory m : MemoryStore.loadAll(server)) {
			brains.put(key(m.name), new CompanionBrain(server, m));
		}
		AICompanionMod.LOGGER.info("Loaded {} companion memories", brains.size());
	}

	private void saveAll() {
		for (CompanionBrain b : brains.values()) b.save();
	}

	private void tick() {
		ticks++;
		for (CompanionBrain b : new ArrayList<>(brains.values())) {
			try {
				b.tick();
			} catch (Exception e) {
				AICompanionMod.LOGGER.error("Companion brain {} crashed", b.getName(), e);
			}
		}
		for (Iterator<PendingSpawn> it = pendingSpawns.iterator(); it.hasNext(); ) {
			PendingSpawn p = it.next();
			if (ticks < p.atTick()) continue;
			CompanionBrain brain = brains.get(key(p.name()));
			if (brain == null) {
				it.remove();
				continue;
			}
			UUID ownerId = brain.getMemory().ownerUuid();
			ServerPlayerEntity owner = ownerId == null ? null : server.getPlayerManager().getPlayer(ownerId);
			if (owner == null || !owner.isAlive()) continue; // wait for the owner
			it.remove();
			if (findEntity(server, brain) == null) {
				spawnBody(brain, owner, p.respawn(), p.deathMessage());
			}
		}
	}

	private void onLoad(CompanionEntity e) {
		CompanionBrain brain = brains.get(key(e.getCompanionName()));
		if (brain == null) {
			// memory file was deleted: rebuild a minimal one from the body
			CompanionMemory m = new CompanionMemory();
			m.name = e.getCompanionName();
			m.owner = e.getOwnerUuid() == null ? "" : e.getOwnerUuid().toString();
			m.entityUuid = e.getUuid().toString();
			brain = new CompanionBrain(server, m);
			brains.put(key(m.name), brain);
			brain.markDirty();
		}
		CompanionMemory m = brain.getMemory();
		UUID current = m.entityUuid();
		if (current == null || m.entityUuid.isEmpty()) {
			m.entityUuid = e.getUuid().toString();
			brain.markDirty();
		} else if (!current.equals(e.getUuid()) || m.away) {
			// A stale copy (the companion was re-summoned or is away): hand over its items and remove it.
			final CompanionBrain owningBrain = brain;
			server.execute(() -> retireStaleBody(e, owningBrain));
			return;
		}
		loaded.put(e.getUuid(), e);
	}

	private void retireStaleBody(CompanionEntity stale, CompanionBrain brain) {
		if (stale.isRemoved()) return;
		CompanionEntity current = findEntity(server, brain);
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < stale.getInventory().size(); i++) {
			ItemStack s = stale.getInventory().removeStack(i);
			if (!s.isEmpty()) items.add(s);
		}
		for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) {
			ItemStack s = stale.getEquippedStack(slot);
			if (!s.isEmpty()) {
				items.add(s.copy());
				stale.equipStack(slot, ItemStack.EMPTY);
			}
		}
		for (ItemStack s : items) {
			ItemStack rest = current != null ? current.insertStack(s) : s;
			if (!rest.isEmpty()) stale.dropStack(rest);
		}
		AICompanionMod.LOGGER.info("Removed a stale copy of companion {}", brain.getName());
		stale.discard();
	}

	// ------------------------------------------------------------------
	// summoning / dismissing
	// ------------------------------------------------------------------

	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	public static boolean isValidName(String name) {
		return VALID_NAME.matcher(name).matches();
	}

	/** Summons (or calls over) a companion for this player. Returns an error message or null. */
	@Nullable
	public String summon(ServerPlayerEntity owner, String name, @Nullable String skin) {
		if (!isValidName(name)) return "Names can only use letters, numbers and _ (max 16 characters).";
		if (server.getPlayerManager().getPlayer(name) != null) return "There's already a real player called " + name + ".";
		CompanionBrain brain = brains.get(key(name));
		if (brain != null) {
			UUID ownerId = brain.getMemory().ownerUuid();
			if (ownerId != null && !ownerId.equals(owner.getUuid())) {
				return name + " belongs to " + brain.getMemory().ownerName + ".";
			}
			CompanionEntity existing = findEntity(server, brain);
			if (existing != null) {
				existing.teleportNear(owner);
				if (skin != null) applySkin(brain, existing, skin);
				return null;
			}
		}
		int active = 0;
		for (CompanionBrain b : brainsOwnedBy(owner.getUuid())) {
			if (b != brain && findEntity(server, b) != null) active++;
		}
		if (active >= CompanionConfig.get().maxCompanionsPerPlayer) {
			return "You already have " + active + " companions out (limit " + CompanionConfig.get().maxCompanionsPerPlayer + "). Dismiss one first.";
		}
		if (brain == null) {
			CompanionMemory m = new CompanionMemory();
			m.name = name;
			m.owner = owner.getUuid().toString();
			m.ownerName = owner.getGameProfile().getName();
			brain = new CompanionBrain(server, m);
			brains.put(key(name), brain);
		}
		brain.getMemory().owner = owner.getUuid().toString();
		brain.getMemory().ownerName = owner.getGameProfile().getName();
		CompanionEntity e = spawnBody(brain, owner, false, null);
		if (e == null) return "Couldn't spawn the companion here.";
		if (skin != null) applySkin(brain, e, skin);
		return null;
	}

	@Nullable
	private CompanionEntity spawnBody(CompanionBrain brain, ServerPlayerEntity owner, boolean respawn, @Nullable String deathMessage) {
		ServerWorld world = owner.getServerWorld();
		CompanionEntity e = ModEntities.COMPANION.create(world);
		if (e == null) return null;
		CompanionMemory m = brain.getMemory();
		e.setCompanionName(m.name);
		e.setOwnerUuid(owner.getUuid());
		e.setStance(Stance.parse(m.stance, Stance.DEFENSIVE));
		e.setGameModeSetting(GameModeSetting.parse(m.gameMode, GameModeSetting.AUTO));
		if (!m.skinValue.isEmpty()) e.setSkin(m.skinName, m.skinValue, m.skinSignature);
		if (!m.savedEntityData.isEmpty()) {
			try {
				NbtCompound nbt = StringNbtReader.parse(m.savedEntityData);
				e.readItemsNbt(nbt, !respawn);
			} catch (Exception ex) {
				AICompanionMod.LOGGER.error("Couldn't restore {}'s items", m.name, ex);
			}
			m.savedEntityData = "";
		}
		BlockPos spot = findSpawnSpot(world, owner.getBlockPos());
		Vec3d pos = Vec3d.ofBottomCenter(spot);
		e.refreshPositionAndAngles(pos.x, pos.y, pos.z, owner.getYaw() + 180.0F, 0.0F);
		e.setHeadYaw(owner.getYaw() + 180.0F);
		m.entityUuid = e.getUuid().toString();
		m.away = false;
		m.autoRejoin = false;
		if (!world.spawnEntity(e)) return null;
		loaded.put(e.getUuid(), e);
		broadcast(Text.translatable("multiplayer.player.joined", m.name).formatted(Formatting.YELLOW));
		brain.onJoined(respawn, deathMessage);
		brain.save();
		return e;
	}

	private static BlockPos findSpawnSpot(ServerWorld world, BlockPos near) {
		for (int r = 2; r <= 4; r++) {
			for (int i = 0; i < 12; i++) {
				int dx = world.random.nextInt(2 * r + 1) - r;
				int dz = world.random.nextInt(2 * r + 1) - r;
				if (Math.abs(dx) + Math.abs(dz) < 2) continue;
				BlockPos p = WorldUtil.findStandableNear(world, near.add(dx, 0, dz), 3);
				if (p != null) return p;
			}
		}
		return near;
	}

	/** Removes the companion from the world, keeping its items in memory. */
	public void dismiss(CompanionBrain brain, boolean autoRejoin) {
		CompanionEntity e = findEntity(server, brain);
		CompanionMemory m = brain.getMemory();
		if (e != null) {
			m.savedEntityData = e.writeItemsNbt().toString();
			e.getInventory().clear();
			for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) e.equipStack(slot, ItemStack.EMPTY);
			m.stance = e.getStance().id();
			m.gameMode = e.getGameModeSetting().id();
			m.x = e.getX();
			m.y = e.getY();
			m.z = e.getZ();
			e.discard();
			loaded.remove(e.getUuid());
			broadcast(Text.translatable("multiplayer.player.left", m.name).formatted(Formatting.YELLOW));
		}
		m.away = true;
		m.autoRejoin = autoRejoin;
		brain.save();
	}

	/** Dismisses and deletes all memories of a companion. */
	public void delete(CompanionBrain brain) {
		dismiss(brain, false);
		brains.remove(key(brain.getName()));
		MemoryStore.delete(server, brain.getName());
	}

	public void applySkin(CompanionBrain brain, CompanionEntity entity, String playerName) {
		SkinFetcher.fetch(playerName).thenAccept(result -> server.execute(() -> {
			if (result.isEmpty()) {
				entity.notifyOwner("Couldn't find a skin for '" + playerName + "' (needs a real Minecraft account name).");
				return;
			}
			SkinFetcher.Skin skin = result.get();
			CompanionMemory m = brain.getMemory();
			m.skinName = skin.name();
			m.skinValue = skin.value();
			m.skinSignature = skin.signature();
			brain.markDirty();
			CompanionEntity current = findEntity(server, brain);
			if (current != null) current.setSkin(skin.name(), skin.value(), skin.signature());
		}));
	}

	public static void onCompanionDied(CompanionEntity e, Text deathMessage) {
		if (instance == null) return;
		instance.broadcast(deathMessage);
		CompanionBrain brain = brainOf(e);
		if (brain == null) return;
		CompanionMemory m = brain.getMemory();
		m.stats.deaths++;
		String msg = deathMessage.getString();
		boolean keep = e.getWorld().getGameRules().getBoolean(net.minecraft.world.GameRules.KEEP_INVENTORY);
		m.addEvent("I died: " + msg + (keep ? "" : " (dropped my stuff at " + e.getBlockX() + " " + e.getBlockY() + " " + e.getBlockZ() + ")"), brain.timeStamp());
		if (keep) m.savedEntityData = e.writeItemsNbt().toString();
		m.stance = e.getStance().id();
		m.gameMode = e.getGameModeSetting().id();
		m.away = true;
		brain.markDirty();
		if (CompanionConfig.get().autoRespawn) {
			instance.pendingSpawns.add(new PendingSpawn(m.name, instance.ticks + CompanionConfig.get().respawnDelaySeconds * 20,
					true, msg + (keep ? " - items kept" : "")));
		}
	}

	// ------------------------------------------------------------------
	// players
	// ------------------------------------------------------------------

	private void onPlayerJoin(ServerPlayerEntity player) {
		int delay = 30;
		for (CompanionBrain b : brainsOwnedBy(player.getUuid())) {
			CompanionMemory m = b.getMemory();
			m.ownerName = player.getGameProfile().getName();
			if (m.away && m.autoRejoin && CompanionConfig.get().leaveWithOwner) {
				pendingSpawns.add(new PendingSpawn(m.name, ticks + delay, false, null));
				delay += 20;
			}
		}
	}

	private void onPlayerLeave(ServerPlayerEntity player) {
		if (!CompanionConfig.get().leaveWithOwner) return;
		for (CompanionBrain b : brainsOwnedBy(player.getUuid())) {
			if (findEntity(server, b) != null) dismiss(b, true);
		}
	}

	private void onOwnerChangedWorld(ServerPlayerEntity player, ServerWorld origin) {
		if (!CompanionConfig.get().followAcrossDimensions) return;
		for (CompanionBrain b : brainsOwnedBy(player.getUuid())) {
			CompanionEntity e = findEntity(server, b);
			if (e != null && e.getWorld() == origin && e.getTaskManager().isFollowing()) {
				e.teleportNear(player);
			}
		}
	}

	private static final Pattern GROUP_WORDS = Pattern.compile("\\b(everyone|everybody|guys|y'?all|all of you|you all|companions|team|squad)\\b");

	private void onChat(ServerPlayerEntity sender, String text) {
		if (text == null || text.isBlank() || text.startsWith("/")) return;
		String lower = text.toLowerCase(Locale.ROOT);
		CompanionConfig cfg = CompanionConfig.get();
		boolean group = GROUP_WORDS.matcher(lower).find();
		List<CompanionBrain> named = new ArrayList<>();
		CompanionBrain nearestOwned = null;
		double nearestDist = Double.MAX_VALUE;
		List<CompanionBrain> groupTargets = new ArrayList<>();
		for (CompanionBrain b : brains.values()) {
			CompanionEntity e = findEntity(server, b);
			if (e == null || !e.isAlive()) continue;
			boolean isOwner = sender.getUuid().equals(b.getMemory().ownerUuid());
			boolean sameWorld = e.getWorld() == sender.getWorld();
			double dist = sameWorld ? e.distanceTo(sender) : Double.MAX_VALUE;
			if (mentions(lower, b.getName())) {
				if (sameWorld || isOwner) named.add(b);
				continue;
			}
			if (isOwner && dist <= cfg.listenRadius) {
				if (group) groupTargets.add(b);
				if (dist < nearestDist) {
					nearestDist = dist;
					nearestOwned = b;
				}
			}
		}
		List<CompanionBrain> targets = new ArrayList<>(named);
		if (named.isEmpty()) {
			if (!groupTargets.isEmpty()) targets.addAll(groupTargets);
			else if (nearestOwned != null && cfg.respondWithoutName) targets.add(nearestOwned);
		}
		for (CompanionBrain b : targets) b.onChat(sender, text);
	}

	private static boolean mentions(String lowerText, String name) {
		String n = Pattern.quote(name.toLowerCase(Locale.ROOT));
		return Pattern.compile("(^|[^a-z0-9_])@?" + n + "([^a-z0-9_]|$)").matcher(lowerText).find();
	}

	private void broadcast(Text text) {
		server.getPlayerManager().broadcast(text, false);
	}

	@Nullable
	public Entity entityOf(CompanionBrain brain) {
		return findEntity(server, brain);
	}

	public MinecraftServer getServer() {
		return server;
	}
}
