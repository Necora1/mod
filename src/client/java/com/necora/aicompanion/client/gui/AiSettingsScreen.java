package com.necora.aicompanion.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.necora.aicompanion.ai.LlmClient;
import com.necora.aicompanion.config.CompanionConfig;
import com.necora.aicompanion.config.ConfigJson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI provider, key and model, plus every gameplay setting. In a world it edits the server's
 * config (operators / singleplayer host only); from the title screen it edits the local file.
 */
public class AiSettingsScreen extends BaseScreen {
	private static final String[] SECTIONS = {"Connection", "Chat", "Memory", "Gameplay", "AI"};
	private static final int ROW = 22;

	private boolean remote;
	@Nullable
	private JsonObject cfg;
	private String section = "Connection";
	private int page;
	private boolean requested;

	// pending edits
	@Nullable
	private String provider;
	@Nullable
	private String model;
	@Nullable
	private String baseUrl;
	private String apiKey = "";
	private boolean clearKey;
	private final Map<String, String> edits = new LinkedHashMap<>();

	private String testText = "";
	private int testColor = SUB;
	private boolean testing;
	private boolean testAfterSave;
	private int cx, cy, cw, ch;
	private int pages = 1;
	private final List<LabelAt> labels = new ArrayList<>();

	// rows of the Connection page, relative to cy
	private static final int MODEL_Y = 42, KEY_Y = 64, URL_Y = 86, TEST_Y = 112, RESULT_Y = 136;

	private record LabelAt(String text, int x, int y, int color) {
	}

	public AiSettingsScreen(@Nullable Screen parent) {
		super(Text.literal("AI Companion Settings"), parent);
	}

