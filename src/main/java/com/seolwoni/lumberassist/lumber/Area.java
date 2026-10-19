package com.seolwoni.lumberassist.lumber;

import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.assistcore.util.Blocks2;
import com.seolwoni.lumberassist.config.LumberConfig;
import com.seolwoni.lumberassist.config.LumberConfig.AreaEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Marking tree areas: axe + Shift + right-click on two corners. */
public final class Area {
	public static final int MAX_SIZE = 64;
	private static BlockPos pending;

	private Area() {
	}

	/** Called for Shift + right-click with an axe. */
	public static void click(BlockPos clicked) {
		BlockState s = Blocks2.state(clicked);
		// Clicking the ground means the sapling layer is one above; clicking a sapling, log or air means it is that block.
		boolean onLayer = Trees.isLog(clicked) || s.isAir() || !s.blocksMotion();
		BlockPos layer = onLayer ? clicked : clicked.above();
		if (Trees.isLog(clicked)) {
			// A trunk: walk down to its bottom so the layer is the ground layer.
			BlockPos b = clicked;
			while (Trees.isLog(b.below()) && clicked.getY() - b.getY() < 64) b = b.below();
			layer = b;
		}
		if (pending == null) {
			pending = layer;
			Alerts.actionBar("첫 번째 모서리 지정: " + layer.getX() + ", " + layer.getZ() + " — 반대쪽 모서리를 Shift + 우클릭하세요", 0xFF55FFFF);
			Alerts.click();
			return;
		}
		BlockPos a = pending;
		pending = null;
		int sx = Math.abs(a.getX() - layer.getX()) + 1, sz = Math.abs(a.getZ() - layer.getZ()) + 1;
		if (sx > MAX_SIZE || sz > MAX_SIZE) {
			Alerts.actionBar("구역이 너무 커요 (최대 " + MAX_SIZE + "x" + MAX_SIZE + ")", 0xFFFF5555);
			Alerts.warning();
			return;
		}
		LumberConfig cfg = LumberConfig.get();
		AreaEntry e = new AreaEntry();
		e.x1 = a.getX();
		e.z1 = a.getZ();
		e.x2 = layer.getX();
		e.z2 = layer.getZ();
		e.y = Math.min(a.getY(), layer.getY());
		e.y2 = Math.max(a.getY(), layer.getY());
		e.name = "구역 " + (cfg.areas.size() + 1);
		cfg.areas.add(e);
		LumberConfig.save();
		String ys = e.minY() == e.maxY() ? "Y " + e.y : "Y " + e.minY() + "~" + e.maxY();
		Alerts.actionBar(e.name + " 지정 완료: " + sx + "x" + sz + " (" + ys + ", 땅 높낮이 ±" + Trees.MARGIN + "칸까지 찾아요)", 0xFF55FF55);
		Alerts.ding();
	}

	public static boolean hasPending() {
		return pending != null;
	}

	public static void cancelPending() {
		pending = null;
	}
}
