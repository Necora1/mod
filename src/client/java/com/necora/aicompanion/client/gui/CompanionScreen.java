package com.necora.aicompanion.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Everything about one companion: orders, building, chat, memory and settings, in tabs. */
public class CompanionScreen extends BaseScreen {
	private enum Tab {
		ORDERS("Orders"), WORK("Work"), CHAT("Chat"), MEMORY("Memory"), SETUP("Setup");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final String[][] STRUCTURES = {
			{"House", "house"}, {"Tower", "tower"}, {"Wall", "wall"}, {"Fence / pen", "fence"},
			{"Platform", "platform"}, {"Bridge", "bridge"}, {"Pillar", "pillar"}};
	private static final String[] SIZES = {"Small", "Medium", "Large"};
	private static final String[][] SPOTS = {{"In front of me", "front"}, {"Where I look", "looking"}, {"Where they are", "you"}};

	private static Tab lastTab = Tab.ORDERS;

	private final String name;
	private Tab tab = lastTab;
	@Nullable
	private JsonObject d;
	private int ticks;
	private String layoutKey = "";
	private int sx, sy, sw, sh, cx, cy, cw, ch;

	// what the player typed / picked, kept across re-inits
	private String chatInput = "";
	private final List<String> sentLines = new ArrayList<>();
	private int structure;
	private int size = 1;
	private String spot = "front";
	private String material = "";
	private String mineBlock = "";
	private String mineCount = "16";
	private String craftItem = "";
	private String craftCount = "1";
	private String giveItem = "all";
	private String giveCount = "";
	private String factInput = "";
	@Nullable
	private String personality;
	private String skinInput = "";
	private String trustInput = "";
	private boolean confirmDelete;
	private boolean confirmForgetAll;

	private final LineList chatList;
	private final LineList memoryList;
	private String chatKey = "";
	private String memoryKey = "";
	@Nullable
	private ButtonWidget giveButton;
	@Nullable
	private CyclingButtonWidget<String> stanceButton;
	@Nullable
	private CyclingButtonWidget<String> modeButton;
	@Nullable
	private TextFieldWidget chatField;
	private boolean focusChat;

	public CompanionScreen(String name, @Nullable Screen parent) {
		super(Text.literal(name), parent);
		this.name = name;
		this.d = GuiClient.detail(name);
		this.chatList = new LineList(net.minecraft.client.MinecraftClient.getInstance().textRenderer);
		this.memoryList = new LineList(net.minecraft.client.MinecraftClient.getInstance().textRenderer);
		if (d != null) personality = str(d, "personality", "");
	}

	private boolean present() {
		return bool(d, "present");
	}

	private boolean canManage() {
		return bool(d, "canManage");
	}

	// ------------------------------------------------------------------
	// layout
	// ------------------------------------------------------------------

	@Override
	protected void init() {
		layoutPanel(420, 244);
		if (ticks == 0) GuiClient.sendFor("detail", name);
		sx = px + 6;
		sy = py + 22;
		sw = 90;
		sh = ph - 22 - 16;
		cx = px + 102;
		cy = py + 44;
		cw = pw - 108;
		ch = ph - 44 - 16;
		layoutKey = layoutKey(d);
		giveButton = null;
		stanceButton = null;
		modeButton = null;
		chatField = null;

		int tw = (cw - 4 * 2) / 5;
		for (int i = 0; i < Tab.values().length; i++) {
			Tab t = Tab.values()[i];
			ButtonWidget b = addDrawableChild(ButtonWidget.builder(Text.literal(t.label), btn -> switchTab(t))
					.dimensions(cx + i * (tw + 2), py + 22, i == 4 ? cw - 4 * (tw + 2) : tw, 18)
					.build());
			b.active = t != tab;
		}

		if (d != null && !present() && canManage()) {
			addDrawableChild(ButtonWidget.builder(Text.literal("Summon"), b -> GuiClient.sendFor("summon", name))
					.dimensions(sx + 4, sy + 110, sw - 8, 20)
					.tooltip(Tooltip.of(Text.literal("Bring " + name + " to you")))
					.build());
		}

		if (d == null) return;
		switch (tab) {
			case ORDERS -> initOrders();
			case WORK -> initWork();
			case CHAT -> initChat();
			case MEMORY -> initMemory();
			case SETUP -> initSetup();
		}
	}

