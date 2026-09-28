package com.necora.aicompanion.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.necora.aicompanion.client.CompanionSkins;
import com.necora.aicompanion.entity.CompanionEntity;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The companion list (key G): everyone you can give orders to, plus "summon a new one". */
public class CompanionMenuScreen extends BaseScreen {
	private static final int ROW = 28;
	static final Identifier HEART = Identifier.ofVanilla("hud/heart/full");
	static final Identifier HEART_HALF = Identifier.ofVanilla("hud/heart/half");
	static final Identifier HEART_EMPTY = Identifier.ofVanilla("hud/heart/container");
	static final Identifier FOOD = Identifier.ofVanilla("hud/food_full");
	static final Identifier FOOD_HALF = Identifier.ofVanilla("hud/food_half");
	static final Identifier FOOD_EMPTY = Identifier.ofVanilla("hud/food_empty");

	private String newName = "";
	private String newSkin = "";
	private int scroll;
	private int ticks;
	private String layoutKey = "";
	private int listTop, listBottom;

	public CompanionMenuScreen(@Nullable Screen parent) {
		super(Text.literal("AI Companions"), parent);
	}

	@Nullable
	private JsonObject data() {
		return GuiClient.lastList();
	}

	private List<JsonObject> companions() {
		List<JsonObject> out = new ArrayList<>();
		JsonObject d = data();
		if (d != null && d.has("companions")) {
			for (JsonElement e : d.getAsJsonArray("companions")) if (e.isJsonObject()) out.add(e.getAsJsonObject());
		}
		return out;
	}

	private int visibleRows() {
		return Math.max(1, (listBottom - listTop) / ROW);
	}

