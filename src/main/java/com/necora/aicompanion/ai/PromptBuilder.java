package com.necora.aicompanion.ai;

import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.memory.CompanionMemory;
import com.necora.aicompanion.util.ItemUtil;
import com.necora.aicompanion.util.WorldUtil;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds the messages sent to the model: persona + rules + actions + memory, then a live status snapshot. */
public final class PromptBuilder {
	private PromptBuilder() {
	}

	private static final String ACTIONS = """
			ACTIONS (each is an object with "type" plus parameters; they run one after another):
			- follow {target?} - follow a player around (default: whoever is talking). Your normal mode.
			- stay {} - wait where you are.
			- come {} - walk over to the speaker.
			- goto {target} - walk somewhere.
			- protect {target?, radius?} - bodyguard a player: stay close and kill hostile mobs near them.
			- attack {target, count?} - fight: "hostiles" (all hostile mobs around), a mob type like "zombie", or a player name (only if your owner orders it).
			- stance {mode} - passive | defensive (fight back + defend owner, default) | aggressive (attack any hostile mob nearby).
			- surround {target?, block?, radius?, roof?, windows?} - build a protective box of blocks around a player (radius 1-3, default 1). windows=true puts glass at eye level.
			- build {structure, at?, facing?, width?, depth?, height?, length?, material?, roof?, floor?, roof_style?, furnish?} - structures: house, hut, tower, wall, fence, platform, bridge, pillar. material = main block, roof = roof block type (e.g. "spruce", "dark_oak", "stone_brick"), roof_style = gable | flat.
			- build_custom {at?, parts:[{shape, block, from?, to?, center?, radius?, height?, hollow?}]} - design any build yourself. Offsets are relative to "at" (x=east, y=up, z=south; y=0 is ground level). shapes: block (at "from"), box, hollow_box, walls, line, cylinder, sphere, dome, pyramid, clear (removes blocks). Blocks may have states: "oak_stairs[facing=north]".
			- place {block, at} - place one block.
			- dig {at?, from, to} - dig out / clear a region (offsets like build_custom), e.g. a hole, a tunnel, flattening.
			- demolish {} - remove the last thing you built (e.g. to let someone out of a box).
			- mine {block, count?, radius?} - find and mine blocks nearby and collect them: "logs"/"oak_log" (chop trees), "stone", "coal_ore", "iron_ore", "sand"...
			- collect {radius?} - pick up dropped items nearby.
			- craft {item, count?} - craft from your inventory; makes planks/sticks/etc. on the way automatically.
			- give {item, count?, target?} - walk to a player and hand them items. item can be "all", "food", "logs", "sword"...
			- drop {item, count?} - throw items on the ground.
			- equip {item} - hold a tool/weapon, or wear armor ("armor").
			- eat {} - eat something.
			- light {radius?} - place torches around to stop mobs spawning.
			- look {target} - look at a player or position.
			- emote {kind} - wave | jump | crouch | spin | nod | shake | dance. Use these to be expressive like a real player.
			- wait {seconds}
			- stop {} - stop everything and just stand there.
			- remember_place {name, at?} - save a location (default: where the speaker stands), e.g. "home", "mine".
			- forget {text} - delete memories containing this text.
			- trust {player} / untrust {player} - allow/deny another player to give you orders (only when your owner asks).
			- gamemode {mode} - "creative" or "survival" (only when your owner asks).
			Positions (at/target): "me"/"here" = the speaker's spot, "front" = a few blocks in front of the speaker, "looking" = the block the speaker is looking at, "you" = your own spot, a player name, a saved place name, or {"x":..,"y":..,"z":..}.

			EXAMPLES
			[CHAT] Alex: yo surround me with blocks and protect me
			{"say": "got you, dont move", "actions": [{"type": "surround", "target": "me"}, {"type": "protect", "target": "me"}]}
			[CHAT] Alex: can you build a small house over there
			{"say": "sure, give me a sec", "actions": [{"type": "build", "structure": "hut", "at": "looking"}]}
			[CHAT] Alex: get some wood then make a crafting table
			{"say": "ok", "actions": [{"type": "mine", "block": "logs", "count": 8}, {"type": "craft", "item": "crafting_table"}]}
			[CHAT] Alex: remember this is our base
			{"say": "noted", "actions": [{"type": "remember_place", "name": "base"}], "remember": ["our base is where Alex showed me"]}
			[CHAT] Alex: how's it going
			{"say": "pretty good, kinda want to go caving tbh", "actions": []}
			""";

