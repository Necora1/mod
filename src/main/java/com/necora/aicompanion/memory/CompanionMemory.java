package com.necora.aicompanion.memory;

import com.necora.aicompanion.config.CompanionConfig;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a companion remembers. Saved per world in <world>/aicompanion/<name>.json, so it
 * survives restarts, deaths and dismissals.
 */
public class CompanionMemory {
	public String name = "Companion";
	public String owner = "";
	public String ownerName = "";
	public String entityUuid = "";
	/** True while the companion is not in the world (dismissed, owner offline, waiting to respawn). */
	public boolean away = false;
	/** Set when the companion left because its owner logged out; it comes back when they return. */
	public boolean autoRejoin = false;
	public String dimension = "minecraft:overworld";
	public double x, y, z;

	public String skinName = "";
	public String skinValue = "";
	public String skinSignature = "";

	public String personality = "";
	public String gameMode = "auto";
	public String stance = "defensive";

	public List<Fact> facts = new ArrayList<>();
	public Map<String, Place> places = new LinkedHashMap<>();
	public String summary = "";
	public List<ChatLine> history = new ArrayList<>();
	public List<EventEntry> events = new ArrayList<>();
	public List<String> trusted = new ArrayList<>();
	/** Inventory + equipment (SNBT) kept while the companion is away. */
	public String savedEntityData = "";
	public Stats stats = new Stats();

	public static class Fact {
		public String text;
		public long day;

		public Fact() {
		}

		public Fact(String text, long day) {
			this.text = text;
			this.day = day;
		}
	}

	public static class Place {
		public int x, y, z;
		public String dim = "minecraft:overworld";

		public Place() {
		}

		public Place(int x, int y, int z, String dim) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.dim = dim;
		}
	}

	public static class ChatLine {
		public String role;
		public String content;

		public ChatLine() {
		}

		public ChatLine(String role, String content) {
			this.role = role;
			this.content = content;
		}
	}

	public static class EventEntry {
		public String text;
		public String when;

		public EventEntry() {
		}

		public EventEntry(String text, String when) {
			this.text = text;
			this.when = when;
		}
	}

	public static class Stats {
		public int kills;
		public int deaths;
		public int blocksPlaced;
		public int blocksBroken;
		public int messages;
	}

	public UUID ownerUuid() {
		try {
			return owner == null || owner.isEmpty() ? null : UUID.fromString(owner);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public UUID entityUuid() {
		try {
			return entityUuid == null || entityUuid.isEmpty() ? null : UUID.fromString(entityUuid);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public boolean addFact(String text, long day) {
		if (text == null) return false;
		String t = text.trim();
		if (t.isEmpty() || t.length() > 300) return false;
		String norm = normalize(t);
		for (Fact f : facts) {
			if (normalize(f.text).equals(norm)) return false;
		}
		facts.add(new Fact(t, day));
		int max = Math.max(5, CompanionConfig.get().maxFacts);
		while (facts.size() > max) facts.remove(0);
		return true;
	}

	public int forget(String containing) {
		if (containing == null || containing.isBlank()) return 0;
		String needle = normalize(containing);
		int removed = 0;
		for (Iterator<Fact> it = facts.iterator(); it.hasNext(); ) {
			if (normalize(it.next().text).contains(needle)) {
				it.remove();
				removed++;
			}
		}
		for (Iterator<String> it = places.keySet().iterator(); it.hasNext(); ) {
			if (normalize(it.next()).contains(needle)) {
				it.remove();
				removed++;
			}
		}
		return removed;
	}

	public void addEvent(String text, String when) {
		events.add(new EventEntry(text, when));
		int max = Math.max(3, CompanionConfig.get().maxEvents);
		while (events.size() > max) events.remove(0);
	}

	public void addHistory(String role, String content) {
		history.add(new ChatLine(role, content));
	}

	public Place findPlace(String name) {
		if (name == null) return null;
		String key = normalize(name);
		for (Map.Entry<String, Place> e : places.entrySet()) {
			if (normalize(e.getKey()).equals(key)) return e.getValue();
		}
		// "my house" -> "house", "the base" -> "base"
		key = key.replaceFirst("^(my|our|the|your) ", "");
		for (Map.Entry<String, Place> e : places.entrySet()) {
			String k = normalize(e.getKey()).replaceFirst("^(my|our|the|your) ", "");
			if (k.equals(key)) return e.getValue();
		}
		return null;
	}

	public boolean isTrusted(String playerName) {
		for (String s : trusted) {
			if (s.equalsIgnoreCase(playerName)) return true;
		}
		return false;
	}

	public static String normalize(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 _]", "").replaceAll("\\s+", " ").trim();
	}
}
