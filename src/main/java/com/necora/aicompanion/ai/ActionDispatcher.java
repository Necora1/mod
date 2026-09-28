package com.necora.aicompanion.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.necora.aicompanion.build.BlockPlacement;
import com.necora.aicompanion.build.Blueprint;
import com.necora.aicompanion.build.Frame;
import com.necora.aicompanion.build.Materials;
import com.necora.aicompanion.build.Shapes;
import com.necora.aicompanion.build.Structures;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.GameModeSetting;
import com.necora.aicompanion.entity.Stance;
import com.necora.aicompanion.memory.CompanionMemory;
import com.necora.aicompanion.task.Target;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.task.behavior.FollowBehavior;
import com.necora.aicompanion.task.behavior.ProtectBehavior;
import com.necora.aicompanion.task.behavior.StayBehavior;
import com.necora.aicompanion.task.tasks.AttackTask;
import com.necora.aicompanion.task.tasks.BuildTask;
import com.necora.aicompanion.task.tasks.CollectItemsTask;
import com.necora.aicompanion.task.tasks.CraftTask;
import com.necora.aicompanion.task.tasks.EatTask;
import com.necora.aicompanion.task.tasks.EmoteTask;
import com.necora.aicompanion.task.tasks.EquipTask;
import com.necora.aicompanion.task.tasks.GiveTask;
import com.necora.aicompanion.task.tasks.GotoTask;
import com.necora.aicompanion.task.tasks.LookTask;
import com.necora.aicompanion.task.tasks.MineTask;
import com.necora.aicompanion.task.tasks.WaitTask;
import com.necora.aicompanion.util.ItemUtil;
import com.necora.aicompanion.util.Json;
import com.necora.aicompanion.util.Names;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Turns the model's action objects into tasks (or immediate changes like stance or memory). */
public final class ActionDispatcher {
	private static final Set<String> HARMLESS = Set.of("look", "emote", "wait", "say", "remember");

	private ActionDispatcher() {
	}

	public record Result(int accepted, List<String> errors) {
	}

	private static final class BuildError extends RuntimeException {
		BuildError(String message) {
			super(message);
		}
	}

	public static Result dispatch(ActionContext ctx, List<JsonObject> actions, boolean queue) {
		CompanionEntity c = ctx.companion();
		List<Task> tasks = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		int accepted = 0;
		boolean stopRequested = false;
		for (JsonObject a : actions) {
			String type = canonical(Json.strOr(a, "", "type", "action", "name", "command", "do"));
			if (type.isEmpty()) continue;
			if (!ctx.trusted() && !HARMLESS.contains(type)) {
				errors.add("'" + type + "' ignored: " + (ctx.speaker() == null ? "nobody" : ctx.speaker().getGameProfile().getName()) + " is not allowed to give you orders");
				continue;
			}
			try {
				if (type.equals("stop")) {
					stopRequested = true;
					tasks.clear();
					accepted++;
					continue;
				}
				if (handleImmediate(ctx, type, a)) {
					accepted++;
					continue;
				}
				Task t = createTask(ctx, type, a);
				if (t != null) {
					tasks.add(t);
					accepted++;
				}
			} catch (BuildError e) {
				errors.add(type + ": " + e.getMessage());
			} catch (Exception e) {
				com.necora.aicompanion.AICompanionMod.LOGGER.warn("Bad action {}", a, e);
				errors.add(type + ": " + e.getMessage());
			}
		}
		if (stopRequested) {
			c.getTaskManager().stopEverything();
		}
		if (!tasks.isEmpty()) {
			if (queue && !stopRequested) c.getTaskManager().enqueue(tasks);
			else c.getTaskManager().replaceAll(tasks);
		}
		return new Result(accepted, errors);
	}