	@Override
	protected void init() {
		layoutPanel(380, 240);
		if (ticks == 0) GuiClient.send("list");
		JsonObject d = data();
		boolean warn = bool(d, "missingKey");
		listTop = py + 22 + (warn ? 22 : 0);
		listBottom = py + ph - 76;
		List<JsonObject> list = companions();
		scroll = Math.max(0, Math.min(scroll, list.size() - visibleRows()));
		layoutKey = layoutKey(d);

		int btnW = 56;
		for (int i = 0; i < visibleRows() && scroll + i < list.size(); i++) {
			JsonObject c = list.get(scroll + i);
			String name = str(c, "name", "?");
			int ry = listTop + i * ROW;
			boolean present = bool(c, "present");
			addDrawableChild(ButtonWidget.builder(Text.literal("Open"), b -> client.setScreen(new CompanionScreen(name, this)))
					.dimensions(px + pw - 8 - btnW, ry + 4, btnW, 20)
					.tooltip(Tooltip.of(Text.literal("Orders, building, chat, memory and settings for " + name)))
					.build());
			if (!present && bool(c, "mine")) {
				addDrawableChild(ButtonWidget.builder(Text.literal("Summon"), b -> GuiClient.sendFor("summon", name))
						.dimensions(px + pw - 12 - 2 * btnW, ry + 4, btnW, 20)
						.tooltip(Tooltip.of(Text.literal("Bring " + name + " to you. They remember everything.")))
						.build());
			}
		}

		// new companion
		int y = py + ph - 58;
		int fieldW = (pw - 16 - 70 - 8) / 2;
		field(px + 8, y, fieldW, newName, "Name, e.g. Steve", 16, s -> newName = s);
		field(px + 12 + fieldW, y, fieldW, newSkin, "Skin: any player (optional)", 16, s -> newSkin = s)
				.setTooltip(Tooltip.of(Text.literal("Name of a real Minecraft player whose skin the companion should wear. Leave empty for a default skin.")));
		addDrawableChild(ButtonWidget.builder(Text.literal("Summon"), b -> summonNew())
				.dimensions(px + pw - 8 - 70, y - 1, 70, 20)
				.tooltip(Tooltip.of(Text.literal("Create a new companion (or call an existing one with that name)")))
				.build());

		int by = py + ph - 32;
		boolean canConfigure = d == null || bool(d, "canConfigure");
		ButtonWidget settings = addDrawableChild(ButtonWidget.builder(Text.literal(warn ? "AI Settings (!)" : "AI Settings"), b -> client.setScreen(new AiSettingsScreen(this)))
				.dimensions(px + 8, by, 110, 20)
				.tooltip(Tooltip.of(Text.literal(canConfigure ? "AI provider (Groq / Ollama / ...), API key, model and gameplay settings" : "Only operators can change the AI settings on this server")))
				.build());
		settings.active = canConfigure;
		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
				.dimensions(px + pw - 8 - 80, by, 80, 20)
				.build());
	}

	private void summonNew() {
		String name = newName.trim();
		if (name.isEmpty()) {
			showMessage("Type a name first.", true);
			return;
		}
		GuiClient.sendFor("summon", name, "skin", newSkin.trim());
		newName = "";
		newSkin = "";
		clearAndInit();
	}

	/** Widgets only depend on which companions exist and whether they're here; rebuild when that changes. */
	private static String layoutKey(@Nullable JsonObject d) {
		if (d == null) return "";
		StringBuilder sb = new StringBuilder();
		sb.append(bool(d, "missingKey")).append(bool(d, "canConfigure"));
		JsonArray arr = d.has("companions") ? d.getAsJsonArray("companions") : new JsonArray();
		for (JsonElement e : arr) {
			JsonObject c = e.getAsJsonObject();
			sb.append('|').append(str(c, "name", "")).append(bool(c, "present")).append(bool(c, "mine"));
		}
		return sb.toString();
	}

	@Override
	public void tick() {
		super.tick();
		if (++ticks % 40 == 0) GuiClient.send("list");
	}

	@Override
	public void onGuiUpdate(String kind, JsonObject data) {
		super.onGuiUpdate(kind, data);
		if (kind.equals("list") && !layoutKey(data).equals(layoutKey) && !typing()) {
			clearAndInit();
		}
	}

	@Override
	protected void renderPanelBackground(DrawContext context, int mouseX, int mouseY) {
		List<JsonObject> list = companions();
		for (int i = 0; i < visibleRows() && scroll + i < list.size(); i++) {
			int ry = listTop + i * ROW;
			boolean hover = mouseX >= px + 6 && mouseX < px + pw - 6 && mouseY >= ry && mouseY < ry + ROW - 2;
			context.fill(px + 6, ry, px + pw - 6, ry + ROW - 2, hover ? 0x40FFFFFF : 0x22FFFFFF);
		}
		context.fill(px + 6, py + ph - 73, px + pw - 6, py + ph - 72, 0xFF3D3D4A);
	}

	@Override
	protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
		JsonObject d = data();
		if (d == null) {
			context.drawCenteredTextWithShadow(textRenderer, "Loading...", width / 2, py + 50, SUB);
			return;
		}
		String ai = str(d, "provider", "?") + " / " + str(d, "model", "?");
		context.drawText(textRenderer, textRenderer.trimToWidth(ai, pw / 2), px + pw - 7 - Math.min(textRenderer.getWidth(ai), pw / 2), py + 5, SUB, false);
		if (bool(d, "missingKey")) {
			context.drawText(textRenderer, "No AI key set: companions can't think or talk yet.", px + 8, py + 24, GOLD, false);
			context.drawText(textRenderer, "Open AI Settings to add a free Groq key or pick a local model.", px + 8, py + 34, SUB, false);
		}
		List<JsonObject> list = companions();
		if (list.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, "No companions yet.", width / 2, listTop + 16, 0xFFFFFFFF);
			context.drawCenteredTextWithShadow(textRenderer, "Type a name below and press Summon.", width / 2, listTop + 30, SUB);
		}
		int textRight = px + pw - 8 - 56 * 2 - 10;
		for (int i = 0; i < visibleRows() && scroll + i < list.size(); i++) {
			JsonObject c = list.get(scroll + i);
			int ry = listTop + i * ROW;
			String name = str(c, "name", "?");
			drawFace(context, c, px + 10, ry + 5);
			int tx = px + 32;
			String title = name + (bool(c, "mine") ? "" : "  (" + str(c, "owner", "?") + ")");
			context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(title, textRight - tx), tx, ry + 4, 0xFFFFFFFF);
			int nx = tx + textRenderer.getWidth(title) + 6;
			if (bool(c, "present") && nx + 60 < textRight) {
				drawVitals(context, textRenderer, nx, ry + 3, (float) num(c, "health", 20), (int) num(c, "food", 20), true);
			}
			String activity = bool(c, "thinking") ? "thinking..." : str(c, "activity", "");
			if (bool(c, "present")) {
				int dist = (int) num(c, "distance", -1);
				if (dist >= 0) activity += " - " + dist + "m";
				else activity += " - in " + str(c, "dimension", "another dimension");
			}
			context.drawText(textRenderer, textRenderer.trimToWidth(activity, textRight - tx), tx, ry + 15, bool(c, "present") ? SUB : 0xFF707080, false);
		}
		if (list.size() > visibleRows()) {
			String more = (scroll + 1) + "-" + Math.min(list.size(), scroll + visibleRows()) + " of " + list.size() + " (scroll)";
			context.drawText(textRenderer, more, px + pw - 8 - textRenderer.getWidth(more), listBottom - 2, SUB, false);
		}
		context.drawText(textRenderer, "New companion", px + 8, py + ph - 69, SUB, false);
	}

	private void drawFace(DrawContext context, JsonObject c, int x, int y) {
		SkinTextures skin = null;
		if (client != null && client.world != null && c.has("entityId")) {
			Entity e = client.world.getEntityById((int) num(c, "entityId", -1));
			if (e instanceof CompanionEntity ce) skin = CompanionSkins.get(ce);
		}
		if (skin == null) {
			UUID id = UUID.nameUUIDFromBytes(str(c, "name", "?").getBytes(StandardCharsets.UTF_8));
			skin = DefaultSkinHelper.getSkinTextures(id);
		}
		PlayerSkinDrawer.draw(context, skin, x, y, 16);
		if (!bool(c, "present")) context.fill(x, y, x + 16, y + 16, 0x90000000);
	}

	/** Hearts and food like the HUD, compressed: one icon + number each. */
	static void drawVitals(DrawContext context, net.minecraft.client.font.TextRenderer tr, int x, int y, float health, int food, boolean showFood) {
		context.drawGuiTexture(health <= 0 ? HEART_EMPTY : HEART, x, y, 9, 9);
		String hp = String.valueOf((int) Math.ceil(health));
		context.drawText(tr, hp, x + 11, y + 1, 0xFFFF8080, false);
		if (showFood) {
			int fx = x + 14 + tr.getWidth(hp);
			context.drawGuiTexture(food <= 0 ? FOOD_EMPTY : FOOD, fx, y, 9, 9);
			context.drawText(tr, String.valueOf(food), fx + 11, y + 1, 0xFFE0B070, false);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int size = companions().size();
		if (size > visibleRows() && mouseY >= listTop && mouseY < listBottom) {
			scroll = Math.max(0, Math.min(size - visibleRows(), scroll - (int) Math.signum(verticalAmount)));
			clearAndInit();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == 257 || keyCode == 335) && typing() && !newName.isBlank()) {
			summonNew();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}
}
