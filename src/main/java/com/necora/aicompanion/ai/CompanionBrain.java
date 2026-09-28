package com.necora.aicompanion.ai;

import com.necora.aicompanion.AICompanionMod;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.memory.CompanionMemory;
import com.necora.aicompanion.memory.MemoryStore;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.util.ItemUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The companion's mind: collects what it hears and notices, asks the language model what to
 * say and do, and keeps its memory. One per companion name; survives the body dying or
 * changing dimension.
 */
public class CompanionBrain {
	private final MinecraftServer server;
	private final CompanionMemory memory;

	private final List<String> pending = new ArrayList<>();
	private boolean pendingDirect;
	@Nullable
	private UUID lastSpeaker;
	private boolean requestInFlight;
	private long lastRequestAt;
	private long nextAllowedAt;
	private long lastInteractionAt = System.currentTimeMillis();
	private long lastErrorNoticeAt;
	private int consecutiveRetries;
	private boolean summarizing;
	private boolean dirty;
	private long lastSaveAt;
	private boolean warnedNoKey;

	private long lastHurtEventAt, lastPunchEventAt, lastGiftEventAt, lastOwnerHurtEventAt;
	private int killsSinceReport;

	@Nullable
	private List<BlockPos> lastBuild;
	private String lastBuildLabel = "the last build";

	public CompanionBrain(MinecraftServer server, CompanionMemory memory) {
		this.server = server;
		this.memory = memory;
	}

	public CompanionMemory getMemory() {
		return memory;
	}

	public String getName() {
		return memory.name;
	}

	public void markDirty() {
		dirty = true;
	}

	public long day() {
		return server.getOverworld().getTimeOfDay() / 24000L + 1;
	}

	public String timeStamp() {
		long t = server.getOverworld().getTimeOfDay();
		int tod = (int) (t % 24000L);
		return String.format("day %d %02d:%02d", t / 24000L + 1, (tod / 1000 + 6) % 24, (tod % 1000) * 60 / 1000);
	}

	@Nullable
	public CompanionEntity entity() {
		return CompanionManager.findEntity(server, this);
	}

	public void setLastBuild(List<BlockPos> positions, String label) {
		this.lastBuild = positions;
		this.lastBuildLabel = label;
	}

	@Nullable
	public List<BlockPos> getLastBuild() {
		return lastBuild;
	}

	public String getLastBuildLabel() {
		return lastBuildLabel;
	}

	// ------------------------------------------------------------------
	// Inputs
	// ------------------------------------------------------------------

	public void onChat(ServerPlayerEntity sender, String message) {
		String name = sender.getGameProfile().getName();
		boolean owner = sender.getUuid().equals(memory.ownerUuid());
		pending.add("[CHAT] " + name + (owner ? "" : " (not your owner)") + ": " + message);
		pendingDirect = true;
		lastSpeaker = sender.getUuid();
		lastInteractionAt = System.currentTimeMillis();
		consecutiveRetries = 0;
		memory.stats.messages++;
	}

	/** Something happened that the model may want to react to. */
	public void onEvent(String text, boolean important) {
		if (!CompanionConfig.get().reactToEvents) {
			memory.addEvent(text, timeStamp());
			dirty = true;
			return;
		}
		pending.add("[EVENT] " + text);
		if (important) pendingDirect = true;
		if (pending.size() > 12) pending.remove(0);
	}

	public void onTaskFinished(Task task, boolean success, String message) {
		if (!task.reportResult() && success) return;
		String text = (success ? "Task done: " : "Task failed: ") + (message == null || message.isBlank() ? task.describe() : message);
		memory.addEvent(text, timeStamp());
		dirty = true;
		onEvent(text + (success ? "" : ". Tell your owner and maybe try something else."), !success);
	}

	public void onTaskProblem(String text) {
		onEvent("Problem: " + text, true);
	}