	private void switchTab(Tab t) {
		tab = t;
		lastTab = t;
		confirmDelete = false;
		confirmForgetAll = false;
		clearAndInit();
	}

	private static String layoutKey(@Nullable JsonObject d) {
		if (d == null) return "none";
		StringBuilder sb = new StringBuilder();
		sb.append(bool(d, "present")).append(bool(d, "canManage")).append(!str(d, "lastBuild", "").isEmpty());
		if (d.has("places")) for (JsonElement e : d.getAsJsonArray("places")) sb.append('|').append(str(e.getAsJsonObject(), "name", ""));
		return sb.toString();
	}

	private ButtonWidget button(String label, String tooltip, int x, int y, int w, ButtonWidget.PressAction action) {
		ButtonWidget.Builder b = ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, w, 20);
		if (!tooltip.isEmpty()) b.tooltip(Tooltip.of(Text.literal(tooltip)));
		return addDrawableChild(b.build());
	}

	private ButtonWidget order(String label, String tooltip, int x, int y, int w, JsonObject... actions) {
		ButtonWidget b = button(label, tooltip, x, y, w, btn -> sendOrder(label, actions));
		b.active = present();
		return b;
	}

	private void sendOrder(String label, JsonObject... actions) {
		JsonArray arr = new JsonArray();
		for (JsonObject a : actions) arr.add(a);
		GuiClient.sendFor("order", name, "actions", arr, "label", label.toLowerCase(Locale.ROOT));
	}

