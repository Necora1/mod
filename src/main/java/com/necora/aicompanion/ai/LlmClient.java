package com.necora.aicompanion.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.necora.aicompanion.AICompanionMod;
import com.necora.aicompanion.config.CompanionConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Minimal chat client for Groq / any OpenAI-compatible server, and Ollama's native API.
 * No external dependencies: uses java.net.http and the Gson bundled with Minecraft.
 */
public final class LlmClient {
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.version(HttpClient.Version.HTTP_1_1)
			.build();

	/** Set when the server told us it doesn't support response_format / format=json. */
	private static volatile boolean jsonModeRejected = false;

	private LlmClient() {
	}

	public record Message(String role, String content) {
	}

	public static class LlmException extends RuntimeException {
		public final int status;
		public final long retryAfterMs;

		public LlmException(String message, int status, long retryAfterMs) {
			super(message);
			this.status = status;
			this.retryAfterMs = retryAfterMs;
		}

		public boolean isRateLimit() {
			return status == 429;
		}
	}

	public static void resetJsonModeState() {
		jsonModeRejected = false;
	}

	/**
	 * Sends a chat completion request. The future completes on an HTTP thread - hop back to the
	 * server thread before touching the world.
	 */
	public static CompletableFuture<String> chat(List<Message> messages, boolean wantJson) {
		CompanionConfig cfg = CompanionConfig.get();
		boolean json = wantJson && cfg.jsonMode && !jsonModeRejected;
		return send(messages, json).handle((result, error) -> {
			if (error == null) return CompletableFuture.completedFuture(result);
			Throwable cause = unwrap(error);
			if (json && cause instanceof LlmException ex && ex.status == 400) {
				String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
				if (msg.contains("response_format") || msg.contains("json") || msg.contains("format")) {
					if (!msg.contains("failed_generation") && !msg.contains("json_validate_failed")) {
						AICompanionMod.LOGGER.warn("LLM server rejected JSON mode, retrying without it: {}", ex.getMessage());
						jsonModeRejected = true;
					}
					return send(messages, false);
				}
			}
			return CompletableFuture.<String>failedFuture(cause);
		}).thenCompose(f -> f);
	}

	private static Throwable unwrap(Throwable t) {
		while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
		return t;
	}

	private static CompletableFuture<String> send(List<Message> messages, boolean json) {
		CompanionConfig cfg = CompanionConfig.get();
		boolean ollama = cfg.effectiveProvider().equals("ollama");
		String base = cfg.effectiveBaseUrl();
		String url;
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.effectiveModel());
		JsonArray msgs = new JsonArray();
		for (Message m : messages) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			msgs.add(o);
		}
		body.add("messages", msgs);

		if (ollama) {
			// A base URL ending in /v1 means the user wants Ollama's OpenAI-compatible endpoint.
			if (base.endsWith("/v1")) {
				url = base + "/chat/completions";
				fillOpenAi(body, cfg, json);
				ollama = false;
			} else {
				url = base + "/api/chat";
				body.addProperty("stream", false);
				if (json) body.addProperty("format", "json");
				body.addProperty("keep_alive", "30m");
				JsonObject options = new JsonObject();
				options.addProperty("temperature", cfg.temperature);
				options.addProperty("num_ctx", cfg.ollamaContextSize);
				options.addProperty("num_predict", cfg.maxResponseTokens);
				body.add("options", options);
			}
		} else {
			url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
			fillOpenAi(body, cfg, json);
		}

		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(Math.max(5, cfg.requestTimeoutSeconds)))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.header("User-Agent", "AICompanion-Minecraft-Mod")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
		String key = cfg.effectiveApiKey();
		if (!key.isEmpty()) req.header("Authorization", "Bearer " + key);
		if (base.contains("openrouter.ai")) {
			req.header("HTTP-Referer", "https://github.com/Necora1/mod");
			req.header("X-Title", "AI Companion");
		}

		final boolean nativeOllama = ollama;
		return HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
			int status = resp.statusCode();
			String text = resp.body() == null ? "" : resp.body();
			if (status < 200 || status >= 300) {
				long retry = resp.headers().firstValue("retry-after").map(LlmClient::parseRetryAfter).orElse(0L);
				throw new LlmException(extractError(text, status), status, retry);
			}
			JsonObject root = JsonParser.parseString(text).getAsJsonObject();
			String content;
			if (nativeOllama) {
				content = root.getAsJsonObject("message").get("content").getAsString();
			} else {
				JsonArray choices = root.getAsJsonArray("choices");
				if (choices == null || choices.isEmpty()) throw new LlmException("empty response: " + truncate(text, 200), status, 0);
				JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
				JsonElement c = message.get("content");
				content = c == null || c.isJsonNull() ? "" : c.getAsString();
			}
			return stripThinking(content);
		});
	}

	private static void fillOpenAi(JsonObject body, CompanionConfig cfg, boolean json) {
		body.addProperty("temperature", cfg.temperature);
		body.addProperty("max_tokens", cfg.maxResponseTokens);
		if (json) {
			JsonObject rf = new JsonObject();
			rf.addProperty("type", "json_object");
			body.add("response_format", rf);
		}
	}

	private static long parseRetryAfter(String v) {
		try {
			return (long) (Double.parseDouble(v.trim()) * 1000);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String extractError(String body, int status) {
		try {
			JsonElement el = JsonParser.parseString(body);
			if (el.isJsonObject()) {
				JsonObject o = el.getAsJsonObject();
				if (o.has("error")) {
					JsonElement err = o.get("error");
					if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
						String m = err.getAsJsonObject().get("message").getAsString();
						if (err.getAsJsonObject().has("code") && !err.getAsJsonObject().get("code").isJsonNull()) {
							m = m + " (" + err.getAsJsonObject().get("code").getAsString() + ")";
						}
						return "HTTP " + status + ": " + m;
					}
					if (err.isJsonPrimitive()) return "HTTP " + status + ": " + err.getAsString();
				}
			}
		} catch (Exception ignored) {
		}
		return "HTTP " + status + ": " + truncate(body, 300);
	}

	/** Reasoning models (qwen3, deepseek-r1, ...) may prefix their answer with a think block. */
	static String stripThinking(String s) {
		if (s == null) return "";
		String out = s;
		int end = out.lastIndexOf("</think>");
		if (end >= 0) out = out.substring(end + "</think>".length());
		return out.trim();
	}

	public static String truncate(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max) + "...";
	}
}
