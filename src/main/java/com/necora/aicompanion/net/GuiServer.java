package com.necora.aicompanion.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.necora.aicompanion.AICompanionMod;
import com.necora.aicompanion.ai.ActionContext;
import com.necora.aicompanion.ai.ActionDispatcher;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.ai.LlmClient;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.config.ConfigJson;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.GameModeSetting;
import com.necora.aicompanion.entity.Stance;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.memory.CompanionMemory;
import com.necora.aicompanion.util.Json;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of the companion screens. Every button in the GUI ends up here as a small JSON
 * request; the answers (companion lists, details, config) go back as JSON too. Permission rules
 * are the same as for the /companion commands: owners (and operators) manage a companion,
 * trusted players may give it orders, only operators (or the singleplayer host) change the config.
 */
public final class GuiServer {
	private static final String[] ACKS = {"ok", "on it", "sure", "got it", "alright", "okay!", "yep", "will do"};
	private static final Map<String, Long> lastAck = new HashMap<>();

	private GuiServer() {
	}

	public static void init() {
		ServerPlayNetworking.registerGlobalReceiver(GuiPayloads.Request.ID, (payload, context) ->
				handle(context.player(), payload.action(), payload.data()));
	}

	public static boolean hasGui(ServerPlayerEntity player) {
		return ServerPlayNetworking.canSend(player, GuiPayloads.Update.ID);
	}

	public static void send(ServerPlayerEntity player, String kind, JsonObject data) {
		if (hasGui(player)) ServerPlayNetworking.send(player, new GuiPayloads.Update(kind, data.toString()));
	}

	public static void toast(ServerPlayerEntity player, String text, boolean error) {
		JsonObject o = new JsonObject();
		o.addProperty("text", text);
		o.addProperty("error", error);
		send(player, "toast", o);
	}

	/** Opens the companion screen on the player's client (right-clicking the companion). */
	public static void openFor(ServerPlayerEntity player, CompanionBrain brain) {
		JsonObject o = new JsonObject();
		o.addProperty("name", brain.getName());
		send(player, "open", o);
		send(player, "detail", detail(player, brain));
	}

	// ------------------------------------------------------------------
	// permissions
	// ------------------------------------------------------------------

	public static boolean isAdmin(ServerPlayerEntity p) {
		if (p.hasPermissionLevel(2)) return true;
		MinecraftServer server = p.getServer();
		return server != null && server.isSingleplayer() && server.isHost(p.getGameProfile());
	}

	public static boolean canManage(ServerPlayerEntity p, CompanionBrain b) {
		return p.getUuid().equals(b.getMemory().ownerUuid()) || isAdmin(p);
	}

	public static boolean canOrder(ServerPlayerEntity p, CompanionBrain b) {
		return canManage(p, b) || b.getMemory().isTrusted(p.getGameProfile().getName());
	}

	// ------------------------------------------------------------------
	// requests
	// ------------------------------------------------------------------

	public static void handle(ServerPlayerEntity p, String action, String data) {
		CompanionManager mgr = CompanionManager.get();
		if (mgr == null) return;
		JsonObject in;
		try {
			JsonElement el = JsonParser.parseString(data == null || data.isBlank() ? "{}" : data);
			in = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			in = new JsonObject();
		}
		try {
			switch (action) {
				case "list" -> send(p, "list", list(p));
				case "config_get" -> sendConfig(p);
				case "config_set" -> configSet(p, in);
				case "config_test" -> configTest(p);
				case "summon" -> summon(p, mgr, in);
				default -> {
					String name = Json.strOr(in, "", "name");
					CompanionBrain b = mgr.brain(name);
					if (b == null) {
						toast(p, "There's no companion called " + name + ".", true);
						send(p, "list", list(p));
						return;
					}
					if (!canOrder(p, b)) {
						toast(p, b.getName() + " belongs to " + b.getMemory().ownerName + ".", true);
						return;
					}
					companionAction(p, mgr, b, action, in);
				}
			}
		} catch (Exception e) {
			AICompanionMod.LOGGER.warn("GUI request '{}' failed", action, e);
			toast(p, "Something went wrong: " + e.getMessage(), true);
		}
	}

