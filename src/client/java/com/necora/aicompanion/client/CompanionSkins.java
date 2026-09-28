package com.necora.aicompanion.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.necora.aicompanion.entity.CompanionEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Resolves a companion's skin: a real player's skin if one was set, otherwise a default skin. */
public final class CompanionSkins {
	private static final Map<UUID, Entry> PROFILES = new HashMap<>();

	private record Entry(String value, GameProfile profile) {
	}

	private CompanionSkins() {
	}

	public static SkinTextures get(CompanionEntity entity) {
		String value = entity.getSkinValue();
		if (value == null || value.isEmpty()) {
			return DefaultSkinHelper.getSkinTextures(entity.getUuid());
		}
		Entry entry = PROFILES.get(entity.getUuid());
		if (entry == null || !entry.value().equals(value)) {
			String name = entity.getSkinName() == null || entity.getSkinName().isEmpty() ? entity.getCompanionName() : entity.getSkinName();
			GameProfile profile = new GameProfile(entity.getUuid(), name);
			String signature = entity.getSkinSignature();
			profile.getProperties().put("textures", new Property("textures", value, signature == null || signature.isEmpty() ? null : signature));
			entry = new Entry(value, profile);
			if (PROFILES.size() > 256) PROFILES.clear();
			PROFILES.put(entity.getUuid(), entry);
		}
		return MinecraftClient.getInstance().getSkinProvider().getSkinTextures(entry.profile());
	}
}
