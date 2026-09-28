package com.necora.aicompanion.client.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** A scrollable box of wrapped text lines, optionally with a small "x" button per entry. */
final class LineList {
	private static final int LINE = 10;

	private record Row(OrderedText text, int color, @Nullable Runnable onRemove) {
	}

	private final TextRenderer textRenderer;
	private final List<Row> rows = new ArrayList<>();
	private int x, y, w, h;
	private int scroll;
	private String emptyText = "";

	LineList(TextRenderer textRenderer) {
		this.textRenderer = textRenderer;
	}

	void setBounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
	}

	void setEmptyText(String text) {
		this.emptyText = text;
	}

	void clear() {
		rows.clear();
	}

	void add(String text, int color, @Nullable Runnable onRemove) {
		int avail = Math.max(20, w - 8 - (onRemove != null ? 10 : 0));
		List<OrderedText> lines = textRenderer.wrapLines(StringVisitable.plain(text), avail);
		for (int i = 0; i < lines.size(); i++) rows.add(new Row(lines.get(i), color, i == 0 ? onRemove : null));
	}

	void add(String text, int color) {
		add(text, color, null);
	}

	void gap() {
		rows.add(new Row(OrderedText.EMPTY, 0, null));
	}

	private int visible() {
		return Math.max(1, (h - 4) / LINE);
	}

	private int maxScroll() {
		return Math.max(0, rows.size() - visible());
	}

	boolean atBottom() {
		return scroll >= maxScroll();
	}

	void scrollToBottom() {
		scroll = maxScroll();
	}

	void clampScroll() {
		scroll = Math.max(0, Math.min(scroll, maxScroll()));
	}

	void render(DrawContext context, int mouseX, int mouseY) {
		context.fill(x, y, x + w, y + h, 0x70000000);
		context.drawBorder(x, y, w, h, 0xFF2E2E38);
		if (rows.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, emptyText, x + w / 2, y + h / 2 - 4, 0xFF808090);
			return;
		}
		clampScroll();
		context.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		int vis = visible();
		for (int i = 0; i < vis && scroll + i < rows.size(); i++) {
			Row r = rows.get(scroll + i);
			int ly = y + 3 + i * LINE;
			context.drawText(textRenderer, r.text(), x + 4, ly, r.color(), false);
			if (r.onRemove() != null) {
				int bx = x + w - 12;
				boolean hover = mouseX >= bx - 1 && mouseX < bx + 8 && mouseY >= ly - 1 && mouseY < ly + 9;
				context.drawText(textRenderer, "x", bx + 1, ly, hover ? 0xFFFF5555 : 0xFF9A5050, false);
			}
		}
		context.disableScissor();
		if (maxScroll() > 0) {
			int barH = Math.max(8, h * vis / rows.size());
			int barY = y + (h - barH) * scroll / maxScroll();
			context.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF8A8A9A);
		}
	}

	boolean mouseClicked(double mouseX, double mouseY) {
		if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return false;
		int i = (int) ((mouseY - y - 3) / LINE);
		int index = scroll + i;
		if (i < 0 || index >= rows.size()) return false;
		Row r = rows.get(index);
		if (r.onRemove() != null && mouseX >= x + w - 13 && mouseX < x + w - 3) {
			r.onRemove().run();
			return true;
		}
		return false;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return false;
		scroll -= (int) Math.signum(amount) * 3;
		clampScroll();
		return true;
	}
}