	private static void summon(ServerPlayerEntity p, CompanionManager mgr, JsonObject in) {
		String name = Json.strOr(in, "", "name").trim();
		String skin = Json.strOr(in, "", "skin").trim();
		if (name.isEmpty()) {
			toast(p, "Give your companion a name first.", true);
			return;
		}
		String error = mgr.summon(p, name, skin.isEmpty() ? null : skin);
		if (error != null) {
			toast(p, error, true);
		} else if (CompanionConfig.get().missingApiKey()) {
			toast(p, name + " is here, but can't think yet: no API key set. Open AI Settings.", true);
		} else {
			toast(p, name + " is here! Talk to them in chat.", false);
		}
		send(p, "list", list(p));
	}

	private static void companionAction(ServerPlayerEntity p, CompanionManager mgr, CompanionBrain b, String action, JsonObject in) {
		CompanionMemory m = b.getMemory();
		CompanionEntity e = CompanionManager.findEntity(p.getServer(), b);
		boolean manage = canManage(p, b);
		switch (action) {
			case "detail" -> {
				send(p, "detail", detail(p, b));
				return;
			}
			case "chat" -> {
				String text = Json.strOr(in, "", "text").trim();
				if (text.isEmpty()) return;
				if (text.length() > 256) text = text.substring(0, 256);
				p.sendMessage(Text.literal("[you -> " + b.getName() + "] " + text).formatted(Formatting.GRAY), false);
				b.onChat(p, text);
			}
			case "order" -> {
				if (e == null) {
					toast(p, b.getName() + " isn't in the world right now. Summon them first.", true);
					return;
				}
				List<JsonObject> actions = new ArrayList<>();
				JsonElement arr = in.get("actions");
				if (arr != null && arr.isJsonArray()) {
					for (JsonElement a : arr.getAsJsonArray()) if (a.isJsonObject()) actions.add(a.getAsJsonObject());
				}
				if (actions.isEmpty()) return;
				boolean queue = Json.bool(in, false, "queue");
				ActionDispatcher.Result r = ActionDispatcher.dispatch(new ActionContext(e, b, p, true), actions, queue);
				String label = Json.strOr(in, describeActions(actions), "label");
				if (!r.errors().isEmpty()) {
					toast(p, b.getName() + " can't: " + String.join("; ", r.errors()), true);
				} else {
					toast(p, b.getName() + ": " + e.getTaskManager().describe(), false);
				}
				if (r.accepted() > 0) {
					m.addEvent(p.getGameProfile().getName() + " ordered you (from the menu) to: " + label, b.timeStamp());
					b.markDirty();
					acknowledge(b, e);
				}
			}
			case "tp" -> {
				if (e == null) {
					toast(p, b.getName() + " isn't in the world right now.", true);
					return;
				}
				e.teleportNear(p);
			}
			case "inventory" -> {
				if (e == null) {
					toast(p, b.getName() + " isn't in the world right now.", true);
					return;
				}
				e.openInventory(p);
				return;
			}
			case "stance" -> {
				Stance s = Stance.parse(Json.strOr(in, "defensive", "value"), Stance.DEFENSIVE);
				m.stance = s.id();
				if (e != null) e.setStance(s);
				b.markDirty();
			}
			default -> {
				if (!manage) {
					toast(p, "Only " + m.ownerName + " can change that.", true);
					return;
				}
				manageAction(p, mgr, b, e, action, in);
				if (action.equals("dismiss") || action.equals("delete")) {
					send(p, "list", list(p));
					return;
				}
			}
		}
		if (mgr.brain(b.getName()) != null) send(p, "detail", detail(p, b));
	}

