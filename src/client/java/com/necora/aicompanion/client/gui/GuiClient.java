package com.necora.aicompanion.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.necora.aicompanion.net.GuiPayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/** Client side of the companion screens: key binding, menu buttons and the packet receiver. */
public final class GuiClient {
	public static final KeyBinding MENU_KEY = new KeyBinding("key.aicompanion.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.aicompanion");

	/** Implemented by our screens to receive updates while they're open. */
	public interface Listener {
		void onGuiUpdate(String kind, JsonObject data);
	}

	@Nullable
	private static JsonObject lastList;
	private static final Map<String, JsonObject> DETAILS = new HashMap<>();

	private GuiClient() {
	}

	public static void init() {
		KeyBindingHelper.registerKeyBinding(MENU_KEY);
		ClientPlayNetworking.registerGlobalReceiver(GuiPayloads.Update.ID, (payload, context) -> receive(context.client(), payload.kind(), payload.data()));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (MENU_KEY.wasPressed()) {
				if (client.currentScreen != null || client.player == null) continue;
				if (isAvailable()) {
					client.setScreen(new CompanionMenuScreen(null));
				} else {
					client.player.sendMessage(Text.literal("AI Companion isn't installed on this server.").formatted(Formatting.GRAY), true);
				}
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			lastList = null;
			DETAILS.clear();
		});
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof TitleScreen) {
				Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("AI Companion"), b -> client.setScreen(new AiSettingsScreen(screen)))
						.dimensions(4, 4, 90, 20)
						.tooltip(Tooltip.of(Text.literal("AI provider, API key, model and other companion settings")))
						.build());
			} else if (screen instanceof GameMenuScreen menu && menu.shouldShowMenu() && isAvailable()) {
				Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("AI Companions"), b -> client.setScreen(new CompanionMenuScreen(screen)))
						.dimensions(4, 4, 90, 20)
						.tooltip(Tooltip.of(Text.literal("Your companions, their orders, memory and the AI settings. Shortcut: ").append(MENU_KEY.getBoundKeyLocalizedText())))
						.build());
			}
		});
	}

	/** True when connected to a server (or singleplayer world) that has the mod. */
	public static boolean isAvailable() {
		return ClientPlayNetworking.canSend(GuiPayloads.Request.ID);
	}

	public static void send(String action, JsonObject data) {
		if (isAvailable()) ClientPlayNetworking.send(new GuiPayloads.Request(action, data.toString()));
	}

	public static void send(String action) {
		send(action, new JsonObject());
	}

	/** Shorthand for requests about one companion: {"name": name, key: value, ...}. */
	public static void sendFor(String action, String name, Object... keyValues) {
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			String key = String.valueOf(keyValues[i]);
			Object v = keyValues[i + 1];
			if (v instanceof JsonElement je) o.add(key, je);
			else if (v instanceof Number n) o.addProperty(key, n);
			else if (v instanceof Boolean bo) o.addProperty(key, bo);
			else o.addProperty(key, String.valueOf(v));
		}
		send(action, o);
	}

	@Nullable
	public static JsonObject lastList() {
		return lastList;
	}

	@Nullable
	public static JsonObject detail(String name) {
		return DETAILS.get(name.toLowerCase(java.util.Locale.ROOT));
	}

	private static void receive(MinecraftClient client, String kind, String json) {
		JsonObject data;
		try {
			JsonElement el = JsonParser.parseString(json);
			data = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			return;
		}
		switch (kind) {
			case "list" -> lastList = data;
			case "detail" -> {
				if (data.has("name")) DETAILS.put(data.get("name").getAsString().toLowerCase(java.util.Locale.ROOT), data);
			}
			case "open" -> {
				if (data.has("name") && (client.currentScreen == null || client.currentScreen instanceof Listener)) {
					client.setScreen(new CompanionScreen(data.get("name").getAsString(), null));
				}
				return;
			}
			default -> {
			}
		}
		Screen screen = client.currentScreen;
		if (screen instanceof Listener l) {
			l.onGuiUpdate(kind, data);
		} else if (kind.equals("toast") && client.player != null && data.has("text")) {
			client.player.sendMessage(Text.literal(data.get("text").getAsString())
					.formatted(data.has("error") && data.get("error").getAsBoolean() ? Formatting.RED : Formatting.GREEN), true);
		}
	}
}
