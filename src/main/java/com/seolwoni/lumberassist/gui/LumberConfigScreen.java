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
	private static final String[] TABS = {com.seolwoni.assistcore.i18n.Tr.t("기본"), com.seolwoni.assistcore.i18n.Tr.t("구역"), com.seolwoni.assistcore.i18n.Tr.t("나무"), com.seolwoni.assistcore.i18n.Tr.t("보관"), com.seolwoni.assistcore.i18n.Tr.t("안전·음식"), "HUD"};
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
		super(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("Lumber Assist 설정")));
		this.parent = parent;
	}

	private LumberConfig c() {
		return LumberConfig.get();
	}

	@Override
	protected void init() {
		labels.clear();
		int cx = this.width / 2, left = cx - 155, right = cx + 5;

		addRenderableWidget(Button.builder(toggleText(com.seolwoni.assistcore.i18n.Tr.t("모드 전체"), c().modEnabled), b -> {
			c().modEnabled = !c().modEnabled;
			if (!c().modEnabled && c().running) Lumberjack.stop(com.seolwoni.assistcore.i18n.Tr.t("모드를 껐어요"), null, false);
			LumberConfig.save();
			rebuildWidgets();
		}).bounds(left, 22, 100, 20).build());
		if (c().running) {
			// Running: stop right here, or come down, store at the chests first and stop there.
			addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("정지")).withColor(0xFFFF7777), b -> {
				Lumberjack.stop(com.seolwoni.assistcore.i18n.Tr.t("벌목을 멈췄어요"), null, false);
				rebuildWidgets();
			}).bounds(left + 105, 22, 48, 20).build());
			addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("돌아가기")).withColor(0xFFFFDD77), b -> {
				Lumberjack.returnHome(this.minecraft);
				this.minecraft.gui.setScreen(null);
			}).bounds(left + 157, 22, 48, 20).build());
		} else {
			Button run = Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("벌목 시작")).withColor(0xFF77FF77), b -> {
				if (Lumberjack.start(this.minecraft)) this.minecraft.gui.setScreen(null);
			}).bounds(left + 105, 22, 100, 20).build();
			run.active = c().modEnabled && this.minecraft.player != null;
			addRenderableWidget(run);
		}

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
		// The worker's journal (Assist Core): this mod's jobs, newest first.
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("일지")), b -> this.minecraft.gui.setScreen(new com.seolwoni.assistcore.gui.JournalScreen(this, "lumber")))
				.bounds(left + 210, 22, 48, 20).build());
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("완료")), b -> onClose())
				.bounds(left + 262, 22, 48, 20).build()); // up top: at the bottom it covered the last rows on small screens
	}

	private void basic(int left, int right, int y) {
		LumberConfig c = c();
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("바퀴 사이 대기"), com.seolwoni.assistcore.i18n.Tr.t("분"), 0, 60, c.waitMinutes, v -> c.waitMinutes = v);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("남길 묘목"), com.seolwoni.assistcore.i18n.Tr.t("개"), 0, 256, c.saplingKeep, v -> c.saplingKeep = v);
		y += ROW;
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("최대 높이"), com.seolwoni.assistcore.i18n.Tr.t("칸"), 4, 64, c.maxHeight, v -> c.maxHeight = v);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("도끼 보호 내구도"), "", 0, 100, c.protectDurability, v -> c.protectDurability = v);
		y += ROW;
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("묘목 다시 심기"), () -> c.replant, v -> c.replant = v);
		toggle(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("떨어진 것 줍기"), () -> c.collectDrops, v -> c.collectDrops = v);
		y += ROW;
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("사람 같은 딜레이"), () -> c.humanDelay, v -> c.humanDelay = v);
		y += ROW + 4;
		note(com.seolwoni.assistcore.i18n.Tr.t("구역 안의 나무를 통째로 베고 묘목을 다시 심어요. 높은 나무는 발밑에 흙·돌을 쌓고 올라가요."), y);
		note(com.seolwoni.assistcore.i18n.Tr.t("끝나면 떨어진 묘목·사과를 줍고 상자에 넣은 뒤 대기해요."), y + 12);
	}

	private void areas(int left, int right, int y) {
		LumberConfig c = c();
		addRenderableWidget(Button.builder(Component.literal(confirmAreas ? com.seolwoni.assistcore.i18n.Tr.t("한 번 더 누르면 초기화") : com.seolwoni.assistcore.i18n.Tr.t("구역 전체 초기화")), b -> {
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
		if (c.areas.isEmpty()) note(com.seolwoni.assistcore.i18n.Tr.t("도끼를 들고 구역의 두 모서리를 Shift + 우클릭하세요"), y + 6);
		int max = Math.max(1, (this.height - y - 40) / 22);
		for (int i = 0; i < Math.min(c.areas.size(), max); i++) {
			AreaEntry f = c.areas.get(i);
			labels.add(new Object[] {f.name + "  " + f.sizeX() + "x" + f.sizeZ() + "  (" + f.minX() + ", " + f.y + ", " + f.minZ() + ")",
					left, y + 6, 0xFFFFFFFF, false});
			addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("삭제")), b -> {
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
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("보관하기"), () -> c.storeEnabled, v -> c.storeEnabled = v);
		addRenderableWidget(Button.builder(Component.literal(confirmChests ? com.seolwoni.assistcore.i18n.Tr.t("한 번 더 누르면 초기화") : com.seolwoni.assistcore.i18n.Tr.t("상자 전체 초기화")), b -> {
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
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("보관 방식: ") + com.seolwoni.assistcore.storage.PortableStorage.LABELS[Math.max(0, Math.min(3, c.storeMode))]), b -> {
			c.storeMode = (c.storeMode + 1) % 4;
			LumberConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		labels.add(new Object[] {switch (c.storeMode) {
			case 1 -> com.seolwoni.assistcore.i18n.Tr.t("엔더 상자를 그 자리에 놓고 담아요 (실크터치로 회수)");
			case 2 -> com.seolwoni.assistcore.i18n.Tr.t("셜커 상자를 그 자리에 놓고 담은 뒤 다시 주워요");
			case 3 -> com.seolwoni.assistcore.i18n.Tr.t("엔더(실크터치) → 셜커 → 지정한 상자 순서");
			default -> com.seolwoni.assistcore.i18n.Tr.t("지정한 상자까지 걸어가서 담아요");
		}, right, y + 6, 0xFFAAAAAA, false});
		y += ROW;
		if (c.chests.isEmpty()) note(c.storeMode == 0 ? com.seolwoni.assistcore.i18n.Tr.t("상자를 바라보고 K로 지정하세요 (최대 10개)") : com.seolwoni.assistcore.i18n.Tr.t("상자도 지정해 두면 들고 다니는 보관함이 없을 때 써요"), y + 6);
		int max = Math.max(1, (this.height - y - 40) / 22);
		int cx = this.width / 2;
		for (int i = 0; i < Math.min(c.chests.size(), max); i++) {
			ChestEntry e = c.chests.get(i);
			int idx = i;
			labels.add(new Object[] {(i + 1) + com.seolwoni.assistcore.i18n.Tr.t("번  ") + e.x + ", " + e.y + ", " + e.z, left, y + 6, 0xFFFFFFFF, false});
			String what = e.items.isEmpty() ? com.seolwoni.assistcore.i18n.Tr.t("나머지 전부") : e.items.size() + com.seolwoni.assistcore.i18n.Tr.t("종류");
			addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("담을 것: ") + what), b ->
					this.minecraft.gui.setScreen(new PickerScreen(this, (idx + 1) + com.seolwoni.assistcore.i18n.Tr.t("번 상자에 담을 아이템"), e.items, false, ITEM_PRESETS,
							com.seolwoni.assistcore.i18n.Tr.t("아무것도 고르지 않으면 나머지를 차례대로 담아요"), LumberConfig::save))).bounds(cx - 20, y, 120, 20).build());
			addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("삭제")), b -> {
				c.chests.remove(e);
				LumberConfig.save();
				rebuildWidgets();
			}).bounds(cx + 105, y, 50, 20).build());
			y += 22;
		}
	}

	private void safety(int left, int right, int y) {
		LumberConfig c = c();
		addRenderableWidget(net.minecraft.client.gui.components.Button.builder(net.minecraft.network.chat.Component.literal(com.seolwoni.assistcore.i18n.Tr.t("몹: ") + new String[] {com.seolwoni.assistcore.i18n.Tr.t("피하기"), com.seolwoni.assistcore.i18n.Tr.t("피하고 싸우기"), com.seolwoni.assistcore.i18n.Tr.t("멈추기"), com.seolwoni.assistcore.i18n.Tr.t("무시")}[Math.max(0, Math.min(3, c.mobMode))]), b -> {
			c.mobMode = (c.mobMode + 1) % 4;
			c.stopOnMob = c.mobMode == 2;
			com.seolwoni.lumberassist.config.LumberConfig.save();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("감지 거리"), com.seolwoni.assistcore.i18n.Tr.t("칸"), 3, 24, c.mobRange, v -> c.mobRange = v);
		y += ROW;
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("체력 이하면 정지"), "", 1, 19, c.minHealth, v -> c.minHealth = v);
		y += ROW;
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("자동 밥"), () -> c.autoEat, v -> c.autoEat = v);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("배고픔 기준"), "/20", 1, 19, c.eatThreshold, v -> c.eatThreshold = v);
	}

	private void hud(int left, int right, int y) {
		LumberConfig c = c();
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("HUD 위치 편집")),
				b -> this.minecraft.gui.setScreen(new HudEditScreen(this))).bounds(left, y, 310, 20).build());
		y += ROW;
		toggle(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("상태 HUD"), () -> c.statusHud, v -> c.statusHud = v);
		toggle(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("통계 HUD"), () -> c.statsHud, v -> c.statsHud = v);
		y += ROW;
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("상태 크기"), "%", 50, 300, Math.round(c.hudScale * 100), v -> c.hudScale = v / 100f);
		slider(right, y, 150, com.seolwoni.assistcore.i18n.Tr.t("통계 크기"), "%", 50, 300, Math.round(c.statsScale * 100), v -> c.statsScale = v / 100f);
		y += ROW;
		slider(left, y, 150, com.seolwoni.assistcore.i18n.Tr.t("배경 투명도"), "", 0, 255, c.hudBgAlpha, v -> c.hudBgAlpha = v);
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("통계 표시: ") + (c.statsTotals ? com.seolwoni.assistcore.i18n.Tr.t("누적") : com.seolwoni.assistcore.i18n.Tr.t("이번 세션"))), b -> {
			c.statsTotals = !c.statsTotals;
			LumberConfig.save();
			rebuildWidgets();
		}).bounds(right, y, 150, 20).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal(com.seolwoni.assistcore.i18n.Tr.t("세션 통계 초기화")), b -> {
			LumberStats.session.reset();
			rebuildWidgets();
		}).bounds(left, y, 150, 20).build());
		addRenderableWidget(Button.builder(Component.literal(confirmStats ? com.seolwoni.assistcore.i18n.Tr.t("한 번 더 누르면 초기화") : com.seolwoni.assistcore.i18n.Tr.t("누적 통계 초기화")), b -> {
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
				? Component.literal(com.seolwoni.assistcore.i18n.Tr.t("켜짐")).withColor(0xFF55FF55)
				: Component.literal(com.seolwoni.assistcore.i18n.Tr.t("꺼짐")).withColor(0xFFFF5555));
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
