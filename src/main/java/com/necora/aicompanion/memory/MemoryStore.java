package com.necora.aicompanion.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.necora.aicompanion.AICompanionMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads and writes companion memories in the world save folder. */
public final class MemoryStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private MemoryStore() {
	}

	public static Path dir(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("aicompanion");
	}

	public static String fileName(String companionName) {
		String safe = companionName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
		if (safe.isEmpty()) safe = "companion";
		return safe + ".json";
	}

	public static List<CompanionMemory> loadAll(MinecraftServer server) {
		List<CompanionMemory> out = new ArrayList<>();
		Path dir = dir(server);
		if (!Files.isDirectory(dir)) return out;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
			for (Path p : stream) {
				try {
					CompanionMemory m = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), CompanionMemory.class);
					if (m != null && m.name != null && !m.name.isBlank()) {
						fixNulls(m);
						out.add(m);
					}
				} catch (Exception e) {
					AICompanionMod.LOGGER.error("Could not read companion memory {}", p, e);
				}
			}
		} catch (IOException e) {
			AICompanionMod.LOGGER.error("Could not list companion memories", e);
		}
		return out;
	}

	public static void save(MinecraftServer server, CompanionMemory memory) {
		Path dir = dir(server);
		try {
			Files.createDirectories(dir);
			Path file = dir.resolve(fileName(memory.name));
			Path tmp = dir.resolve(fileName(memory.name) + ".tmp");
			Files.writeString(tmp, GSON.toJson(memory), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			try {
				Files.writeString(dir.resolve(fileName(memory.name)), GSON.toJson(memory), StandardCharsets.UTF_8);
			} catch (IOException e2) {
				AICompanionMod.LOGGER.error("Could not save companion memory for {}", memory.name, e2);
			}
		}
	}

	public static void delete(MinecraftServer server, String name) {
		try {
			Files.deleteIfExists(dir(server).resolve(fileName(name)));
		} catch (IOException e) {
			AICompanionMod.LOGGER.error("Could not delete companion memory for {}", name, e);
		}
	}

	private static void fixNulls(CompanionMemory m) {
		if (m.facts == null) m.facts = new ArrayList<>();
		if (m.places == null) m.places = new java.util.LinkedHashMap<>();
		if (m.history == null) m.history = new ArrayList<>();
		if (m.events == null) m.events = new ArrayList<>();
		if (m.trusted == null) m.trusted = new ArrayList<>();
		if (m.stats == null) m.stats = new CompanionMemory.Stats();
		if (m.summary == null) m.summary = "";
		if (m.personality == null) m.personality = "";
		if (m.gameMode == null) m.gameMode = "auto";
		if (m.stance == null) m.stance = "defensive";
		if (m.skinName == null) m.skinName = "";
		if (m.skinValue == null) m.skinValue = "";
		if (m.skinSignature == null) m.skinSignature = "";
		if (m.savedEntityData == null) m.savedEntityData = "";
		if (m.owner == null) m.owner = "";
		if (m.ownerName == null) m.ownerName = "";
		if (m.entityUuid == null) m.entityUuid = "";
		if (m.dimension == null) m.dimension = "minecraft:overworld";
	}
}