	/** Maps synonyms onto the canonical action names. */
	static String canonical(String raw) {
		String t = raw.toLowerCase(Locale.ROOT).trim().replace(' ', '_').replace('-', '_');
		return switch (t) {
			case "follow", "follow_me", "follow_player", "come_with_me" -> "follow";
			case "stay", "wait_here", "stay_here", "hold_position", "sit", "hold" -> "stay";
			case "come", "come_here", "come_to_me", "approach" -> "come";
			case "goto", "go", "go_to", "walk", "walk_to", "move", "move_to", "travel", "navigate" -> "goto";
			case "protect", "guard", "defend", "bodyguard", "guard_player" -> "protect";
			case "attack", "kill", "fight", "hunt", "attack_mob", "kill_mobs" -> "attack";
			case "stance", "set_stance", "combat_mode", "aggression" -> "stance";
			case "surround", "box_in", "enclose", "panic_box", "surround_player", "bunker", "protective_box", "wall_in" -> "surround";
			case "build", "construct", "build_structure" -> "build";
			case "build_custom", "custom_build", "custom", "design", "blueprint", "build_shape", "shape", "shapes" -> "build_custom";
			case "place", "place_block", "put", "set_block" -> "place";
			case "dig", "clear", "clear_area", "excavate", "dig_area", "flatten", "tunnel", "dig_hole", "hole" -> "dig";
			case "demolish", "undo", "undo_build", "remove_build", "tear_down", "destroy_build", "unbuild", "let_out", "free" -> "demolish";
			case "mine", "gather", "chop", "chop_trees", "cut_trees", "collect_blocks", "get", "harvest", "mine_blocks", "farm" -> "mine";
			case "collect", "pickup", "pick_up", "collect_items", "loot", "grab" -> "collect";
			case "craft", "make", "create" -> "craft";
			case "give", "hand", "hand_over", "give_item", "share", "toss", "give_items" -> "give";
			case "drop", "throw", "discard", "drop_item" -> "drop";
			case "equip", "hold_item", "wield", "wear", "use", "switch_to" -> "equip";
			case "eat", "eat_food", "heal" -> "eat";
			case "light", "light_up", "place_torches", "torches", "light_area" -> "light";
			case "look", "look_at", "face", "watch", "turn_to" -> "look";
			case "emote", "gesture", "wave", "jump", "dance", "crouch", "sneak", "spin", "nod", "shake", "shake_head", "celebrate", "tbag", "bow" -> "emote";
			case "wait", "pause", "sleep", "idle", "delay" -> "wait";
			case "stop", "cancel", "halt", "stop_all", "nevermind", "freeze" -> "stop";
			case "remember_place", "set_home", "remember_location", "save_location", "mark", "save_place", "mark_location", "sethome" -> "remember_place";
			case "remember", "note", "memorize", "remember_fact" -> "remember";
			case "forget", "forget_fact", "delete_memory" -> "forget";
			case "trust", "add_trusted", "allow" -> "trust";
			case "untrust", "distrust", "remove_trusted", "disallow" -> "untrust";
			case "gamemode", "game_mode", "set_gamemode", "set_game_mode", "switch_gamemode", "creative", "survival" -> "gamemode";
			case "say", "chat", "tell", "talk", "reply", "respond" -> "say";
			case "teleport", "tp", "teleport_to" -> "teleport";
			default -> t;
		};
	}

	// ------------------------------------------------------------------
	// immediate actions
	// ------------------------------------------------------------------