	public void onHurt(DamageSource source, float amount) {
		CompanionEntity c = entity();
		if (c == null) return;
		long now = System.currentTimeMillis();
		boolean low = c.getHealth() <= 8;
		if (now - lastHurtEventAt < (low ? 20000 : 60000)) return;
		lastHurtEventAt = now;
		Entity attacker = source.getAttacker();
		String by = attacker == null ? source.getName() : attacker instanceof PlayerEntity p ? p.getGameProfile().getName() : Registries.ENTITY_TYPE.getId(attacker.getType()).getPath();
		if (low || attacker instanceof PlayerEntity) {
			onEvent("You got hurt by " + by + " (health now " + (int) c.getHealth() + "/20)", low);
		}
	}

	public void onPunchedByOwner() {
		long now = System.currentTimeMillis();
		if (now - lastPunchEventAt < 30000) return;
		lastPunchEventAt = now;
		onEvent(memory.ownerName + " just punched you (it didn't hurt - friendly fire is off)", true);
	}

	public void onItemReceived(PlayerEntity giver, ItemStack stack) {
		long now = System.currentTimeMillis();
		memory.addEvent(giver.getGameProfile().getName() + " gave you " + ItemUtil.describe(stack), timeStamp());
		dirty = true;
		if (now - lastGiftEventAt < 15000) return;
		lastGiftEventAt = now;
		onEvent(giver.getGameProfile().getName() + " tossed you " + ItemUtil.describe(stack), false);
	}

	public void onKilled(LivingEntity victim) {
		memory.stats.kills++;
		dirty = true;
		if (victim instanceof PlayerEntity p) {
			onEvent("You killed " + p.getGameProfile().getName(), true);
		} else if (!(victim instanceof Monster) || ++killsSinceReport >= 5) {
			killsSinceReport = 0;
			memory.addEvent("killed a " + Registries.ENTITY_TYPE.getId(victim.getType()).getPath(), timeStamp());
		}
	}

	public void onOwnerHurt(ServerPlayerEntity owner, DamageSource source) {
		long now = System.currentTimeMillis();
		if (owner.getHealth() > 8 || now - lastOwnerHurtEventAt < 45000) return;
		lastOwnerHurtEventAt = now;
		Entity attacker = source.getAttacker();
		String by = attacker == null ? source.getName() : attacker instanceof PlayerEntity p ? p.getGameProfile().getName() : Registries.ENTITY_TYPE.getId(attacker.getType()).getPath();
		onEvent(owner.getGameProfile().getName() + " is getting hurt by " + by + " and is low on health (" + (int) owner.getHealth() + "/20)!", true);
	}

	public void onOwnerDied(String deathMessage) {
		memory.addEvent(deathMessage, timeStamp());
		dirty = true;
		onEvent("Your owner died: " + deathMessage, true);
	}

	public void onJoined(boolean respawned, @Nullable String deathMessage) {
		lastInteractionAt = System.currentTimeMillis();
		if (respawned) {
			onEvent("You respawned after dying (" + deathMessage + "). Your items " + (deathMessage != null && deathMessage.contains("kept") ? "were kept" : "dropped where you died") + ".", true);
		} else if (CompanionConfig.get().greetOnJoin) {
			onEvent("You just joined the game next to " + memory.ownerName + ". Say hi (short).", true);
		}
	}

	// ------------------------------------------------------------------
	// Tick: decide when to call the model
	// ------------------------------------------------------------------

	public void tick() {
		long now = System.currentTimeMillis();
		CompanionConfig cfg = CompanionConfig.get();
		if (!requestInFlight && !pending.isEmpty() && now >= nextAllowedAt) {
			boolean eventsOnly = !pendingDirect;
			if (!eventsOnly || now - lastRequestAt > cfg.minSecondsBetweenEventCalls * 1000L) {
				send();
			}
		}
		if (cfg.idleChatter && !requestInFlight && pending.isEmpty() && now - lastInteractionAt > cfg.idleChatterMinutes * 60_000L) {
			lastInteractionAt = now;
			CompanionEntity c = entity();
			ServerPlayerEntity owner = c == null ? null : c.getOwner();
			if (c != null && owner != null && owner.getWorld() == c.getWorld() && owner.distanceTo(c) < 32) {
				pending.add("[EVENT] It's been quiet for a while. If you feel like it, make a short casual remark (about what you see, what you're doing, or a question for "
						+ memory.ownerName + "), or suggest something to do. Otherwise say nothing.");
			}
		}
		if (dirty && now - lastSaveAt > 5000) save();
	}

