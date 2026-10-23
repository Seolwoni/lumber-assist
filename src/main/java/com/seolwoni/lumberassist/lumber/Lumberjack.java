package com.seolwoni.lumberassist.lumber;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.seolwoni.assistcore.engine.Action;
import com.seolwoni.assistcore.engine.ActionQueue;
import com.seolwoni.assistcore.engine.WaitAction;
import com.seolwoni.assistcore.util.Aim;
import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.assistcore.util.Blocks2;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.lumberassist.config.LumberConfig;
import com.seolwoni.lumberassist.config.LumberConfig.AreaEntry;
import com.seolwoni.lumberassist.config.LumberConfig.Spot;
import com.seolwoni.lumberassist.hud.LumberStats;
import com.seolwoni.lumberassist.lumber.Trees.Tree;
import com.seolwoni.lumberassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runs lumber rounds: fell every tree in the marked areas (climbing tall trunks on a pillar and digging
 * back down), put saplings back, pick up what fell, store the wood, wait, go again.
 */
public final class Lumberjack {
	static final int RUN = Action.RUN, DONE = Action.DONE, FAIL = Action.FAIL;
	static final double REACH = 4.4;

	private enum Phase { ROUND, STORE, WAIT }

	private static final ActionQueue queue = new ActionQueue(a -> {
		if (a.failMode() == Action.STOP) stop(com.seolwoni.assistcore.i18n.Tr.t("벌목을 멈췄어요"), a.failReason(), true);
	});
	private static Phase phase = Phase.ROUND;
	private static String status = com.seolwoni.assistcore.i18n.Tr.t("꺼짐");
	private static long ticks, waitUntil;
	private static int nextPause, nextLook, walkFails;
	private static boolean spotsDone, collected, stored;
	/** Trees already tried this round (their first base), so a tree we cannot finish is not retried forever. */
	private static final Set<BlockPos> tried = new HashSet<>();
	/** Tree kinds seen this round, so the storage trip knows which saplings to bring. */
	static final Set<String> seenKinds = new HashSet<>();
	private static int treesThisRound;

	/** Why it last stopped, for the debug log and tests. */
	public static String lastStop = "";

	private Lumberjack() {
	}

	public static String status() {
		return status;
	}

	static void status(String s) {
		status = s;
	}

	public static long waitSecondsLeft() {
		return phase == Phase.WAIT ? Math.max(0, (waitUntil - ticks) / 20) : -1;
	}

	public static int treesThisRound() {
		return treesThisRound;
	}

	static void add(Action a) {
		queue.add(a);
	}

	static void pushFront(Action a) {
		queue.pushFront(a);
	}

	static void pushFront(List<? extends Action> list) {
		queue.pushFront(list);
	}

	// ------------------------------------------------------------------ start / stop

	/** "돌아가기" pressed while a tree was being cut: go back as soon as that tree is done. */
	private static boolean returnPending;

