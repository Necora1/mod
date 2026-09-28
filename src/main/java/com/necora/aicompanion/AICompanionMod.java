package com.necora.aicompanion;

import com.necora.aicompanion.command.CompanionCommands;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.net.GuiPayloads;
import com.necora.aicompanion.net.GuiServer;
import com.necora.aicompanion.registry.ModEntities;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AICompanionMod implements ModInitializer {
	public static final String MOD_ID = "aicompanion";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CompanionConfig.load();
		ModEntities.init();
		CompanionManager.init();
		GuiPayloads.register();
		GuiServer.init();
		CommandRegistrationCallback.EVENT.register(CompanionCommands::register);
		LOGGER.info("AI Companion ready (provider: {}, model: {})", CompanionConfig.get().effectiveProvider(), CompanionConfig.get().effectiveModel());
	}
}