	public void save() {
		dirty = false;
		lastSaveAt = System.currentTimeMillis();
		CompanionEntity c = entity();
		if (c != null) {
			memory.x = c.getX();
			memory.y = c.getY();
			memory.z = c.getZ();
			memory.dimension = c.getWorld().getRegistryKey().getValue().toString();
		}
		MemoryStore.save(server, memory);
	}

	private void send() {
		CompanionEntity c = entity();
		if (c == null) {
			pending.clear();
			pendingDirect = false;
			return;
		}
		CompanionConfig cfg = CompanionConfig.get();
		if (cfg.missingApiKey()) {
			if (!warnedNoKey) {
				warnedNoKey = true;
				c.notifyOwner("I can't think without an AI model! Set a Groq API key with /companion config key <key> (free at console.groq.com), or use a local model: /companion config provider ollama");
			}
			pending.clear();
			pendingDirect = false;
			return;
		}
		ServerPlayerEntity speaker = lastSpeaker == null ? null : server.getPlayerManager().getPlayer(lastSpeaker);
		String userLines = String.join("\n", pending);
		pending.clear();
		pendingDirect = false;

		List<LlmClient.Message> messages = new ArrayList<>();
		messages.add(new LlmClient.Message("system", PromptBuilder.systemPrompt(this, c)));
		int maxHistory = Math.max(2, cfg.maxHistoryMessages);
		List<CompanionMemory.ChatLine> history = memory.history;
		int from = Math.max(0, history.size() - maxHistory);
		for (int i = from; i < history.size(); i++) {
			CompanionMemory.ChatLine line = history.get(i);
			if (i == from && line.role.equals("assistant")) continue;
			messages.add(new LlmClient.Message(line.role, line.content));
		}
		messages.add(new LlmClient.Message("user", PromptBuilder.status(c, speaker) + "\n" + userLines));

		requestInFlight = true;
		lastRequestAt = System.currentTimeMillis();
		final UUID speakerId = lastSpeaker;
		LlmClient.chat(messages, true).whenComplete((text, error) -> server.execute(() -> handleResponse(userLines, text, error, speakerId)));
	}

