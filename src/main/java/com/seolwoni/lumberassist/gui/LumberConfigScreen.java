package com.seolwoni.lumberassist.gui;

import com.seolwoni.assistcore.gui.PickerScreen;

import com.seolwoni.assistcore.gui.IntSlider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.seolwoni.lumberassist.config.LumberConfig;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.lumberassist.config.LumberConfig.AreaEntry;
import com.seolwoni.lumberassist.lumber.Lumberjack;
import com.seolwoni.lumberassist.lumber.Trees;
import com.seolwoni.lumberassist.hud.LumberStats;
import com.seolwoni.assistcore.hud.HudEditScreen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Lumber Assist settings (opened from Mod Menu). Start/stop at the very top. */
public class LumberConfigScreen extends Screen {
	private static final String[] TABS = {"기본", "구역", "나무", "보관", "안전·음식", "HUD"};
	private static final List<String> ITEM_PRESETS = List.of(
			"minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log", "minecraft:acacia_log",
			"minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log", "minecraft:pale_oak_log",
			"minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling", "minecraft:apple", "minecraft:stick");
	private static final int ROW = 24;
	private static int tab = 0;

	private final Screen parent;
	private final List<Object[]> labels = new ArrayList<>();
	private boolean confirmAreas, confirmChests, confirmStats;

	public LumberConfigScreen(Screen parent) {
		super(Component.literal("Lumber Assist 설정"));
		this.parent = parent;
	}

	private LumberConfig c() {
		return LumberConfig.get();
	}

