package com.necora.aicompanion.registry;

import com.necora.aicompanion.AICompanionMod;
import com.necora.aicompanion.entity.CompanionEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModEntities {
	public static final EntityType<CompanionEntity> COMPANION = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(AICompanionMod.MOD_ID, "companion"),
			EntityType.Builder.<CompanionEntity>create(CompanionEntity::new, SpawnGroup.MISC)
					.dimensions(0.6F, 1.8F)
					.eyeHeight(1.62F)
					.maxTrackingRange(10)
					.build("companion"));

	private ModEntities() {
	}

	public static void init() {
		FabricDefaultAttributeRegistry.register(COMPANION, CompanionEntity.createCompanionAttributes());
	}
}
