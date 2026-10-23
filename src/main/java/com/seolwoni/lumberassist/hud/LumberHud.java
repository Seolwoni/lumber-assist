package com.seolwoni.lumberassist.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.seolwoni.assistcore.hud.AssistHud;
import com.seolwoni.assistcore.hud.HudBox;
import com.seolwoni.assistcore.hud.HudBox.Seg;
import com.seolwoni.lumberassist.config.LumberConfig;
import com.seolwoni.lumberassist.lumber.Area;
import com.seolwoni.lumberassist.lumber.Lumberjack;
import com.seolwoni.lumberassist.lumber.Trees;

/** Two HUD boxes: status and wood statistics. Drawing, moving and hiding come from Assist Core. */
public final class LumberHud {
	private static final int GOLD = HudBox.GOLD, WHITE = HudBox.WHITE, GRAY = HudBox.GRAY, GREEN = HudBox.GREEN, RED = HudBox.RED;

	public static final HudBox STATUS = new HudBox() {
		@Override
		public boolean enabled() {
			return LumberConfig.get().modEnabled && LumberConfig.get().statusHud;
		}

		@Override
		public List<List<Seg>> lines() {
			return statusLines();
		}

		@Override
		public float x() {
			return LumberConfig.get().hudX;
		}

		@Override
		public float y() {
			return LumberConfig.get().hudY;
		}

		@Override
		public float scale() {
			return LumberConfig.get().hudScale;
		}

		@Override
		public void setPos(float x, float y) {
			LumberConfig.get().hudX = x;
			LumberConfig.get().hudY = y;
		}

		@Override
		protected void storeScale(float s) {
			LumberConfig.get().hudScale = s;
		}

		@Override
		public int bgAlpha() {
			return LumberConfig.get().hudBgAlpha;
		}

		@Override
		public void save() {
			LumberConfig.save();
		}
	};

	public static final HudBox STATS = new HudBox() {
		@Override
		public boolean enabled() {
			return LumberConfig.get().modEnabled && LumberConfig.get().statsHud;
		}

		@Override
		public List<List<Seg>> lines() {
			return statsLines();
		}

		@Override
		public float x() {
			return LumberConfig.get().statsX;
		}

		@Override
		public float y() {
			return LumberConfig.get().statsY;
		}

		@Override
		public float scale() {
			return LumberConfig.get().statsScale;
		}

		@Override
		public void setPos(float x, float y) {
			LumberConfig.get().statsX = x;
			LumberConfig.get().statsY = y;
		}

		@Override
		protected void storeScale(float s) {
			LumberConfig.get().statsScale = s;
		}

		@Override
		public int bgAlpha() {
			return LumberConfig.get().hudBgAlpha;
		}

		@Override
		public void save() {
			LumberConfig.save();
		}
	};

	private LumberHud() {
	}

	public static void register() {
		AssistHud.register(STATUS);
		AssistHud.register(STATS);
	}

	private static List<List<Seg>> statusLines() {
		LumberConfig cfg = LumberConfig.get();
		List<List<Seg>> out = new ArrayList<>();
		out.add(List.of(new Seg("Lumber Assist", GOLD)));
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("상태: "), GOLD), new Seg(Lumberjack.status(), cfg.running ? GREEN : RED)));
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("구역: "), GOLD), new Seg(cfg.areas.size() + com.seolwoni.assistcore.i18n.Tr.t("개"), WHITE),
				new Seg(com.seolwoni.assistcore.i18n.Tr.t("  상자: "), GOLD), new Seg(cfg.chests.size() + com.seolwoni.assistcore.i18n.Tr.t("개"), WHITE)));
		if (cfg.running && Lumberjack.waitSecondsLeft() < 0) {
			out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("이번 바퀴: "), GOLD), new Seg(Lumberjack.treesThisRound() + com.seolwoni.assistcore.i18n.Tr.t("그루"), WHITE)));
		}
		if (Area.hasPending()) out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("반대쪽 모서리를 지정하세요"), 0xFF55FFFF)));
		return out;
	}

	private static List<List<Seg>> statsLines() {
		LumberConfig cfg = LumberConfig.get();
		LumberStats st = LumberStats.shown();
		List<List<Seg>> out = new ArrayList<>();
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("벌목 통계 "), GOLD), new Seg(cfg.statsTotals ? com.seolwoni.assistcore.i18n.Tr.t("(누적)") : com.seolwoni.assistcore.i18n.Tr.t("(이번 세션)"), GRAY)));
		long total = st.totalLogs();
		long now = System.currentTimeMillis();
		List<Map.Entry<String, LumberStats.Entry>> list = new ArrayList<>(st.logs.entrySet());
		list.sort(Comparator.comparingLong((Map.Entry<String, LumberStats.Entry> e) -> e.getValue().count).reversed());
		if (list.isEmpty()) out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("  아직 벤 나무가 없어요"), GRAY)));
		for (Map.Entry<String, LumberStats.Entry> e : list) {
			LumberStats.Entry v = e.getValue();
			out.add(List.of(new Seg(ago(now - v.lastMs) + " | ", GRAY),
					new Seg(Trees.label(e.getKey()), Trees.color(e.getKey())),
					new Seg(": " + v.count, WHITE),
					new Seg(" (" + (total <= 0 ? "0%" : String.format("%.1f%%", v.count * 100.0 / total)) + ")", GRAY)));
		}
		double hours = st.ticks / 20.0 / 3600.0;
		long perHour = hours > 0.01 ? Math.round(total / hours) : 0;
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("총 원목: "), GOLD), new Seg(total + "", WHITE), new Seg(" [" + perHour + com.seolwoni.assistcore.i18n.Tr.t("/시간]"), GRAY)));
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("나무: "), GOLD), new Seg(st.trees + com.seolwoni.assistcore.i18n.Tr.t("그루"), WHITE), new Seg(com.seolwoni.assistcore.i18n.Tr.t("  심은 묘목: "), GOLD), new Seg(st.planted + "", WHITE)));
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("바퀴: "), GOLD), new Seg(st.rounds + "", WHITE), new Seg(com.seolwoni.assistcore.i18n.Tr.t("  보관: "), GOLD), new Seg(st.trips + com.seolwoni.assistcore.i18n.Tr.t("번"), WHITE)));
		out.add(List.of(new Seg(com.seolwoni.assistcore.i18n.Tr.t("벌목 시간: "), GOLD), new Seg(time(st.ticks / 20), WHITE)));
		return out;
	}

	private static String ago(long ms) {
		long s = ms / 1000;
		if (s < 60) return s + "s";
		if (s < 3600) return String.format("%.1fm", s / 60.0);
		if (s < 86400) return String.format("%.1fh", s / 3600.0);
		return String.format("%.1fd", s / 86400.0);
	}

	private static String time(long s) {
		long h = s / 3600, m = (s % 3600) / 60;
		return h > 0 ? h + "h " + m + "m" : m + "m " + (s % 60) + "s";
	}
}
