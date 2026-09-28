package com.necora.aicompanion.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.config.ConfigJson;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.GameModeSetting;
import com.necora.aicompanion.entity.Stance;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.memory.CompanionMemory;
import com.necora.aicompanion.net.GuiServer;
import com.necora.aicompanion.registry.ModEntities;
import com.necora.aicompanion.task.behavior.IdleBehavior;
import com.necora.aicompanion.task.behavior.StayBehavior;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** The server half of the companion screens: requests from the GUI change the right things, and only for the right people. */
public class GuiGameTests implements FabricGameTest {

	private static JsonObject req(String name, Object... keyValues) {
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			Object v = keyValues[i + 1];
			if (v instanceof JsonElement e) o.add((String) keyValues[i], e);
			else if (v instanceof Number n) o.addProperty((String) keyValues[i], n);
			else o.addProperty((String) keyValues[i], String.valueOf(v));
		}
		return o;
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 100)
	public void guiRequestsDriveTheCompanion(TestContext ctx) {
		ServerWorld world = ctx.getWorld();
		CompanionEntity c = ModEntities.COMPANION.create(world);
		if (c == null) throw new IllegalStateException("could not create companion");
		String name = "Gui" + (System.nanoTime() % 100000);
		c.setCompanionName(name);
		c.setGameModeSetting(GameModeSetting.CREATIVE);
		Vec3d pos = Vec3d.ofBottomCenter(ctx.getAbsolutePos(new BlockPos(2, 0, 2)));
		c.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		world.spawnEntity(c);
		c.getTaskManager().setBehavior(new IdleBehavior(c));

		ctx.runAtTick(5, () -> {
			CompanionBrain brain = CompanionManager.brainOf(c);
			ctx.assertTrue(brain != null, "companion has no brain");
			ServerPlayerEntity owner = ctx.createMockCreativeServerPlayerInWorld();
			ServerPlayerEntity stranger = ctx.createMockCreativeServerPlayerInWorld();
			try {
				CompanionMemory m = brain.getMemory();
				m.owner = owner.getUuidAsString();
				m.ownerName = owner.getGameProfile().getName();
				c.setOwnerUuid(owner.getUuid());
				String bn = brain.getName();

				GuiServer.handle(owner, "stance", req(bn, "value", "aggressive").toString());
				ctx.assertTrue(c.getStance() == Stance.AGGRESSIVE, "stance not changed: " + c.getStance());

				GuiServer.handle(owner, "add_fact", req(bn, "value", "the owner loves cake").toString());
				ctx.assertTrue(m.facts.stream().anyMatch(f -> f.text.equals("the owner loves cake")), "fact not added");
				int index = m.facts.size() - 1;
				GuiServer.handle(owner, "remove_fact", req(bn, "index", index, "value", "the owner loves cake").toString());
				ctx.assertTrue(m.facts.stream().noneMatch(f -> f.text.equals("the owner loves cake")), "fact not removed");

				JsonArray actions = new JsonArray();
				JsonObject stay = new JsonObject();
				stay.addProperty("type", "stay");
				actions.add(stay);
				GuiServer.handle(owner, "order", req(bn, "actions", actions, "label", "stay").toString());
				// behaviours are picked up from the queue on the companion's next tick
				boolean staying = c.getTaskManager().getBehavior() instanceof StayBehavior
						|| c.getTaskManager().getQueue().stream().anyMatch(t -> t instanceof StayBehavior);
				ctx.assertTrue(staying, "stay order not applied: " + c.getTaskManager().describe());
				ctx.assertTrue(m.events.stream().anyMatch(e -> e.text.contains("ordered you")), "order not remembered as an event");

				GuiServer.handle(owner, "personality", req(bn, "value", "a grumpy dwarf").toString());
				ctx.assertTrue(m.personality.equals("a grumpy dwarf"), "personality not saved");

				// strangers can't manage, delete, or order someone else's companion
				GuiServer.handle(stranger, "delete", req(bn).toString());
				GuiServer.handle(stranger, "stance", req(bn, "value", "passive").toString());
				ctx.assertTrue(CompanionManager.get().brain(bn) != null, "a stranger deleted the companion");
				ctx.assertTrue(c.getStance() == Stance.AGGRESSIVE, "a stranger changed the stance");
				ctx.assertTrue(GuiServer.list(stranger).getAsJsonArray("companions").isEmpty(), "stranger sees the companion in their list");

				JsonObject detail = GuiServer.detail(owner, brain);
				ctx.assertTrue(detail.get("present").getAsBoolean(), "detail: not present");
				ctx.assertTrue(detail.get("canManage").getAsBoolean(), "detail: owner can't manage");
				ctx.assertTrue(detail.get("stance").getAsString().equals("aggressive"), "detail: wrong stance");
				ctx.assertTrue(detail.has("items") && detail.has("chat") && detail.has("stats"), "detail: missing sections");
				boolean listed = false;
				for (JsonElement e : GuiServer.list(owner).getAsJsonArray("companions")) {
					if (e.getAsJsonObject().get("name").getAsString().equals(bn)) listed = true;
				}
				ctx.assertTrue(listed, "owner's list doesn't contain the companion");

				// the conversation log shown in the Chat tab
				CompanionMemory log = new CompanionMemory();
				log.addHistory("user", "[CHAT] Alex: build me a house\n[EVENT] it started raining");
				log.addHistory("assistant", "{\"say\":\"sure thing\",\"actions\":[{\"type\":\"build\",\"structure\":\"house\"}]}");
				JsonArray lines = GuiServer.chatLog(log, "Bob");
				ctx.assertTrue(lines.size() == 4, "expected 4 chat lines, got " + lines);
				ctx.assertTrue(lines.get(0).getAsJsonObject().get("text").getAsString().equals("Alex: build me a house"), "player line: " + lines.get(0));
				ctx.assertTrue(lines.get(2).getAsJsonObject().get("text").getAsString().equals("<Bob> sure thing"), "companion line: " + lines.get(2));
				ctx.assertTrue(lines.get(3).getAsJsonObject().get("kind").getAsString().equals("action"), "action line: " + lines.get(3));

				GuiServer.handle(owner, "dismiss", req(bn).toString());
				ctx.assertTrue(m.away, "dismiss didn't send the companion away");
			} finally {
				ctx.getWorld().getServer().getPlayerManager().remove(owner);
				ctx.getWorld().getServer().getPlayerManager().remove(stranger);
			}
			ctx.complete();
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void settingsScreenConfigRoundTrip(TestContext ctx) {
		CompanionConfig cfg = new CompanionConfig();
		cfg.apiKey = "gsk_supersecretvalue1234";
		JsonObject described = ConfigJson.describe(cfg);
		ctx.assertTrue(!described.toString().contains("supersecret"), "the API key leaked into the settings JSON");
		ctx.assertTrue(described.get("keySet").getAsBoolean(), "keySet should be true");
		ctx.assertTrue(described.getAsJsonArray("fields").size() == ConfigJson.FIELDS.size(), "every listed setting should be described");

		JsonObject changes = new JsonObject();
		changes.addProperty("provider", "ollama");
		JsonObject fields = new JsonObject();
		fields.addProperty("listenRadius", "30");
		fields.addProperty("hunger", "false");
		fields.addProperty("temperature", "0.5");
		fields.addProperty("maxBuildBlocks", "lots");
		fields.addProperty("apiKey", "should be ignored");
		changes.add("fields", fields);
		String error = ConfigJson.apply(cfg, changes);
		ctx.assertTrue(cfg.provider.equals("ollama") && cfg.effectiveBaseUrl().contains("11434"), "provider not switched: " + cfg.provider);
		ctx.assertTrue(cfg.listenRadius == 30 && !cfg.hunger && cfg.temperature == 0.5, "fields not applied");
		ctx.assertTrue(error != null && error.contains("number"), "bad number not reported: " + error);
		ctx.assertTrue(cfg.apiKey.equals("gsk_supersecretvalue1234"), "fields must not touch the API key");

		JsonObject clear = new JsonObject();
		clear.addProperty("clearKey", true);
		ConfigJson.apply(cfg, clear);
		ctx.assertTrue(cfg.apiKey.isEmpty(), "clearKey didn't clear the key");
		ctx.complete();
	}
}