	private static void manageAction(ServerPlayerEntity p, CompanionManager mgr, CompanionBrain b, @Nullable CompanionEntity e, String action, JsonObject in) {
		CompanionMemory m = b.getMemory();
		String value = Json.strOr(in, "", "value").trim();
		switch (action) {
			case "mode" -> {
				GameModeSetting s = GameModeSetting.parse(value, null);
				if (s == null) return;
				if (s == GameModeSetting.CREATIVE && !p.isCreative() && !isAdmin(p)) {
					toast(p, "You need to be in creative (or an operator) to put a companion in creative.", true);
					return;
				}
				m.gameMode = s.id();
				if (e != null) e.setGameModeSetting(s);
				b.markDirty();
			}
			case "skin" -> {
				if (value.isEmpty()) return;
				if (e != null) {
					mgr.applySkin(b, e, value);
					toast(p, "Fetching " + value + "'s skin...", false);
				} else {
					toast(p, b.getName() + " has to be in the world to change skin.", true);
				}
			}
			case "personality" -> {
				m.personality = value.length() > 400 ? value.substring(0, 400) : value;
				b.markDirty();
				toast(p, "Saved " + b.getName() + "'s personality.", false);
			}
			case "trust" -> {
				if (value.isEmpty() || value.length() > 16) return;
				m.trusted.removeIf(s -> s.equalsIgnoreCase(value));
				m.trusted.add(value);
				b.markDirty();
			}
			case "untrust" -> {
				m.trusted.removeIf(s -> s.equalsIgnoreCase(value));
				b.markDirty();
			}
			case "add_fact" -> {
				if (m.addFact(value, b.day())) b.markDirty();
			}
			case "remove_fact" -> {
				int index = Json.integer(in, -1, "index");
				if (index >= 0 && index < m.facts.size() && m.facts.get(index).text.equals(value)) {
					m.facts.remove(index);
					b.markDirty();
				}
			}
			case "remove_place" -> {
				if (m.places.remove(value) != null) b.markDirty();
			}
			case "forget" -> {
				switch (value.toLowerCase(Locale.ROOT)) {
					case "chat" -> {
						m.history.clear();
						m.summary = "";
					}
					case "facts" -> m.facts.clear();
					case "places" -> m.places.clear();
					case "events" -> m.events.clear();
					case "all" -> {
						m.history.clear();
						m.facts.clear();
						m.places.clear();
						m.events.clear();
						m.summary = "";
					}
					default -> {
						return;
					}
				}
				b.markDirty();
				toast(p, b.getName() + " forgot their " + value.toLowerCase(Locale.ROOT) + ".", false);
			}
			case "dismiss" -> {
				mgr.dismiss(b, false);
				toast(p, b.getName() + " left. Summon them again any time, they'll remember everything.", false);
			}
			case "delete" -> {
				mgr.delete(b);
				toast(p, b.getName() + " and all their memories were deleted.", false);
			}
			default -> toast(p, "Unknown request " + action, true);
		}
	}

	/** A quick "ok" in chat, like a player would type, but not for every single click. */
	private static void acknowledge(CompanionBrain b, CompanionEntity e) {
		long now = System.currentTimeMillis();
		Long last = lastAck.get(b.getName());
		if (last != null && now - last < 8000) return;
		lastAck.put(b.getName(), now);
		e.queueChat(ACKS[e.getRandom().nextInt(ACKS.length)], 0);
	}

	private static String describeActions(List<JsonObject> actions) {
		List<String> parts = new ArrayList<>();
		for (JsonObject a : actions) {
			String type = Json.strOr(a, "?", "type");
			String what = Json.strOr(a, "", "structure", "block", "item", "what");
			parts.add(what.isEmpty() ? type : type + " " + what);
		}
		return String.join(", ", parts);
	}

	// ------------------------------------------------------------------
	// state for the client
	// ------------------------------------------------------------------

	public static JsonObject list(ServerPlayerEntity p) {
		CompanionManager mgr = CompanionManager.get();
		JsonObject o = new JsonObject();
		CompanionConfig cfg = CompanionConfig.get();
		o.addProperty("missingKey", cfg.missingApiKey());
		o.addProperty("provider", cfg.effectiveProvider());
		o.addProperty("model", cfg.effectiveModel());
		o.addProperty("canConfigure", isAdmin(p));
		o.addProperty("maxCompanions", cfg.maxCompanionsPerPlayer);
		JsonArray arr = new JsonArray();
		if (mgr != null) {
			List<CompanionBrain> sorted = new ArrayList<>(mgr.brains());
			UUID me = p.getUuid();
			sorted.sort((a, b) -> {
				boolean am = me.equals(a.getMemory().ownerUuid()), bm = me.equals(b.getMemory().ownerUuid());
				if (am != bm) return am ? -1 : 1;
				return a.getName().compareToIgnoreCase(b.getName());
			});
			for (CompanionBrain b : sorted) {
				if (!canOrder(p, b)) continue;
				arr.add(summary(p, b));
			}
		}
		o.add("companions", arr);
		return o;
	}