	@Override
	protected void init() {
		labels.clear();
		int cx = this.width / 2, left = cx - 155, right = cx + 5;

		addRenderableWidget(Button.builder(toggleText("모드 전체", c().modEnabled), b -> {
			c().modEnabled = !c().modEnabled;
			if (!c().modEnabled && c().running) Lumberjack.stop("모드를 껐어요", null, false);
			LumberConfig.save();
			rebuildWidgets();
		}).bounds(left, 22, 150, 20).build());
		Button run = Button.builder(Component.literal(c().running ? "벌목 정지" : "벌목 시작").withColor(c().running ? 0xFFFF7777 : 0xFF77FF77), b -> {
			if (c().running) {
				Lumberjack.stop("벌목을 멈췄어요", null, false);
				rebuildWidgets();
			} else if (Lumberjack.start(this.minecraft)) {
				this.minecraft.gui.setScreen(null);
			}
		}).bounds(right, 22, 150, 20).build();
		run.active = c().modEnabled && this.minecraft.player != null;
		addRenderableWidget(run);

		int tw = (310 - 2 * 2) / 3;
		for (int i = 0; i < TABS.length; i++) {
			final int idx = i;
			Button tb = Button.builder(Component.literal(TABS[i]), b -> {
				tab = idx;
				rebuildWidgets();
			}).bounds(left + (i % 3) * (tw + 2), i < 3 ? 46 : 68, tw, 20).build();
			tb.active = i != tab;
			addRenderableWidget(tb);
		}

		int y = 96;
		switch (tab) {
			case 0 -> basic(left, right, y);
			case 1 -> areas(left, right, y);
			case 2 -> trees(left, right, y);
			case 3 -> storage(left, right, y);
			case 4 -> safety(left, right, y);
			default -> hud(left, right, y);
		}
		addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose())
				.bounds(cx - 75, this.height - 26, 150, 20).build());
	}

	private void basic(int left, int right, int y) {
		LumberConfig c = c();
		slider(left, y, 150, "바퀴 사이 대기", "분", 0, 60, c.waitMinutes, v -> c.waitMinutes = v);
		slider(right, y, 150, "남길 묘목", "개", 0, 256, c.saplingKeep, v -> c.saplingKeep = v);
		y += ROW;
		slider(left, y, 150, "최대 높이", "칸", 4, 64, c.maxHeight, v -> c.maxHeight = v);
		slider(right, y, 150, "도끼 보호 내구도", "", 0, 100, c.protectDurability, v -> c.protectDurability = v);
		y += ROW;
		toggle(left, y, 150, "묘목 다시 심기", () -> c.replant, v -> c.replant = v);
		toggle(right, y, 150, "떨어진 것 줍기", () -> c.collectDrops, v -> c.collectDrops = v);
		y += ROW;
		toggle(left, y, 150, "사람 같은 딜레이", () -> c.humanDelay, v -> c.humanDelay = v);
		y += ROW + 4;
		note("구역 안의 나무를 통째로 베고 묘목을 다시 심어요. 높은 나무는 발밑에 흙·돌을 쌓고 올라가요.", y);
		note("끝나면 떨어진 묘목·사과를 줍고 상자에 넣은 뒤 대기해요.", y + 12);
	}

	private void areas(int left, int right, int y) {
		LumberConfig c = c();
		addRenderableWidget(Button.builder(Component.literal(confirmAreas ? "한 번 더 누르면 초기화" : "구역 전체 초기화"), b -> {
			if (!confirmAreas) {
				confirmAreas = true;
				rebuildWidgets();
				return;
			}
			confirmAreas = false;
			c.areas.clear();
			c.spots.clear();
			LumberConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 310, 20).build());
		y += ROW;
		if (c.areas.isEmpty()) note("도끼를 들고 구역의 두 모서리를 Shift + 우클릭하세요", y + 6);
		int max = Math.max(1, (this.height - y - 40) / 22);
		for (int i = 0; i < Math.min(c.areas.size(), max); i++) {
			AreaEntry f = c.areas.get(i);
			labels.add(new Object[] {f.name + "  " + f.sizeX() + "x" + f.sizeZ() + "  (" + f.minX() + ", " + f.y + ", " + f.minZ() + ")",
					left, y + 6, 0xFFFFFFFF, false});
			addRenderableWidget(Button.builder(Component.literal("삭제"), b -> {
				c.areas.remove(f);
				c.spots.removeIf(s -> f.contains(s.x, s.z, 0));
				LumberConfig.save();
				rebuildWidgets();
			}).bounds(left + 260, y, 50, 20).build());
			y += 22;
		}
	}

	private void trees(int left, int right, int y) {
		LumberConfig c = c();
		for (int i = 0; i < LumberConfig.TREES.size(); i++) {
			String kind = LumberConfig.TREES.get(i);
			int x = i % 2 == 0 ? left : right;
			toggle(x, y + (i / 2) * 22, 150, Trees.label(kind), () -> c.trees.contains(kind), v -> {
				if (v && !c.trees.contains(kind)) c.trees.add(kind);
				if (!v) c.trees.remove(kind);
			});
		}
	}

	private void storage(int left, int right, int y) {
		LumberConfig c = c();
		toggle(left, y, 150, "보관하기", () -> c.storeEnabled, v -> c.storeEnabled = v);
		addRenderableWidget(Button.builder(Component.literal(confirmChests ? "한 번 더 누르면 초기화" : "상자 전체 초기화"), b -> {
			if (!confirmChests) {
				confirmChests = true;
				rebuildWidgets();
				return;
			}
			confirmChests = false;
			c.chests.clear();
			LumberConfig.save();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
		y += ROW;
		if (c.chests.isEmpty()) note("상자를 바라보고 K로 지정하세요 (최대 10개)", y + 6);
		int max = Math.max(1, (this.height - y - 40) / 22);
		int cx = this.width / 2;
		for (int i = 0; i < Math.min(c.chests.size(), max); i++) {
			ChestEntry e = c.chests.get(i);
			int idx = i;
			labels.add(new Object[] {(i + 1) + "번  " + e.x + ", " + e.y + ", " + e.z, left, y + 6, 0xFFFFFFFF, false});
			String what = e.items.isEmpty() ? "나머지 전부" : e.items.size() + "종류";
			addRenderableWidget(Button.builder(Component.literal("담을 것: " + what), b ->
					this.minecraft.gui.setScreen(new PickerScreen(this, (idx + 1) + "번 상자에 담을 아이템", e.items, false, ITEM_PRESETS,
							"아무것도 고르지 않으면 나머지를 차례대로 담아요", LumberConfig::save))).bounds(cx - 20, y, 120, 20).build());
			addRenderableWidget(Button.builder(Component.literal("삭제"), b -> {
				c.chests.remove(e);
				LumberConfig.save();
				rebuildWidgets();
			}).bounds(cx + 105, y, 50, 20).build());
			y += 22;
		}
	}

	private void safety(int left, int right, int y) {
		LumberConfig c = c();
		toggle(left, y, 150, "몹 오면 정지", () -> c.stopOnMob, v -> c.stopOnMob = v);
		slider(right, y, 150, "감지 거리", "칸", 3, 24, c.mobRange, v -> c.mobRange = v);
		y += ROW;
		slider(left, y, 150, "체력 이하면 정지", "", 1, 19, c.minHealth, v -> c.minHealth = v);
		y += ROW;
		toggle(left, y, 150, "자동 밥", () -> c.autoEat, v -> c.autoEat = v);
		slider(right, y, 150, "배고픔 기준", "/20", 1, 19, c.eatThreshold, v -> c.eatThreshold = v);
	}

	private void hud(int left, int right, int y) {
		LumberConfig c = c();
		addRenderableWidget(Button.builder(Component.literal("HUD 위치 편집"),
				b -> this.minecraft.gui.setScreen(new HudEditScreen(this))).bounds(left, y, 310, 20).build());
		y += ROW;
		toggle(left, y, 150, "상태 HUD", () -> c.statusHud, v -> c.statusHud = v);
		toggle(right, y, 150, "통계 HUD", () -> c.statsHud, v -> c.statsHud = v);
		y += ROW;
		slider(left, y, 150, "상태 크기", "%", 50, 300, Math.round(c.hudScale * 100), v -> c.hudScale = v / 100f);
		slider(right, y, 150, "통계 크기", "%", 50, 300, Math.round(c.statsScale * 100), v -> c.statsScale = v / 100f);
		y += ROW;
		slider(left, y, 150, "배경 투명도", "", 0, 255, c.hudBgAlpha, v -> c.hudBgAlpha = v);
		addRenderableWidget(Button.builder(Component.literal("통계 표시: " + (c.statsTotals ? "누적" : "이번 세션")), b -> {
			c.statsTotals = !c.statsTotals;
			LumberConfig.save();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal("세션 통계 초기화"), b -> {
			LumberStats.session.reset();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal(confirmStats ? "한 번 더 누르면 초기화" : "누적 통계 초기화"), b -> {
			if (!confirmStats) {
				confirmStats = true;
				rebuildWidgets();
				return;
			}
			confirmStats = false;
			LumberStats.total.reset();
			LumberStats.saveTotal();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
	}

	private void slider(int x, int y, int w, String label, String unit, int min, int max, int cur, Consumer<Integer> set) {
		addRenderableWidget(new IntSlider(x, y, w, label, unit, min, max, cur, v -> {
			set.accept(v);
			LumberConfig.save();
		}));
	}

	private void toggle(int x, int y, int w, String label, Supplier<Boolean> get, Consumer<Boolean> set) {
		addRenderableWidget(Button.builder(toggleText(label, get.get()), b -> {
			boolean v = !get.get();
			set.accept(v);
			LumberConfig.save();
			b.setMessage(toggleText(label, v));
		}).bounds(x, y, w, 20).build());
	}

	private static Component toggleText(String label, boolean on) {
		return Component.literal(label + ": ").append(on
				? Component.literal("켜짐").withColor(0xFF55FF55)
				: Component.literal("꺼짐").withColor(0xFFFF5555));
	}

	private void note(String text, int y) {
		labels.add(new Object[] {text, this.width / 2, y, 0xFFAAAAAA, true});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		for (Object[] l : labels) {
			if ((boolean) l[4]) g.centeredText(this.font, Component.literal((String) l[0]), (int) l[1], (int) l[2], (int) l[3]);
			else g.text(this.font, Component.literal((String) l[0]).getVisualOrderText(), (int) l[1], (int) l[2], (int) l[3], true);
		}
	}

	@Override
	public void onClose() {
		LumberConfig.save();
		this.minecraft.gui.setScreen(parent);
	}
}