	private static JsonObject action(String type, Object... keyValues) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			Object v = keyValues[i + 1];
			if (v instanceof Number n) o.addProperty(String.valueOf(keyValues[i]), n);
			else if (v instanceof Boolean b) o.addProperty(String.valueOf(keyValues[i]), b);
			else o.addProperty(String.valueOf(keyValues[i]), String.valueOf(v));
		}
		return o;
	}

	private void initOrders() {
		int gap = 3;
		int w = (cw - 2 * gap) / 3;
		int[] col = {cx, cx + w + gap, cx + 2 * (w + gap)};
		int y = cy;
		order("Follow me", "Walk (or fly) with you", col[0], y, w, action("follow"));
		order("Stay", "Wait where they are now", col[1], y, w, action("stay"));
		order("Come here", "Walk over to you", col[2], y, w, action("come"));
		y += 22;
		order("Protect me", "Stay close and fight anything that comes near you", col[0], y, w, action("protect"));
		order("Surround me", "Build a protective box of blocks around you", col[1], y, w, action("surround"));
		order("Stop", "Stop everything they're doing", col[2], y, w, action("stop"));
		y += 22;
		button("Teleport here", "Teleport them next to you", col[0], y, w, b -> GuiClient.sendFor("tp", name)).active = present();
		button("Inventory", "Open their inventory to give or take items", col[1], y, w, b -> GuiClient.sendFor("inventory", name)).active = present();
		String last = str(d, "lastBuild", "");
		ButtonWidget undo = order("Undo build", last.isEmpty() ? "Nothing built recently" : "Tear down " + last + " (also: let me out of a box)", col[2], y, w, action("demolish"));
		undo.active = present() && !last.isEmpty();
		y += 22;
		order("Attack mobs", "Hunt down hostile mobs nearby", col[0], y, w, action("attack", "target", "hostiles"));
		order("Pick up items", "Collect the dropped items around them", col[1], y, w, action("collect"));
		order("Eat", "Eat something from their inventory", col[2], y, w, action("eat"));
		y += 22;
		order("Light up area", "Place torches around, so mobs can't spawn", col[0], y, w, action("light"));
		order("Set home here", "Remember where you're standing as \"home\"", col[1], y, w, action("remember_place", "name", "home", "at", "me"));
		boolean hasHome = false;
		if (d != null && d.has("places")) {
			for (JsonElement e : d.getAsJsonArray("places")) if (str(e.getAsJsonObject(), "name", "").equalsIgnoreCase("home")) hasHome = true;
		}
		ButtonWidget home = order("Go home", hasHome ? "Walk to the place saved as home" : "Set a home first", col[2], y, w, action("goto", "target", "home"));
		home.active = present() && hasHome;
		y += 26;

		int half = (cw - gap) / 2;
		stanceButton = addDrawableChild(CyclingButtonWidget.<String>builder(v -> Text.literal(capitalize(v)))
				.values("passive", "defensive", "aggressive")
				.initially(str(d, "stance", "defensive"))
				.tooltip(v -> Tooltip.of(Text.literal(switch (v) {
					case "passive" -> "Never fights, runs away from danger";
					case "aggressive" -> "Attacks hostile mobs on sight";
					default -> "Fights back and defends you";
				})))
				.build(cx, y, half, 20, Text.literal("Stance"), (btn, v) -> GuiClient.sendFor("stance", name, "value", v)));
		modeButton = addDrawableChild(CyclingButtonWidget.<String>builder(v -> Text.literal(v.equals("auto") ? "Same as me" : capitalize(v)))
				.values("auto", "survival", "creative")
				.initially(str(d, "mode", "auto"))
				.tooltip(v -> Tooltip.of(Text.literal(switch (v) {
					case "survival" -> "Needs real blocks and tools, can get hurt, gets hungry";
					case "creative" -> "Unlimited blocks, instant breaking, flies, can't be hurt";
					default -> "Copies your game mode";
				})))
				.build(cx + half + gap, y, cw - half - gap, 20, Text.literal("Mode"), (btn, v) -> GuiClient.sendFor("mode", name, "value", v)));
		modeButton.active = canManage();
	}

	private void initWork() {
		int gap = 3;
		int half = (cw - gap) / 2;
		int y = cy + 11;
		List<String> structureIds = new ArrayList<>();
		for (int i = 0; i < STRUCTURES.length; i++) structureIds.add(String.valueOf(i));
		addDrawableChild(CyclingButtonWidget.<String>builder(v -> Text.literal(STRUCTURES[Integer.parseInt(v)][0]))
				.values(structureIds)
				.initially(String.valueOf(structure))
				.omitKeyText()
				.build(cx, y, half, 20, Text.literal("Build"), (btn, v) -> structure = Integer.parseInt(v)));
		addDrawableChild(CyclingButtonWidget.<String>builder(Text::literal)
				.values(SIZES)
				.initially(SIZES[size])
				.build(cx + half + gap, y, cw - half - gap, 20, Text.literal("Size"), (btn, v) -> size = List.of(SIZES).indexOf(v)));
		y += 22;
		field(cx + 1, y + 1, half - 2, material, "Material: default", 48, s -> material = s)
				.setTooltip(Tooltip.of(Text.literal("Block to build with, e.g. spruce_planks, stone_bricks, cobblestone. Empty = a sensible default (or whatever they have).")));
		List<String> spots = new ArrayList<>();
		for (String[] s : SPOTS) spots.add(s[1]);
		if (d != null && d.has("places")) {
			for (JsonElement e : d.getAsJsonArray("places")) spots.add("place:" + str(e.getAsJsonObject(), "name", ""));
		}
		if (!spots.contains(spot)) spot = "front";
		addDrawableChild(CyclingButtonWidget.<String>builder(CompanionScreen::spotLabel)
				.values(spots)
				.initially(spot)
				.omitKeyText()
				.build(cx + half + gap, y, cw - half - gap, 20, Text.literal("Where"), (btn, v) -> spot = v));
		y += 22;
		int third = (cw - 2 * gap) / 3;
		button("Build it", "Start building. In survival they need the blocks (hand them over or ask them to gather).", cx, y, third, b -> sendOrder("build " + STRUCTURES[structure][0].toLowerCase(Locale.ROOT), buildAction())).active = present();
		String last = str(d, "lastBuild", "");
		ButtonWidget undo = order("Undo build", last.isEmpty() ? "Nothing built recently" : "Tear down " + last, cx + third + gap, y, third, action("demolish"));
		undo.active = present() && !last.isEmpty();
		order("Dig a hole", "Dig a 3x3 hole, 2 deep, where you're looking", cx + 2 * (third + gap), y, cw - 2 * (third + gap), action("dig", "at", "looking"));
		y += 26 + 11;

		int labelW = 42;
		int countW = 30;
		int goW = 52;
		int fieldW = cw - labelW - countW - goW - 2 * gap;
		int fx = cx + labelW;
		int countX = fx + fieldW + gap;
		int goX = countX + countW + gap;
		field(fx + 1, y + 1, fieldW - 2, mineBlock, "oak_log, stone, iron...", 48, s -> mineBlock = s)
				.setTooltip(Tooltip.of(Text.literal("What to mine or chop: a block or item name (logs, stone, coal, iron_ore, sand...)")));
		field(countX + 1, y + 1, countW - 2, mineCount, "16", 4, s -> mineCount = s);
		button("Mine", "Go get it: finds the nearest blocks, digs them up and collects the drops", goX, y, goW, b -> {
			if (mineBlock.isBlank()) {
				showMessage("What should " + name + " mine? Type a block, e.g. oak_log", true);
				return;
			}
			sendOrder("mine " + mineBlock.trim(), action("mine", "block", mineBlock.trim(), "count", parseInt(mineCount, 16)));
		}).active = present();
		y += 22;
		field(fx + 1, y + 1, fieldW - 2, craftItem, "wooden_pickaxe, torch...", 48, s -> craftItem = s)
				.setTooltip(Tooltip.of(Text.literal("What to craft. They gather nothing by themselves: the ingredients (or raw materials) must be in their inventory.")));
		field(countX + 1, y + 1, countW - 2, craftCount, "1", 4, s -> craftCount = s);
		button("Craft", "Craft it (including the parts it needs, like planks and sticks)", goX, y, goW, b -> {
			if (craftItem.isBlank()) {
				showMessage("What should " + name + " craft? Type an item, e.g. wooden_pickaxe", true);
				return;
			}
			sendOrder("craft " + craftItem.trim(), action("craft", "item", craftItem.trim(), "count", parseInt(craftCount, 1)));
		}).active = present();
		y += 22;
		giveButton = button(giveLabel(), "Click to pick what they should give you", fx, y, fieldW, b -> cycleGiveItem());
		field(countX + 1, y + 1, countW - 2, giveCount, "all", 4, s -> giveCount = s);
		button("Give", "Walk over and toss it to you", goX, y, goW, b ->
				sendOrder("give " + giveItem, action("give", "item", giveItem, "count", parseInt(giveCount, 0)))).active = present();
	}

	private static Text spotLabel(String v) {
		for (String[] s : SPOTS) if (s[1].equals(v)) return Text.literal(s[0]);
		return Text.literal("At " + v.substring(v.indexOf(':') + 1));
	}

	private JsonObject buildAction() {
		String id = STRUCTURES[structure][1];
		JsonObject a = action("build");
		a.addProperty("structure", id.equals("house") ? (size == 0 ? "small_house" : size == 2 ? "big_house" : "house") : id);
		int[] dims = switch (id) {
			case "tower" -> new int[]{6, 10, 16};
			case "wall" -> new int[]{5, 9, 17};
			case "fence" -> new int[]{7, 9, 15};
			case "platform" -> new int[]{5, 7, 11};
			case "bridge" -> new int[]{6, 12, 24};
			case "pillar" -> new int[]{3, 6, 12};
			default -> null;
		};
		if (dims != null) {
			String key = switch (id) {
				case "tower", "pillar" -> "height";
				case "wall", "bridge" -> "length";
				default -> "width";
			};
			a.addProperty(key, dims[size]);
		}
		if (!material.isBlank()) a.addProperty("material", material.trim());
		a.addProperty("at", spot.startsWith("place:") ? spot.substring(6) : spot);
		return a;
	}

	private List<String> giveChoices() {
		List<String> out = new ArrayList<>();
		out.add("all");
		if (d != null && d.has("items")) {
			for (JsonElement e : d.getAsJsonArray("items")) out.add(str(e.getAsJsonObject(), "id", "?"));
		}
		return out;
	}

	private void cycleGiveItem() {
		List<String> choices = giveChoices();
		int i = choices.indexOf(giveItem);
		giveItem = choices.get((i + 1) % choices.size());
		if (giveButton != null) giveButton.setMessage(Text.literal(giveLabel()));
	}

	private String giveLabel() {
		if (giveItem.equals("all")) return giveChoices().size() > 1 ? "Everything" : "Everything (empty)";
		int count = 0;
		if (d != null && d.has("items")) {
			for (JsonElement e : d.getAsJsonArray("items")) {
				if (str(e.getAsJsonObject(), "id", "").equals(giveItem)) count = (int) num(e.getAsJsonObject(), "count", 0);
			}
		}
		return giveItem.replace('_', ' ') + " x" + count;
	}

	private void initChat() {
		chatList.setBounds(cx, cy, cw, ch - 24);
		chatList.setEmptyText("Say hi! (normal chat works too)");
		chatField = field(cx + 1, cy + ch - 20, cw - 56, chatInput, "Say something to " + name + "...", 256, s -> chatInput = s);
		button("Send", "", cx + cw - 52, cy + ch - 21, 52, b -> sendChat());
		setInitialFocus(chatField);
		// a tab button click re-focuses the (removed) button afterwards; grab focus on the next tick
		focusChat = true;
		chatKey = "";
		refreshChat();
		chatList.scrollToBottom();
	}

	private void sendChat() {
		String text = chatInput.trim();
		if (text.isEmpty()) return;
		GuiClient.sendFor("chat", name, "text", text);
		sentLines.add(text);
		chatInput = "";
		if (chatField != null) chatField.setText("");
		chatKey = "";
		refreshChat();
		chatList.scrollToBottom();
	}

	private void refreshChat() {
		String key = (d == null || !d.has("chat") ? "" : d.get("chat").toString()) + bool(d, "thinking") + sentLines.size();
		if (key.equals(chatKey)) return;
		boolean bottom = chatList.atBottom();
		chatKey = key;
		chatList.clear();
		if (d != null && d.has("chat")) {
			for (JsonElement e : d.getAsJsonArray("chat")) {
				JsonObject line = e.getAsJsonObject();
				String kind = str(line, "kind", "");
				String text = str(line, "text", "");
				int color = switch (kind) {
					case "companion" -> 0xFFFFE08A;
					case "event" -> 0xFF8A8A9A;
					case "action" -> 0xFF6FC3DF;
					default -> 0xFFFFFFFF;
				};
				chatList.add(text, color);
			}
		}
		for (String s : sentLines) chatList.add("You: " + s, 0xFFBBBBBB);
		if (bool(d, "thinking")) chatList.add(name + " is typing...", 0xFF8A8A9A);
		if (bottom) chatList.scrollToBottom();
	}

	private void initMemory() {
		memoryList.setBounds(cx, cy, cw, ch - 48);
		memoryList.setEmptyText("Nothing remembered yet");
		memoryKey = "";
		refreshMemory();
		int y = cy + ch - 44;
		TextFieldWidget f = field(cx + 1, y + 1, cw - 84, factInput, "Something to remember...", 300, s -> factInput = s);
		f.setTooltip(Tooltip.of(Text.literal("A fact " + name + " should always remember, e.g. \"my base is at the big oak tree\"")));
		button("Remember", "", cx + cw - 80, y, 80, b -> addFact()).active = canManage();
		f.setEditable(canManage());
		y += 23;
		int gap = 3;
		int w = (cw - 3 * gap) / 4;
		button("Chat", "Forget the conversation so far (keeps facts and places)", cx, y, w, b -> forget("chat")).active = canManage();
		button("Facts", "Forget all facts", cx + (w + gap), y, w, b -> forget("facts")).active = canManage();
		button("Places", "Forget all saved places", cx + 2 * (w + gap), y, w, b -> forget("places")).active = canManage();
		button(confirmForgetAll ? "Sure?" : "Everything", "Wipe their whole memory", cx + 3 * (w + gap), y, cw - 3 * (w + gap), b -> {
			if (!confirmForgetAll) {
				confirmForgetAll = true;
				clearAndInit();
			} else {
				confirmForgetAll = false;
				forget("all");
				clearAndInit();
			}
		}).active = canManage();
	}

	private void forget(String what) {
		GuiClient.sendFor("forget", name, "value", what);
		memoryKey = "";
	}

	private void addFact() {
		if (factInput.isBlank()) return;
		GuiClient.sendFor("add_fact", name, "value", factInput.trim());
		factInput = "";
		clearAndInit();
	}

	private void refreshMemory() {
		String key = d == null ? "" : String.valueOf(d.get("facts")) + d.get("places") + d.get("events") + d.get("summary");
		if (key.equals(memoryKey)) return;
		memoryKey = key;
		memoryList.clear();
		if (d == null) return;
		boolean manage = canManage();
		JsonArray facts = d.has("facts") ? d.getAsJsonArray("facts") : new JsonArray();
		memoryList.add("Facts (" + facts.size() + ")", GOLD);
		if (facts.isEmpty()) memoryList.add("  nothing yet - tell them things in chat, or add one below", SUB);
		for (int i = 0; i < facts.size(); i++) {
			final int index = i;
			final String text = facts.get(i).getAsString();
			memoryList.add("- " + text, 0xFFE8E8E8, manage ? () -> GuiClient.sendFor("remove_fact", name, "index", index, "value", text) : null);
		}
		memoryList.gap();
		JsonArray places = d.has("places") ? d.getAsJsonArray("places") : new JsonArray();
		memoryList.add("Places (" + places.size() + ")", GOLD);
		if (places.isEmpty()) memoryList.add("  none - say \"remember this spot as home\"", SUB);
		for (JsonElement e : places) {
			JsonObject p = e.getAsJsonObject();
			String place = str(p, "name", "?");
			String line = "- " + place + ": " + (int) num(p, "x", 0) + ", " + (int) num(p, "y", 0) + ", " + (int) num(p, "z", 0) + " (" + str(p, "dim", "") + ")";
			memoryList.add(line, 0xFFE8E8E8, manage ? () -> GuiClient.sendFor("remove_place", name, "value", place) : null);
		}
		JsonArray events = d.has("events") ? d.getAsJsonArray("events") : new JsonArray();
		if (!events.isEmpty()) {
			memoryList.gap();
			memoryList.add("Recently", GOLD);
			for (JsonElement e : events) memoryList.add("- " + e.getAsString(), SUB);
		}
		String summary = str(d, "summary", "");
		if (!summary.isBlank()) {
			memoryList.gap();
			memoryList.add("Older conversations (summary)", GOLD);
			memoryList.add(summary, SUB);
		}
	}

	private void initSetup() {
		boolean manage = canManage();
		int y = cy + 11;
		if (personality == null) personality = str(d, "personality", "");
		TextFieldWidget pf = field(cx + 1, y + 1, cw - 58, personality, "Default: " + str(d, "defaultPersonality", ""), 400, s -> personality = s);
		pf.setTooltip(Tooltip.of(Text.literal("Who they are and how they talk, e.g. \"grumpy dwarf who loves mining and hates water\". Empty = the default personality.")));
		pf.setEditable(manage);
		button("Save", "", cx + cw - 54, y, 54, b -> GuiClient.sendFor("personality", name, "value", personality == null ? "" : personality.trim())).active = manage;
		y += 22 + 11;
		TextFieldWidget sf = field(cx + 1, y + 1, cw - 58, skinInput, str(d, "skin", "").isEmpty() ? "Player name, e.g. jeb_" : "Now: " + str(d, "skin", ""), 16, s -> skinInput = s);
		sf.setEditable(manage);
		button("Apply", "Wear this player's skin", cx + cw - 54, y, 54, b -> {
			if (!skinInput.isBlank()) GuiClient.sendFor("skin", name, "value", skinInput.trim());
		}).active = manage && present();
		y += 22 + 11;
		TextFieldWidget tf = field(cx + 1, y + 1, cw - 112, trustInput, "Player name", 16, s -> trustInput = s);
		tf.setEditable(manage);
		tf.setTooltip(Tooltip.of(Text.literal("Trusted players can give orders too (friends on a server)")));
		button("Trust", "Let this player give orders", cx + cw - 108, y, 52, b -> trust("trust")).active = manage;
		button("Untrust", "", cx + cw - 54, y, 54, b -> trust("untrust")).active = manage;

		int by = cy + ch - 21;
		int half = (cw - 3) / 2;
		button("Send away", "They leave the game (items and memories are kept). Summon them back any time.", cx, by, half, b -> {
			GuiClient.sendFor("dismiss", name);
			close();
		}).active = manage && present();
		ButtonWidget del = button(confirmDelete ? "Really delete?" : "Delete forever", "Deletes " + name + " and ALL their memories. Can't be undone.", cx + half + 3, by, cw - half - 3, b -> {
			if (!confirmDelete) {
				confirmDelete = true;
				clearAndInit();
			} else {
				GuiClient.sendFor("delete", name);
				close();
			}
		});
		del.active = manage;
	}

	private void trust(String what) {
		if (trustInput.isBlank()) return;
		GuiClient.sendFor(what, name, "value", trustInput.trim());
		trustInput = "";
		clearAndInit();
	}

	// ------------------------------------------------------------------
	// updates
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (++ticks % 20 == 0) GuiClient.sendFor("detail", name);
		if (focusChat && chatField != null) {
			setFocused(chatField);
			focusChat = false;
		}
	}

	@Override
	public void onGuiUpdate(String kind, JsonObject data) {
		super.onGuiUpdate(kind, data);
		if (!kind.equals("detail") || !str(data, "name", "").equalsIgnoreCase(name)) return;
		boolean first = d == null;
		d = data;
		if (personality == null) personality = str(d, "personality", "");
		if (!chatKey.isEmpty() && d.has("chat") && !sentLines.isEmpty() && !chatKey.startsWith(d.get("chat").toString())) {
			// the reply arrived, and with it our lines are part of the log
			sentLines.clear();
		}
		if (first || (!layoutKey(d).equals(layoutKey) && !typing())) {
			clearAndInit();
			return;
		}
		if (stanceButton != null && !stanceButton.getValue().equals(str(d, "stance", "defensive"))) stanceButton.setValue(str(d, "stance", "defensive"));
		if (modeButton != null && !modeButton.getValue().equals(str(d, "mode", "auto"))) modeButton.setValue(str(d, "mode", "auto"));
		if (giveButton != null) {
			if (!giveChoices().contains(giveItem)) giveItem = "all";
			giveButton.setMessage(Text.literal(giveLabel()));
		}
		if (tab == Tab.CHAT) refreshChat();
		if (tab == Tab.MEMORY) refreshMemory();
	}

	// ------------------------------------------------------------------
	// drawing
	// ------------------------------------------------------------------

	@Override
	protected void renderPanelBackground(DrawContext context, int mouseX, int mouseY) {
		context.fill(sx, sy, sx + sw, sy + sh, 0x50000000);
		context.drawBorder(sx, sy, sw, sh, 0xFF2E2E38);
	}

	@Override
	protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
		if (d == null) {
			context.drawCenteredTextWithShadow(textRenderer, "Loading...", cx + cw / 2, cy + 40, SUB);
			return;
		}
		// header: owner / thinking
		String right = bool(d, "thinking") ? "thinking" + ".".repeat((int) (System.currentTimeMillis() / 400 % 4)) : bool(d, "mine") ? "" : "owner: " + str(d, "owner", "?");
		if (!right.isEmpty()) context.drawText(textRenderer, right, px + pw - 7 - textRenderer.getWidth(right), py + 5, SUB, false);

		renderSidebar(context, mouseX, mouseY);

		switch (tab) {
			case ORDERS -> {
				if (!present()) hint(context, name + " isn't here. Summon them first (left).", cy + ch - 10);
				else hint(context, "Or just ask in chat: \"build a house here\"", cy + ch - 10);
			}
			case WORK -> {
				context.drawText(textRenderer, "Build", cx, cy + 1, GOLD, false);
				int y = cy + 11 + 22 + 22 + 26;
				context.drawText(textRenderer, "Gather, craft, give", cx, y + 1, GOLD, false);
				y += 11;
				context.drawText(textRenderer, "Mine", cx, y + 6, 0xFFE0E0E0, false);
				context.drawText(textRenderer, "Craft", cx, y + 28, 0xFFE0E0E0, false);
				context.drawText(textRenderer, "Give me", cx, y + 50, 0xFFE0E0E0, false);
			}
			case CHAT -> chatList.render(context, mouseX, mouseY);
			case MEMORY -> memoryList.render(context, mouseX, mouseY);
			case SETUP -> {
				context.drawText(textRenderer, "Personality", cx, cy + 1, GOLD, false);
				context.drawText(textRenderer, "Skin", cx, cy + 34, GOLD, false);
				JsonArray trusted = d.has("trusted") ? d.getAsJsonArray("trusted") : new JsonArray();
				List<String> names = new ArrayList<>();
				for (JsonElement e : trusted) names.add(e.getAsString());
				String t = "Trusted: " + (names.isEmpty() ? "only you" : String.join(", ", names));
				context.drawText(textRenderer, textRenderer.trimToWidth(t, cw), cx, cy + 67, GOLD, false);
				JsonObject st = d.has("stats") ? d.getAsJsonObject("stats") : new JsonObject();
				String s1 = "Mobs killed: " + (int) num(st, "kills", 0) + "   Deaths: " + (int) num(st, "deaths", 0);
				String s2 = "Blocks placed: " + (int) num(st, "placed", 0) + "   broken: " + (int) num(st, "broken", 0);
				context.drawText(textRenderer, s1, cx, cy + 102, SUB, false);
				context.drawText(textRenderer, s2, cx, cy + 112, SUB, false);
				if (!canManage()) hint(context, "Only " + str(d, "owner", "the owner") + " can change these.", cy + 124);
			}
		}
	}

	private void hint(DrawContext context, String text, int y) {
		context.drawText(textRenderer, textRenderer.trimToWidth(text, cw), cx, y, 0xFF8A8A9A, false);
	}

	private void renderSidebar(DrawContext context, int mouseX, int mouseY) {
		int boxBottom = sy + 104;
		Entity e = null;
		if (client != null && client.world != null && present()) e = client.world.getEntityById((int) num(d, "entityId", -1));
		if (e instanceof LivingEntity living) {
			InventoryScreen.drawEntity(context, sx + 2, sy + 2, sx + sw - 2, boxBottom, 38, 0.0625F, mouseX, mouseY, living);
		} else {
			String text = present() ? "too far to see" : bool(d, "away") ? "away" : "not here";
			context.drawCenteredTextWithShadow(textRenderer, text, sx + sw / 2, sy + 48, 0xFF707080);
		}
		int y = boxBottom + 4;
		if (present()) {
			CompanionMenuScreen.drawVitals(context, textRenderer, sx + 5, y, (float) num(d, "health", 20), (int) num(d, "food", 20), true);
			y += 12;
			String mode = (bool(d, "creative") ? "Creative" : "Survival") + ", " + str(d, "stance", "");
			context.drawText(textRenderer, textRenderer.trimToWidth(mode, sw - 8), sx + 5, y, 0xFFC8C8D0, false);
			y += 12;
			String activity = capitalize(str(d, "activity", ""));
			int maxLines = Math.max(1, (sy + sh - y - 2) / 9);
			List<OrderedText> lines = textRenderer.wrapLines(StringVisitable.plain(activity), sw - 10);
			for (int i = 0; i < lines.size() && i < maxLines; i++) {
				context.drawText(textRenderer, lines.get(i), sx + 5, y + i * 9, SUB, false);
			}
		} else {
			y += 26;
			List<OrderedText> lines = textRenderer.wrapLines(StringVisitable.plain("Memory and settings still work while they're gone."), sw - 10);
			for (int i = 0; i < lines.size(); i++) context.drawText(textRenderer, lines.get(i), sx + 5, y + i * 9, 0xFF707080, false);
		}
	}

	// ------------------------------------------------------------------
	// input
	// ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (button == 0) {
			if (tab == Tab.MEMORY && memoryList.mouseClicked(mouseX, mouseY)) {
				memoryKey = "";
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (tab == Tab.CHAT && chatList.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		if (tab == Tab.MEMORY && memoryList.mouseScrolled(mouseX, mouseY, verticalAmount)) return true;
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if ((keyCode == 257 || keyCode == 335) && typing()) {
			if (tab == Tab.CHAT) {
				sendChat();
				return true;
			}
			if (tab == Tab.MEMORY && !factInput.isBlank()) {
				addFact();
				return true;
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	private static int parseInt(String s, int def) {
		try {
			return Math.max(0, Integer.parseInt(s.trim()));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static String capitalize(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
