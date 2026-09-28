package com.necora.aicompanion.uitest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.client.gui.AiSettingsScreen;
import com.necora.aicompanion.client.gui.CompanionMenuScreen;
import com.necora.aicompanion.client.gui.CompanionScreen;
import com.necora.aicompanion.client.gui.GuiClient;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.Stance;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.memory.CompanionMemory;
import com.necora.aicompanion.net.GuiServer;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.task.TaskManager;
import com.necora.aicompanion.task.behavior.FollowBehavior;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Drives the real client through every companion screen (run with -Daicompanion.uitest=true,
 * see the runUitest Gradle task). It clicks buttons like a player would, checks that the server
 * reacts, and reports widgets that overlap, leave the screen or don't fit their label.
 * Needs a world called "uitest" in the saves folder (CI copies the GameTest world).
 */
public class UiSmokeTest implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("aicompanion-uitest");
	private static final String WORLD = "uitest";
	private static final String NAME = "Steve";

	private record Step(String name, @Nullable Predicate<MinecraftClient> until, int maxWait, boolean critical,
						Consumer<MinecraftClient> action, int waitAfter) {
	}

	private final Deque<Step> steps = new ArrayDeque<>();
	private final List<String> failures = new ArrayList<>();
	private final List<String> warnings = new ArrayList<>();
	private int checks;
	private boolean started;
	private boolean finished;
	private int cooldown;
	private int waited;
	private int ticks;

	@Override
	public void onInitializeClient() {
		if (!Boolean.getBoolean("aicompanion.uitest")) return;
		LOG.info("UITEST enabled");
		plan();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void then(String name, int waitAfter, Consumer<MinecraftClient> action) {
		steps.add(new Step(name, null, 0, false, action, waitAfter));
	}

	private void waitFor(String name, int maxWait, Predicate<MinecraftClient> until) {
		steps.add(new Step(name, until, maxWait, false, c -> {
		}, 0));
	}

	/** Like waitFor, but the rest of the test is pointless if this never happens. */
	private void require(String name, int maxWait, Predicate<MinecraftClient> until) {
		steps.add(new Step(name, until, maxWait, true, c -> {
		}, 0));
	}

	// ------------------------------------------------------------------
	// the test
	// ------------------------------------------------------------------

	private void plan() {
		// ---- title screen: local settings
		then("title screen", 0, c -> {
			lint(c, "title", "AI Companion");
			check(findPressable(c, "AI Companion") != null, "title screen has the AI Companion button");
		});
		then("open settings from the title screen", 5, c -> press(c, "AI Companion"));
		require("local settings screen opens", 40, c -> c.currentScreen instanceof AiSettingsScreen);
		then("settings: Connection", 0, c -> {
			lint(c, "settings-connection", null);
			shot(c, "settings-connection");
		});
		for (String section : new String[]{"Chat", "Memory", "AI", "Gameplay"}) {
			then("settings: open " + section, 4, c -> press(c, section));
			then("settings: " + section, 0, c -> {
				lint(c, "settings-" + section.toLowerCase(), null);
				shot(c, "settings-" + section.toLowerCase());
			});
		}
		then("settings: next page", 4, c -> {
			if (findPressable(c, ">") != null) press(c, ">");
		});
		then("settings: second page", 0, c -> lint(c, "settings-gameplay-2", null));
		then("settings: gui scale 1", 6, c -> setGuiScale(c, 1));
		then("settings: large layout", 0, c -> {
			lint(c, "settings-scale1", null);
			shot(c, "settings-scale1");
		});
		then("settings: gui scale auto", 6, c -> setGuiScale(c, 0));
		then("close settings", 5, c -> c.currentScreen.close());
		require("back on the title screen", 40, c -> c.currentScreen instanceof TitleScreen);

		// ---- in a world
		then("start the test world", 0, c -> {
			if (!c.getLevelStorage().levelExists(WORLD)) {
				fail("test world '" + WORLD + "' is missing, can't test in-world screens");
				steps.clear();
				return;
			}
			c.createIntegratedServerLoader().start(WORLD, () -> fail("world loading was cancelled"));
		});
		require("world loaded", 20 * 150, c -> c.world != null && c.player != null && c.getServer() != null
				&& GuiClient.isAvailable() && c.currentScreen == null);
		then("summon a companion with some memories", 40, c -> onServer(c, server -> {
			ServerPlayerEntity sp = player(server, c);
			String error = CompanionManager.get().summon(sp, NAME, null);
			if (error != null) throw new IllegalStateException("summon failed: " + error);
			CompanionBrain b = CompanionManager.get().brain(NAME);
			CompanionEntity e = entity(server);
			e.getInventory().setStack(0, new ItemStack(Items.OAK_LOG, 32));
			e.getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, 64));
			e.getInventory().setStack(2, new ItemStack(Items.BREAD, 5));
			CompanionMemory m = b.getMemory();
			m.addFact("the player loves building castles by the sea", b.day());
			m.addFact("the player is scared of creepers and wants to be warned about them, always, even when busy", b.day());
			m.places.put("home", new CompanionMemory.Place(sp.getBlockX(), sp.getBlockY(), sp.getBlockZ(), "minecraft:overworld"));
			m.addHistory("user", "[CHAT] " + sp.getGameProfile().getName() + ": hey steve, how's it going?");
			m.addHistory("assistant", "{\"say\":\"pretty good! want to build something today?\",\"actions\":[{\"type\":\"follow\"}]}");
			m.addEvent("built an oak planks house", b.timeStamp());
			return null;
		}));
		then("press the menu key", 0, c -> KeyBinding.onKeyPressed(GuiClient.MENU_KEY.getDefaultKey()));
		require("G opens the menu and it lists the companion", 100, c -> c.currentScreen instanceof CompanionMenuScreen && listHas(NAME));
		then("menu", 5, c -> {
			lint(c, "menu", null);
			shot(c, "menu");
		});
		then("open the companion screen", 0, c -> press(c, "Open"));
		require("companion screen gets its data", 100, c -> c.currentScreen instanceof CompanionScreen && GuiClient.detail(NAME) != null);
		then("let it settle", 10, c -> {
		});
		then("tab: Orders", 0, c -> {
			lint(c, "tab-orders", null);
			shot(c, "tab-orders");
		});
		then("click Follow me", 15, c -> press(c, "Follow me"));
		then("server: companion follows", 0, c -> check(onServer(c, s -> {
			TaskManager tm = entity(s).getTaskManager();
			if (tm.getBehavior() instanceof FollowBehavior) return true;
			for (Task t : tm.getQueue()) if (t instanceof FollowBehavior) return true;
			return false;
		}), "Follow me button makes the companion follow"));
		then("click the stance button", 15, c -> {
			CyclingButtonWidget<?> b = cycling(c, "Stance");
			if (b == null) fail("no stance button");
			else b.onPress();
		});
		then("server: stance changed", 0, c -> check(onServer(c, s -> entity(s).getStance() == Stance.AGGRESSIVE),
				"stance button switches defensive -> aggressive"));

		then("open tab Work", 6, c -> press(c, "Work"));
		then("tab: Work", 0, c -> {
			lint(c, "tab-work", null);
			shot(c, "tab-work");
		});
		then("open tab Chat", 6, c -> press(c, "Chat"));
		then("tab: Chat", 0, c -> {
			lint(c, "tab-chat", null);
			shot(c, "tab-chat");
		});
		then("type and send a chat message", 15, c -> {
			firstTextField(c).setText("hello from the ui test");
			press(c, "Send");
		});
		then("server: chat arrived", 0, c -> check(onServer(c, s -> CompanionManager.get().brain(NAME).getMemory().stats.messages > 0),
				"chat box message reaches the companion"));
		then("open tab Memory", 6, c -> press(c, "Memory"));
		then("tab: Memory", 0, c -> {
			lint(c, "tab-memory", null);
			shot(c, "tab-memory");
		});
		then("add a fact", 15, c -> {
			firstTextField(c).setText("ui tests are fun");
			press(c, "Remember");
		});
		then("server: fact saved", 0, c -> check(onServer(c, s -> CompanionManager.get().brain(NAME).getMemory().facts.stream()
				.anyMatch(f -> f.text.equals("ui tests are fun"))), "Remember button saves a fact"));
		then("open tab Setup", 6, c -> press(c, "Setup"));
		then("tab: Setup", 0, c -> {
			lint(c, "tab-setup", null);
			shot(c, "tab-setup");
		});

		then("close the screen", 5, c -> c.setScreen(null));
		then("server opens the screen (right-click)", 0, c -> onServer(c, s -> {
			GuiServer.openFor(player(s, c), CompanionManager.get().brain(NAME));
			return null;
		}));
		waitFor("right-click opens the companion screen", 60, c -> c.currentScreen instanceof CompanionScreen);
		then("companion: gui scale 1", 10, c -> setGuiScale(c, 1));
		then("companion: large layout", 0, c -> {
			lint(c, "companion-scale1", null);
			shot(c, "companion-scale1");
		});
		then("companion: gui scale auto", 10, c -> setGuiScale(c, 0));

		then("open settings in the world", 0, c -> c.setScreen(new AiSettingsScreen(null)));
		waitFor("server settings arrive", 60, c -> c.currentScreen instanceof AiSettingsScreen && findPressable(c, "Gameplay") != null);
		then("settings from the server", 0, c -> {
			lint(c, "settings-server", null);
			shot(c, "settings-server");
		});

		then("open the pause menu", 10, c -> c.setScreen(new GameMenuScreen(true)));
		then("pause menu", 0, c -> {
			lint(c, "pause", "AI Companions");
			check(findPressable(c, "AI Companions") != null, "pause menu has the AI Companions button");
		});
		then("pause menu -> companions", 10, c -> press(c, "AI Companions"));
		waitFor("pause menu button opens the list", 40, c -> c.currentScreen instanceof CompanionMenuScreen);

		then("open the companion screen again", 0, c -> press(c, "Open"));
		waitFor("companion screen", 60, c -> c.currentScreen instanceof CompanionScreen);
		then("open tab Setup again", 6, c -> press(c, "Setup"));
		then("send the companion away", 20, c -> press(c, "Send away"));
		then("server: companion left", 0, c -> check(onServer(c, s -> CompanionManager.get().brain(NAME).getMemory().away),
				"Send away dismisses the companion"));
		then("done", 5, c -> c.setScreen(null));
	}

	// ------------------------------------------------------------------
	// runner
	// ------------------------------------------------------------------

	private void tick(MinecraftClient client) {
		if (finished) return;
		ticks++;
		if (ticks > 20 * 60 * 6) {
			fail("the UI test took longer than 6 minutes");
			finish(client);
			return;
		}
		if (client.currentScreen instanceof AccessibilityOnboardingScreen) {
			client.setScreen(new TitleScreen());
			return;
		}
		if (!started) {
			if (client.currentScreen instanceof TitleScreen && client.getOverlay() == null) {
				started = true;
				cooldown = 40;
				client.options.pauseOnLostFocus = false;
			}
			return;
		}
		if (cooldown > 0) {
			cooldown--;
			return;
		}
		Step step = steps.peek();
		if (step == null) {
			finish(client);
			return;
		}
		if (step.until() != null) {
			boolean ok;
			try {
				ok = step.until().test(client);
			} catch (RuntimeException e) {
				ok = false;
			}
			if (!ok && ++waited < step.maxWait()) return;
			steps.poll();
			waited = 0;
			check(ok, step.name());
			if (!ok && step.critical()) {
				LOG.error("UITEST: '{}' never happened (screen: {}), stopping", step.name(), screenName(client));
				steps.clear();
			}
			return;
		}
		steps.poll();
		LOG.info("UITEST step: {}", step.name());
		try {
			step.action().accept(client);
		} catch (Throwable t) {
			fail(step.name() + ": " + t);
			LOG.error("UITEST step '{}' threw", step.name(), t);
		}
		cooldown = step.waitAfter();
	}

	private void finish(MinecraftClient client) {
		finished = true;
		for (String w : warnings) LOG.warn("UITEST WARNING: {}", w);
		for (String f : failures) LOG.error("UITEST FAILURE: {}", f);
		LOG.info("UITEST RESULT: {} checks, {} failures, {} warnings", checks, failures.size(), warnings.size());
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(90_000);
			} catch (InterruptedException ignored) {
				return;
			}
			LOG.warn("UITEST: client didn't stop in time, halting");
			Runtime.getRuntime().halt(0);
		}, "uitest-watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
		client.scheduleStop();
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private void check(boolean ok, String what) {
		checks++;
		if (ok) LOG.info("UITEST ok: {}", what);
		else fail(what);
	}

	private void fail(String what) {
		failures.add(what);
		LOG.error("UITEST FAIL: {}", what);
	}

	private static String screenName(MinecraftClient c) {
		return c.currentScreen == null ? "none" : c.currentScreen.getClass().getSimpleName();
	}

	/**
	 * Logs every widget of the open screen and reports overlaps, widgets outside the screen and
	 * labels wider than their button. {@code onlyFor}: only check this widget (vanilla screens).
	 */
	private void lint(MinecraftClient c, String label, @Nullable String onlyFor) {
		Screen s = c.currentScreen;
		checks++;
		if (s == null) {
			fail(label + ": no screen open");
			return;
		}
		List<ClickableWidget> widgets = new ArrayList<>();
		for (Element e : s.children()) {
			if (e instanceof ClickableWidget w && w.visible) widgets.add(w);
		}
		StringBuilder sb = new StringBuilder();
		for (ClickableWidget w : widgets) {
			String msg = w.getMessage().getString();
			sb.append(String.format("%n    %-20s %-38s %4d,%-4d %3dx%-3d%s", w.getClass().getSimpleName(), "'" + msg + "'",
					w.getX(), w.getY(), w.getWidth(), w.getHeight(), w.active ? "" : " (disabled)"));
			if (onlyFor != null && !msg.equals(onlyFor)) continue;
			if (w.getX() < 0 || w.getY() < 0 || w.getRight() > s.width || w.getBottom() > s.height) {
				fail(label + ": '" + msg + "' is partly off screen");
			}
			if (w instanceof PressableWidget) {
				int tw = c.textRenderer.getWidth(w.getMessage());
				if (tw > w.getWidth() - 8) warnings.add(label + ": label doesn't fit: '" + msg + "' needs " + tw + "px, has " + (w.getWidth() - 8));
			}
		}
		for (int i = 0; i < widgets.size(); i++) {
			for (int j = i + 1; j < widgets.size(); j++) {
				ClickableWidget a = widgets.get(i);
				ClickableWidget b = widgets.get(j);
				String am = a.getMessage().getString();
				String bm = b.getMessage().getString();
				if (onlyFor != null && !am.equals(onlyFor) && !bm.equals(onlyFor)) continue;
				if (a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()) {
					fail(label + ": '" + am + "' overlaps '" + bm + "'");
				}
			}
		}
		LOG.info("UITEST layout [{}] {} {}x{} (gui scale {}):{}", label, s.getClass().getSimpleName(), s.width, s.height,
				c.getWindow().getScaleFactor(), sb);
	}

	private static void shot(MinecraftClient c, String name) {
		ScreenshotRecorder.saveScreenshot(c.runDirectory, "uitest-" + name + ".png", c.getFramebuffer(), msg -> {
		});
	}

	private static void setGuiScale(MinecraftClient c, int scale) {
		c.options.getGuiScale().setValue(scale);
		c.onResolutionChanged();
	}

	@Nullable
	private static PressableWidget findPressable(MinecraftClient c, String label) {
		if (c.currentScreen == null) return null;
		for (Element e : c.currentScreen.children()) {
			if (e instanceof PressableWidget p && p.visible && p.getMessage().getString().equals(label)) return p;
		}
		return null;
	}

	private void press(MinecraftClient c, String label) {
		PressableWidget p = findPressable(c, label);
		if (p == null) {
			fail("no button '" + label + "' on " + screenName(c));
			return;
		}
		if (!p.active) LOG.info("UITEST note: pressing disabled button '{}'", label);
		p.onPress();
	}

	@Nullable
	private static CyclingButtonWidget<?> cycling(MinecraftClient c, String prefix) {
		if (c.currentScreen == null) return null;
		for (Element e : c.currentScreen.children()) {
			if (e instanceof CyclingButtonWidget<?> b && b.getMessage().getString().startsWith(prefix)) return b;
		}
		return null;
	}

	private static TextFieldWidget firstTextField(MinecraftClient c) {
		if (c.currentScreen != null) {
			for (Element e : c.currentScreen.children()) {
				if (e instanceof TextFieldWidget f) return f;
			}
		}
		throw new IllegalStateException("no text field on " + screenName(c));
	}

	private static boolean listHas(String name) {
		JsonObject list = GuiClient.lastList();
		if (list == null || !list.has("companions")) return false;
		for (JsonElement e : list.getAsJsonArray("companions")) {
			if (e.getAsJsonObject().get("name").getAsString().equals(name)) return true;
		}
		return false;
	}

	private static ServerPlayerEntity player(MinecraftServer server, MinecraftClient c) {
		UUID id = c.player.getUuid();
		ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
		if (sp == null) throw new IllegalStateException("no server player");
		return sp;
	}

	private static CompanionEntity entity(MinecraftServer server) {
		CompanionBrain b = CompanionManager.get().brain(NAME);
		CompanionEntity e = b == null ? null : CompanionManager.findEntity(server, b);
		if (e == null) throw new IllegalStateException("companion entity not found");
		return e;
	}

	/** Runs something on the integrated server thread and waits for the result. */
	private static <T> T onServer(MinecraftClient c, Function<MinecraftServer, T> f) {
		IntegratedServer server = c.getServer();
		if (server == null) throw new IllegalStateException("no integrated server");
		try {
			return server.submit((Supplier<T>) () -> f.apply(server)).get(20, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw new RuntimeException(e.getCause());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
