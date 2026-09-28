package com.necora.aicompanion.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.necora.aicompanion.AICompanionMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod configuration, stored as JSON in config/aicompanion.json.
 * Every field can also be changed in-game with /companion config.
 */
public class CompanionConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static CompanionConfig instance = new CompanionConfig();

	// ---- LLM provider ----
	/** groq | ollama | openai (any OpenAI-compatible server: LM Studio, llama.cpp, OpenRouter, vLLM...) */
	public String provider = "groq";
	/** API key. Leave empty to use the GROQ_API_KEY / OPENAI_API_KEY environment variable. Not needed for Ollama. */
	public String apiKey = "";
	/** Override the provider's default URL. Empty = default. */
	public String baseUrl = "";
	/** Model id. Empty = provider default (groq: llama-3.3-70b-versatile, ollama: llama3.1). */
	public String model = "";
	public double temperature = 0.8;
	public int maxResponseTokens = 700;
	public int requestTimeoutSeconds = 60;
	/** Ask the server for strict JSON output. Turned off automatically if the server rejects it. */
	public boolean jsonMode = true;
	/** Context window requested from Ollama (its default is too small for the companion prompt). */
	public int ollamaContextSize = 8192;

	// ---- Conversation & memory ----
	/** How many recent chat messages are sent to the model. Older ones get summarized into long-term memory. */
	public int maxHistoryMessages = 20;
	public int maxFacts = 60;
	public int maxEvents = 20;
	/** Companions hear chat from players within this many blocks (their owner is always heard when nearby). */
	public int listenRadius = 48;
	/** If true, the owner doesn't need to say the companion's name - it answers anything the owner says nearby. */
	public boolean respondWithoutName = true;
	/** 0 = companion chat is seen by everyone on the server (like a real player). Otherwise only within this range. */
	public int chatRange = 0;
	/** Delay chat replies a little, proportional to their length, as if the companion was typing. */
	public boolean typingDelay = true;
	/** The companion occasionally says something on its own when things are quiet. */
	public boolean idleChatter = true;
	public int idleChatterMinutes = 6;
	/** Tell the model about finished tasks, getting hurt, receiving items, etc., so it can react. */
	public boolean reactToEvents = true;
	/** Minimum seconds between two automatic (non-chat) model calls, to save tokens / rate limit. */
	public int minSecondsBetweenEventCalls = 10;
	public boolean greetOnJoin = true;

	// ---- Companion behaviour ----
	/** Default personality. Can be changed per companion with /companion personality. */
	public String defaultPersonality = "easygoing, friendly and a bit witty; loves building and exploring; loyal to their friends; talks like a normal gamer in chat";
	public int maxCompanionsPerPlayer = 3;
	/** Companion leaves the game when its owner logs out and joins again when they log back in. */
	public boolean leaveWithOwner = true;
	/** When following, teleport to the owner if further than this (0 = never, walk only). */
	public int teleportToOwnerDistance = 64;
	/** Follow the owner through portals / dimension changes. */
	public boolean followAcrossDimensions = true;
	/** Keep the companion's chunks loaded while it's working or following, so it keeps going when you walk away. */
	public boolean keepChunksLoaded = true;
	/** In survival, the companion needs the blocks in its inventory to build. */
	public boolean requireMaterialsInSurvival = true;
	/** If a block can't be reached normally, allow placing/breaking it from a bit further away (up to 6.5 blocks). */
	public boolean reachAssist = true;
	/** Companion has a hunger bar and must eat in survival. */
	public boolean hunger = true;
	/** Respawn the companion after it dies. */
	public boolean autoRespawn = true;
	public int respawnDelaySeconds = 8;
	/** Owner can hurt their own companion. */
	public boolean ownerCanHurtCompanion = false;
	/** Hostile mobs attack the companion like they attack players. */
	public boolean mobsTargetCompanion = true;
	/** Safety cap on the size of a single build / dig job. */
	public int maxBuildBlocks = 6000;
	/** Allow the model to switch the companion's game mode (only when the owner is in creative or an operator). */
	public boolean allowModelGameModeChange = true;
	/** Allow companions to break blocks that hold items or data (chests, furnaces, ...). */
	public boolean allowBreakingContainers = false;

	public static CompanionConfig get() {
		return instance;
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("aicompanion.json");
	}

	public static void load() {
		Path path = path();
		if (Files.exists(path)) {
			try {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				CompanionConfig loaded = GSON.fromJson(json, CompanionConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				AICompanionMod.LOGGER.error("Failed to read {}, using defaults", path, e);
			}
		}
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(instance), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AICompanionMod.LOGGER.error("Failed to write config", e);
		}
	}

	// ---- Provider helpers ----

	public String effectiveProvider() {
		String p = provider == null ? "groq" : provider.trim().toLowerCase();
		return switch (p) {
			case "ollama", "openai", "groq" -> p;
			case "lmstudio", "lm-studio", "llamacpp", "llama.cpp", "openrouter", "custom", "local" -> "openai";
			default -> "groq";
		};
	}

	public String effectiveBaseUrl() {
		if (baseUrl != null && !baseUrl.isBlank()) {
			String url = baseUrl.trim();
			while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
			return url;
		}
		String p = provider == null ? "" : provider.trim().toLowerCase();
		return switch (p) {
			case "ollama" -> "http://localhost:11434";
			case "lmstudio", "lm-studio" -> "http://localhost:1234/v1";
			case "llamacpp", "llama.cpp", "local" -> "http://localhost:8080/v1";
			case "openrouter" -> "https://openrouter.ai/api/v1";
			case "openai" -> "https://api.openai.com/v1";
			default -> "https://api.groq.com/openai/v1";
		};
	}

	public String effectiveModel() {
		if (model != null && !model.isBlank()) return model.trim();
		String p = provider == null ? "" : provider.trim().toLowerCase();
		return switch (p) {
			case "ollama" -> "llama3.1";
			case "lmstudio", "lm-studio", "llamacpp", "llama.cpp", "local" -> "local-model";
			case "openrouter" -> "meta-llama/llama-3.3-70b-instruct";
			case "openai" -> "gpt-4o-mini";
			default -> "llama-3.3-70b-versatile";
		};
	}

	public String effectiveApiKey() {
		if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
		String p = effectiveProvider();
		String env = null;
		if (p.equals("groq")) env = System.getenv("GROQ_API_KEY");
		if (env == null || env.isBlank()) env = System.getenv("OPENAI_API_KEY");
		if (env == null || env.isBlank()) env = System.getenv("AICOMPANION_API_KEY");
		return env == null ? "" : env.trim();
	}

	/** True if the selected provider needs a key and none is configured. */
	public boolean missingApiKey() {
		String p = provider == null ? "" : provider.trim().toLowerCase();
		boolean needsKey = p.equals("groq") || p.equals("openai") || p.equals("openrouter") || p.isEmpty();
		return needsKey && effectiveApiKey().isEmpty() && (baseUrl == null || baseUrl.isBlank() || baseUrl.contains("groq.com") || baseUrl.contains("openai.com") || baseUrl.contains("openrouter"));
	}
}
