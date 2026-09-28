package com.necora.aicompanion.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Converts the config to and from the JSON the settings screen works with. The same code is
 * used for the local file (title screen) and for a server's config (sent over the network).
 * The API key itself is never sent to a client, only whether one is set.
 */
public final class ConfigJson {
	private ConfigJson() {
	}

	public record FieldInfo(String name, String label, String help, String section) {
	}

	public static final List<String> PROVIDERS = List.of("groq", "ollama", "lmstudio", "llamacpp", "openrouter", "openai");

	public static final List<FieldInfo> FIELDS = List.of(
			new FieldInfo("respondWithoutName", "Answer me without its name", "Your companion answers anything you say nearby, even if you don't say its name.", "Chat"),
			new FieldInfo("listenRadius", "Hearing range (blocks)", "Companions hear players within this many blocks.", "Chat"),
			new FieldInfo("chatRange", "Chat range (0 = everyone)", "0 = everyone on the server sees what companions say, like a real player.", "Chat"),
			new FieldInfo("typingDelay", "Typing delay", "Replies appear after a short, human-like typing pause.", "Chat"),
			new FieldInfo("idleChatter", "Idle chatter", "Now and then, the companion says something on its own when things are quiet.", "Chat"),
			new FieldInfo("idleChatterMinutes", "Idle chatter every (min)", "Roughly how often idle chatter happens.", "Chat"),
			new FieldInfo("reactToEvents", "React to events", "Comment on finished jobs, gifts, getting hurt, deaths...", "Chat"),
			new FieldInfo("minSecondsBetweenEventCalls", "Seconds between reactions", "Minimum time between two automatic AI calls (saves tokens and rate limits).", "Chat"),
			new FieldInfo("greetOnJoin", "Say hi when joining", "Greets you when you log in.", "Chat"),
			new FieldInfo("defaultPersonality", "Default personality", "Used by companions that don't have their own personality.", "Memory"),
			new FieldInfo("maxHistoryMessages", "Recent messages sent to AI", "Older chat gets summarized into long-term memory.", "Memory"),
			new FieldInfo("maxFacts", "Max remembered facts", "Oldest facts are dropped beyond this.", "Memory"),
			new FieldInfo("maxEvents", "Max remembered events", "Recent things that happened (builds, deaths, gifts...).", "Memory"),
			new FieldInfo("maxCompanionsPerPlayer", "Companions per player", "How many companions one player can have in the world at once.", "Gameplay"),
			new FieldInfo("requireMaterialsInSurvival", "Survival builds need blocks", "In survival, companions need the real blocks in their inventory to build.", "Gameplay"),
			new FieldInfo("hunger", "Hunger", "Companions get hungry and have to eat in survival.", "Gameplay"),
			new FieldInfo("autoRespawn", "Respawn after death", "Companions come back after dying (their items drop where they died).", "Gameplay"),
			new FieldInfo("respawnDelaySeconds", "Respawn delay (s)", "", "Gameplay"),
			new FieldInfo("mobsTargetCompanion", "Mobs attack companions", "Hostile mobs go after companions like they go after players.", "Gameplay"),
			new FieldInfo("ownerCanHurtCompanion", "I can hurt my companion", "Off = your hits don't hurt it (it just complains).", "Gameplay"),
			new FieldInfo("leaveWithOwner", "Leave and join with me", "Companions log out when you do and come back with you.", "Gameplay"),
			new FieldInfo("teleportToOwnerDistance", "Teleport to me after (blocks)", "0 = never teleport, always walk.", "Gameplay"),
			new FieldInfo("followAcrossDimensions", "Follow through portals", "", "Gameplay"),
			new FieldInfo("keepChunksLoaded", "Keep working when I leave", "Keeps the companion's chunks loaded so jobs continue far away from you.", "Gameplay"),
			new FieldInfo("reachAssist", "Reach assist", "Lets companions place or break blocks up to 6.5 blocks away when they can't get closer.", "Gameplay"),
			new FieldInfo("maxBuildBlocks", "Max blocks per build", "Safety limit for a single build or dig job.", "Gameplay"),
			new FieldInfo("allowModelGameModeChange", "AI may switch game mode", "Lets the AI switch between survival and creative when you ask (only if you're in creative or an operator).", "Gameplay"),
			new FieldInfo("allowBreakingContainers", "May break chests etc.", "Allow companions to break blocks that hold items (chests, furnaces...).", "Gameplay"),
			new FieldInfo("temperature", "Creativity (temperature)", "Higher = more varied, chattier replies. 0.2 - 1.2 is sensible.", "AI"),
			new FieldInfo("maxResponseTokens", "Max reply length (tokens)", "", "AI"),
			new FieldInfo("requestTimeoutSeconds", "Request timeout (s)", "Local models on slow PCs may need more.", "AI"),
			new FieldInfo("jsonMode", "Strict JSON mode", "Asks the model server for strict JSON. Turned off automatically if the server doesn't support it.", "AI"),
			new FieldInfo("ollamaContextSize", "Ollama context size", "Context window requested from Ollama.", "AI"));