	private static boolean handleImmediate(ActionContext ctx, String type, JsonObject a) {
		CompanionEntity c = ctx.companion();
		CompanionBrain brain = ctx.brain();
		CompanionMemory m = brain.getMemory();
		switch (type) {
			case "stance" -> {
				Stance s = Stance.parse(Json.strOr(a, "defensive", "mode", "stance", "value", "level"), Stance.DEFENSIVE);
				c.setStance(s);
				m.stance = s.id();
				if (s == Stance.PASSIVE) c.getCombat().stop();
				brain.markDirty();
				return true;
			}
			case "remember_place" -> {
				String name = Json.strOr(a, "home", "name", "place", "label", "location_name");
				Positions.Resolved r = Positions.resolve(ctx, Json.get(a, "at", "position", "pos", "location", "target"), a, "me");
				if (r == null) throw new BuildError("don't know where that is");
				m.places.put(name, new CompanionMemory.Place(r.pos().getX(), r.pos().getY(), r.pos().getZ(), c.getWorld().getRegistryKey().getValue().toString()));
				brain.markDirty();
				return true;
			}
			case "remember" -> {
				String text = Json.str(a, "fact", "text", "note", "value", "memory");
				if (text != null) m.addFact(text, brain.day());
				brain.markDirty();
				return true;
			}
			case "forget" -> {
				String text = Json.str(a, "text", "fact", "about", "what", "value");
				if (text != null) m.forget(text);
				brain.markDirty();
				return true;
			}
			case "trust", "untrust" -> {
				if (ctx.speaker() == null || !c.isOwner(ctx.speaker())) throw new BuildError("only your owner can change who you trust");
				String player = Json.str(a, "player", "name", "target", "who");
				if (player == null) throw new BuildError("which player?");
				m.trusted.removeIf(s -> s.equalsIgnoreCase(player));
				if (type.equals("trust")) m.trusted.add(player);
				brain.markDirty();
				return true;
			}
			case "gamemode" -> {
				String mode = Json.str(a, "mode", "gamemode", "value", "game_mode", "type");
				if (mode == null || mode.equalsIgnoreCase("gamemode")) mode = Json.str(a, "type");
				GameModeSetting setting = GameModeSetting.parse(mode, null);
				if (setting == null) throw new BuildError("mode must be creative, survival or auto");
				ServerPlayerEntity owner = c.getOwner();
				boolean allowed = CompanionConfig.get().allowModelGameModeChange && ctx.speaker() != null && c.isOwner(ctx.speaker())
						&& owner != null && (owner.isCreative() || owner.hasPermissionLevel(2));
				if (!allowed) throw new BuildError("not allowed (owner must be in creative or an operator)");
				c.setGameModeSetting(setting);
				m.gameMode = setting.id();
				brain.markDirty();
				return true;
			}
			case "say" -> {
				String text = Json.str(a, "text", "message", "say", "value");
				if (text != null) c.queueChat(text, 0);
				return true;
			}
			case "teleport" -> {
				if (!c.isCreativeMode()) throw new BuildError("you can only teleport in creative mode - walk instead");
				Entity who = ctx.speaker() != null ? ctx.speaker() : c.getOwner();
				if (who instanceof ServerPlayerEntity p) c.teleportNear(p);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	// ------------------------------------------------------------------
	// tasks
	// ------------------------------------------------------------------

	@Nullable
	private static Task createTask(ActionContext ctx, String type, JsonObject a) {
		CompanionEntity c = ctx.companion();
		ServerPlayerEntity speaker = ctx.speaker();
		switch (type) {
			case "follow" -> {
				PlayerEntity p = playerArg(ctx, a, "target", "player", "who");
				if (p == null) throw new BuildError("who should I follow?");
				return new FollowBehavior(c, p.getUuid());
			}
			case "stay" -> {
				Positions.Resolved r = Positions.resolve(ctx, Json.get(a, "at", "position", "target"), a, "you");
				return new StayBehavior(c, r == null ? c.getBlockPos() : r.pos());
			}
			case "come" -> {
				Entity who = speaker != null ? speaker : c.getOwner();
				if (who == null) throw new BuildError("nobody to come to");
				return new GotoTask(c, Target.of(who, Positions.name(who)), 2.5);
			}
			case "goto" -> {
				Positions.Resolved r = Positions.resolve(ctx, Json.get(a, "target", "to", "at", "position", "location", "place", "destination"), a, "me");
				if (r == null) throw new BuildError("don't know where that is");
				Target t = r.entity() != null ? Target.of(r.entity(), r.label()) : Target.of(r.pos(), r.label());
				return new GotoTask(c, t, r.entity() != null ? 2.5 : 1.5);
			}
			case "protect" -> {
				PlayerEntity p = playerArg(ctx, a, "target", "player", "who");
				if (p == null) throw new BuildError("who should I protect?");
				return new ProtectBehavior(c, p.getUuid(), Json.dbl(a, 12, "radius", "range", "distance"));
			}
			case "attack" -> {
				return attack(ctx, a);
			}
			case "surround" -> {
				return surround(ctx, a);
			}
			case "build" -> {
				return build(ctx, a);
			}
			case "build_custom" -> {
				return buildCustom(ctx, a);
			}
			case "place" -> {
				BlockState state = blockArg(a, "block", "item", "material", "what");
				if (state == null) throw new BuildError("which block?");
				Positions.Resolved r = Positions.resolve(ctx, Json.get(a, "at", "position", "pos", "target", "location"), a, "looking");
				if (r == null) throw new BuildError("where?");
				Blueprint bp = new Blueprint("a " + Registries.BLOCK.getId(state.getBlock()).getPath()).anchor(r.pos());
				bp.set(r.pos(), state, BlockPlacement.PHASE_STRUCTURE);
				return new BuildTask(c, bp, bp.name, false);
			}
			case "dig" -> {
				return dig(ctx, a);
			}
			case "demolish" -> {
				List<BlockPos> last = ctx.brain().getLastBuild();
				if (last == null || last.isEmpty()) throw new BuildError("I haven't built anything recently");
				Blueprint bp = new Blueprint(ctx.brain().getLastBuildLabel()).topDown(true);
				for (BlockPos p : last) bp.forceClear(p);
				bp.anchor(last.get(0));
				return new BuildTask(c, bp, "tearing down " + ctx.brain().getLastBuildLabel(), true) {
					@Override
					public String describe() {
						return super.describe();
					}
				};
			}
			case "mine" -> {
				String what = Json.str(a, "block", "blocks", "item", "resource", "what", "target", "material", "type_of_block");
				Names.BlockMatcher matcher = Names.blockMatcher(what);
				if (matcher == null) throw new BuildError("don't know what '" + what + "' is");
				int count = Json.integer(a, 16, "count", "amount", "quantity", "number", "n");
				int radius = Json.integer(a, 32, "radius", "range", "distance");
				return new MineTask(c, matcher, count, radius);
			}
			case "collect" -> {
				return new CollectItemsTask(c, Json.integer(a, 12, "radius", "range"));
			}
			case "craft" -> {
				String what = Json.str(a, "item", "what", "target", "name", "recipe", "result");
				if (what == null) throw new BuildError("craft what?");
				List<Item> candidates = craftCandidates(c, what);
				if (candidates.isEmpty()) throw new BuildError("don't know the item '" + what + "'");
				return new CraftTask(c, candidates, Json.integer(a, 1, "count", "amount", "quantity", "number", "n"));
			}
			case "give", "drop" -> {
				String what = Json.strOr(a, "all", "item", "items", "what", "name", "thing");
				Predicate<ItemStack> matcher = Names.itemMatcher(what);
				if (matcher == null) throw new BuildError("give what?");
				int count = Json.integer(a, 0, "count", "amount", "quantity", "number", "n");
				if (what.equalsIgnoreCase("all") || what.equalsIgnoreCase("everything")) count = 0;
				else if (count == 0) count = type.equals("give") ? defaultGiveCount(c, matcher) : 0;
				PlayerEntity to = null;
				if (type.equals("give")) {
					to = playerArg(ctx, a, "target", "to", "player", "recipient", "who");
					if (to == null) throw new BuildError("give it to whom?");
				}
				return new GiveTask(c, matcher, what, count, to);
			}
			case "equip" -> {
				String what = Json.str(a, "item", "what", "name", "thing");
				Predicate<ItemStack> matcher = Names.itemMatcher(what);
				if (matcher == null) throw new BuildError("equip what?");
				if (what.equalsIgnoreCase("best_weapon") || what.equalsIgnoreCase("weapon")) {
					matcher = ItemUtil::isWeapon;
				}
				return new EquipTask(c, matcher, what);
			}
			case "eat" -> {
				return new EatTask(c);
			}
			case "light" -> {
				int radius = Json.integer(a, 10, "radius", "range", "size");
				Positions.Resolved r = Positions.resolve(ctx, Json.get(a, "at", "center", "target"), a, "you");
				BlockPos center = r == null ? c.getBlockPos() : r.pos();
				Blueprint bp = Structures.lights(c.getWorld(), center, radius);
				if (bp.isEmpty()) throw new BuildError("it's already bright enough around here");
				return new BuildTask(c, bp, "torches", false);
			}
			case "look" -> {
				Positions.Resolved r = Positions.resolve(ctx, Json.get(a, "target", "at", "who", "position"), a, "me");
				if (r == null) throw new BuildError("look at what?");
				Target t = r.entity() != null ? Target.of(r.entity(), r.label()) : Target.of(r.pos(), r.label());
				return new LookTask(c, t, Json.integer(a, 3, "seconds", "duration") * 20);
			}
			case "emote" -> {
				String kind = Json.strOr(a, null, "kind", "emote", "gesture", "name", "style", "animation");
				if (kind == null) kind = Json.strOr(a, "wave", "type", "action");
				return new EmoteTask(c, kind, speaker);
			}
			case "wait" -> {
				return new WaitTask(c, (int) (Json.dbl(a, 3, "seconds", "duration", "time", "secs") * 20));
			}
			default -> throw new BuildError("unknown action '" + type + "'");
		}
	}

	private static int defaultGiveCount(CompanionEntity c, Predicate<ItemStack> matcher) {
		// "give me some dirt" without a count: a stack; "give me the sword": one item
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (!s.isEmpty() && matcher.test(s)) return s.getMaxCount() == 1 ? 1 : Math.min(64, c.countItems(matcher));
		}
		ItemStack main = c.getMainHandStack();
		if (!main.isEmpty() && matcher.test(main)) return main.getMaxCount() == 1 ? 1 : main.getCount();
		return 0;
	}

	@Nullable
	private static PlayerEntity playerArg(ActionContext ctx, JsonObject a, String... keys) {
		String name = Json.str(a, keys);
		CompanionEntity c = ctx.companion();
		if (name == null || name.isBlank() || name.equalsIgnoreCase("me") || name.equalsIgnoreCase("speaker") || name.equalsIgnoreCase("player")) {
			return ctx.speaker() != null ? ctx.speaker() : c.getOwner();
		}
		if (name.equalsIgnoreCase("owner")) return c.getOwner();
		if (c.getServer() == null) return null;
		for (ServerPlayerEntity p : c.getServer().getPlayerManager().getPlayerList()) {
			if (p.getGameProfile().getName().equalsIgnoreCase(name.trim())) return p;
		}
		return null;
	}

	@Nullable
	private static BlockState blockArg(JsonObject a, String... keys) {
		String name = Json.str(a, keys);
		return name == null ? null : Names.blockState(name);
	}

	private static List<Item> craftCandidates(CompanionEntity c, String what) {
		List<Item> out = new ArrayList<>();
		String n = Names.normalize(what);
		String[] tiers = {"netherite", "diamond", "iron", "stone", "wooden", "golden"};
		for (String tool : new String[]{"pickaxe", "sword", "axe", "shovel", "hoe"}) {
			if (n.equals(tool) || n.equals(tool + "s") || n.equals("a_" + tool)) {
				for (String tier : tiers) {
					Item it = Names.item(tier + "_" + tool);
					if (it != null) out.add(it);
				}
				return out;
			}
		}
		Item item = Names.item(what);
		if (item != null) out.add(item);
		boolean planks = item != null && new ItemStack(item).isIn(ItemTags.PLANKS) || n.equals("planks") || n.equals("wood") || n.equals("wooden_planks");
		if (planks) {
			for (Item p : Registries.ITEM) {
				if (new ItemStack(p).isIn(ItemTags.PLANKS) && !out.contains(p)) out.add(p);
			}
		}
		if (n.equals("slab") || n.equals("stairs") || n.equals("door") || n.equals("fence")) {
			for (Item p : Registries.ITEM) {
				String id = ItemUtil.id(p);
				if (id.endsWith("_" + n) && !out.contains(p)) out.add(p);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------
	// combat
	// ------------------------------------------------------------------

	private static Task attack(ActionContext ctx, JsonObject a) {
		CompanionEntity c = ctx.companion();
		String target = Json.strOr(a, "hostiles", "target", "mob", "entity", "who", "what", "enemy", "type_of_mob");
		int count = Json.integer(a, 0, "count", "amount", "number", "n");
		int radius = Json.integer(a, 24, "radius", "range");
		String t = Names.normalize(target);
		if (t.isEmpty() || t.startsWith("hostile") || t.equals("mobs") || t.equals("monsters") || t.equals("enemies") || t.equals("all") || t.equals("everything") || t.equals("them")) {
			return new AttackTask(c, AttackTask.Mode.HOSTILES, null, null, count, radius, "hostile mobs");
		}
		if (c.getServer() != null) {
			for (ServerPlayerEntity p : c.getServer().getPlayerManager().getPlayerList()) {
				if (p.getGameProfile().getName().equalsIgnoreCase(target.trim())) {
					if (c.isOwner(p)) throw new BuildError("you won't attack your own owner");
					if (ctx.speaker() == null || !c.isOwner(ctx.speaker())) throw new BuildError("only your owner can tell you to attack a player");
					return new AttackTask(c, AttackTask.Mode.ENTITY, null, p, 1, 48, p.getGameProfile().getName());
				}
			}
		}
		EntityType<?> type = Names.entityType(t);
		if (type == null) throw new BuildError("don't know what '" + target + "' is");
		return new AttackTask(c, AttackTask.Mode.TYPE, type, null, count, radius, Registries.ENTITY_TYPE.getId(type).getPath());
	}

	// ------------------------------------------------------------------
	// building
	// ------------------------------------------------------------------

	private static Task surround(ActionContext ctx, JsonObject a) {
		CompanionEntity c = ctx.companion();
		PlayerEntity p = playerArg(ctx, a, "target", "player", "who");
		Entity who = p != null ? p : c;
		int radius = MathHelper.clamp(Json.integer(a, 1, "radius", "size", "distance"), 1, 4);
		int area = (2 * radius + 1) * (2 * radius + 1) * 3;
		Block block = Materials.resolve(Json.str(a, "block", "material", "blocks", "with"), Blocks.COBBLESTONE, c, area);
		boolean roof = Json.bool(a, true, "roof", "top", "ceiling");
		boolean floor = Json.bool(a, false, "floor", "bottom");
		BlockState window = null;
		if (Json.bool(a, false, "windows", "window", "glass", "see_through")) {
			window = (c.isCreativeMode() || c.countItem(Blocks.GLASS.asItem()) >= 4 ? Blocks.GLASS : block).getDefaultState();
		}
		BlockPos feet = who.getBlockPos();
		Blueprint bp = Structures.surround(c.getWorld(), feet, radius, block.getDefaultState(), roof, floor, window);
		adaptToInventory(c, bp);
		String label = "a " + Materials.path(block) + " shelter around " + Positions.name(who);
		return new BuildTask(c, bp, label, false);
	}

	private static Task build(ActionContext ctx, JsonObject a) {
		CompanionEntity c = ctx.companion();
		String structure = Names.normalize(Json.strOr(a, "house", "structure", "what", "name", "kind", "building", "object"));
		JsonElement at = Json.get(a, "at", "position", "location", "where", "pos", "target");
		Positions.Resolved r = Positions.resolve(ctx, at, a, "front");
		if (r == null) throw new BuildError("don't know where to build that");
		Direction facing = r.facing();
		BlockPos spot = r.pos();
		int width = Json.integer(a, -1, "width", "w", "size");
		int depth = Json.integer(a, -1, "depth", "d", "length_z");
		int height = Json.integer(a, -1, "height", "h", "tall");
		int length = Json.integer(a, -1, "length", "long", "distance");
		String material = Json.str(a, "material", "block", "walls", "wall", "main_block", "blocks", "with");
		String roofMat = Json.str(a, "roof", "roof_material", "roof_block");
		String floorMat = Json.str(a, "floor", "floor_material", "floor_block");
		String roofStyle = Json.strOr(a, "gable", "roof_style", "roof_type", "style");
		boolean furnish = Json.bool(a, true, "furnish", "furniture", "furnished", "interior");
		boolean windows = Json.bool(a, true, "windows", "window");
		Blueprint bp;
		String label;

		switch (structure) {
			case "house", "home", "cabin", "cottage", "base", "building", "hut", "shelter", "shack", "small_house", "big_house", "mansion" -> {
				boolean small = structure.equals("hut") || structure.equals("shelter") || structure.equals("shack") || structure.equals("small_house");
				boolean big = structure.equals("big_house") || structure.equals("mansion");
				Structures.HouseSpec s = new Structures.HouseSpec();
				s.width = width > 0 ? width : small ? 5 : big ? 11 : 7;
				s.depth = depth > 0 ? depth : (width > 0 ? width : small ? 5 : big ? 9 : 7);
				s.height = height > 0 ? height : small ? 3 : big ? 5 : 4;
				int estimate = s.width * s.depth * 2 + (s.width + s.depth) * 2 * s.height;
				s.wall = Materials.resolve(material, small && !c.isCreativeMode() ? Blocks.COBBLESTONE : Blocks.OAK_PLANKS, c, estimate / 2);
				s.floor = floorMat != null ? Materials.resolve(floorMat, s.wall, c, s.width * s.depth) : defaultFloor(s.wall);
				s.roof = roofMat != null ? Materials.resolve(roofMat, s.wall, c, 20) : defaultRoof(s.wall);
				s.corner = Json.str(a, "corners", "pillars", "corner") != null
						? Materials.resolve(Json.str(a, "corners", "pillars", "corner"), s.wall, c, s.height * 4)
						: Materials.log(s.wall);
				s.door = Materials.door(s.wall, c);
				s.bed = Materials.bed(c);
				s.window = Materials.pane(Json.str(a, "window_block") != null ? Materials.resolve(Json.str(a, "window_block"), Blocks.GLASS, c, 4) : Blocks.GLASS);
				s.windows = windows;
				s.furnish = furnish && !small;
				s.roofStyle = roofStyle.toLowerCase(Locale.ROOT).startsWith("flat") || small ? Structures.RoofStyle.FLAT : Structures.RoofStyle.GABLE;
				Frame f = frameFor(c, r, facing, s.width, s.depth);
				bp = Structures.house(c.getWorld(), f, s);
				label = (small ? "a small " : "a ") + Materials.path(s.wall).replace('_', ' ') + " " + (small ? "hut" : "house");
			}
			case "tower", "watchtower", "lookout", "lookout_tower" -> {
				int w = width > 0 ? width : 5;
				int h = height > 0 ? height : 10;
				Block wall = Materials.resolve(material, Blocks.STONE_BRICKS, c, w * 4 * h);
				Block floor = floorMat != null ? Materials.resolve(floorMat, wall, c, w * w * 2) : defaultFloor(wall);
				Frame f = frameFor(c, r, facing, w, w);
				bp = Structures.tower(c.getWorld(), f, w, h, wall, floor);
				label = "a " + Materials.path(wall).replace('_', ' ') + " tower";
			}
			case "wall", "barrier", "defensive_wall" -> {
				int len = length > 0 ? length : width > 0 ? width : 9;
				int h = height > 0 ? height : 3;
				Block wall = Materials.resolve(material, Blocks.COBBLESTONE, c, len * h);
				BlockPos center = r.inFront() ? r.pos() : r.pos();
				Direction right = facing.rotateYClockwise();
				BlockPos from = center.offset(right, -(len / 2));
				BlockPos to = center.offset(right, len - 1 - len / 2);
				JsonElement fromEl = Json.get(a, "from", "start");
				JsonElement toEl = Json.get(a, "to", "end");
				if (fromEl != null && toEl != null) {
					Positions.Resolved rf = Positions.resolve(ctx, fromEl, a, "me");
					Positions.Resolved rt = Positions.resolve(ctx, toEl, a, "me");
					if (rf != null && rt != null) {
						from = rf.pos();
						to = rt.pos();
					}
				}
				bp = Structures.wall(c.getWorld(), from, to, h, wall.getDefaultState());
				label = "a " + Materials.path(wall).replace('_', ' ') + " wall";
			}
			case "fence", "pen", "enclosure", "corral", "animal_pen", "fence_ring" -> {
				int w = width > 0 ? width : 9;
				int d = depth > 0 ? depth : w;
				Block base = Materials.resolve(material, Blocks.OAK_PLANKS, c, w * 4);
				Block fence = Materials.path(base).contains("fence") || Materials.path(base).endsWith("_wall") ? base : Materials.fence(base);
				Frame f = frameFor(c, r, facing, w, d);
				bp = Structures.fence(c.getWorld(), f, w, d, fence);
				label = "a " + Materials.path(fence).replace('_', ' ');
			}
			case "platform", "floor", "foundation", "deck", "pad" -> {
				int w = width > 0 ? width : 7;
				int d = depth > 0 ? depth : w;
				Block block = Materials.resolve(material, Blocks.OAK_PLANKS, c, w * d);
				Frame f = frameFor(c, r, facing, w, d);
				bp = Structures.platform(c.getWorld(), f, w, d, f.origin().getY() - 1, block.getDefaultState());
				label = "a " + Materials.path(block).replace('_', ' ') + " platform";
			}
			case "bridge", "walkway", "path" -> {
				int len = length > 0 ? length : 12;
				int w = width > 0 ? width : 3;
				Block block = Materials.resolve(material, Blocks.OAK_PLANKS, c, len * w);
				Entity speaker = ctx.speaker() != null ? ctx.speaker() : c;
				BlockPos start = r.inFront() || at == null ? speaker.getBlockPos() : r.pos();
				boolean rails = Json.bool(a, true, "rails", "railing", "sides");
				BlockState rail = rails ? Materials.fence(block).getDefaultState() : null;
				bp = Structures.bridge(start, facing, len, w, block.getDefaultState(), rail);
				label = "a " + Materials.path(block).replace('_', ' ') + " bridge";
			}
			case "pillar", "column", "pole", "tower_of_blocks", "pillar_up" -> {
				int h = height > 0 ? height : 6;
				Block block = Materials.resolve(material, Blocks.COBBLESTONE, c, h);
				bp = Structures.pillar(r.pos(), h, block.getDefaultState());
				label = "a pillar";
			}
			default -> throw new BuildError("I don't have a plan for '" + structure + "' - use build_custom to design it from shapes");
		}
		if (!c.isCreativeMode()) adaptToInventory(c, bp);
		return new BuildTask(c, bp, label, false);
	}

	private static Block defaultFloor(Block wall) {
		String p = Materials.path(wall);
		if (p.equals("oak_planks")) return Blocks.SPRUCE_PLANKS;
		if (p.endsWith("_planks")) return wall;
		return wall;
	}

	private static Block defaultRoof(Block wall) {
		String p = Materials.path(wall);
		if (p.equals("oak_planks")) return Blocks.SPRUCE_PLANKS;
		if (p.contains("cobblestone")) return Blocks.COBBLESTONE;
		return wall;
	}

	private static Frame frameFor(CompanionEntity c, Positions.Resolved r, Direction facing, int width, int depth) {
		Frame f;
		if (r.inFront() || r.entity() != null) {
			BlockPos feet = r.entity() != null ? r.entity().getBlockPos() : r.pos().offset(facing, -3);
			f = Frame.inFrontOf(feet, facing, width, 2);
		} else {
			f = Frame.centeredOn(r.pos(), facing, width, depth);
		}
		// Sit on the ground in the middle of the footprint.
		BlockPos center = f.at(width / 2, 0, depth / 2);
		int y = Structures.groundLevel(c.getWorld(), center);
		return new Frame(new BlockPos(f.origin().getX(), y, f.origin().getZ()), f.forward());
	}

	/**
	 * Survival: swap materials the companion doesn't have enough of for ones it does (stairs ->
	 * planks, logs -> wall block, anything -> the most plentiful block).
	 */
	private static void adaptToInventory(CompanionEntity c, Blueprint bp) {
		if (c.isCreativeMode() || !CompanionConfig.get().requireMaterialsInSurvival) return;
		for (int round = 0; round < 8; round++) {
			Map<Item, Integer> need = bp.materials();
			boolean changed = false;
			for (Map.Entry<Item, Integer> e : need.entrySet()) {
				int have = c.countItem(e.getKey());
				if (have >= e.getValue()) continue;
				Block from = Block.getBlockFromItem(e.getKey());
				if (from == Blocks.AIR) continue;
				Block to = null;
				String p = Materials.path(from);
				if (p.endsWith("_stairs") || p.endsWith("_slab")) {
					Block full = Materials.fullBlock(from);
					if (full != from && c.countItem(full.asItem()) >= need.getOrDefault(full.asItem(), 0) + e.getValue()) to = full;
				}
				if (to == null) {
					Block auto = Materials.auto(c, from, e.getValue() + need.getOrDefault(from.asItem(), 0));
					if (auto != from && c.countItem(auto.asItem()) >= need.getOrDefault(auto.asItem(), 0) + e.getValue()) to = auto;
				}
				if (to != null) {
					bp.substitute(from, to.getDefaultState());
					changed = true;
					break;
				}
			}
			if (!changed) break;
		}
	}

	private static Task buildCustom(ActionContext ctx, JsonObject a) {
		CompanionEntity c = ctx.companion();
		Positions.Resolved r = Positions.resolve(ctx, Json.get(a, "at", "origin", "position", "location", "where"), a, "front");
		if (r == null) throw new BuildError("don't know where to build that");
		BlockPos origin = r.pos();
		if (r.inFront()) {
			int y = Structures.groundLevel(c.getWorld(), origin);
			origin = new BlockPos(origin.getX(), y, origin.getZ());
		}
		JsonElement partsEl = Json.get(a, "parts", "shapes", "blocks", "components", "elements");
		JsonArray parts;
		if (partsEl != null && partsEl.isJsonArray()) {
			parts = partsEl.getAsJsonArray();
		} else if (Json.get(a, "shape") != null) {
			parts = new JsonArray();
			parts.add(a);
		} else {
			throw new BuildError("needs a parts list");
		}
		String name = Json.strOr(a, "custom build", "name", "label", "title", "structure");
		Blueprint bp = new Blueprint(name).anchor(origin);
		int limit = CompanionConfig.get().maxBuildBlocks;
		String defaultBlock = Json.strOr(a, "stone_bricks", "block", "material");
		for (JsonElement el : parts) {
			if (!el.isJsonObject()) continue;
			JsonObject part = el.getAsJsonObject();
			String blockName = Json.strOr(part, defaultBlock, "block", "material", "with");
			String shape = Json.strOr(part, "", "shape", "type", "kind");
			BlockState state;
			if (shape.equalsIgnoreCase("clear") || shape.equalsIgnoreCase("air") || blockName.equalsIgnoreCase("air")) {
				state = Blocks.AIR.getDefaultState();
			} else {
				state = Names.blockState(blockName);
				if (state == null) state = Materials.resolve(blockName, Blocks.STONE_BRICKS, c, 64).getDefaultState();
			}
			String err = Shapes.apply(bp, origin, part, state, limit);
			if (err != null) throw new BuildError(err);
		}
		if (!c.isCreativeMode()) adaptToInventory(c, bp);
		return new BuildTask(c, bp, name, false);
	}

	private static Task dig(ActionContext ctx, JsonObject a) {
		CompanionEntity c = ctx.companion();
		Positions.Resolved r = Positions.resolve(ctx, Json.get(a, "at", "origin", "position", "location", "where", "center"), a, "looking");
		if (r == null) r = Positions.resolve(ctx, null, a, "front");
		if (r == null) throw new BuildError("where should I dig?");
		BlockPos origin = r.pos();
		BlockPos from = Json.vec(Json.get(a, "from", "start"));
		BlockPos to = Json.vec(Json.get(a, "to", "end"));
		Direction facing = r.facing();
		if (from == null || to == null) {
			int w = Math.max(1, Json.integer(a, 3, "width", "w", "size"));
			int d = Math.max(1, Json.integer(a, w, "depth_z", "length", "long"));
			int depth = Math.max(1, Json.integer(a, 2, "depth", "deep", "height", "h"));
			boolean down = !Json.strOr(a, "down", "direction", "dir").toLowerCase(Locale.ROOT).startsWith("up");
			Frame f = Frame.centeredOn(origin, facing, w, d);
			BlockPos o = f.origin();
			BlockPos far = f.at(w - 1, 0, d - 1);
			int y1 = down ? r.pos().getY() - 1 : r.pos().getY();
			int y0 = down ? y1 - depth + 1 : y1 + depth - 1;
			from = new BlockPos(o.getX(), y0, o.getZ()).subtract(origin);
			to = new BlockPos(far.getX(), y1, far.getZ()).subtract(origin);
		}
		JsonObject part = new JsonObject();
		part.addProperty("shape", "clear");
		JsonArray fa = new JsonArray();
		fa.add(from.getX());
		fa.add(from.getY());
		fa.add(from.getZ());
		JsonArray ta = new JsonArray();
		ta.add(to.getX());
		ta.add(to.getY());
		ta.add(to.getZ());
		part.add("from", fa);
		part.add("to", ta);
		Blueprint bp = new Blueprint("digging").anchor(origin).topDown(true);
		String err = Shapes.apply(bp, origin, part, Blocks.AIR.getDefaultState(), CompanionConfig.get().maxBuildBlocks);
		if (err != null) throw new BuildError(err);
		return new BuildTask(c, bp, "a hole", true);
	}

	/** For status lines. */
	public static String describeTarget(@Nullable LivingEntity e) {
		return e == null ? "nothing" : Positions.name(e);
	}
}