	private static JsonObject summary(ServerPlayerEntity p, CompanionBrain b) {
		CompanionMemory m = b.getMemory();
		CompanionEntity e = CompanionManager.findEntity(p.getServer(), b);
		JsonObject c = new JsonObject();
		c.addProperty("name", b.getName());
		c.addProperty("owner", m.ownerName == null ? "?" : m.ownerName);
		c.addProperty("mine", p.getUuid().equals(m.ownerUuid()));
		c.addProperty("canManage", canManage(p, b));
		c.addProperty("present", e != null);
		c.addProperty("away", m.away);
		c.addProperty("thinking", b.isThinking());
		c.addProperty("skin", m.skinName == null ? "" : m.skinName);
		if (e != null) {
			c.addProperty("entityId", e.getId());
			c.addProperty("health", e.getHealth());
			c.addProperty("maxHealth", e.getMaxHealth());
			c.addProperty("food", e.getFoodLevel());
			c.addProperty("creative", e.isCreativeMode());
			c.addProperty("activity", e.getTaskManager().describe());
			c.addProperty("distance", e.getWorld() == p.getWorld() ? (int) Math.sqrt(e.squaredDistanceTo(p)) : -1);
			c.addProperty("dimension", e.getWorld().getRegistryKey().getValue().getPath());
		} else {
			c.addProperty("activity", m.away ? "away" : "not loaded (far away)");
		}
		return c;
	}

	public static JsonObject detail(ServerPlayerEntity p, CompanionBrain b) {
		CompanionMemory m = b.getMemory();
		CompanionEntity e = CompanionManager.findEntity(p.getServer(), b);
		JsonObject o = summary(p, b);
		o.addProperty("mode", GameModeSetting.parse(m.gameMode, GameModeSetting.AUTO).id());
		o.addProperty("stance", e != null ? e.getStance().id() : Stance.parse(m.stance, Stance.DEFENSIVE).id());
		o.addProperty("personality", m.personality == null ? "" : m.personality);
		o.addProperty("defaultPersonality", CompanionConfig.get().defaultPersonality);
		o.addProperty("summary", m.summary == null ? "" : m.summary);
		o.addProperty("lastBuild", b.getLastBuild() == null || b.getLastBuild().isEmpty() ? "" : b.getLastBuildLabel());
		if (e != null) {
			o.addProperty("x", e.getBlockX());
			o.addProperty("y", e.getBlockY());
			o.addProperty("z", e.getBlockZ());
			o.addProperty("armor", e.getArmor());
			o.add("items", items(e));
		}
		JsonArray trusted = new JsonArray();
		for (String t : m.trusted) trusted.add(t);
		o.add("trusted", trusted);

		JsonArray facts = new JsonArray();
		for (CompanionMemory.Fact f : m.facts) facts.add(f.text);
		o.add("facts", facts);

		JsonArray places = new JsonArray();
		for (Map.Entry<String, CompanionMemory.Place> en : m.places.entrySet()) {
			CompanionMemory.Place pl = en.getValue();
			JsonObject po = new JsonObject();
			po.addProperty("name", en.getKey());
			po.addProperty("x", pl.x);
			po.addProperty("y", pl.y);
			po.addProperty("z", pl.z);
			po.addProperty("dim", pl.dim == null ? "" : pl.dim.replace("minecraft:", ""));
			places.add(po);
		}
		o.add("places", places);

		JsonArray events = new JsonArray();
		for (int i = Math.max(0, m.events.size() - 12); i < m.events.size(); i++) {
			CompanionMemory.EventEntry ev = m.events.get(i);
			events.add(LlmClient.truncate(ev.text, 200));
		}
		o.add("events", events);

		o.add("chat", chatLog(m, b.getName()));

		JsonObject stats = new JsonObject();
		stats.addProperty("kills", m.stats.kills);
		stats.addProperty("deaths", m.stats.deaths);
		stats.addProperty("placed", m.stats.blocksPlaced);
		stats.addProperty("broken", m.stats.blocksBroken);
		stats.addProperty("messages", m.stats.messages);
		o.add("stats", stats);
		return o;
	}