	public static String mask(String key) {
		if (key == null || key.isBlank()) return "";
		String k = key.trim();
		if (k.length() < 10) return "****";
		return k.substring(0, 4) + "..." + k.substring(k.length() - 4);
	}

	public static JsonObject describe(CompanionConfig c) {
		JsonObject o = new JsonObject();
		o.addProperty("provider", c.provider == null ? "groq" : c.provider);
		o.addProperty("model", c.model == null ? "" : c.model);
		o.addProperty("baseUrl", c.baseUrl == null ? "" : c.baseUrl);
		o.addProperty("effectiveModel", c.effectiveModel());
		o.addProperty("effectiveUrl", c.effectiveBaseUrl());
		boolean keySet = c.apiKey != null && !c.apiKey.isBlank();
		o.addProperty("keySet", keySet);
		o.addProperty("keyMasked", mask(c.apiKey));
		o.addProperty("keyFromEnv", !keySet && !c.effectiveApiKey().isEmpty());
		o.addProperty("missingKey", c.missingApiKey());
		JsonArray fields = new JsonArray();
		for (FieldInfo info : FIELDS) {
			try {
				Field f = CompanionConfig.class.getField(info.name());
				JsonObject fo = new JsonObject();
				fo.addProperty("name", info.name());
				fo.addProperty("label", info.label());
				fo.addProperty("help", info.help());
				fo.addProperty("section", info.section());
				Class<?> t = f.getType();
				if (t == boolean.class) {
					fo.addProperty("type", "bool");
					fo.addProperty("value", f.getBoolean(c));
				} else if (t == int.class) {
					fo.addProperty("type", "int");
					fo.addProperty("value", f.getInt(c));
				} else if (t == double.class) {
					fo.addProperty("type", "double");
					fo.addProperty("value", f.getDouble(c));
				} else {
					fo.addProperty("type", "string");
					Object v = f.get(c);
					fo.addProperty("value", v == null ? "" : v.toString());
				}
				fields.add(fo);
			} catch (ReflectiveOperationException ignored) {
				// field list and class out of sync: skip it
			}
		}
		o.add("fields", fields);
		return o;
	}

	/**
	 * Applies the changes in {@code in}: provider, model, baseUrl, apiKey (only when non-empty),
	 * clearKey, and fields {name: value}. Returns an error message, or null when everything applied.
	 */
	@Nullable
	public static String apply(CompanionConfig c, JsonObject in) {
		StringBuilder errors = new StringBuilder();
		if (in.has("provider")) {
			String p = in.get("provider").getAsString().trim().toLowerCase(java.util.Locale.ROOT);
			if (!p.isEmpty() && !p.equals(c.provider)) {
				c.provider = p;
				// the old provider's URL and model rarely make sense for the new one
				if (!in.has("baseUrl")) c.baseUrl = "";
				if (!in.has("model")) c.model = "";
			}
		}
		if (in.has("model")) c.model = in.get("model").getAsString().trim();
		if (in.has("baseUrl")) c.baseUrl = in.get("baseUrl").getAsString().trim();
		if (in.has("apiKey")) {
			String key = in.get("apiKey").getAsString().trim();
			if (!key.isEmpty()) c.apiKey = key;
		}
		if (in.has("clearKey") && in.get("clearKey").getAsBoolean()) c.apiKey = "";
		if (in.has("fields") && in.get("fields").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("fields").entrySet()) {
				String name = e.getKey();
				boolean known = FIELDS.stream().anyMatch(fi -> fi.name().equals(name));
				if (!known) continue;
				try {
					Field f = CompanionConfig.class.getField(name);
					Class<?> t = f.getType();
					String v = e.getValue().getAsString().trim();
					if (t == boolean.class) f.setBoolean(c, v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on"));
					else if (t == int.class) f.setInt(c, Math.max(0, Integer.parseInt(v)));
					else if (t == double.class) f.setDouble(c, Math.max(0, Double.parseDouble(v)));
					else f.set(c, e.getValue().getAsString());
				} catch (NumberFormatException ex) {
					if (!errors.isEmpty()) errors.append(", ");
					errors.append(labelOf(name)).append(" needs a number");
				} catch (ReflectiveOperationException | UnsupportedOperationException | IllegalStateException ex) {
					if (!errors.isEmpty()) errors.append(", ");
					errors.append("couldn't change ").append(labelOf(name));
				}
			}
		}
		return errors.isEmpty() ? null : errors.toString();
	}

	private static String labelOf(String field) {
		for (FieldInfo f : FIELDS) if (f.name().equals(field)) return f.label();
		return field;
	}
}