	public static boolean start(Minecraft mc) {
		returnPending = false;
		{
			var dc = com.seolwoni.lumberassist.config.LumberConfig.get();
			com.seolwoni.assistpath.defense.Defense.config.enabled = dc.mobMode <= 1;
			com.seolwoni.assistpath.defense.Defense.config.attack = dc.mobMode == 1;
			com.seolwoni.assistpath.defense.Defense.reset(mc);
		}
		LumberConfig cfg = LumberConfig.get();
		if (mc.player == null || mc.level == null) return false;
		if (cfg.areas.isEmpty()) {
			Alerts.title(com.seolwoni.assistcore.i18n.Tr.t("구역이 없어요"), com.seolwoni.assistcore.i18n.Tr.t("도끼를 들고 Shift + 우클릭으로 구역 모서리를 지정하세요"), 0xFFFF5555);
			Alerts.warning();
			return false;
		}
		queue.clear();
		beginRound();
		nextPause = Human.rand(1500, 4000);
		cfg.running = true;
		LumberConfig.save();
		Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("벌목 시작: 구역 ") + cfg.areas.size() + com.seolwoni.assistcore.i18n.Tr.t("개"), 0xFF55FF55);
		com.seolwoni.assistcore.log.UsageLog.start("lumber", com.seolwoni.assistcore.i18n.Tr.t("구역 ") + cfg.areas.size() + com.seolwoni.assistcore.i18n.Tr.t("개, 상자 ") + cfg.chests.size() + com.seolwoni.assistcore.i18n.Tr.t("개, 다시 심기 ") + (cfg.replant ? com.seolwoni.assistcore.i18n.Tr.t("켬") : com.seolwoni.assistcore.i18n.Tr.t("끔")));
		return true;
	}

	/**
	 * The "돌아가기" button (or its key): stop cutting, come down off the tree (clearing a pillar on the way),
	 * pick up what lies close by, store at the chests and stop. Switch on again to carry on.
	 */
	public static void returnHome(Minecraft mc) {
		LumberConfig cfg = LumberConfig.get();
		LocalPlayer p = mc.player;
		if (!cfg.running || p == null) return;
		// In the middle of a tree: finish that tree first (stopping half way up leaves its top floating in the air,
		// and it is not found as a tree again). Pressed again: go right away.
		if (!queue.isEmpty() && !returnPending) {
			returnPending = true;
			Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("이 나무만 마저 베고 돌아가요 (한 번 더 누르면 바로)"), 0xFFFFAA00);
			return;
		}
		returnPending = false;
		queue.clear();
		Body.release(mc);
		mc.options.keyShift.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		com.seolwoni.assistcore.log.UsageLog.event("lumber", com.seolwoni.assistcore.i18n.Tr.t("돌아가기 버튼: ") + p.blockPosition().toShortString());
		Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("하던 것을 멈추고 상자로 돌아가요"), 0xFFFFAA00);
		status(com.seolwoni.assistcore.i18n.Tr.t("돌아가는 중"));
		// Up a tree (or on a pillar): come down to the ground under us first.
		BlockPos f = p.blockPosition();
		for (int k = 1; k < 48; k++) {
			BlockPos b = f.below(k);
			if (Trees.soil(b)) {
				if (b.getY() + 1 < f.getY()) add(new DescendAction(b.getY() + 1));
				break;
			}
		}
		addComeDown(p);
		add((mc2, p2) -> {
			pushFront(new LocalCollectAction(p2.blockPosition()));
			return Action.DONE;
		});
		if (cfg.storeEnabled && !cfg.chests.isEmpty()) add(new Storage.TripAction());
		add((mc2, p2) -> {
			stop(com.seolwoni.assistcore.i18n.Tr.t("돌아왔어요"), cfg.storeEnabled && !cfg.chests.isEmpty() ? com.seolwoni.assistcore.i18n.Tr.t("상자에 보관하고 멈췄어요. 다시 켜면 이어서 해요") : com.seolwoni.assistcore.i18n.Tr.t("다시 켜면 이어서 해요"), true);
			return Action.DONE;
		});
	}

	public static void stop(String title, String sub, boolean alert) {
		lastStop = (title == null ? "" : title) + (sub == null ? "" : " / " + sub);
		com.seolwoni.lumberassist.hud.LumberStats ls = com.seolwoni.lumberassist.hud.LumberStats.session;
		com.seolwoni.assistcore.log.UsageLog.end("lumber", lastStop.isEmpty() ? com.seolwoni.assistcore.i18n.Tr.t("직접 껐어요") : lastStop, com.seolwoni.assistcore.i18n.Tr.t("나무 ") + ls.trees + com.seolwoni.assistcore.i18n.Tr.t(", 원목 ") + ls.totalLogs() + com.seolwoni.assistcore.i18n.Tr.t(", 심은 묘목 ") + ls.planted + com.seolwoni.assistcore.i18n.Tr.t(", 상자 왕복 ") + ls.trips
				+ com.seolwoni.assistcore.i18n.Tr.t("번 | ") + com.seolwoni.assistpath.AssistPath.Stats.summary());
		Minecraft mc = Minecraft.getInstance();
		LumberConfig cfg = LumberConfig.get();
		cfg.running = false;
		LumberConfig.save();
		queue.clear();
		Body.release(mc);
		mc.options.keyShift.setDown(false);
		mc.options.keyUse.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?> s && !(s instanceof InventoryScreen)) {
			mc.player.closeContainer();
		}
		status(com.seolwoni.assistcore.i18n.Tr.t("꺼짐"));
		LumberStats.saveTotal();
		if (title != null) {
			if (alert) {
				Alerts.title(title, sub, 0xFFFF5555);
				Alerts.warning();
			} else {
				Alerts.actionBar(title + (sub == null ? "" : " - " + sub), 0xFFFFAA00);
			}
		}
	}

	private static void beginRound() {
		phase = Phase.ROUND;
		tried.clear();
		seenKinds.clear();
		spotsDone = false;
		collected = false;
		stored = false;
		walkFails = 0;
		treesThisRound = 0;
		LumberStats.round();
	}

	// ------------------------------------------------------------------ tick

	public static void tick(Minecraft mc) {
		LumberConfig cfg = LumberConfig.get();
		LocalPlayer p = mc.player;
		if (!cfg.running) return;
		if (p == null || mc.level == null || mc.gameMode == null) {
			stop(null, null, false);
			return;
		}
		if (mc.isPaused()) return;
		if (!safety(mc, p, cfg)) return;
		// Defense (Assist Path): while a creeper, an arrow or a mob needs dealing with, it has the controls.
		com.seolwoni.assistpath.defense.Defense.config.region = null; // anywhere (only the miner limits it)
		if (cfg.mobMode <= 1 && com.seolwoni.assistpath.defense.Defense.guard(mc, p)) {
			String why = com.seolwoni.assistpath.defense.Defense.giveUp();
			if (why != null) {
				com.seolwoni.assistpath.defense.Defense.reset(mc);
				stop(why, com.seolwoni.assistcore.i18n.Tr.t("싸우기를 켜거나 안전한 곳에서 다시 켜주세요"), true);
			}
			return;
		}
		ticks++;
		if (phase != Phase.WAIT) LumberStats.tick();
		Human.humanLike = cfg.humanDelay;
		Human.sprintJump = true;
		Human.pace = Math.max(Human.pace, 1); // run on long stretches (Mine sets its own pace setting)
		if (queue.tick(mc, p)) return;
		if (returnPending) {
			returnPending = false;
			returnHome(mc);
			return;
		}
		plan(mc, p, cfg);
	}

	private static boolean safety(Minecraft mc, LocalPlayer p, LumberConfig cfg) {
		if (p.isDeadOrDying()) {
			BlockPos b = p.blockPosition();
			Alerts.chat("Lumber Assist", com.seolwoni.assistcore.i18n.Tr.t("사망 위치: ") + b.getX() + ", " + b.getY() + ", " + b.getZ());
			stop(com.seolwoni.assistcore.i18n.Tr.t("죽었어요"), com.seolwoni.assistcore.i18n.Tr.t("사망 위치를 채팅에 남겼어요"), true);
			return false;
		}
		if (p.isInLava() || p.isOnFire()) {
			stop(com.seolwoni.assistcore.i18n.Tr.t("위험! 불이나 용암"), com.seolwoni.assistcore.i18n.Tr.t("벌목을 멈췄어요"), true);
			return false;
		}
		if (p.getHealth() <= cfg.minHealth) {
			stop(com.seolwoni.assistcore.i18n.Tr.t("체력이 낮아요"), com.seolwoni.assistcore.i18n.Tr.t("벌목을 멈췄어요"), true);
			return false;
		}
		if (cfg.mobMode == 2 && ticks % 10 == 0) {
			AABB box = p.getBoundingBox().inflate(cfg.mobRange);
			for (Entity e : mc.level.getEntities(p, box)) {
				if (e instanceof Enemy && e.isAlive()) {
					stop(com.seolwoni.assistcore.i18n.Tr.t("몹이 다가와요"), e.getName().getString() + com.seolwoni.assistcore.i18n.Tr.t(" 근처에 있어요"), true);
					return false;
				}
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ planning

	private static void plan(Minecraft mc, LocalPlayer p, LumberConfig cfg) {
		switch (phase) {
			case WAIT -> {
				if (ticks >= waitUntil) {
					beginRound();
					return;
				}
				status(com.seolwoni.assistcore.i18n.Tr.t("다음 바퀴까지 ") + (waitSecondsLeft() / 60) + com.seolwoni.assistcore.i18n.Tr.t("분 ") + (waitSecondsLeft() % 60) + com.seolwoni.assistcore.i18n.Tr.t("초"));
				if (--nextLook <= 0) {
					nextLook = Human.rand(120, 400);
					float yaw = p.getYRot() + (float) Human.jitter(25);
					float pitch = Math.max(-10f, Math.min(30f, p.getXRot() + (float) Human.jitter(6)));
					Body.newTurn();
					add((m, pl) -> Body.turnTo(pl, yaw, pitch, 1.5f) ? DONE : RUN);
				}
				return;
			}
			case STORE -> {
				phase = Phase.WAIT;
				waitUntil = ticks + (long) cfg.waitMinutes * 1200L;
				nextLook = Human.rand(100, 300);
				return;
			}
			default -> {
			}
		}

		if (--nextPause <= 0) {
			nextPause = Human.rand(1500, 4000);
			add(new WaitAction(Human.rand(30, 100)));
			status(com.seolwoni.assistcore.i18n.Tr.t("잠깐 쉬는 중"));
			return;
		}
		if (Chores.plan(mc, p, cfg)) return;

		Tree next = nextTree(p, cfg);
		if (next != null) {
			tried.add(next.first());
			seenKinds.add(next.kind());
			status(com.seolwoni.assistcore.i18n.Tr.t("나무 베는 중 (") + Trees.label(next.kind()) + ")");
			add(new FellAction(next));
			return;
		}
		// Round end: pick up drops (saplings!), store the wood and fetch saplings, then plant the empty spots.
		if (!collected) {
			collected = true;
			if (cfg.collectDrops && planCollect(mc, p, cfg)) return;
		}
		if (!stored) {
			stored = true;
			for (Spot s : cfg.spots) {
				if (Blocks2.state(new BlockPos(s.x, s.y, s.z)).isAir()) seenKinds.add(s.tree);
			}
			if (cfg.storeEnabled && !cfg.chests.isEmpty()) {
				status(com.seolwoni.assistcore.i18n.Tr.t("상자에 보관하러 가는 중"));
				addComeDown(p);
				add(new Storage.TripAction());
				return;
			}
		}
		if (!spotsDone) {
			spotsDone = true;
			if (cfg.replant && planEmptySpots(p, cfg)) return;
		}
		phase = Phase.STORE;
	}

	private static Tree nextTree(LocalPlayer p, LumberConfig cfg) {
		Tree best = null;
		double bestD = Double.MAX_VALUE;
		for (AreaEntry a : cfg.areas) {
			for (Tree t : Trees.find(a)) {
				if (tried.contains(t.first())) continue;
				double d = p.blockPosition().distSqr(t.first());
				if (d < bestD) {
					bestD = d;
					best = t;
				}
			}
		}
		return best;
	}

	/** Puts saplings back where trees used to stand but nothing grows now. */
	private static boolean planEmptySpots(LocalPlayer p, LumberConfig cfg) {
		List<List<BlockPos>> groups = new ArrayList<>();
		List<String> kinds = new ArrayList<>();
		Set<BlockPos> spots = new HashSet<>();
		for (Spot s : cfg.spots) spots.add(new BlockPos(s.x, s.y, s.z));
		java.util.Map<Item, Integer> left = new java.util.HashMap<>();
		for (Spot s : cfg.spots) {
			BlockPos pos = new BlockPos(s.x, s.y, s.z);
			Item sap = Trees.sapling(s.tree);
			if (sap == null) continue;
			int have = left.computeIfAbsent(sap, k -> Inv.count(p, st -> st.is(k)));
			if (Trees.needsFour(s.tree)) {
				// Only the north-west corner of a free 2x2 of spots plants the square.
				List<BlockPos> sq = List.of(pos, pos.east(), pos.south(), pos.south().east());
				boolean ok = have >= 4;
				for (BlockPos b : sq) ok &= spots.contains(b) && Blocks2.state(b).isAir() && Trees.soil(b.below());
				if (!ok) continue;
				left.put(sap, have - 4);
				groups.add(sq);
			} else {
				if (have <= 0 || !Blocks2.state(pos).isAir() || !Trees.soil(pos.below())) continue;
				left.put(sap, have - 1);
				groups.add(List.of(pos));
			}
			kinds.add(s.tree);
		}
		if (groups.isEmpty()) return false;
		status(com.seolwoni.assistcore.i18n.Tr.t("빈 자리에 묘목 심는 중 (") + groups.size() + com.seolwoni.assistcore.i18n.Tr.t("곳)"));
		BlockPos from = p.blockPosition();
		for (int g = 0; g < groups.size(); g++) {
			List<BlockPos> cells = groups.get(g);
			BlockPos stand = standNear(cells, from);
			if (stand == null) continue;
			add(new WalkAction(stand, true));
			for (BlockPos c : cells) add(new PlantAction(c, Trees.sapling(kinds.get(g)), kinds.get(g)));
			from = stand;
		}
		return true;
	}

	/** Walks over to drops (saplings, apples, sticks, logs) lying in the areas. */
	private static boolean planCollect(Minecraft mc, LocalPlayer p, LumberConfig cfg) {
		List<BlockPos> stops = new ArrayList<>();
		for (AreaEntry a : cfg.areas) {
			AABB box = new AABB(a.minX() - 2, a.minY() - Trees.MARGIN - 2, a.minZ() - 2, a.maxX() + 3, a.maxY() + Trees.MARGIN + 4, a.maxZ() + 3);
			for (Entity e : mc.level.getEntities(p, box)) {
				if (!(e instanceof ItemEntity) || !e.isAlive()) continue;
				BlockPos at = e.blockPosition();
				boolean near = false;
				for (BlockPos s : stops) if (s.distManhattan(at) <= 2) near = true;
				if (near) continue;
				BlockPos stand = standNear(List.of(at), at);
				if (standable(at)) stand = at;
				if (stand != null) stops.add(stand);
				if (stops.size() >= 16) break;
			}
		}
		if (stops.isEmpty()) return false;
		status(com.seolwoni.assistcore.i18n.Tr.t("떨어진 묘목·사과 줍는 중"));
		BlockPos from = p.blockPosition();
		while (!stops.isEmpty()) {
			final BlockPos f = from;
			BlockPos n = stops.stream().min(Comparator.comparingDouble(s -> s.distSqr(f))).get();
			stops.remove(n);
			add(new WalkAction(n, true));
			add(new WaitAction(Human.rand(4, 10)));
			from = n;
		}
		return true;
	}

	/** A free cell on real ground (not on leaves or logs) near the player, a few blocks down at most. */
	static BlockPos groundCellNear(LocalPlayer p) {
		BlockPos me = p.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				for (int dy = -6; dy <= 1; dy++) {
					BlockPos c = me.offset(dx, dy, dz);
					if (!standable(c) || Trees.isLeaves(c.below()) || Trees.isLog(c.below())) continue;
					double d = dx * dx + dz * dz + Math.abs(dy) * 0.5;
					if (d < bestD) {
						bestD = d;
						best = c;
					}
				}
			}
		}
		return best;
	}

	/** Standing up on leaves or logs (after picking something up there): step down to the ground first. */
	static void addComeDown(LocalPlayer p) {
		BlockPos under = p.blockPosition().below();
		if (!Trees.isLeaves(under) && !Trees.isLog(under)) return;
		BlockPos g = groundCellNear(p);
		if (g != null) add(new WalkAction(g, true));
	}

	static void walkFailed() {
		if (++walkFails >= 6) stop(com.seolwoni.assistcore.i18n.Tr.t("길이 막혔어요"), com.seolwoni.assistcore.i18n.Tr.t("구역 안에 막힌 곳이 있는지 확인해주세요"), true);
	}

	static void walkOk() {
		walkFails = 0;
	}

	// ------------------------------------------------------------------ helpers

	/** A free cell next to (any of) the given trunk cells to stand in, nearest to {@code from}. */
	static BlockPos standNear(List<BlockPos> cells, BlockPos from) {
		Set<BlockPos> skip = new HashSet<>(cells);
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos c : cells) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int dy = -1; dy <= 1; dy++) {
						BlockPos q = c.offset(dx, dy, dz);
						if (skip.contains(q) || (dx == 0 && dz == 0)) continue;
						if (!standableOrClearable(q)) continue;
						double d = q.distSqr(from) + Math.abs(dy) * 4 + (dx != 0 && dz != 0 ? 1 : 0);
						if (d < bestD) {
							bestD = d;
							best = q;
						}
					}
				}
			}
		}
		return best;
	}

	/** Up to 8 natural leaves in reach of {@code stand}, then wait and pick up nearby drops. */
	static List<Action> leafDrops(LocalPlayer p, BlockPos stand) {
		Vec3 eye = new Vec3(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
		List<BlockPos> found = new ArrayList<>();
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = 0; dy <= 5; dy++) {
				for (int dz = -4; dz <= 4; dz++) {
					BlockPos b = stand.offset(dx, dy, dz);
					if (!Trees.isLeaves(b) || eye.distanceTo(Vec3.atCenterOf(b)) > REACH) continue;
					var st = Blocks2.state(b);
					if (st.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
							&& st.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)) continue;
					found.add(b);
				}
			}
		}
		found.sort(Comparator.comparingDouble(b -> eye.distanceTo(Vec3.atCenterOf(b))));
		List<Action> out = new ArrayList<>();
		for (int i = 0; i < Math.min(8, found.size()); i++) out.add(new MineBlockAction(found.get(i), null));
		if (out.isEmpty()) return out;
		out.add(new WaitAction(Human.delay(10, 16)));
		out.add(new LocalCollectAction(stand));
		return out;
	}

	/**
	 * After a tree (or in the middle of a tall one): picks up what fell around it before it can despawn (drops vanish
	 * after five minutes). Drops on the ground: walk over. Drops caught on leaves or vines: from the ground, cut the
	 * leaves holding them so they fall, then pick them up. Like a player tidying up under a tree.
	 */
	static final class TreeCollectAction implements Action {
		private final Tree tree;
		private final int groundY, pass;
		/** In the middle of a tree: only pick up what lies on the ground, no climbing to free caught logs. */
		private final boolean quick;

		TreeCollectAction(Tree tree, int groundY, int pass) {
			this(tree, groundY, pass, false);
		}

		TreeCollectAction(Tree tree, int groundY, int pass, boolean quick) {
			this.tree = tree;
			this.groundY = groundY;
			this.pass = pass;
			this.quick = quick;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		static AABB box(Tree tree, int groundY) {
			BlockPos f = tree.first();
			return new AABB(f.getX() - 7, groundY - 4, f.getZ() - 7, f.getX() + 9, groundY + LumberConfig.get().maxHeight + 10, f.getZ() + 9);
		}

		/** Something under the item that holds it up in the air: leaves or vines. */
		private static BlockPos holder(BlockPos at) {
			for (int d = 0; d <= 1; d++) {
				BlockPos b = at.below(d);
				if (Trees.isLeaves(b) || isSoft(b) && !Blocks2.isOpen(b)) return b;
			}
			return null;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			List<Action> todo = new ArrayList<>();
			List<BlockPos> visited = new ArrayList<>();
			BlockPos from = p.blockPosition();
			List<ItemEntity> items = new ArrayList<>();
			for (Entity e : mc.level.getEntities(p, box(tree, groundY))) if (e instanceof ItemEntity ie && ie.isAlive()) items.add(ie);
			final BlockPos f0 = from;
			items.sort(Comparator.comparingDouble(e -> e.blockPosition().distSqr(f0)));
			int stops = 0;
			for (ItemEntity ie : items) {
				if (stops >= 10) break;
				BlockPos at = ie.blockPosition();
				boolean done = false;
				for (BlockPos v : visited) if (v.distManhattan(at) <= 1) done = true;
				if (done) continue;
				BlockPos hold = holder(at);
				if (hold != null && (at.getY() > groundY + 1 || Trees.isLeaves(at.below()))) {
					// Caught up in the leaves: find a ground cell from where the holding leaves are in reach.
					BlockPos best = null;
					double bestD = Double.MAX_VALUE;
					for (int dx = -3; dx <= 3; dx++) {
						for (int dz = -3; dz <= 3; dz++) {
							for (int dy = -2; dy <= 2; dy++) {
								BlockPos c = new BlockPos(at.getX() + dx, groundY + dy, at.getZ() + dz);
								if (!standable(c)) continue;
								Vec3 eye = new Vec3(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
								if (eye.distanceTo(Vec3.atCenterOf(hold)) > REACH - 0.2) continue;
								double d = c.distSqr(from);
								if (d < bestD) {
									bestD = d;
									best = c;
								}
							}
						}
					}
					if (best == null && quick) continue;
					if (best == null) {
						// Too high to reach from the ground (four to six blocks up): build up one to three blocks
						// beside it, cut the leaf, and take the blocks back down, like a player would.
						int bestH = -1;
						for (int dx = -2; dx <= 2 && bestH < 0; dx++) {
							for (int dz = -2; dz <= 2 && bestH < 0; dz++) {
								for (int dy = -1; dy <= 1 && bestH < 0; dy++) {
									BlockPos c = new BlockPos(at.getX() + dx, groundY + dy, at.getZ() + dz);
									if (c.getX() == hold.getX() && c.getZ() == hold.getZ()) continue;
									if (!standable(c) || Trees.isLeaves(c.below())) continue;
									for (int h = 1; h <= 3; h++) {
										if (!Blocks2.isOpen(c.above(h + 1))) break;
										Vec3 eye = new Vec3(c.getX() + 0.5, c.getY() + h + 1.62, c.getZ() + 0.5);
										if (eye.distanceTo(Vec3.atCenterOf(hold)) <= REACH - 0.2) {
											best = c;
											bestH = h;
											break;
										}
									}
								}
							}
						}
						if (best == null) continue;
						todo.add(new WalkAction(best, true));
						for (int h = 0; h < bestH; h++) todo.add(new PillarUpAction());
						todo.add(new MineBlockAction(hold, null));
						todo.add(new WaitAction(Human.delay(10, 16)));
						todo.add(new DescendAction(best.getY()));
						todo.add(new WaitAction(Human.delay(6, 12)));
						todo.add(new LocalCollectAction(best));
						visited.add(at);
						from = best;
						stops++;
						continue;
					}
					todo.add(new WalkAction(best, true));
					todo.add(new MineBlockAction(hold, null));
					todo.add(new WaitAction(Human.delay(14, 22))); // let it fall
					todo.add(new LocalCollectAction(best));
					visited.add(at);
					from = best;
					stops++;
					continue;
				}
				if (Blocks2.isFluid(at) && mc.level.getFluidState(at).is(net.minecraft.tags.FluidTags.WATER)) {
					// In water: wade in when it is shallow (one or two deep), like a player would; deeper, just say where.
					BlockPos bottom = at;
					while (Blocks2.isFluid(bottom.below()) && at.getY() - bottom.getY() < 6) bottom = bottom.below();
					BlockPos top = at;
					while (Blocks2.isFluid(top.above()) && top.getY() - at.getY() < 6) top = top.above();
					int depth = top.getY() - bottom.getY() + 1;
					if (depth <= 2 && Blocks2.isSolid(bottom.below()) && Math.abs(bottom.getY() - groundY) <= 3) {
						todo.add(new WalkAction(bottom, true));
						todo.add(new WaitAction(Human.delay(6, 12)));
						visited.add(at);
						from = bottom;
						stops++;
					} else if (waterNoted.add(bottom.immutable())) {
						String where = bottom.getX() + ", " + bottom.getY() + ", " + bottom.getZ();
						Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("깊은 물에 빠진 원목은 두고 가요: ") + where, 0xFFFFAA55);
						com.seolwoni.assistcore.log.UsageLog.event("lumber", com.seolwoni.assistcore.i18n.Tr.t("깊은 물(") + depth + com.seolwoni.assistcore.i18n.Tr.t("칸)에 빠진 원목을 두고 감 (") + where + ")");
					}
					continue;
				}
				if (Math.abs(at.getY() - groundY) > 3) continue;
				BlockPos stand = standable(at) && !Trees.isLeaves(at.below()) ? at : standNear(List.of(at), at);
				if (stand == null || Trees.isLeaves(stand.below())) continue;
				todo.add(new WalkAction(stand, true));
				visited.add(at);
				from = stand;
				stops++;
			}
			if (todo.isEmpty()) {
				addComeDownFront(p);
				return DONE;
			}
			status(com.seolwoni.assistcore.i18n.Tr.t("나무 밑에 떨어진 것 줍는 중"));
			if (pass < 1) todo.add(new TreeCollectAction(tree, groundY, pass + 1, quick)); // things that fell from the cut leaves
			pushFront(todo);
			return DONE;
		}

		private static final Set<BlockPos> waterNoted = new HashSet<>();

		/** Ended up on leaves or logs (a log caught up there): step down to the ground before going on. */
		private static void addComeDownFront(LocalPlayer p) {
			BlockPos under = p.blockPosition().below();
			if (!Trees.isLeaves(under) && !Trees.isLog(under)) return;
			BlockPos g = groundCellNear(p);
			if (g != null) pushFront(new WalkAction(g, true));
		}

		/** Logs caught on leaves within reach from up here (while climbing): cut the leaf so they fall to the ground. */
		static List<Action> shakeLeaves(Minecraft mc, LocalPlayer p, Tree tree, int groundY) {
			List<Action> out = new ArrayList<>();
			for (Entity e : mc.level.getEntities(p, box(tree, groundY))) {
				if (!(e instanceof ItemEntity ie) || !ie.isAlive() || !Inv.isLogItem(ie.getItem())) continue;
				BlockPos hold = holder(ie.blockPosition());
				if (hold == null || ie.blockPosition().getY() <= groundY + 1 || eyeDist(p, hold) > REACH) continue;
				if (hold.equals(p.blockPosition().below())) continue;
				out.add(new MineBlockAction(hold, null));
				if (out.size() >= 3) break;
			}
			return out;
		}

		/** A dropped log lying low down for longer than {@code ticks}: time to go down and fetch it. */
		static boolean oldDrops(Minecraft mc, LocalPlayer p, Tree tree, int groundY, int ticks) {
			for (Entity e : mc.level.getEntities(p, box(tree, groundY))) {
				if (e instanceof ItemEntity ie && ie.isAlive() && ie.getAge() > ticks && Inv.isLogItem(ie.getItem())) return true;
			}
			return false;
		}
	}

	/** Picks up drops within a few blocks, then steps back. */
	static final class LocalCollectAction implements Action {
		private final BlockPos home;

		LocalCollectAction(BlockPos home) {
			this.home = home;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			List<Action> walks = new ArrayList<>();
			for (Entity e : mc.level.getEntities(p, new AABB(home).inflate(4, 3, 4))) {
				if (!(e instanceof ItemEntity) || !e.isAlive()) continue;
				BlockPos at = e.blockPosition();
				BlockPos stand = standable(at) && !Trees.isLeaves(at.below()) ? at : standNear(List.of(at), at);
				if (stand != null && !Trees.isLeaves(stand.below())) walks.add(new WalkAction(stand, true));
				if (walks.size() >= 6) break;
			}
			if (walks.isEmpty()) return DONE;
			walks.add(new WalkAction(home, true));
			pushFront(walks);
			return DONE;
		}
	}

	/** Feet and head free, something solid underneath. */
	static boolean standable(BlockPos feet) {
		for (BlockPos b : new BlockPos[] {feet, feet.above()}) {
			var st = Blocks2.state(b);
			if (st.blocksMotion() || !st.getFluidState().isEmpty()) return false;
		}
		return Blocks2.isSolid(feet.below()) || Trees.soil(feet.below());
	}

	/** Standable, or only leaves in the way (they get cleared). */
	static boolean standableOrClearable(BlockPos feet) {
		if (!Blocks2.isSolid(feet.below()) && !Trees.soil(feet.below())) return false;
		for (BlockPos b : new BlockPos[] {feet, feet.above()}) {
			if (Trees.isLog(b) || Blocks2.isFluid(b)) return false;
			if (!Blocks2.isOpen(b) && !Trees.isLeaves(b) && !isSoft(b)) return false;
		}
		return true;
	}

	/** Small plants and saplings: break them if they are in the way. */
	static boolean isSoft(BlockPos b) {
		var s = Blocks2.state(b);
		return !s.blocksMotion() && s.getFluidState().isEmpty();
	}

	static double eyeDist(LocalPlayer p, BlockPos b) {
		return p.getEyePosition().distanceTo(Vec3.atCenterOf(b));
	}

	// ------------------------------------------------------------------ actions

	/** Fells one tree: logs in reach from the ground, then climb on a pillar for the rest, dig down, replant. */
	/** What happened to each tree this session (for the tests and bug reports). */
	public static final List<String> fellLog = new ArrayList<>();

	static void note(Tree tree, String what, int left) {
		if (fellLog.size() < 60) fellLog.add(tree.kind() + " " + tree.bases().size() + "x at " + tree.first().toShortString() + ": " + what + " (" + left + " logs left)");
		if (left > 0) com.seolwoni.assistcore.log.UsageLog.event("lumber", com.seolwoni.assistcore.i18n.Tr.t("나무 남음: ") + tree.kind() + (tree.bases().size() == 4 ? " (2x2)" : "") + " " + tree.first().toShortString() + " — " + what + com.seolwoni.assistcore.i18n.Tr.t(", 원목 ") + left + com.seolwoni.assistcore.i18n.Tr.t("개"));
	}

	/**
	 * Gets next to a tree: lets Assist Path pick any reachable spot within two blocks of the trunk (on a slope
	 * the planned standing cell may be out of reach while the one beside it is fine), then the planned cell as before.
	 */
	static final class ApproachAction implements Action {
		private final Tree tree;
		private final BlockPos stand;
		private com.seolwoni.assistpath.walk.PathWalker walker;

		ApproachAction(Tree tree, BlockPos stand) {
			this.tree = tree;
			this.stand = stand;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (walker == null) {
				// Any free cell right next to the trunk (sideways or diagonally, a step up or down) will do.
				List<com.seolwoni.assistpath.goals.Goal> goals = new ArrayList<>();
				java.util.Set<BlockPos> bases = new java.util.HashSet<>(tree.bases());
				for (BlockPos b : tree.bases()) {
					for (int dx = -1; dx <= 1; dx++) {
						for (int dz = -1; dz <= 1; dz++) {
							for (int dy = -1; dy <= 1; dy++) {
								BlockPos c = b.offset(dx, dy, dz);
								if (bases.contains(c.atY(b.getY())) || !standable(c)) continue;
								goals.add(new com.seolwoni.assistpath.goals.GoalBlock(c));
							}
						}
					}
				}
				for (com.seolwoni.assistpath.goals.Goal g : goals) {
					if (g.isInGoal(com.seolwoni.assistpath.walk.PathWalker.feet(p))) return DONE;
				}
				if (goals.isEmpty()) {
					pushFront(new WalkAction(stand, true));
					return DONE;
				}
				walker = new com.seolwoni.assistpath.walk.PathWalker(new com.seolwoni.assistpath.goals.GoalComposite(goals.toArray(new com.seolwoni.assistpath.goals.Goal[0])));
			}
			int r = walker.tick(mc, p);
			if (r == RUN) return RUN;
			if (r == FAIL) pushFront(new WalkAction(stand, true));
			return DONE;
		}
	}

	static final class FellAction implements Action {
		private final Tree tree;
		private final int groundY;
		private List<BlockPos> logs;
		private BlockPos stand;
		private int stage, climbs, stuck, lastFeetY = Integer.MIN_VALUE, approaches, intoTries, fetches, shakes;
		private boolean leavesTried, counted, cleaned, swept;

		FellAction(Tree tree) {
			this.tree = tree;
			this.groundY = tree.first().getY();
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		private List<BlockPos> remaining() {
			List<BlockPos> out = new ArrayList<>();
			for (BlockPos b : logs) if (Trees.isLog(b)) out.add(b);
			return out;
		}

		private boolean inTrunk(LocalPlayer p) {
			return tree.bases().stream().anyMatch(b -> b.getX() == p.blockPosition().getX() && b.getZ() == p.blockPosition().getZ());
		}

		/**
		 * Close enough to the trunk to climb up beside it and still reach the logs: right next to it, or one
		 * block further out when the ground right beside it cannot be reached (a ledge, a steep slope).
		 */
		private boolean beside(LocalPlayer p) {
			for (BlockPos b : tree.bases()) {
				double dx = p.getX() - (b.getX() + 0.5), dz = p.getZ() - (b.getZ() + 0.5);
				if (Math.abs(dx) <= 2.6 && Math.abs(dz) <= 2.6) return true;
			}
			return false;
		}

		private boolean mineReachable(LocalPlayer p) {
			List<Action> digs = new ArrayList<>();
			BlockPos feet = p.blockPosition();
			for (BlockPos b : remaining()) {
				// Never dig the block we stand on while up on the trunk.
				if (b.equals(feet.below())) continue;
				if (eyeDist(p, b) <= REACH) digs.add(new MineBlockAction(b, tree.kind()));
			}
			if (digs.isEmpty()) return false;
			pushFront(digs);
			return true;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			LumberConfig cfg = LumberConfig.get();
			switch (stage) {
				case 0 -> {
					logs = Trees.logs(tree, 6, cfg.maxHeight + 8);
					if (logs.isEmpty()) return DONE;
					stand = standNear(tree.bases(), p.blockPosition());
					if (stand == null) {
						note(tree, "no place to stand next to it", logs.size());
						return DONE;
					}
					// A tree we cannot get to must not stop the whole job: the walk is quiet, the tree is skipped.
					pushFront(new ApproachAction(tree, stand));
					stage = 1;
					return RUN;
				}
				case 1 -> {
					// From the ground: everything in reach.
					if (mineReachable(p)) return RUN;
					List<BlockPos> left = remaining();
					if (left.isEmpty()) {
						note(tree, "all cut from the ground", 0);
						stage = 4;
						return RUN;
					}
					// The rest is higher up: step into the (now empty) trunk and climb.
					BlockPos into = null;
					for (BlockPos b : tree.bases()) {
						if (Blocks2.isOpen(b) && Blocks2.isOpen(b.above())) {
							into = b;
							break;
						}
					}
					if (into == null && approaches < 2) {
						// Nothing in reach and the trunk is still whole: we never got next to it (a slope, bushes). Go again.
						BlockPos near = standNear(tree.bases(), p.blockPosition());
						double far = tree.bases().stream().mapToDouble(b -> Math.sqrt(b.distToCenterSqr(p.position()))).min().orElse(0);
						if (near != null && far > 2.5) {
							approaches++;
							stand = near;
							pushFront(new ApproachAction(tree, near));
							return RUN;
						}
					}
					if (into == null && beside(p) && left.stream().anyMatch(b -> b.getY() + 0.5 > p.getEyeY() - 1)) {
						// The trunk is still whole at the bottom (a slope, or a big 2x2 trunk): climb up right beside it.
						status(com.seolwoni.assistcore.i18n.Tr.t("높은 나무에 올라가는 중"));
						stage = 2;
						return RUN;
					}
					if (into == null) {
						note(tree, beside(p) ? "no way into the trunk to climb" : "could not get next to it", left.size());
						stage = 4;
						return RUN;
					}
					status(com.seolwoni.assistcore.i18n.Tr.t("높은 나무에 올라가는 중"));
					pushFront(new WalkAction(into, true));
					stage = 2;
					return RUN;
				}
				case 2 -> {
					// Climbing: dig what is in reach, otherwise build up one block.
					if (mineReachable(p)) return RUN;
					List<BlockPos> left = remaining();
					double eyeY = p.getEyeY();
					boolean above = left.stream().anyMatch(b -> b.getY() + 0.5 > eyeY - 1);
					int height = p.blockPosition().getY() - groundY;
					if (!left.isEmpty() && above && !inTrunk(p) && intoTries++ < 2) {
						// Inside the trunk is the best place to climb (nothing in the way): try getting in again first.
						// Did not get into the trunk (bumped into a leaf or a step): try once more from the ground.
						stage = 1;
						return RUN;
					}
					if (left.isEmpty() || !above || height >= cfg.maxHeight || !(inTrunk(p) || beside(p))) {
						note(tree, left.isEmpty() ? "all cut" : !above ? "rest is out of reach below" : height >= cfg.maxHeight ? "max climb height " + cfg.maxHeight : "not next to the trunk", left.size());
						stage = 3;
						return RUN;
					}
					if (p.blockPosition().getY() == lastFeetY) {
						if (++stuck >= 3) {
							BlockPos f = p.blockPosition();
							note(tree, "stuck climbing at " + f.toShortString() + " (above: " + Blocks2.id(f.above(2)) + ", " + Blocks2.id(f.above(3))
									+ "; below: " + Blocks2.id(f.below()) + "; inTrunk=" + inTrunk(p) + ", onGround=" + p.onGround() + ")", left.size());
							stage = 3;
							return RUN;
						}
					} else {
						stuck = 0;
					}
					lastFeetY = p.blockPosition().getY();
					if (shakes < 24) {
						List<Action> shake = TreeCollectAction.shakeLeaves(mc, p, tree, groundY);
						if (!shake.isEmpty()) {
							shakes += shake.size();
							// Cutting leaves is not being stuck: the height check starts over after it.
							lastFeetY = Integer.MIN_VALUE;
							stuck = 0;
							pushFront(shake);
							return RUN;
						}
					}
					// A big tree takes a while: logs that fell early would vanish after five minutes on the ground.
					// Go down, pick them up, then climb again (the pillar is dug out on the way down).
					if (fetches < 3 && TreeCollectAction.oldDrops(mc, p, tree, groundY, 20 * 150)) {
						fetches++;
						status(com.seolwoni.assistcore.i18n.Tr.t("떨어진 원목 먼저 주우러 내려가는 중"));
						List<Action> down = new ArrayList<>();
						down.add(new DescendAction(groundY, tree));
						down.add(new TreeCollectAction(tree, groundY, 0, true));
						// Back to the trunk before climbing again (picking up may have taken us a few steps away).
						BlockPos back = standNear(tree.bases(), p.blockPosition());
						if (back != null) down.add(new ApproachAction(tree, back));
						pushFront(down);
						stuck = 0;
						lastFeetY = Integer.MIN_VALUE;
						intoTries = 0;
						stage = 1;
						return RUN;
					}
					BlockPos head = p.blockPosition().above(2);
					if (!Blocks2.isOpen(head)) {
						if (Trees.isLog(head) || Trees.isLeaves(head) || isSoft(head)) {
							pushFront(new MineBlockAction(head, Trees.isLog(head) ? tree.kind() : null));
							return RUN;
						}
						note(tree, "head blocked by " + Blocks2.id(head), left.size());
						stage = 3;
						return RUN;
					}
					climbs++;
					pushFront(new PillarUpAction());
					return RUN;
				}
				case 3 -> {
					if (p.blockPosition().getY() > groundY) {
						status(com.seolwoni.assistcore.i18n.Tr.t("나무에서 내려오는 중"));
						pushFront(new DescendAction(groundY, tree));
					}
					stage = 4;
					return RUN;
				}
				case 6 -> {
					if (mineReachable(p)) return RUN;
					if (!remaining().isEmpty()) note(tree, "left after the last look from the ground", remaining().size());
					stage = 4;
					return RUN;
				}
				case 4 -> {
					if (!cleaned) {
						// Back on the ground: anything left low down (a stump on a slope, the bottom of a big trunk)?
						cleaned = true;
						if (!remaining().isEmpty()) {
							BlockPos s2 = standNear(tree.bases(), p.blockPosition());
							if (s2 != null) {
								pushFront(new WalkAction(s2, true));
								stage = 6;
								return RUN;
							}
						}
					}
					if (!swept) {
						// Pick up what fell around this tree now, before it can despawn while we work on the next ones.
						swept = true;
						pushFront(new TreeCollectAction(tree, groundY, 0));
						return RUN;
					}
					if (!counted) {
						counted = true;
						LumberStats.tree(tree.kind());
						treesThisRound++;
						remember(cfg);
					}
					if (!cfg.replant) return DONE;
					// Step out of the trunk, then plant.
					if (inTrunk(p) || stand == null || !standable(stand)) {
						stand = standNear(tree.bases(), p.blockPosition());
					}
					if (stand == null) return DONE;
					List<Action> todo = new ArrayList<>();
					todo.add(new WalkAction(stand, true));
					Item sap = Trees.sapling(tree.kind());
					if (sap != null && Inv.count(p, st -> st.is(sap)) < tree.bases().size() && !leavesTried) {
						// No sapling yet: knock down a few of the tree's own leaves (natural ones only, never
						// player-placed) and pick up what falls, like a player would.
						leavesTried = true;
						List<Action> leaves = leafDrops(p, stand);
						if (!leaves.isEmpty()) {
							status(com.seolwoni.assistcore.i18n.Tr.t("묘목을 얻으려고 나뭇잎 치는 중"));
							leaves.add(0, new WalkAction(stand, true));
							pushFront(leaves);
							return RUN;
						}
					}
					if (sap != null) {
						int have = Inv.count(p, s -> s.is(sap));
						if (tree.bases().size() == 4 && have >= 4) {
							for (BlockPos b : tree.bases()) todo.add(new PlantAction(b, sap, tree.kind()));
						} else if (!Trees.needsFour(tree.kind())) {
							todo.add(new PlantAction(tree.first(), sap, tree.kind()));
						}
					}
					stage = 5;
					pushFront(todo);
					return RUN;
				}
				default -> {
					return DONE;
				}
			}
		}

		private void remember(LumberConfig cfg) {
			for (BlockPos b : tree.bases().size() == 4 && !Trees.needsFour(tree.kind()) ? List.of(tree.first()) : tree.bases()) {
				boolean known = cfg.spots.stream().anyMatch(s -> s.x == b.getX() && s.y == b.getY() && s.z == b.getZ());
				if (known) continue;
				Spot s = new Spot();
				s.x = b.getX();
				s.y = b.getY();
				s.z = b.getZ();
				s.tree = tree.kind();
				cfg.spots.add(s);
			}
			LumberConfig.save();
		}
	}

	/** Walks to a cell, clearing leaves or small plants in the way. */
	static final class WalkAction implements Action {
		private final BlockPos target;
		private final boolean quiet;
		private boolean started;
		/** Assist Path walk for longer ways; falls back to walking straight at the target. */
		private com.seolwoni.assistpath.walk.PathWalker walker;
		private int pre, post = -1, limit, clears, still;
		private Vec3 lastPos;

		WalkAction(BlockPos target) {
			this(target, false);
		}

		/** quiet = a failure here does not count towards "the way is blocked". */
		WalkAction(BlockPos target, boolean quiet) {
			this.target = target;
			this.quiet = quiet;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (!started) {
				started = true;
				List<Action> clear = new ArrayList<>();
				for (BlockPos b : new BlockPos[] {target, target.above()}) {
					if (!Blocks2.isOpen(b) && (Trees.isLeaves(b) || isSoft(b)) && clears < 4) clear.add(new MineBlockAction(b, null));
				}
				if (!clear.isEmpty()) {
					clears++;
					started = false;
					pushFront(clear);
					return RUN;
				}
				pre = Human.delay(0, 4);
				Body.resetWalk();
				Body.newTurn();
				limit = (int) (60 + Math.sqrt(p.blockPosition().distSqr(target)) * 14);
				// Straight there when nothing is in the way; otherwise let Assist Path find the way round.
				if (p.blockPosition().distManhattan(target) >= 3 && !com.seolwoni.assistcore.util.Blocks2.lineWalkable(p.position(), target)) walker = new com.seolwoni.assistpath.walk.PathWalker(new com.seolwoni.assistpath.goals.GoalBlock(target));
			}
			if (pre-- > 0) return RUN;
			if (post >= 0) return post-- <= 0 ? DONE : RUN;
			if (walker != null) {
				int r = walker.tick(mc, p);
				if (r == RUN) return RUN;
				walker = null;
				Body.resetWalk();
				Body.newTurn();
				return RUN;
			}
			if (Body.walkTo(p, target)) {
				walkOk();
				post = Human.delay(0, 3);
				return RUN;
			}
			// Not getting anywhere: leaves or a bush in the way (a forest floor). Cut them like a player would.
			if (lastPos != null && p.position().distanceToSqr(lastPos) < 0.0004) {
				if (++still >= 20 && clears < 8) {
					still = 0;
					List<Action> clear = new ArrayList<>();
					double dx = target.getX() + 0.5 - p.getX(), dz = target.getZ() + 0.5 - p.getZ();
					double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
					java.util.Set<BlockPos> seen = new java.util.HashSet<>();
					for (double d = 0.6; d <= Math.min(2.2, len + 0.5); d += 0.4) {
						for (int up = 0; up <= 2; up++) { // feet, head, and above the head (leaves there stop a jump up a step)
							BlockPos c = BlockPos.containing(p.getX() + dx / len * d, p.getY() + 0.1 + up, p.getZ() + dz / len * d);
							if (!seen.add(c)) continue;
							if (!Blocks2.isOpen(c) && (Trees.isLeaves(c) || isSoft(c))) clear.add(new MineBlockAction(c, null));
						}
					}
					BlockPos overHead = BlockPos.containing(p.getX(), p.getY() + 2.1, p.getZ());
					if (!Blocks2.isOpen(overHead) && (Trees.isLeaves(overHead) || isSoft(overHead)) && seen.add(overHead)) clear.add(new MineBlockAction(overHead, null));
					if (!clear.isEmpty()) {
						clears++;
						Body.release(mc);
						Body.resetWalk();
						pushFront(clear);
						return RUN;
					}
				}
			} else {
				still = 0;
			}
			lastPos = p.position();
			if (Body.walkTicks() > limit) {
				Body.release(mc);
				if (!quiet) walkFailed();
				return FAIL;
			}
			return RUN;
		}
	}

	/** Digs one block (with the axe for logs). {@code kind} != null counts it as a log of that tree. */
	static final class MineBlockAction implements Action {
		private final BlockPos pos;
		private final String kind;
		private final boolean count;
		private int stage, delay, ticks;
		private Vec3 aim;
		private Direction face;

		MineBlockAction(BlockPos pos, String kind) {
			this(pos, kind, true);
		}

		/** count = false: do not add it to the log statistics (pillar blocks dug back out). */
		MineBlockAction(BlockPos pos, String kind, boolean count) {
			this.pos = pos;
			this.kind = kind;
			this.count = count;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public String failReason() {
			return com.seolwoni.assistcore.i18n.Tr.t("도끼가 없어요");
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (Blocks2.isOpen(pos) || Blocks2.unbreakable(pos)) return DONE;
					if (eyeDist(p, pos) > REACH + 0.3) return FAIL;
					if (kind != null && !Inv.selectAxe(mc, p)) {
						stop(com.seolwoni.assistcore.i18n.Tr.t("도끼가 없어요"), com.seolwoni.assistcore.i18n.Tr.t("쓸 수 있는 도끼를 인벤토리에 넣어주세요"), true);
						return RUN;
					}
					face = Aim.faceToward(p.getEyePosition(), pos);
					aim = Aim.facePoint(pos, face, 0.1);
					Body.newTurn();
					delay = Human.delay(0, 4);
					stage = 1;
					return RUN;
				}
				case 1 -> {
					if (Body.lookAt(p, aim, 2.5f) && delay-- <= 0) stage = 2;
					return RUN;
				}
				default -> {
					Body.lookAt(p, aim, 1.5f);
					mc.gameMode.continueDestroyBlock(pos, face);
					p.swing(InteractionHand.MAIN_HAND);
					if (Blocks2.isOpen(pos)) {
						if (kind != null && count) LumberStats.log(kind);
						return DONE;
					}
					if (++ticks > 400) {
						mc.gameMode.stopDestroyBlock();
						return FAIL;
					}
					return RUN;
				}
			}
		}
	}

	/** Jumps and puts a block under its own feet: one step up the trunk. */
	static final class PillarUpAction implements Action {
		private int stage, slot, wait, startY, tries, centering;

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					slot = Inv.bringToHotbar(mc, p, Inv::isFiller);
					if (slot == -1) slot = Inv.bringToHotbar(mc, p, Inv::isLogItem);
					if (slot == -1) {
						Alerts.actionBar(com.seolwoni.assistcore.i18n.Tr.t("올라갈 때 쓸 흙·돌이 없어서 높은 부분은 남겨둬요"), 0xFFFFAA00);
						return FAIL;
					}
					Inv.select(p, slot);
					Body.newTurn();
					startY = p.blockPosition().getY();
					stage = 1;
					return RUN;
				}
				case 1 -> {
					// Stand in the middle of the block first: inside a trunk, being off-centre lets the head
					// catch on the leaves around it and the jump does not get high enough.
					double cx = Math.floor(p.getX()) + 0.5 - p.getX(), cz = Math.floor(p.getZ()) + 0.5 - p.getZ();
					double off = Math.sqrt(cx * cx + cz * cz);
					if (off > 0.12 && centering++ < 30) {
						float yaw = (float) (Math.toDegrees(Math.atan2(cz, cx)) - 90.0);
						Body.turnTo(p, yaw, 88f, 2f);
						float err = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - p.getYRot()));
						mc.options.keyUp.setDown(err < 25f && (off > 0.3 || centering % 2 == 0));
						return RUN;
					}
					mc.options.keyUp.setDown(false);
					// Look straight down, as a player does when pillaring.
					if (!Body.turnTo(p, p.getYRot(), 88f, 3f)) return RUN;
					if (!p.onGround()) return RUN;
					mc.options.keyJump.setDown(true);
					wait = 0;
					stage = 2;
					return RUN;
				}
				case 2 -> {
					mc.options.keyJump.setDown(false);
					Body.turnTo(p, p.getYRot(), 88f, 1f);
					BlockPos below = new BlockPos(p.blockPosition().getX(), startY - 1, p.blockPosition().getZ());
					BlockPos target = below.above();
					if (p.getY() > startY + 1.05 && Blocks2.isOpen(target)) {
						Vec3 hit = new Vec3(target.getX() + 0.5 + Human.jitter(0.1), target.getY(), target.getZ() + 0.5 + Human.jitter(0.1));
						mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, below, false));
						p.swing(InteractionHand.MAIN_HAND);
						stage = 3;
						wait = 0;
						return RUN;
					}
					if (++wait > 20) {
						if (++tries >= 3) return FAIL;
						stage = 1;
					}
					return RUN;
				}
				default -> {
					// Land on the new block.
					if (p.onGround() && p.blockPosition().getY() > startY) return DONE;
					if (p.onGround() && ++wait > 6) {
						if (++tries >= 3) return FAIL;
						stage = 1;
						return RUN;
					}
					if (++wait > 40) return FAIL;
					return RUN;
				}
			}
		}
	}

	/** Digs the pillar out from under its own feet, one block at a time, back to the ground. */
	static final class DescendAction implements Action {
		private final int groundY;
		private final Tree tree;
		private int wait, fails, lastY = Integer.MIN_VALUE, shakes;

		DescendAction(int groundY) {
			this(groundY, null);
		}

		/** With the tree: on the way down, knock logs caught on its leaves loose (they fall to the ground). */
		DescendAction(int groundY, Tree tree) {
			this.groundY = groundY;
			this.tree = tree;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (!p.onGround()) return RUN;
			int y = p.blockPosition().getY();
			if (y <= groundY) return DONE;
			if (wait-- > 0) return RUN;
			if (y == lastY && ++fails > 4) return FAIL;
			lastY = y;
			BlockPos under = p.blockPosition().below();
			if (Blocks2.isOpen(under)) return RUN;
			if (tree != null && shakes < 24) {
				List<Action> shake = TreeCollectAction.shakeLeaves(mc, p, tree, groundY);
				if (!shake.isEmpty()) {
					shakes += shake.size();
					lastY = Integer.MIN_VALUE; // cutting leaves is not being stuck
					pushFront(shake);
					return RUN;
				}
			}
			wait = Human.rand(2, 5);
			pushFront(new MineBlockAction(under, Trees.isLog(under) ? Trees.kind(under) : null, false));
			return RUN;
		}
	}

	/** Plants a sapling on the soil block under {@code pos}. */
	static final class PlantAction implements Action {
		private final BlockPos pos;
		private final Item sapling;
		private final String kind;
		private int stage, delay, wait, slot, tries;
		private Vec3 hit;

		PlantAction(BlockPos pos, Item sapling, String kind) {
			this.pos = pos;
			this.sapling = sapling;
			this.kind = kind;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (!Blocks2.state(pos).isAir() || !Trees.soil(pos.below())) return DONE;
					slot = Inv.bringToHotbar(mc, p, s -> s.is(sapling));
					if (slot == -1) return DONE;
					hit = new Vec3(pos.getX() + 0.5 + Human.jitter(0.12), pos.getY(), pos.getZ() + 0.5 + Human.jitter(0.12));
					if (p.getEyePosition().distanceTo(hit) > 4.5) return FAIL;
					delay = Human.delay(1, 5);
					Body.newTurn();
					stage = 1;
					return RUN;
				}
				case 1 -> {
					if (delay-- > 0) return RUN;
					Inv.select(p, slot);
					stage = 2;
					return RUN;
				}
				case 2 -> {
					if (!Body.lookAt(p, hit, 3f)) return RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos.below(), false));
					p.swing(InteractionHand.MAIN_HAND);
					wait = Human.rand(2, 4);
					stage = 3;
					return RUN;
				}
				default -> {
					if (wait-- > 0) return RUN;
					if (!Blocks2.state(pos).isAir()) {
						LumberStats.planted();
						return DONE;
					}
					if (++tries >= 3) return FAIL;
					stage = 0;
					return RUN;
				}
			}
		}
	}
}
