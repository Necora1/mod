package com.necora.aicompanion.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.ai.LlmClient;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.GameModeSetting;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.registry.ModEntities;
import com.necora.aicompanion.task.behavior.IdleBehavior;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end: a chat message goes through the brain, an HTTP request hits a fake LLM server
 * (OpenAI-style and Ollama-style), and the reply turns into chat + a real build in the world.
 */
public class LlmPipelineGameTests implements FabricGameTest {

	private static String modelReply(BlockPos at) {
		return "{\"say\": \"on it\", \"actions\": [{\"type\": \"build\", \"structure\": \"pillar\", \"height\": 2, \"material\": \"stone\", "
				+ "\"at\": {\"x\": " + at.getX() + ", \"y\": " + at.getY() + ", \"z\": " + at.getZ() + "}}], \"remember\": [\"the tester likes stone\"]}";
	}

	private static HttpServer startServer(String path, boolean ollama, AtomicReference<String> lastBody, String reply) throws IOException {
		HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		http.createContext(path, exchange -> {
			lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			JsonObject message = new JsonObject();
			message.addProperty("role", "assistant");
			message.addProperty("content", reply);
			JsonObject response = new JsonObject();
			if (ollama) {
				response.add("message", message);
				response.addProperty("done", true);
			} else {
				JsonObject choice = new JsonObject();
				choice.add("message", message);
				JsonArray choices = new JsonArray();
				choices.add(choice);
				response.add("choices", choices);
			}
			byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		http.start();
		return http;
	}

	private static CompanionEntity spawn(TestContext ctx, String name) {
		ServerWorld world = ctx.getWorld();
		CompanionEntity c = ModEntities.COMPANION.create(world);
		c.setCompanionName(name);
		c.setGameModeSetting(GameModeSetting.CREATIVE);
		Vec3d pos = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(1, 0, 1)));
		c.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		world.spawnEntity(c);
		c.getTaskManager().setBehavior(new IdleBehavior(c));
		return c;
	}

	private void run(TestContext ctx, String provider, String baseSuffix, String path, boolean ollama, String name, String expectInRequest) throws IOException {
		BlockPos at = ctx.getAbsolutePos(new BlockPos(5, 0, 5));
		AtomicReference<String> lastBody = new AtomicReference<>();
		HttpServer http = startServer(path, ollama, lastBody, modelReply(at));
		CompanionConfig cfg = CompanionConfig.get();
		String oldProvider = cfg.provider, oldUrl = cfg.baseUrl, oldKey = cfg.apiKey, oldModel = cfg.model;
		boolean oldTyping = cfg.typingDelay, oldReact = cfg.reactToEvents, oldIdle = cfg.idleChatter;
		cfg.provider = provider;
		cfg.baseUrl = "http://127.0.0.1:" + http.getAddress().getPort() + baseSuffix;
		cfg.apiKey = "test-key";
		cfg.model = "test-model";
		cfg.typingDelay = false;
		cfg.reactToEvents = false;
		cfg.idleChatter = false;
		LlmClient.resetJsonModeState();
		Runnable restore = () -> {
			http.stop(0);
			cfg.provider = oldProvider;
			cfg.baseUrl = oldUrl;
			cfg.apiKey = oldKey;
			cfg.model = oldModel;
			cfg.typingDelay = oldTyping;
			cfg.reactToEvents = oldReact;
			cfg.idleChatter = oldIdle;
		};

		CompanionEntity c = spawn(ctx, name);
		CompanionBrain brain = CompanionManager.brainOf(c);
		ctx.assertTrue(brain != null, "no brain");
		brain.onChat("Tester", null, "build a little stone pillar over there");

		ctx.runAtEveryTick(() -> {
			String problem = null;
			String body = lastBody.get();
			if (body == null) problem = "no request reached the fake LLM server";
			else if (!body.contains(expectInRequest)) problem = "request is missing " + expectInRequest + ": " + LlmClient.truncate(body, 300);
			else if (!body.contains("build a little stone pillar")) problem = "request is missing the chat line";
			else if (!ctx.getWorld().getBlockState(at).isOf(Blocks.STONE) || !ctx.getWorld().getBlockState(at.up()).isOf(Blocks.STONE)) {
				problem = "pillar not built; doing: " + c.getTaskManager().describe();
			} else if (brain.getMemory().facts.stream().noneMatch(f -> f.text.contains("likes stone"))) problem = "fact not remembered";
			else if (brain.getMemory().history.size() < 2) problem = "history not recorded";

			if (problem == null) {
				restore.run();
				ctx.complete();
			} else if (ctx.getTick() >= 395) {
				restore.run();
				ctx.throwGameTestException(problem);
			}
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "llm_openai")
	public void openAiCompatibleRoundTrip(TestContext ctx) throws IOException {
		run(ctx, "openai", "/v1", "/v1/chat/completions", false, "LlmOpenAi", "\"response_format\"");
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400, batchId = "llm_ollama")
	public void ollamaRoundTrip(TestContext ctx) throws IOException {
		run(ctx, "ollama", "", "/api/chat", true, "LlmOllama", "\"num_ctx\"");
	}
}
