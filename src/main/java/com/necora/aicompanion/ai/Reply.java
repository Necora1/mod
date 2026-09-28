package com.necora.aicompanion.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.necora.aicompanion.util.Json;

import java.util.ArrayList;
import java.util.List;

/** The model's answer: what to say, what to do, what to remember. Parsing is deliberately forgiving. */
public record Reply(String say, List<JsonObject> actions, List<String> remember, boolean queue, String compact) {

	public static Reply parse(String raw, String companionName) {
		String text = raw == null ? "" : raw.trim();
		text = text.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)\\s*```\\s*$", "").trim();
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start >= 0 && end > start) {
			try {
				JsonElement el = JsonParser.parseString(text.substring(start, end + 1));
				if (el.isJsonObject()) return fromJson(el.getAsJsonObject(), companionName);
			} catch (Exception ignored) {
				// fall through: treat as plain chat
			}
		}
		String say = cleanSay(text.replaceAll("[{}\\[\\]\"]", " "), companionName);
		JsonObject compact = new JsonObject();
		compact.addProperty("say", say);
		compact.add("actions", new JsonArray());
		return new Reply(say, List.of(), List.of(), false, compact.toString());
	}

	private static Reply fromJson(JsonObject o, String companionName) {
		String say = Json.strOr(o, "", "say", "message", "chat", "reply", "response", "text", "speech");
		say = cleanSay(say, companionName);
		List<JsonObject> actions = new ArrayList<>();
		JsonElement a = Json.get(o, "actions", "action", "commands", "tasks", "do");
		if (a != null) {
			if (a.isJsonArray()) {
				for (JsonElement e : a.getAsJsonArray()) addAction(actions, e);
			} else {
				addAction(actions, a);
			}
		}
		List<String> remember = new ArrayList<>();
		JsonElement r = Json.get(o, "remember", "memory", "memories", "notes");
		if (r != null) {
			if (r.isJsonArray()) {
				for (JsonElement e : r.getAsJsonArray()) {
					if (e.isJsonPrimitive()) remember.add(e.getAsString());
					else if (e.isJsonObject() && e.getAsJsonObject().has("text")) remember.add(e.getAsJsonObject().get("text").getAsString());
				}
			} else if (r.isJsonPrimitive() && !r.getAsString().isBlank()) {
				remember.add(r.getAsString());
			}
		}
		boolean queue = Json.bool(o, false, "queue", "append", "after_current");
		JsonObject compact = new JsonObject();
		compact.addProperty("say", say);
		JsonArray arr = new JsonArray();
		actions.forEach(arr::add);
		compact.add("actions", arr);
		if (!remember.isEmpty()) {
			JsonArray rem = new JsonArray();
			remember.forEach(rem::add);
			compact.add("remember", rem);
		}
		if (queue) compact.addProperty("queue", true);
		return new Reply(say, actions, remember, queue, compact.toString());
	}

	private static void addAction(List<JsonObject> out, JsonElement e) {
		if (e == null || e.isJsonNull()) return;
		if (e.isJsonObject()) {
			JsonObject obj = e.getAsJsonObject();
			// {"build": {...}} style
			if (!obj.has("type") && !obj.has("action") && obj.size() == 1) {
				String key = obj.keySet().iterator().next();
				JsonElement inner = obj.get(key);
				JsonObject copy = inner.isJsonObject() ? inner.getAsJsonObject().deepCopy() : new JsonObject();
				copy.addProperty("type", key);
				out.add(copy);
				return;
			}
			out.add(obj);
		} else if (e.isJsonPrimitive()) {
			JsonObject obj = new JsonObject();
			obj.addProperty("type", e.getAsString());
			out.add(obj);
		}
	}

	private static String cleanSay(String say, String name) {
		if (say == null) return "";
		String s = say.trim();
		String lower = s.toLowerCase();
		String n = name.toLowerCase();
		if (lower.startsWith("<" + n + ">")) s = s.substring(n.length() + 2).trim();
		else if (lower.startsWith(n + ":")) s = s.substring(n.length() + 1).trim();
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
		s = s.replace("*", "").replaceAll("\\s+", " ").trim();
		if (s.equalsIgnoreCase("null") || s.equals("...")) return "";
		return s.length() > 400 ? s.substring(0, 400) : s;
	}
}
