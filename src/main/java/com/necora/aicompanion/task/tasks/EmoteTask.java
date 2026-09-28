package com.necora.aicompanion.task.tasks;

import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.task.Task;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Player body language: waving (arm swings), jumping, crouch spam, spinning, nodding... */
public class EmoteTask extends Task {
	private final String type;
	@Nullable
	private final Entity lookAt;
	private float startYaw;

	public EmoteTask(CompanionEntity companion, String type, @Nullable Entity lookAt) {
		super(companion);
		this.type = normalize(type);
		this.lookAt = lookAt;
	}

	private static String normalize(String t) {
		String s = t == null ? "wave" : t.toLowerCase(Locale.ROOT).trim();
		if (s.contains("wave") || s.contains("hi") || s.contains("hello") || s.contains("bye")) return "wave";
		if (s.contains("crouch") || s.contains("sneak") || s.contains("tbag") || s.contains("t-bag") || s.contains("squat")) return "crouch";
		if (s.contains("spin") || s.contains("turn")) return "spin";
		if (s.contains("nod") || s.contains("yes")) return "nod";
		if (s.contains("shake") || s.contains("no")) return "shake";
		if (s.contains("dance") || s.contains("celebrat") || s.contains("party")) return "dance";
		if (s.contains("jump") || s.contains("hop")) return "jump";
		if (s.contains("punch") || s.contains("hit") || s.contains("swing")) return "wave";
		return "jump";
	}

	@Override
	protected Status start() {
		startYaw = c.getYaw();
		c.getMover().stop();
		return Status.RUNNING;
	}

	@Override
	protected Status tick() {
		if (lookAt != null && !type.equals("spin")) c.getLookControl().lookAt(lookAt, 40.0F, 40.0F);
		switch (type) {
			case "wave" -> {
				if (ticks % 6 == 1) c.swingHand(Hand.MAIN_HAND);
				if (ticks >= 24) return Status.SUCCESS;
			}
			case "jump" -> {
				if (c.isOnGround() && ticks < 40) c.getJumpControl().setActive();
				if (ticks >= 44) return Status.SUCCESS;
			}
			case "crouch" -> {
				if (ticks % 6 == 1) c.crouchFor(3);
				if (ticks >= 36) return Status.SUCCESS;
			}
			case "spin" -> {
				float yaw = startYaw + ticks * 24.0F;
				c.setYaw(yaw);
				c.setBodyYaw(yaw);
				c.setHeadYaw(yaw);
				if (ticks >= 30) return Status.SUCCESS;
			}
			case "nod" -> {
				float pitch = (ticks / 4) % 2 == 0 ? 25.0F : -10.0F;
				c.setPitch(pitch);
				if (ticks >= 24) {
					c.setPitch(0);
					return Status.SUCCESS;
				}
			}
			case "shake" -> {
				float yaw = c.getBodyYaw() + ((ticks / 3) % 2 == 0 ? 35.0F : -35.0F);
				c.setHeadYaw(yaw);
				if (ticks >= 24) return Status.SUCCESS;
			}
			case "dance" -> {
				if (ticks % 8 == 1) c.crouchFor(3);
				if (ticks % 16 == 5 && c.isOnGround()) c.getJumpControl().setActive();
				if (ticks % 10 == 0) c.swingHand(ticks % 20 == 0 ? Hand.MAIN_HAND : Hand.OFF_HAND);
				float yaw = startYaw + (float) Math.sin(ticks * 0.3) * 45.0F;
				c.setBodyYaw(yaw);
				if (ticks >= 60) return Status.SUCCESS;
			}
			default -> {
				return Status.SUCCESS;
			}
		}
		return Status.RUNNING;
	}

	@Override
	public boolean reportResult() {
		return false;
	}

	@Override
	public String describe() {
		return "emote: " + type;
	}
}