	private void handleResponse(String userLines, @Nullable String text, @Nullable Throwable error, @Nullable UUID speakerId) {
		requestInFlight = false;
		CompanionEntity c = entity();
		if (error != null) {
			Throwable cause = error;
			while (cause.getCause() != null && !(cause instanceof LlmClient.LlmException)) cause = cause.getCause();
			long now = System.currentTimeMillis();
			String msg = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
			AICompanionMod.LOGGER.warn("[{}] LLM request failed: {}", memory.name, msg);
			if (cause instanceof LlmClient.LlmException ex && ex.isRateLimit()) {
				long wait = ex.retryAfterMs > 0 ? ex.retryAfterMs : 8000;
				nextAllowedAt = now + wait;
				if (consecutiveRetries++ < 2) {
					pending.add(0, userLines);
					pendingDirect = true;
				}
				if (c != null && now - lastErrorNoticeAt > 60000) {
					lastErrorNoticeAt = now;
					c.notifyOwner("(rate limited by the AI provider, retrying in " + Math.max(1, wait / 1000) + "s)");
				}
			} else {
				nextAllowedAt = now + 3000;
				if (c != null && now - lastErrorNoticeAt > 30000) {
					lastErrorNoticeAt = now;
					c.notifyOwner("AI request failed: " + LlmClient.truncate(msg, 180));
				}
			}
			return;
		}
		if (c == null) return;
		Reply reply = Reply.parse(text, memory.name);
		memory.addHistory("user", userLines);
		memory.addHistory("assistant", reply.compact());
		dirty = true;

		if (!reply.say().isEmpty()) c.queueChat(reply.say(), 0);
		for (String fact : reply.remember()) memory.addFact(fact, day());

		if (!reply.actions().isEmpty()) {
			ServerPlayerEntity speaker = speakerId == null ? null : server.getPlayerManager().getPlayer(speakerId);
			boolean trusted = speaker == null || c.canTakeOrdersFrom(speaker);
			ActionDispatcher.Result result = ActionDispatcher.dispatch(new ActionContext(c, this, speaker, trusted), reply.actions(), reply.queue());
			if (!result.errors().isEmpty()) {
				String errs = String.join("; ", result.errors());
				AICompanionMod.LOGGER.info("[{}] action problems: {}", memory.name, errs);
				memory.addEvent("action problem: " + LlmClient.truncate(errs, 200), timeStamp());
				// Let the model correct itself once if nothing at all could be done.
				if (result.accepted() == 0 && consecutiveRetries++ < 1) {
					pending.add("[SYSTEM] Your last actions could not be done: " + errs + ". Fix them (check the action list) or tell the player.");
					pendingDirect = true;
				}
			} else {
				consecutiveRetries = 0;
			}
		}
		lastInteractionAt = System.currentTimeMillis();
		maybeSummarize();
	}

	// ------------------------------------------------------------------
	// Long-term memory: summarize old chat
	// ------------------------------------------------------------------

	private void maybeSummarize() {
		int max = Math.max(4, CompanionConfig.get().maxHistoryMessages);
		if (summarizing || memory.history.size() <= max + 6) return;
		int cut = memory.history.size() - max;
		List<CompanionMemory.ChatLine> old = new ArrayList<>(memory.history.subList(0, cut));
		memory.history.subList(0, cut).clear();
		summarizing = true;
		StringBuilder convo = new StringBuilder();
		for (CompanionMemory.ChatLine l : old) {
			String content = l.content;
			if (l.role.equals("assistant")) {
				Reply r = Reply.parse(content, memory.name);
				content = memory.name + ": " + r.say() + (r.actions().isEmpty() ? "" : " [did: " + actionNames(r) + "]");
			}
			convo.append(content).append('\n');
		}
		List<LlmClient.Message> msgs = new ArrayList<>();
		msgs.add(new LlmClient.Message("system", "You maintain the long-term memory of " + memory.name
				+ ", a Minecraft player who plays with " + memory.ownerName + ". Merge the existing summary with the new conversation into ONE updated summary "
				+ "of at most 150 words, written from " + memory.name + "'s point of view (\"I\"). Keep what matters later: names, relationships, promises, preferences, "
				+ "places, what was built or found, important events. Drop small talk. Reply with the summary text only."));
		msgs.add(new LlmClient.Message("user", "Existing summary: " + (memory.summary.isBlank() ? "(none)" : memory.summary) + "\n\nNew conversation:\n" + convo));
		LlmClient.chat(msgs, false).whenComplete((text, error) -> server.execute(() -> {
			summarizing = false;
			if (error == null && text != null && !text.isBlank()) {
				String s = text.trim();
				if (s.length() > 1500) s = s.substring(0, 1500);
				memory.summary = s;
			} else {
				// keep the gist so nothing is lost completely
				memory.history.addAll(0, old.subList(Math.max(0, old.size() - 4), old.size()));
			}
			dirty = true;
		}));
	}

	private static String actionNames(Reply r) {
		List<String> names = new ArrayList<>();
		for (var a : r.actions()) {
			String t = a.has("type") ? a.get("type").getAsString() : a.has("action") ? a.get("action").getAsString() : "?";
			names.add(t);
		}
		return String.join(", ", names);
	}

	public boolean isThinking() {
		return requestInFlight;
	}
}