	@Override
	protected void init() {
		layoutPanel(430, 244);
		if (!requested) {
			requested = true;
			remote = GuiClient.isAvailable();
			if (remote) GuiClient.send("config_get");
			else cfg = ConfigJson.describe(CompanionConfig.get());
		}
		labels.clear();
		cx = px + 10;
		cy = py + 46;
		cw = pw - 20;
		ch = ph - 46 - 40;

		int by = py + ph - 32;
		boolean editable = cfg != null && (!remote || bool(cfg, "canConfigure"));
		ButtonWidget save = addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> save(false))
				.dimensions(px + pw - 10 - 150 - 4, by, 75, 20).build());
		save.active = editable;
		addDrawableChild(ButtonWidget.builder(Text.literal(hasEdits() ? "Cancel" : "Done"), b -> close())
				.dimensions(px + pw - 10 - 75, by, 75, 20).build());
		if (cfg == null || !editable) return;

		int tw = (cw - 4 * 2) / 5;
		for (int i = 0; i < SECTIONS.length; i++) {
			String s = SECTIONS[i];
			ButtonWidget b = addDrawableChild(ButtonWidget.builder(Text.literal(s), btn -> {
				section = s;
				page = 0;
				clearAndInit();
			}).dimensions(cx + i * (tw + 2), py + 22, i == 4 ? cw - 4 * (tw + 2) : tw, 18).build());
			b.active = !s.equals(section);
		}

		if (section.equals("Connection")) initConnection();
		else initFields();
	}

	private boolean hasEdits() {
		return provider != null || model != null || baseUrl != null || !apiKey.isEmpty() || clearKey || !edits.isEmpty();
	}

	private String currentProvider() {
		return provider != null ? provider : str(cfg, "provider", "groq");
	}

	/** Defaults of a provider, computed with a scratch config so they match the server logic. */
	private static CompanionConfig preview(String provider) {
		CompanionConfig c = new CompanionConfig();
		c.provider = provider;
		return c;
	}

	private void initConnection() {
		int labelW = 70;
		int fx = cx + labelW;
		int fw = cw - labelW;
		int y = cy;
		String prov = currentProvider();
		List<String> providers = new ArrayList<>(ConfigJson.PROVIDERS);
		if (!providers.contains(prov)) providers.add(prov);
		addDrawableChild(CyclingButtonWidget.<String>builder(AiSettingsScreen::providerName)
				.values(providers)
				.initially(prov)
				.omitKeyText()
				.build(fx, y, fw, 20, Text.literal("Provider"), (btn, v) -> {
					provider = v;
					// a model or URL typed for the old provider rarely fits the new one
					model = "";
					baseUrl = "";
					clearAndInit();
				}));
		labels.add(new LabelAt("Provider", cx, y + 6, 0xFFE0E0E0));
		y = cy + MODEL_Y;

		CompanionConfig defaults = preview(prov);
		String modelValue = model != null ? model : str(cfg, "model", "");
		TextFieldWidget mf = field(fx + 1, y + 1, fw - 2, modelValue, "default: " + defaults.effectiveModel(), 128, s -> model = s);
		mf.setTooltip(Tooltip.of(Text.literal(modelHelp(prov))));
		labels.add(new LabelAt("Model", cx, y + 6, 0xFFE0E0E0));
		y = cy + KEY_Y;

		boolean needsKey = prov.equals("groq") || prov.equals("openrouter") || prov.equals("openai");
		String keyHint;
		if (clearKey) keyHint = "removed (save to apply)";
		else if (bool(cfg, "keySet") && !providerChanged()) keyHint = "saved: " + str(cfg, "keyMasked", "") + " - paste to replace";
		else if (bool(cfg, "keyFromEnv") && !providerChanged()) keyHint = "from environment variable";
		else keyHint = needsKey ? "paste your API key here" : "not needed for local models";
		TextFieldWidget kf = field(fx + 1, y + 1, fw - 2 - 50, apiKey, keyHint, 256, s -> apiKey = s);
		kf.setRenderTextProvider((text, start) -> OrderedText.styledForwardsVisitedString("*".repeat(text.length()), net.minecraft.text.Style.EMPTY));
		kf.setTooltip(Tooltip.of(Text.literal("The key is stored in config/aicompanion.json on the " + (remote ? "server" : "computer") + " running the world. It's never shown again.")));
		ButtonWidget clear = addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), b -> {
			clearKey = true;
			apiKey = "";
			clearAndInit();
		}).dimensions(fx + fw - 48, y, 48, 20).tooltip(Tooltip.of(Text.literal("Remove the saved key"))).build());
		clear.active = bool(cfg, "keySet") && !clearKey;
		labels.add(new LabelAt("API key", cx, y + 6, 0xFFE0E0E0));
		y = cy + URL_Y;

		String urlValue = baseUrl != null ? baseUrl : str(cfg, "baseUrl", "");
		TextFieldWidget uf = field(fx + 1, y + 1, fw - 2, urlValue, "default: " + defaults.effectiveBaseUrl(), 256, s -> baseUrl = s);
		uf.setTooltip(Tooltip.of(Text.literal("Only change this for a server on another PC or port, e.g. http://192.168.1.20:11434")));
		labels.add(new LabelAt("Server URL", cx, y + 6, 0xFFE0E0E0));
		y = cy + TEST_Y;

		int third = (cw - 6) / 3;
		ButtonWidget test = addDrawableChild(ButtonWidget.builder(Text.literal(testing ? "Testing..." : "Test connection"), b -> {
			if (hasEdits()) {
				testAfterSave = true;
				save(true);
			} else {
				runTest();
			}
		}).dimensions(cx, y, third, 20).tooltip(Tooltip.of(Text.literal("Saves your changes, then sends a tiny request to the AI"))).build());
		test.active = !testing;
		addDrawableChild(ButtonWidget.builder(Text.literal("Free Groq key"), b -> ConfirmLinkScreen.open(this, "https://console.groq.com/keys"))
				.dimensions(cx + third + 3, y, third, 20)
				.tooltip(Tooltip.of(Text.literal("Opens console.groq.com - sign in and create a key (free)"))).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Get Ollama"), b -> ConfirmLinkScreen.open(this, "https://ollama.com/download"))
				.dimensions(cx + 2 * (third + 3), y, cw - 2 * (third + 3), 20)
				.tooltip(Tooltip.of(Text.literal("Run models on your own PC. After installing, run: ollama pull llama3.1"))).build());
	}

	private boolean providerChanged() {
		return provider != null && !provider.equals(str(cfg, "provider", "groq"));
	}

	private static Text providerName(String p) {
		return Text.literal(switch (p) {
			case "groq" -> "Groq (free cloud, needs key)";
			case "ollama" -> "Ollama (local, on this PC)";
			case "lmstudio" -> "LM Studio (local)";
			case "llamacpp" -> "llama.cpp server (local)";
			case "openrouter" -> "OpenRouter (cloud, needs key)";
			case "openai" -> "OpenAI / compatible";
			default -> p;
		});
	}

	private static String providerHelp(String p) {
		return switch (p) {
			case "groq" -> "Fast and free. Click \"Free Groq key\", create a key and paste it above.";
			case "ollama" -> "Runs on your PC, no key. Install Ollama, then run \"ollama pull llama3.1\" (bigger models are smarter).";
			case "lmstudio" -> "Start LM Studio's local server (port 1234) and load a model. Put the model name above.";
			case "llamacpp" -> "Start llama-server (port 8080) with a model. No key needed.";
			case "openrouter" -> "Many models behind one key from openrouter.ai.";
			case "openai" -> "OpenAI, or any OpenAI-compatible server (set the URL).";
			default -> "";
		};
	}

	private static String modelHelp(String p) {
		return switch (p) {
			case "groq" -> "e.g. llama-3.3-70b-versatile (smart) or llama-3.1-8b-instant (higher rate limits)";
			case "ollama" -> "Any model you pulled: llama3.1, qwen2.5:14b, llama3.3...";
			default -> "The model name your server expects";
		};
	}

	private void initFields() {
		List<JsonObject> fields = new ArrayList<>();
		if (cfg != null && cfg.has("fields")) {
			for (JsonElement e : cfg.getAsJsonArray("fields")) {
				JsonObject f = e.getAsJsonObject();
				if (str(f, "section", "").equals(section)) fields.add(f);
			}
		}
		int columns = cw >= 380 ? 2 : 1;
		int colW = (cw - (columns - 1) * 8) / columns;
		int rows = Math.max(1, (ch - 24) / ROW);
		int perPage = rows * columns;
		// long text settings take a whole row
		pages = Math.max(1, (fields.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		int from = page * perPage;
		int slot = 0;
		for (int i = from; i < fields.size() && slot < perPage; i++, slot++) {
			JsonObject f = fields.get(i);
			int col = columns == 2 ? slot / rows : 0;
			int row = columns == 2 ? slot % rows : slot;
			int x = cx + col * (colW + 8);
			int y = cy + row * ROW;
			addField(f, x, y, colW);
		}
		if (pages > 1) {
			int y = cy + ch - 20;
			ButtonWidget prev = addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> {
				page--;
				clearAndInit();
			}).dimensions(cx + cw - 64, y, 30, 20).build());
			prev.active = page > 0;
			ButtonWidget next = addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> {
				page++;
				clearAndInit();
			}).dimensions(cx + cw - 30, y, 30, 20).build());
			next.active = page < pages - 1;
			labels.add(new LabelAt("Page " + (page + 1) + "/" + pages, cx + cw - 70 - textRenderer.getWidth("Page " + (page + 1) + "/" + pages), y + 6, SUB));
		}
	}

	private void addField(JsonObject f, int x, int y, int w) {
		String name = str(f, "name", "");
		String label = str(f, "label", name);
		String help = str(f, "help", "");
		String type = str(f, "type", "string");
		String value = edits.containsKey(name) ? edits.get(name) : str(f, "value", "");
		Tooltip tip = help.isEmpty() ? null : Tooltip.of(Text.literal(help));
		switch (type) {
			case "bool" -> addDrawableChild(CyclingButtonWidget.onOffBuilder(Boolean.parseBoolean(value))
					.tooltip(v -> tip)
					.build(x, y, w, 20, Text.literal(label), (btn, v) -> edits.put(name, String.valueOf(v))));
			case "int", "double" -> {
				String shown = type.equals("int") ? value : trimDouble(value);
				int fw = 56;
				TextFieldWidget tf = field(x + w - fw + 1, y + 1, fw - 2, shown, "", 10, s -> edits.put(name, s));
				tf.setTextPredicate(s -> s.matches(type.equals("int") ? "\\d*" : "\\d*\\.?\\d*"));
				if (tip != null) tf.setTooltip(tip);
				labels.add(new LabelAt(textRenderer.trimToWidth(label, w - fw - 4), x, y + 6, 0xFFE0E0E0));
			}
			default -> {
				TextFieldWidget tf = field(x + 1, y + 1, w - 2, value, label, 400, s -> edits.put(name, s));
				if (tip != null) tf.setTooltip(Tooltip.of(Text.literal(label + ": " + help)));
			}
		}
	}

	private static String trimDouble(String v) {
		try {
			double d = Double.parseDouble(v);
			if (d == Math.rint(d)) return String.valueOf((long) d);
			return String.valueOf(Math.round(d * 100) / 100.0);
		} catch (NumberFormatException e) {
			return v;
		}
	}

	private JsonObject changes() {
		JsonObject o = new JsonObject();
		if (provider != null) o.addProperty("provider", provider);
		if (model != null) o.addProperty("model", model.trim());
		if (baseUrl != null) o.addProperty("baseUrl", baseUrl.trim());
		if (!apiKey.isBlank()) o.addProperty("apiKey", apiKey.trim());
		if (clearKey && apiKey.isBlank()) o.addProperty("clearKey", true);
		JsonObject fields = new JsonObject();
		for (Map.Entry<String, String> e : edits.entrySet()) fields.addProperty(e.getKey(), e.getValue());
		o.add("fields", fields);
		return o;
	}

	private void save(boolean thenTest) {
		JsonObject o = changes();
		if (remote) {
			GuiClient.send("config_set", o);
			// the server answers with the new config (onGuiUpdate)
		} else {
			String error = ConfigJson.apply(CompanionConfig.get(), o);
			CompanionConfig.save();
			LlmClient.resetJsonModeState();
			cfg = ConfigJson.describe(CompanionConfig.get());
			showMessage(error == null ? "Saved." : "Saved, except: " + error, error != null);
			resetEdits();
			if (thenTest) {
				testAfterSave = false;
				runTest();
			}
			clearAndInit();
		}
	}

	private void resetEdits() {
		provider = null;
		model = null;
		baseUrl = null;
		apiKey = "";
		clearKey = false;
		edits.clear();
	}

	private void runTest() {
		testing = true;
		testText = "Asking " + str(cfg, "effectiveModel", "the model") + "...";
		testColor = SUB;
		if (remote) {
			GuiClient.send("config_test");
		} else {
			long start = System.currentTimeMillis();
			List<LlmClient.Message> msgs = List.of(
					new LlmClient.Message("system", "Reply with a JSON object {\"say\": \"<a short greeting as a Minecraft player>\"}."),
					new LlmClient.Message("user", "Say hi."));
			LlmClient.chat(msgs, true).whenComplete((text, err) -> MinecraftClient.getInstance().execute(() -> {
				JsonObject r = new JsonObject();
				r.addProperty("ms", System.currentTimeMillis() - start);
				if (err != null) {
					Throwable t = err.getCause() != null ? err.getCause() : err;
					r.addProperty("ok", false);
					r.addProperty("text", String.valueOf(t.getMessage()));
				} else {
					r.addProperty("ok", true);
					r.addProperty("text", LlmClient.truncate(text, 200));
				}
				onTestResult(r);
			}));
		}
		if (client != null) clearAndInit();
	}

	private void onTestResult(JsonObject r) {
		testing = false;
		boolean ok = bool(r, "ok");
		testColor = ok ? GOOD : BAD;
		testText = (ok ? "Works! (" : "Failed (") + (long) num(r, "ms", 0) + " ms): " + str(r, "text", "");
		if (client != null && client.currentScreen == this) clearAndInit();
	}

	@Override
	public void onGuiUpdate(String kind, JsonObject data) {
		super.onGuiUpdate(kind, data);
		if (kind.equals("config")) {
			boolean first = cfg == null;
			cfg = data;
			if (!first) resetEdits();
			if (testAfterSave) {
				testAfterSave = false;
				runTest();
			}
			clearAndInit();
		} else if (kind.equals("test")) {
			onTestResult(data);
		}
	}

	@Override
	protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
		String where = remote ? (MinecraftClient.getInstance().isInSingleplayer() ? "this world" : "server") : "this computer";
		context.drawText(textRenderer, where, px + pw - 7 - textRenderer.getWidth(where), py + 5, SUB, false);
		if (cfg == null) {
			context.drawCenteredTextWithShadow(textRenderer, "Loading...", width / 2, py + 60, SUB);
			return;
		}
		if (remote && !bool(cfg, "canConfigure")) {
			context.drawCenteredTextWithShadow(textRenderer, "Only operators can change the AI settings on this server.", width / 2, py + 60, GOLD);
			return;
		}
		for (LabelAt l : labels) context.drawText(textRenderer, l.text(), l.x(), l.y(), l.color(), false);
		if (section.equals("Connection")) {
			String prov = currentProvider();
			drawWrapped(context, providerHelp(prov), cx + 70, cy + 22, cw - 70, SUB, 2);
			int y = cy + RESULT_Y;
			if (!testText.isEmpty()) {
				drawWrapped(context, testText, cx, y, cw, testColor, 2);
			} else if (bool(cfg, "missingKey") && !providerChanged() && apiKey.isBlank()) {
				drawWrapped(context, "No key yet: companions can't think until you add one (or pick a local model).", cx, y, cw, GOLD, 2);
			}
		}
		if (hasEdits()) {
			context.drawText(textRenderer, "Unsaved changes", px + 10, py + ph - 26, GOLD, false);
		}
	}

	private void drawWrapped(DrawContext context, String text, int x, int y, int w, int color, int maxLines) {
		List<OrderedText> lines = textRenderer.wrapLines(StringVisitable.plain(text), w);
		for (int i = 0; i < lines.size() && i < maxLines; i++) context.drawText(textRenderer, lines.get(i), x, y + i * 10, color, false);
	}
}