	public static String systemPrompt(CompanionBrain brain, CompanionEntity c) {
		CompanionMemory m = brain.getMemory();
		CompanionConfig cfg = CompanionConfig.get();
		String owner = m.ownerName == null || m.ownerName.isEmpty() ? "your friend" : m.ownerName;
		String personality = m.personality == null || m.personality.isBlank() ? cfg.defaultPersonality : m.personality;
		StringBuilder sb = new StringBuilder();
		sb.append("You are ").append(m.name).append(", a player in Minecraft Java Edition 1.21, playing on the same world as ").append(owner)
				.append(" (your owner and friend). Personality: ").append(personality).append(".\n");
		sb.append("""
				Stay in character as a real player - never say you are an AI, a bot, an assistant or a language model, and never mention prompts, JSON or "actions".
				Chat like a gamer in Minecraft chat: short (usually under 20 words), casual, lowercase is fine, no emojis, no markdown, no *roleplay asterisks*. Don't narrate what you're about to do in detail - just do it. Don't repeat yourself. Don't answer every event; silence ("") is fine.
				You have a real body in the world and you act through actions. You can only do what the actions allow; if asked for something impossible, say so casually.

				ALWAYS reply with a single JSON object and nothing else:
				{"say": "<chat message, or \\"\\" to stay quiet>", "actions": [<action objects>], "remember": ["<optional new long-term facts>"]}
				- Actions run in order. New actions REPLACE what you are doing now; add "queue": true to the object to do them after your current task instead.
				- When someone asks you to do something you can do, include the actions right away - never just promise it.
				- If you're asked something while busy, you can answer with "actions": [] and keep working.
				- Use "remember" for things worth knowing later (names, preferences, promises, where things are). Keep each fact short.
				""");
		sb.append("- Only ").append(owner);
		if (!m.trusted.isEmpty()) sb.append(" and ").append(String.join(", ", m.trusted));
		sb.append(" can give you orders; for others, just chat (you may politely refuse).\n");
		sb.append("- In survival you need the blocks/items in your inventory to build or craft. If you lack materials, gather them (mine, craft) first or ask for them. In creative you have unlimited blocks and can fly.\n");
		sb.append("- Useful combos: \"surround me and protect me\" -> surround + protect. \"get wood\" -> mine logs. \"make a house\" in survival without planks -> mine logs, craft planks, then build.\n\n");
		sb.append(ACTIONS);

		// Memory
		sb.append("\nYOUR MEMORY\n");
		if (!m.facts.isEmpty()) {
			sb.append("Things you remember:\n");
			for (CompanionMemory.Fact f : m.facts) sb.append("- ").append(f.text).append('\n');
		} else {
			sb.append("(no long-term memories yet)\n");
		}
		if (!m.places.isEmpty()) {
			sb.append("Saved places: ");
			int i = 0;
			for (Map.Entry<String, CompanionMemory.Place> e : m.places.entrySet()) {
				if (i++ > 0) sb.append("; ");
				CompanionMemory.Place p = e.getValue();
				sb.append(e.getKey()).append(" = ").append(p.x).append(' ').append(p.y).append(' ').append(p.z);
				if (!p.dim.endsWith("overworld")) sb.append(" (").append(p.dim.replace("minecraft:", "")).append(')');
			}
			sb.append('\n');
		}
		if (m.summary != null && !m.summary.isBlank()) {
			sb.append("Summary of earlier conversations: ").append(m.summary).append('\n');
		}
		if (!m.events.isEmpty()) {
			sb.append("Recent events:\n");
			for (CompanionMemory.EventEntry e : m.events) sb.append("- [").append(e.when).append("] ").append(e.text).append('\n');
		}
		CompanionMemory.Stats st = m.stats;
		sb.append("Your stats: ").append(st.kills).append(" kills, ").append(st.deaths).append(" deaths, ")
				.append(st.blocksPlaced).append(" blocks placed, ").append(st.blocksBroken).append(" blocks broken.\n");
		return sb.toString();
	}