	/** Items the companion carries, merged by type (for the "give me" picker). */
	private static JsonArray items(CompanionEntity e) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		List<ItemStack> stacks = new ArrayList<>();
		stacks.add(e.getMainHandStack());
		stacks.add(e.getOffHandStack());
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			stacks.add(e.getEquippedStack(slot));
		}
		for (int i = 0; i < e.getInventory().size(); i++) stacks.add(e.getInventory().getStack(i));
		for (ItemStack s : stacks) {
			if (s.isEmpty()) continue;
			counts.merge(Registries.ITEM.getId(s.getItem()).getPath(), s.getCount(), Integer::sum);
		}
		JsonArray arr = new JsonArray();
		for (Map.Entry<String, Integer> en : counts.entrySet()) {
			JsonObject io = new JsonObject();
			io.addProperty("id", en.getKey());
			io.addProperty("count", en.getValue());
			arr.add(io);
			if (arr.size() >= 45) break;
		}
		return arr;
	}

	/** The conversation as readable lines: who said what, and what the companion decided to do. */
	public static JsonArray chatLog(CompanionMemory m, String companionName) {
		JsonArray out = new JsonArray();
		int from = Math.max(0, m.history.size() - 40);
		for (int i = from; i < m.history.size(); i++) {
			CompanionMemory.ChatLine line = m.history.get(i);
			if (line.content == null) continue;
			if ("user".equals(line.role)) {
				for (String l : line.content.split("\n")) {
					if (l.startsWith("[CHAT] ")) chatLine(out, "player", l.substring(7));
					else if (l.startsWith("[EVENT] ") && !l.contains("It's been quiet")) chatLine(out, "event", l.substring(8));
				}
			} else if ("assistant".equals(line.role)) {
				try {
					JsonObject r = JsonParser.parseString(line.content).getAsJsonObject();
					String say = Json.strOr(r, "", "say");
					if (!say.isEmpty()) chatLine(out, "companion", "<" + companionName + "> " + say);
					JsonElement acts = r.get("actions");
					if (acts != null && acts.isJsonArray() && !acts.getAsJsonArray().isEmpty()) {
						List<JsonObject> list = new ArrayList<>();
						for (JsonElement a : acts.getAsJsonArray()) if (a.isJsonObject()) list.add(a.getAsJsonObject());
						if (!list.isEmpty()) chatLine(out, "action", "-> " + describeActions(list));
					}
				} catch (Exception ex) {
					chatLine(out, "companion", "<" + companionName + "> " + line.content);
				}
			}
		}
		// keep the packet small: only the newest lines
		while (out.size() > 60) out.remove(0);
		return out;
	}

	private static void chatLine(JsonArray out, String kind, String text) {
		JsonObject o = new JsonObject();
		o.addProperty("kind", kind);
		o.addProperty("text", LlmClient.truncate(text, 300));
		out.add(o);
	}

	// ------------------------------------------------------------------
	// config
	// ------------------------------------------------------------------

	private static void sendConfig(ServerPlayerEntity p) {
		JsonObject o = isAdmin(p) ? ConfigJson.describe(CompanionConfig.get()) : new JsonObject();
		o.addProperty("canConfigure", isAdmin(p));
		send(p, "config", o);
	}

	private static void configSet(ServerPlayerEntity p, JsonObject in) {
		if (!isAdmin(p)) {
			toast(p, "Only operators can change the AI settings on this server.", true);
			return;
		}
		String error = ConfigJson.apply(CompanionConfig.get(), in);
		CompanionConfig.save();
		LlmClient.resetJsonModeState();
		if (error != null) toast(p, "Saved, except: " + error, true);
		else toast(p, "AI settings saved.", false);
		sendConfig(p);
	}

	private static void configTest(ServerPlayerEntity p) {
		if (!isAdmin(p)) {
			toast(p, "Only operators can test the AI connection.", true);
			return;
		}
		MinecraftServer server = p.getServer();
		UUID id = p.getUuid();
		long start = System.currentTimeMillis();
		List<LlmClient.Message> msgs = List.of(
				new LlmClient.Message("system", "Reply with a JSON object {\"say\": \"<a short greeting as a Minecraft player>\"}."),
				new LlmClient.Message("user", "Say hi."));
		LlmClient.chat(msgs, true).whenComplete((text, err) -> {
			if (server == null) return;
			server.execute(() -> {
				ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
				if (player == null) return;
				JsonObject o = new JsonObject();
				long ms = System.currentTimeMillis() - start;
				o.addProperty("ms", ms);
				if (err != null) {
					Throwable t = err.getCause() != null ? err.getCause() : err;
					o.addProperty("ok", false);
					o.addProperty("text", String.valueOf(t.getMessage()));
				} else {
					o.addProperty("ok", true);
					o.addProperty("text", LlmClient.truncate(text, 200));
				}
				send(player, "test", o);
			});
		});
	}
}
