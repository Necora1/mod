package com.necora.aicompanion.ai;

import com.necora.aicompanion.entity.CompanionEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

/**
 * @param speaker who asked (null for autonomous decisions)
 * @param trusted whether that person may give orders
 */
public record ActionContext(CompanionEntity companion, CompanionBrain brain, @Nullable ServerPlayerEntity speaker, boolean trusted) {
}
