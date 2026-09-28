package com.necora.aicompanion.client.gui;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/** Shared look for the companion screens: a dark panel with a header, and a message line at the bottom. */
abstract class BaseScreen extends Screen implements GuiClient.Listener {
	static final int PANEL = 0xE8141419;
	static final int PANEL_BORDER = 0xFF3D3D4A;
	static final int HEADER = 0xFF23232C;
	static final int SUB = 0xFFA0A0B0;
	static final int GOLD = 0xFFFFD166;
	static final int GOOD = 0xFF7BE07B;
	static final int BAD = 0xFFFF6B6B;

	@Nullable
	protected final Screen parent;
	protected int px, py, pw, ph;
	private String message = "";
	private boolean messageError;
	private long messageUntil;

	protected BaseScreen(Text title, @Nullable Screen parent) {
		super(title);
		this.parent = parent;
	}

	protected void layoutPanel(int maxWidth, int maxHeight) {
		pw = Math.min(maxWidth, width - 8);
		ph = Math.min(maxHeight, height - 8);
		px = (width - pw) / 2;
		py = (height - ph) / 2;
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		if (client != null && client.world != null) {
			renderInGameBackground(context);
		} else {
			super.renderBackground(context, mouseX, mouseY, delta);
		}
		context.fill(px, py, px + pw, py + ph, PANEL);
		context.drawBorder(px, py, pw, ph, PANEL_BORDER);
		context.fill(px + 1, py + 1, px + pw - 1, py + 18, HEADER);
		renderPanelBackground(context, mouseX, mouseY);
	}

	/** Extra backgrounds (boxes behind widgets) drawn before the widgets. */
	protected void renderPanelBackground(DrawContext context, int mouseX, int mouseY) {
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawTextWithShadow(textRenderer, title, px + 7, py + 5, 0xFFFFFFFF);
		renderContent(context, mouseX, mouseY, delta);
		if (System.currentTimeMillis() < messageUntil && !message.isEmpty()) {
			String text = textRenderer.trimToWidth(message, pw - 12);
			context.drawText(textRenderer, text, px + 6, py + ph - 11, messageError ? BAD : GOOD, false);
		}
	}

	protected abstract void renderContent(DrawContext context, int mouseX, int mouseY, float delta);

	protected void showMessage(String text, boolean error) {
		message = text;
		messageError = error;
		messageUntil = System.currentTimeMillis() + (error ? 9000 : 5000);
	}

	@Override
	public void onGuiUpdate(String kind, JsonObject data) {
		if (kind.equals("toast") && data.has("text")) {
			showMessage(data.get("text").getAsString(), data.has("error") && data.get("error").getAsBoolean());
		}
	}

	protected boolean typing() {
		return getFocused() instanceof TextFieldWidget field && field.isFocused();
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (!typing() && GuiClient.MENU_KEY.matchesKey(keyCode, scanCode)) {
			if (client != null) client.setScreen(null);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		if (client != null) client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	protected TextFieldWidget field(int x, int y, int w, String value, String placeholder, int maxLength, java.util.function.Consumer<String> onChange) {
		// text fields don't clip their placeholder, so shorten it to the box
		String hint = placeholder;
		if (textRenderer.getWidth(hint) > w - 8) hint = textRenderer.trimToWidth(hint, w - 8 - textRenderer.getWidth("...")).stripTrailing() + "...";
		TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.literal(hint));
		f.setMaxLength(maxLength);
		f.setText(value);
		f.setPlaceholder(Text.literal(hint).withColor(0xFF6E6E7A));
		f.setChangedListener(onChange);
		return addDrawableChild(f);
	}

	protected static String str(@Nullable JsonObject o, String key, String def) {
		if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
		try {
			return o.get(key).getAsString();
		} catch (Exception e) {
			return def;
		}
	}

	protected static boolean bool(@Nullable JsonObject o, String key) {
		if (o == null || !o.has(key)) return false;
		try {
			return o.get(key).getAsBoolean();
		} catch (Exception e) {
			return false;
		}
	}

	protected static double num(@Nullable JsonObject o, String key, double def) {
		if (o == null || !o.has(key)) return def;
		try {
			return o.get(key).getAsDouble();
		} catch (Exception e) {
			return def;
		}
	}
}