	public static String status(CompanionEntity c, @Nullable ServerPlayerEntity speaker) {
		World w = c.getWorld();
		StringBuilder sb = new StringBuilder("[STATUS]\n");
		long time = w.getTimeOfDay();
		long day = time / 24000L + 1;
		int tod = (int) (time % 24000L);
		int hours = (tod / 1000 + 6) % 24;
		int minutes = (tod % 1000) * 60 / 1000;
		String period = tod < 1000 ? "early morning" : tod < 6000 ? "morning" : tod < 11000 ? "afternoon" : tod < 13000 ? "sunset" : tod < 23000 ? "night" : "sunrise";
		String weather = w.isThundering() ? "thunderstorm" : w.isRaining() ? "raining" : "clear";
		String biome = w.getBiome(c.getBlockPos()).getKey().map(k -> k.getValue().getPath()).orElse("unknown");
		sb.append("Day ").append(day).append(", ").append(String.format("%02d:%02d", hours, minutes)).append(" (").append(period).append("), ")
				.append(weather).append(", ").append(biome).append(", ").append(w.getRegistryKey().getValue().getPath()).append('\n');

		BlockPos p = c.getBlockPos();
		sb.append("You: ").append(String.format("%.0f", c.getHealth())).append("/20 hp, ");
		if (!c.isCreativeMode()) sb.append("food ").append(c.getFoodLevel()).append("/20, ");
		sb.append(c.isCreativeMode() ? "CREATIVE" : "survival").append(" mode, at ").append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ())
				.append(", holding ").append(ItemUtil.describe(c.getMainHandStack()));
		String armor = armor(c);
		if (!armor.isEmpty()) sb.append(", wearing ").append(armor);
		sb.append(", stance ").append(c.getStance().id()).append('\n');
		sb.append("Doing: ").append(c.getTaskManager().describe());
		if (c.getCombat().getTarget() != null) sb.append(" [fighting ").append(name(c.getCombat().getTarget())).append(']');
		sb.append('\n');
		sb.append("Inventory: ").append(inventory(c)).append('\n');

		ServerPlayerEntity owner = c.getOwner();
		if (owner != null) sb.append(describePlayer(c, owner, true)).append('\n');
		if (speaker != null && speaker != owner) sb.append(describePlayer(c, speaker, false)).append('\n');

