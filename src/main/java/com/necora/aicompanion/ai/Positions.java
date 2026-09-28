package com.necora.aicompanion.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.memory.CompanionMemory;
import com.necora.aicompanion.util.Json;
import com.necora.aicompanion.util.WorldUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Resolves "me", "front", "looking", "home", player names and coordinates into world positions. */
public final class Positions {
	private Positions() {
	}

	/**
	 * @param pos     block position (feet level for spots, the target block for "looking")
	 * @param entity  the entity if the position refers to one (so movers can track it)
	 * @param inFront true when the spot is "in front of the speaker" (builds start there instead of centering on it)
	 */
	public record Resolved(BlockPos pos, @Nullable Entity entity, Direction facing, String label, boolean inFront) {
	}

	public static Direction facing(ActionContext ctx, @Nullable JsonObject args) {
		String f = args == null ? null : Json.str(args, "facing", "direction", "dir");
		if (f != null) {
			Direction d = Direction.byName(f.trim().toLowerCase(Locale.ROOT));
			if (d != null && d.getAxis().isHorizontal()) return d;
		}
		Entity ref = ctx.speaker() != null ? ctx.speaker() : ctx.companion();
		return ref.getHorizontalFacing();
	}

	@Nullable
	public static Resolved resolve(ActionContext ctx, @Nullable JsonElement spec, @Nullable JsonObject args, String defaultSpec) {
		CompanionEntity c = ctx.companion();
		Direction facing = facing(ctx, args);
		Entity speaker = ctx.speaker() != null ? ctx.speaker() : (c.getOwner() != null ? c.getOwner() : c);
		if (spec == null || spec.isJsonNull() || (spec.isJsonPrimitive() && spec.getAsString().isBlank())) {
			return resolveWord(ctx, defaultSpec, speaker, facing);
		}
		if (spec.isJsonObject() || spec.isJsonArray()) {
			BlockPos p = Json.vec(spec);
			if (p == null) return null;
			boolean relative = spec.isJsonObject() && Json.bool(spec.getAsJsonObject(), false, "relative");
			if (relative) p = speaker.getBlockPos().add(p);
			return new Resolved(p, null, facing, p.getX() + " " + p.getY() + " " + p.getZ(), false);
		}
		String s = spec.getAsString().trim();
		BlockPos coords = Json.vec(spec);
		if (coords != null) return new Resolved(coords, null, facing, s, false);
		if (s.contains("~")) {
			String[] parts = s.split("[\\s,]+");
			if (parts.length == 3) {
				BlockPos base = speaker.getBlockPos();
				int[] v = new int[3];
				int[] b = {base.getX(), base.getY(), base.getZ()};
				try {
					for (int i = 0; i < 3; i++) {
						String t = parts[i];
						v[i] = t.startsWith("~") ? b[i] + (t.length() > 1 ? Integer.parseInt(t.substring(1)) : 0) : Integer.parseInt(t);
					}
					BlockPos p = new BlockPos(v[0], v[1], v[2]);
					return new Resolved(p, null, facing, s, false);
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return resolveWord(ctx, s, speaker, facing);
	}

	@Nullable
	private static Resolved resolveWord(ActionContext ctx, String word, Entity speaker, Direction facing) {
		CompanionEntity c = ctx.companion();
		String w = word.toLowerCase(Locale.ROOT).trim().replace('-', '_').replace(' ', '_');
		switch (w) {
			case "me", "here", "speaker", "player", "my_position", "where_i_am", "us", "my_location", "current" -> {
				return new Resolved(speaker.getBlockPos(), speaker, facing, speaker == c ? "here" : name(speaker), false);
			}
			case "front", "in_front", "ahead", "in_front_of_me", "forward", "there", "nearby", "near_me", "next_to_me", "beside_me" -> {
				BlockPos p = speaker.getBlockPos().offset(facing, 3);
				BlockPos ground = WorldUtil.findStandableNear(c.getWorld(), p, 4);
				return new Resolved(ground != null ? ground : p, null, facing, "in front of " + name(speaker), true);
			}
			case "looking", "look", "crosshair", "where_i_look", "where_im_looking", "where_i'm_looking", "target", "that", "that_block", "cursor" -> {
				if (speaker instanceof PlayerEntity player) {
					HitResult hit = player.raycast(96.0, 1.0F, false);
					if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
						BlockPos p = bh.getBlockPos().offset(bh.getSide());
						return new Resolved(p, null, facing, "where " + name(speaker) + " is looking", false);
					}
				}
				return null;
			}
			case "you", "yourself", "self", "your_position", "you're", "your_spot" -> {
				return new Resolved(c.getBlockPos(), c, facing, "your spot", false);
			}
			case "owner" -> {
				ServerPlayerEntity owner = c.getOwner();
				if (owner == null) return null;
				return new Resolved(owner.getBlockPos(), owner, facing, name(owner), false);
			}
			default -> {
			}
		}
		// A player?
		if (c.getServer() != null) {
			for (ServerPlayerEntity p : c.getServer().getPlayerManager().getPlayerList()) {
				if (p.getGameProfile().getName().equalsIgnoreCase(word.trim())) {
					if (p.getWorld() != c.getWorld()) return null;
					return new Resolved(p.getBlockPos(), p, facing, name(p), false);
				}
			}
		}
		// A saved place?
		CompanionMemory.Place place = ctx.brain().getMemory().findPlace(word);
		if (place != null) {
			if (!c.getWorld().getRegistryKey().getValue().toString().equals(place.dim)) return null;
			return new Resolved(new BlockPos(place.x, place.y, place.z), null, facing, word, false);
		}
		// Another companion?
		for (CompanionEntity other : c.getWorld().getEntitiesByClass(CompanionEntity.class, c.getBoundingBox().expand(128), e -> true)) {
			if (other.getCompanionName().equalsIgnoreCase(word.trim())) {
				return new Resolved(other.getBlockPos(), other, facing, other.getCompanionName(), false);
			}
		}
		return null;
	}

	public static String name(Entity e) {
		if (e instanceof PlayerEntity p) return p.getGameProfile().getName();
		if (e instanceof CompanionEntity c) return c.getCompanionName();
		return e.getName().getString();
	}
}
