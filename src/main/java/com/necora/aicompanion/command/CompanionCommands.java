package com.necora.aicompanion.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.necora.aicompanion.ai.ActionContext;
import com.necora.aicompanion.ai.ActionDispatcher;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.ai.LlmClient;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.GameModeSetting;
import com.necora.aicompanion.entity.Stance;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.memory.CompanionMemory;
import com.necora.aicompanion.task.behavior.FollowBehavior;
import com.necora.aicompanion.task.behavior.StayBehavior;
import com.necora.aicompanion.task.tasks.GotoTask;
import com.necora.aicompanion.task.Target;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class CompanionCommands {
	private CompanionCommands() {
	}

	private static final SuggestionProvider<ServerCommandSource> MY_COMPANIONS = (ctx, builder) -> {
		CompanionManager mgr = CompanionManager.get();
		List<String> names = new ArrayList<>();
		if (mgr != null) {
			ServerPlayerEntity p = ctx.getSource().getPlayer();
			for (CompanionBrain b : mgr.brains()) {
				if (p == null || ctx.getSource().hasPermissionLevel(2) || p.getUuid().equals(b.getMemory().ownerUuid())) names.add(b.getName());
			}
		}
		return CommandSource.suggestMatching(names, builder);
	};

	private static final SuggestionProvider<ServerCommandSource> PLAYERS = (ctx, builder) ->
			CommandSource.suggestMatching(ctx.getSource().getServer().getPlayerNames(), builder);

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess access, CommandManager.RegistrationEnvironment env) {
		LiteralArgumentBuilder<ServerCommandSource> root = literal("companion").executes(ctx -> help(ctx.getSource()));
		root.then(literal("help").executes(ctx -> help(ctx.getSource())));

		root.then(literal("summon")
				.then(argument("name", StringArgumentType.word())
						.suggests(MY_COMPANIONS)
						.executes(ctx -> summon(ctx, null))
						.then(argument("skin", StringArgumentType.word()).suggests(PLAYERS).executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "skin"))))));
		root.then(literal("dismiss").then(nameArg().executes(ctx -> {
			CompanionBrain b = myBrain(ctx);
			if (b == null) return 0;
			CompanionManager.get().dismiss(b, false);
			ok(ctx, b.getName() + " left. Use /companion summon " + b.getName() + " to bring them back (they'll remember everything).");
			return 1;
		})));
		root.then(literal("delete").then(nameArg().then(literal("confirm").executes(ctx -> {
			CompanionBrain b = myBrain(ctx);
			if (b == null) return 0;
			CompanionManager.get().delete(b);
			ok(ctx, b.getName() + " and all their memories were deleted.");
			return 1;
		})).executes(ctx -> {
			fail(ctx, "This erases the companion and ALL its memories. Run /companion delete " + StringArgumentType.getString(ctx, "name") + " confirm");
			return 0;
		})));
		root.then(literal("list").executes(CompanionCommands::list));

		root.then(literal("say").then(nameArg().then(argument("message", StringArgumentType.greedyString()).executes(ctx -> {
			CompanionBrain b = myBrain(ctx);
			ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
			if (b == null) return 0;
			String msg = StringArgumentType.getString(ctx, "message");
			p.sendMessage(Text.literal("[you -> " + b.getName() + "] " + msg).formatted(Formatting.GRAY), false);
			b.onChat(p, msg);
			return 1;
		}))));
		root.then(literal("do").then(nameArg().then(argument("json", StringArgumentType.greedyString()).executes(CompanionCommands::doActions))));

		root.then(literal("stop").then(nameArg().executes(ctx -> withBody(ctx, (b, e, p) -> {
			e.getTaskManager().stopEverything();
			ok(ctx, b.getName() + " stopped.");
		}))));
		root.then(literal("follow").then(nameArg().executes(ctx -> withBody(ctx, (b, e, p) -> {
			e.getTaskManager().clearQueue();
			e.getTaskManager().setBehavior(new FollowBehavior(e, p.getUuid()));
			ok(ctx, b.getName() + " is following you.");
		}))));
		root.then(literal("stay").then(nameArg().executes(ctx -> withBody(ctx, (b, e, p) -> {
			e.getTaskManager().clearQueue();
			e.getTaskManager().setBehavior(new StayBehavior(e, e.getBlockPos()));
			ok(ctx, b.getName() + " will wait there.");
		}))));
		root.then(literal("come").then(nameArg().executes(ctx -> withBody(ctx, (b, e, p) -> {
			e.getTaskManager().replaceAll(List.of(new GotoTask(e, Target.of(p, p.getGameProfile().getName()), 2.5)));
			ok(ctx, b.getName() + " is coming.");
		}))));
		root.then(literal("tp").then(nameArg().executes(ctx -> withBody(ctx, (b, e, p) -> {
			e.teleportNear(p);
			ok(ctx, "Teleported " + b.getName() + " to you.");
		}))));
		root.then(literal("inventory").then(nameArg().executes(ctx -> withBody(ctx, (b, e, p) -> e.openInventory(p)))));

		root.then(literal("mode").then(nameArg().then(argument("mode", StringArgumentType.word())
				.suggests((c, sb) -> CommandSource.suggestMatching(List.of("auto", "survival", "creative"), sb))
				.executes(ctx -> withBody(ctx, (b, e, p) -> {
					GameModeSetting s = GameModeSetting.parse(StringArgumentType.getString(ctx, "mode"), null);
					if (s == null) {
						fail(ctx, "Use auto, survival or creative.");
						return;
					}
					if (s == GameModeSetting.CREATIVE && !p.isCreative() && !ctx.getSource().hasPermissionLevel(2)) {
						fail(ctx, "You need to be in creative (or an operator) to put a companion in creative.");
						return;
					}
					e.setGameModeSetting(s);
					b.getMemory().gameMode = s.id();
					b.markDirty();
					ok(ctx, b.getName() + " game mode: " + s.id() + (s == GameModeSetting.AUTO ? " (same as you)" : ""));
				})))));
		root.then(literal("stance").then(nameArg().then(argument("stance", StringArgumentType.word())
				.suggests((c, sb) -> CommandSource.suggestMatching(List.of("passive", "defensive", "aggressive"), sb))
				.executes(ctx -> withBody(ctx, (b, e, p) -> {
					Stance s = Stance.parse(StringArgumentType.getString(ctx, "stance"), Stance.DEFENSIVE);
					e.setStance(s);
					b.getMemory().stance = s.id();
					b.markDirty();
					ok(ctx, b.getName() + " stance: " + s.id());
				})))));
		root.then(literal("skin").then(nameArg().then(argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(ctx -> withBody(ctx, (b, e, p) -> {
			String skin = StringArgumentType.getString(ctx, "player");
			CompanionManager.get().applySkin(b, e, skin);
			ok(ctx, "Fetching " + skin + "'s skin for " + b.getName() + "...");
		})))));
		root.then(literal("personality").then(nameArg().then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
			CompanionBrain b = myBrain(ctx);
			if (b == null) return 0;
			String text = StringArgumentType.getString(ctx, "text");
			b.getMemory().personality = text.equalsIgnoreCase("default") ? "" : text;
			b.markDirty();
			ok(ctx, b.getName() + "'s personality: " + (b.getMemory().personality.isEmpty() ? "(default)" : text));
			return 1;
		}))));
		root.then(literal("trust").then(nameArg().then(argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(ctx -> {
			CompanionBrain b = myBrain(ctx);
			if (b == null) return 0;
			String who = StringArgumentType.getString(ctx, "player");
			b.getMemory().trusted.removeIf(s -> s.equalsIgnoreCase(who));
			b.getMemory().trusted.add(who);
			b.markDirty();
			ok(ctx, b.getName() + " will now also take orders from " + who + ".");
			return 1;
		}))));
		root.then(literal("untrust").then(nameArg().then(argument("player", StringArgumentType.word()).executes(ctx -> {
			CompanionBrain b = myBrain(ctx);
			if (b == null) return 0;
			String who = StringArgumentType.getString(ctx, "player");
			b.getMemory().trusted.removeIf(s -> s.equalsIgnoreCase(who));
			b.markDirty();
			ok(ctx, b.getName() + " no longer takes orders from " + who + ".");
			return 1;
		}))));
		root.then(literal("memory").then(nameArg().executes(CompanionCommands::showMemory)));
		root.then(literal("forget").then(nameArg()
				.executes(ctx -> forget(ctx, "chat"))
				.then(argument("what", StringArgumentType.word())
						.suggests((c, sb) -> CommandSource.suggestMatching(List.of("chat", "facts", "places", "events", "all"), sb))
						.executes(ctx -> forget(ctx, StringArgumentType.getString(ctx, "what"))))));
		root.then(literal("status").then(nameArg().executes(ctx -> withBody(ctx, (b, e, p) -> {
			ok(ctx, b.getName() + ": " + (int) e.getHealth() + "/20 hp, food " + e.getFoodLevel() + ", " + (e.isCreativeMode() ? "creative" : "survival")
					+ ", stance " + e.getStance().id() + "\nDoing: " + e.getTaskManager().describe()
					+ (b.isThinking() ? "\n(thinking...)" : ""));
		}))));

		root.then(configCommands());

		LiteralCommandNode<ServerCommandSource> node = dispatcher.register(root);
		dispatcher.register(literal("ai").executes(ctx -> help(ctx.getSource())).redirect(node));
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String> nameArg() {
		return argument("name", StringArgumentType.word()).suggests(MY_COMPANIONS);
	}

	// ------------------------------------------------------------------

	private interface BodyAction {
		void run(CompanionBrain brain, CompanionEntity entity, ServerPlayerEntity player) throws CommandSyntaxException;
	}

	private static int withBody(CommandContext<ServerCommandSource> ctx, BodyAction action) throws CommandSyntaxException {
		CompanionBrain b = myBrain(ctx);
		if (b == null) return 0;
		CompanionEntity e = CompanionManager.findEntity(ctx.getSource().getServer(), b);
		if (e == null) {
			fail(ctx, b.getName() + " isn't in the world right now. Use /companion summon " + b.getName());
			return 0;
		}
		action.run(b, e, ctx.getSource().getPlayerOrThrow());
		return 1;
	}

	@Nullable
	private static CompanionBrain myBrain(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		CompanionManager mgr = CompanionManager.get();
		if (mgr == null) return null;
		String name = StringArgumentType.getString(ctx, "name");
		CompanionBrain b = mgr.brain(name);
		if (b == null) {
			fail(ctx, "No companion called " + name + ". Summon one with /companion summon " + name);
			return null;
		}
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		if (!p.getUuid().equals(b.getMemory().ownerUuid()) && !ctx.getSource().hasPermissionLevel(2)) {
			fail(ctx, name + " belongs to " + b.getMemory().ownerName + ".");
			return null;
		}
		return b;
	}

	private static void ok(CommandContext<ServerCommandSource> ctx, String msg) {
		ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.GREEN), false);
	}

	private static void fail(CommandContext<ServerCommandSource> ctx, String msg) {
		ctx.getSource().sendError(Text.literal(msg));
	}

	private static int summon(CommandContext<ServerCommandSource> ctx, @Nullable String skin) throws CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		CompanionManager mgr = CompanionManager.get();
		if (mgr == null) return 0;
		String name = StringArgumentType.getString(ctx, "name");
		String error = mgr.summon(p, name, skin);
		if (error != null) {
			fail(ctx, error);
			return 0;
		}
		if (CompanionConfig.get().missingApiKey()) {
			ctx.getSource().sendFeedback(() -> Text.literal("Heads up: no API key set, so " + name + " can't think yet. Get a free Groq key at console.groq.com and run /companion config key <key> - or use a local model with /companion config provider ollama")
					.formatted(Formatting.GOLD), false);
		} else {
			ok(ctx, name + " is here! Talk to them in chat. Right-click them (or press G) for their menu, sneak + right-click = follow/stay.");
		}
		return 1;
	}

	private static int list(CommandContext<ServerCommandSource> ctx) {
		CompanionManager mgr = CompanionManager.get();
		if (mgr == null || mgr.brains().isEmpty()) {
			ctx.getSource().sendFeedback(() -> Text.literal("No companions yet. Try /companion summon Steve"), false);
			return 0;
		}
		for (CompanionBrain b : mgr.brains()) {
			CompanionEntity e = CompanionManager.findEntity(ctx.getSource().getServer(), b);
			String state = e != null ? (int) e.getHealth() + "hp, " + e.getTaskManager().describe() : b.getMemory().away ? "away" : "not loaded";
			ctx.getSource().sendFeedback(() -> Text.literal("- " + b.getName() + " (owner " + b.getMemory().ownerName + "): " + state), false);
		}
		return 1;
	}

	private static int doActions(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		CompanionBrain b = myBrain(ctx);
		if (b == null) return 0;
		CompanionEntity e = CompanionManager.findEntity(ctx.getSource().getServer(), b);
		if (e == null) {
			fail(ctx, b.getName() + " isn't in the world right now.");
			return 0;
		}
		String json = StringArgumentType.getString(ctx, "json");
		List<JsonObject> actions = new ArrayList<>();
		boolean queue = false;
		try {
			JsonElement el = JsonParser.parseString(json);
			if (el.isJsonArray()) {
				for (JsonElement a : el.getAsJsonArray()) if (a.isJsonObject()) actions.add(a.getAsJsonObject());
			} else if (el.isJsonObject()) {
				JsonObject o = el.getAsJsonObject();
				if (o.has("actions") && o.get("actions").isJsonArray()) {
					JsonArray arr = o.getAsJsonArray("actions");
					for (JsonElement a : arr) if (a.isJsonObject()) actions.add(a.getAsJsonObject());
					queue = o.has("queue") && o.get("queue").getAsBoolean();
				} else {
					actions.add(o);
				}
			} else {
				JsonObject o = new JsonObject();
				o.addProperty("type", json.trim());
				actions.add(o);
			}
		} catch (Exception ex) {
			JsonObject o = new JsonObject();
			o.addProperty("type", json.trim());
			actions.add(o);
		}
		ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
		ActionDispatcher.Result r = ActionDispatcher.dispatch(new ActionContext(e, b, p, true), actions, queue);
		if (!r.errors().isEmpty()) fail(ctx, String.join("; ", r.errors()));
		if (r.accepted() > 0) ok(ctx, b.getName() + ": " + e.getTaskManager().describe());
		return r.accepted();
	}

	private static int showMemory(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
		CompanionBrain b = myBrain(ctx);
		if (b == null) return 0;
		CompanionMemory m = b.getMemory();
		ServerCommandSource src = ctx.getSource();
		src.sendFeedback(() -> Text.literal("=== " + m.name + "'s memory ===").formatted(Formatting.GOLD), false);
		src.sendFeedback(() -> Text.literal("Personality: " + (m.personality.isEmpty() ? "(default)" : m.personality)), false);
		if (!m.facts.isEmpty()) {
			src.sendFeedback(() -> Text.literal("Facts:").formatted(Formatting.YELLOW), false);
			for (CompanionMemory.Fact f : m.facts) src.sendFeedback(() -> Text.literal(" - " + f.text), false);
		}
		if (!m.places.isEmpty()) {
			src.sendFeedback(() -> Text.literal("Places:").formatted(Formatting.YELLOW), false);
			for (Map.Entry<String, CompanionMemory.Place> e : m.places.entrySet()) {
				CompanionMemory.Place p = e.getValue();
				src.sendFeedback(() -> Text.literal(" - " + e.getKey() + ": " + p.x + " " + p.y + " " + p.z + " (" + p.dim + ")"), false);
			}
		}
		if (!m.summary.isBlank()) src.sendFeedback(() -> Text.literal("Summary: ").formatted(Formatting.YELLOW).append(Text.literal(m.summary).formatted(Formatting.WHITE)), false);
		if (!m.events.isEmpty()) {
			src.sendFeedback(() -> Text.literal("Recent events:").formatted(Formatting.YELLOW), false);
			int from = Math.max(0, m.events.size() - 8);
			for (int i = from; i < m.events.size(); i++) {
				CompanionMemory.EventEntry ev = m.events.get(i);
				src.sendFeedback(() -> Text.literal(" - [" + ev.when + "] " + ev.text), false);
			}
		}
		src.sendFeedback(() -> Text.literal("Chat lines remembered: " + m.history.size() + ", trusted players: " + (m.trusted.isEmpty() ? "none" : String.join(", ", m.trusted))).formatted(Formatting.GRAY), false);
		return 1;
	}

	private static int forget(CommandContext<ServerCommandSource> ctx, String what) throws CommandSyntaxException {
		CompanionBrain b = myBrain(ctx);
		if (b == null) return 0;
		CompanionMemory m = b.getMemory();
		switch (what.toLowerCase(Locale.ROOT)) {
			case "chat", "history" -> m.history.clear();
			case "facts" -> m.facts.clear();
			case "places" -> m.places.clear();
			case "events" -> m.events.clear();
			case "all", "everything" -> {
				m.history.clear();
				m.facts.clear();
				m.places.clear();
				m.events.clear();
				m.summary = "";
			}
			default -> {
				int n = m.forget(what);
				ok(ctx, "Forgot " + n + " memories about '" + what + "'.");
				b.markDirty();
				return 1;
			}
		}
		b.markDirty();
		ok(ctx, b.getName() + " forgot their " + what + ".");
		return 1;
	}

	// ------------------------------------------------------------------
	// config
	// ------------------------------------------------------------------

	private static boolean canConfigure(ServerCommandSource src) {
		if (src.hasPermissionLevel(2)) return true;
		ServerPlayerEntity p = src.getPlayer();
		return p != null && src.getServer().isSingleplayer() && src.getServer().isHost(p.getGameProfile());
	}

	private static LiteralArgumentBuilder<ServerCommandSource> configCommands() {
		LiteralArgumentBuilder<ServerCommandSource> cfg = literal("config").requires(CompanionCommands::canConfigure);
		cfg.executes(CompanionCommands::showConfig);
		cfg.then(literal("show").executes(CompanionCommands::showConfig));
		cfg.then(literal("reload").executes(ctx -> {
			CompanionConfig.load();
			LlmClient.resetJsonModeState();
			ok(ctx, "Config reloaded from " + CompanionConfig.path());
			return 1;
		}));
		cfg.then(literal("provider").then(argument("provider", StringArgumentType.word())
				.suggests((c, sb) -> CommandSource.suggestMatching(List.of("groq", "ollama", "lmstudio", "llamacpp", "openrouter", "openai"), sb))
				.executes(ctx -> {
					CompanionConfig c = CompanionConfig.get();
					c.provider = StringArgumentType.getString(ctx, "provider").toLowerCase(Locale.ROOT);
					c.baseUrl = "";
					c.model = "";
					CompanionConfig.save();
					LlmClient.resetJsonModeState();
					ok(ctx, "Provider: " + c.provider + " -> " + c.effectiveBaseUrl() + ", model " + c.effectiveModel()
							+ ". Change the model with /companion config model <name>.");
					return 1;
				})));
		cfg.then(literal("model").then(argument("model", StringArgumentType.greedyString()).executes(ctx -> {
			CompanionConfig c = CompanionConfig.get();
			c.model = StringArgumentType.getString(ctx, "model").trim();
			CompanionConfig.save();
			ok(ctx, "Model: " + c.effectiveModel());
			return 1;
		})));
		cfg.then(literal("key").then(argument("key", StringArgumentType.greedyString()).executes(ctx -> {
			CompanionConfig c = CompanionConfig.get();
			c.apiKey = StringArgumentType.getString(ctx, "key").trim();
			CompanionConfig.save();
			ok(ctx, "API key saved (" + mask(c.apiKey) + ") in " + CompanionConfig.path().getFileName() + ".");
			return 1;
		})));
		cfg.then(literal("url").then(argument("url", StringArgumentType.greedyString()).executes(ctx -> {
			CompanionConfig c = CompanionConfig.get();
			c.baseUrl = StringArgumentType.getString(ctx, "url").trim();
			CompanionConfig.save();
			LlmClient.resetJsonModeState();
			ok(ctx, "Base URL: " + c.effectiveBaseUrl());
			return 1;
		})));
		cfg.then(literal("set").then(argument("field", StringArgumentType.word())
				.suggests((c, sb) -> CommandSource.suggestMatching(configFields(), sb))
				.then(argument("value", StringArgumentType.greedyString()).executes(ctx -> {
					String field = StringArgumentType.getString(ctx, "field");
					String value = StringArgumentType.getString(ctx, "value");
					String err = setField(field, value);
					if (err != null) {
						fail(ctx, err);
						return 0;
					}
					CompanionConfig.save();
					ok(ctx, field + " = " + value);
					return 1;
				}))));
		cfg.then(literal("test").executes(CompanionCommands::testConnection));
		return cfg;
	}

	private static String mask(String key) {
		if (key == null || key.length() < 8) return "***";
		return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
	}

	private static List<String> configFields() {
		List<String> out = new ArrayList<>();
		for (Field f : CompanionConfig.class.getFields()) {
			if (!Modifier.isStatic(f.getModifiers())) out.add(f.getName());
		}
		return out;
	}

	@Nullable
	private static String setField(String name, String value) {
		try {
			Field f = CompanionConfig.class.getField(name);
			if (Modifier.isStatic(f.getModifiers())) return "Unknown setting " + name;
			Object cfg = CompanionConfig.get();
			Class<?> t = f.getType();
			if (t == String.class) f.set(cfg, value);
			else if (t == int.class) f.setInt(cfg, Integer.parseInt(value.trim()));
			else if (t == double.class) f.setDouble(cfg, Double.parseDouble(value.trim()));
			else if (t == boolean.class) f.setBoolean(cfg, value.trim().equalsIgnoreCase("true") || value.trim().equals("1") || value.trim().equalsIgnoreCase("on"));
			else return "Can't set " + name + " from a command";
			return null;
		} catch (NoSuchFieldException e) {
			return "Unknown setting " + name + ". Settings: " + String.join(", ", configFields());
		} catch (NumberFormatException e) {
			return "That's not a number.";
		} catch (IllegalAccessException e) {
			return "Can't change " + name;
		}
	}

	private static int showConfig(CommandContext<ServerCommandSource> ctx) {
		CompanionConfig c = CompanionConfig.get();
		ServerCommandSource src = ctx.getSource();
		src.sendFeedback(() -> Text.literal("=== AI Companion config ===").formatted(Formatting.GOLD), false);
		src.sendFeedback(() -> Text.literal("Provider: " + c.effectiveProvider() + " (" + c.provider + ")"), false);
		src.sendFeedback(() -> Text.literal("URL: " + c.effectiveBaseUrl()), false);
		src.sendFeedback(() -> Text.literal("Model: " + c.effectiveModel()), false);
		src.sendFeedback(() -> Text.literal("API key: " + (c.effectiveApiKey().isEmpty() ? "none" : mask(c.effectiveApiKey()))), false);
		src.sendFeedback(() -> Text.literal("File: " + CompanionConfig.path() + " (other settings: /companion config set <name> <value>)").formatted(Formatting.GRAY), false);
		return 1;
	}

	private static int testConnection(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		long start = System.currentTimeMillis();
		src.sendFeedback(() -> Text.literal("Asking " + CompanionConfig.get().effectiveModel() + "..."), false);
		List<LlmClient.Message> msgs = List.of(
				new LlmClient.Message("system", "Reply with a JSON object {\"say\": \"<a short greeting as a Minecraft player>\"}."),
				new LlmClient.Message("user", "Say hi."));
		LlmClient.chat(msgs, true).whenComplete((text, err) -> src.getServer().execute(() -> {
			long ms = System.currentTimeMillis() - start;
			if (err != null) {
				Throwable t = err.getCause() != null ? err.getCause() : err;
				src.sendError(Text.literal("Failed after " + ms + "ms: " + t.getMessage()));
			} else {
				src.sendFeedback(() -> Text.literal("OK in " + ms + "ms: " + LlmClient.truncate(text, 200)).formatted(Formatting.GREEN), false);
			}
		}));
		return 1;
	}

	private static int help(ServerCommandSource src) {
		String[] lines = {
				"§6=== AI Companion ===",
				"§e/companion summon <name> [skin]§r - bring a companion (skin = any real player name)",
				"§e/companion dismiss <name>§r - send them away (they keep memories + items)",
				"§7Just talk in chat - your companion hears you when nearby (or say their name).",
				"§7Examples: \"surround me with blocks and protect me\", \"build a house here\", \"get some wood\", \"follow me\", \"remember this spot as home\"",
				"§e/companion stop|follow|stay|come|tp <name>§r - quick orders",
				"§e/companion mode <name> auto|survival|creative§r, §e/companion stance <name> passive|defensive|aggressive",
				"§e/companion skin <name> <player>§r, §e/companion personality <name> <text>",
				"§e/companion memory <name>§r, §e/companion forget <name> [chat|facts|places|all]",
				"§e/companion inventory <name>§r, sneak+right-click = follow/stay. Press §eG§r for the companion menu.",
				"§e/companion do <name> <json>§r - run actions directly, e.g. {\"type\":\"build\",\"structure\":\"tower\"}",
				"§e/companion config§r - provider/model/key (groq, ollama, lmstudio...), §e/companion config test",
		};
		for (String l : lines) src.sendFeedback(() -> Text.literal(l), false);
		return 1;
	}

	@SuppressWarnings("unused")
	private static MutableText clickable(String text, String command) {
		return Text.literal(text).setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)).withColor(Formatting.AQUA));
	}
}