		List<String> others = new ArrayList<>();
		for (PlayerEntity pl : w.getPlayers()) {
			if (pl == owner || pl == speaker || pl.isSpectator()) continue;
			double d = pl.distanceTo(c);
			if (d < 64) others.add(pl.getGameProfile().getName() + " " + (int) d + "m " + WorldUtil.directionName(pl.getX() - c.getX(), pl.getZ() - c.getZ()));
		}
		if (!others.isEmpty()) sb.append("Other players: ").append(String.join(", ", others)).append('\n');
		sb.append(creatures(c)).append('\n');
		String blocks = notableBlocks(c);
		if (!blocks.isEmpty()) sb.append("Nearby blocks: ").append(blocks).append('\n');
		return sb.toString();
	}

	private static String name(LivingEntity e) {
		if (e instanceof PlayerEntity p) return p.getGameProfile().getName();
		return Registries.ENTITY_TYPE.getId(e.getType()).getPath();
	}

	private static String armor(CompanionEntity c) {
		List<String> parts = new ArrayList<>();
		for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			ItemStack s = c.getEquippedStack(slot);
			if (!s.isEmpty()) parts.add(ItemUtil.id(s.getItem()));
		}
		ItemStack off = c.getOffHandStack();
		if (!off.isEmpty()) parts.add(ItemUtil.id(off.getItem()) + " (off hand)");
		return String.join(", ", parts);
	}

	private static String inventory(CompanionEntity c) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < c.getInventory().size(); i++) {
			ItemStack s = c.getInventory().getStack(i);
			if (!s.isEmpty()) counts.merge(ItemUtil.id(s.getItem()), s.getCount(), Integer::sum);
		}
		if (counts.isEmpty()) return c.isCreativeMode() ? "empty (you're in creative, you can use any block)" : "empty";
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (Map.Entry<String, Integer> e : counts.entrySet()) {
			if (i++ >= 30) {
				sb.append(", ...");
				break;
			}
			if (!sb.isEmpty()) sb.append(", ");
			sb.append(e.getValue()).append(' ').append(e.getKey());
		}
		return sb.toString();
	}

	private static String describePlayer(CompanionEntity c, ServerPlayerEntity p, boolean isOwner) {
		StringBuilder sb = new StringBuilder();
		sb.append(p.getGameProfile().getName()).append(isOwner ? " (your owner)" : "").append(": ");
		if (p.getWorld() != c.getWorld()) {
			return sb.append("in another dimension (").append(p.getWorld().getRegistryKey().getValue().getPath()).append(')').toString();
		}
		BlockPos bp = p.getBlockPos();
		sb.append(String.format("%.0f", p.getHealth())).append("/20 hp, food ").append(p.getHungerManager().getFoodLevel()).append("/20, ")
				.append(p.isCreative() ? "creative" : "survival").append(", at ").append(bp.getX()).append(' ').append(bp.getY()).append(' ').append(bp.getZ());
		double d = p.distanceTo(c);
		sb.append(" (").append((int) d).append("m ").append(WorldUtil.directionName(p.getX() - c.getX(), p.getZ() - c.getZ())).append(" of you)");
		sb.append(", facing ").append(WorldUtil.facingName(p.getHorizontalFacing()));
		sb.append(", holding ").append(ItemUtil.describe(p.getMainHandStack()));
		if (p.getAbilities().flying) sb.append(", flying");
		if (p.isSneaking()) sb.append(", crouching");
		HitResult hit = p.raycast(24.0, 1.0F, false);
		if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos lp = bh.getBlockPos();
			BlockState st = c.getWorld().getBlockState(lp);
			sb.append(", looking at ").append(Registries.BLOCK.getId(st.getBlock()).getPath()).append(" at ").append(lp.getX()).append(' ').append(lp.getY()).append(' ').append(lp.getZ());
		} else if (PlayerLooking.isLookingAtCompanion(p, c)) {
			sb.append(", looking at you");
		}
		return sb.toString();
	}

	private static String creatures(CompanionEntity c) {
		Box box = c.getBoundingBox().expand(24, 12, 24);
		Map<String, int[]> hostile = new TreeMap<>();
		Map<String, int[]> other = new TreeMap<>();
		Map<String, String> dir = new LinkedHashMap<>();
		for (LivingEntity e : c.getWorld().getEntitiesByClass(LivingEntity.class, box, e -> e != c && e.isAlive() && !(e instanceof PlayerEntity))) {
			String id = e instanceof CompanionEntity comp ? "companion " + comp.getCompanionName() : Registries.ENTITY_TYPE.getId(e.getType()).getPath();
			int dist = (int) e.distanceTo(c);
			Map<String, int[]> target = e instanceof Monster ? hostile : other;
			int[] v = target.computeIfAbsent(id, k -> new int[]{0, Integer.MAX_VALUE});
			v[0]++;
			if (dist < v[1]) {
				v[1] = dist;
				dir.put(id, WorldUtil.directionName(e.getX() - c.getX(), e.getZ() - c.getZ()));
			}
		}
		StringBuilder sb = new StringBuilder("Hostile mobs nearby: ");
		appendCounts(sb, hostile, dir, "none");
		sb.append("\nOther creatures: ");
		appendCounts(sb, other, dir, "none");
		return sb.toString();
	}

	private static void appendCounts(StringBuilder sb, Map<String, int[]> map, Map<String, String> dir, String empty) {
		if (map.isEmpty()) {
			sb.append(empty);
			return;
		}
		int i = 0;
		for (Map.Entry<String, int[]> e : map.entrySet()) {
			if (i++ >= 10) break;
			if (i > 1) sb.append(", ");
			sb.append(e.getKey());
			if (e.getValue()[0] > 1) sb.append(" x").append(e.getValue()[0]);
			sb.append(" (").append(e.getValue()[1]).append("m ").append(dir.getOrDefault(e.getKey(), "")).append(')');
		}
	}

	private static String notableBlocks(CompanionEntity c) {
		World w = c.getWorld();
		BlockPos center = c.getBlockPos();
		Map<String, int[]> found = new TreeMap<>();
		BlockPos.Mutable m = new BlockPos.Mutable();
		int r = 10;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -5; dy <= 8; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					BlockState s = w.getBlockState(m);
					if (s.isAir()) continue;
					String key = null;
					if (s.isIn(BlockTags.LOGS)) key = "trees/logs";
					else if (s.isIn(BlockTags.COAL_ORES)) key = "coal_ore";
					else if (s.isIn(BlockTags.IRON_ORES)) key = "iron_ore";
					else if (s.isIn(BlockTags.COPPER_ORES)) key = "copper_ore";
					else if (s.isIn(BlockTags.GOLD_ORES)) key = "gold_ore";
					else if (s.isIn(BlockTags.DIAMOND_ORES)) key = "diamond_ore";
					else if (s.isIn(BlockTags.REDSTONE_ORES)) key = "redstone_ore";
					else if (s.isIn(BlockTags.LAPIS_ORES)) key = "lapis_ore";
					else if (s.isIn(BlockTags.EMERALD_ORES)) key = "emerald_ore";
					else if (s.isIn(BlockTags.BEDS)) key = "bed";
					else if (s.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) key = "lava";
					else {
						String id = Registries.BLOCK.getId(s.getBlock()).getPath();
						if (id.equals("chest") || id.equals("crafting_table") || id.equals("furnace") || id.equals("barrel") || id.equals("anvil")
								|| id.equals("enchanting_table") || id.equals("water")) key = id;
					}
					if (key == null) continue;
					int d = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
					int[] v = found.computeIfAbsent(key, k -> new int[]{0, Integer.MAX_VALUE, 0, 0, 0});
					v[0]++;
					if (d < v[1]) {
						v[1] = d;
						v[2] = m.getX();
						v[3] = m.getY();
						v[4] = m.getZ();
					}
				}
			}
		}
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, int[]> e : found.entrySet()) {
			if (!sb.isEmpty()) sb.append(", ");
			int[] v = e.getValue();
			sb.append(e.getKey());
			if (!e.getKey().equals("water") && !e.getKey().equals("lava")) sb.append(" x").append(v[0]);
			sb.append(" (nearest ").append(v[2]).append(' ').append(v[3]).append(' ').append(v[4]).append(')');
		}
		return sb.toString();
	}

	/** Helper kept separate so the status code reads clearly. */
	static final class PlayerLooking {
		static boolean isLookingAtCompanion(PlayerEntity p, CompanionEntity c) {
			return com.necora.aicompanion.task.behavior.PlayerSocial.isLookingAt(p, c);
		}
	}
}
