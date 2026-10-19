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
		if (a.failMode() == Action.STOP) stop("벌목을 멈췄어요", a.failReason(), true);
	});
	private static Phase phase = Phase.ROUND;
	private static String status = "꺼짐";
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

	public static boolean start(Minecraft mc) {
		LumberConfig cfg = LumberConfig.get();
		if (mc.player == null || mc.level == null) return false;
		if (cfg.areas.isEmpty()) {
			Alerts.title("구역이 없어요", "도끼를 들고 Shift + 우클릭으로 구역 모서리를 지정하세요", 0xFFFF5555);
			Alerts.warning();
			return false;
		}
		queue.clear();
		beginRound();
		nextPause = Human.rand(1500, 4000);
		cfg.running = true;
		LumberConfig.save();
		Alerts.actionBar("벌목 시작: 구역 " + cfg.areas.size() + "개", 0xFF55FF55);
		return true;
	}

	public static void stop(String title, String sub, boolean alert) {
		lastStop = (title == null ? "" : title) + (sub == null ? "" : " / " + sub);
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
		status("꺼짐");
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
		ticks++;
		if (phase != Phase.WAIT) LumberStats.tick();
		Human.humanLike = cfg.humanDelay;
		if (queue.tick(mc, p)) return;
		plan(mc, p, cfg);
	}

	private static boolean safety(Minecraft mc, LocalPlayer p, LumberConfig cfg) {
		if (p.isDeadOrDying()) {
			BlockPos b = p.blockPosition();
			Alerts.chat("Lumber Assist", "사망 위치: " + b.getX() + ", " + b.getY() + ", " + b.getZ());
			stop("죽었어요", "사망 위치를 채팅에 남겼어요", true);
			return false;
		}
		if (p.isInLava() || p.isOnFire()) {
			stop("위험! 불이나 용암", "벌목을 멈췄어요", true);
			return false;
		}
		if (p.getHealth() <= cfg.minHealth) {
			stop("체력이 낮아요", "벌목을 멈췄어요", true);
			return false;
		}
		if (cfg.stopOnMob && ticks % 10 == 0) {
			AABB box = p.getBoundingBox().inflate(cfg.mobRange);
			for (Entity e : mc.level.getEntities(p, box)) {
				if (e instanceof Enemy && e.isAlive()) {
					stop("몹이 다가와요", e.getName().getString() + " 근처에 있어요", true);
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
				status("다음 바퀴까지 " + (waitSecondsLeft() / 60) + "분 " + (waitSecondsLeft() % 60) + "초");
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
			status("잠깐 쉬는 중");
			return;
		}
		if (Chores.plan(mc, p, cfg)) return;

		Tree next = nextTree(p, cfg);
		if (next != null) {
			tried.add(next.first());
			seenKinds.add(next.kind());
			status("나무 베는 중 (" + Trees.label(next.kind()) + ")");
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
				status("상자에 보관하러 가는 중");
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
		status("빈 자리에 묘목 심는 중 (" + groups.size() + "곳)");
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
		status("떨어진 묘목·사과 줍는 중");
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

	static void walkFailed() {
		if (++walkFails >= 6) stop("길이 막혔어요", "구역 안에 막힌 곳이 있는지 확인해주세요", true);
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
				BlockPos stand = standable(at) ? at : standNear(List.of(at), at);
				if (stand != null) walks.add(new WalkAction(stand, true));
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
	}

	static final class FellAction implements Action {
		private final Tree tree;
		private final int groundY;
		private List<BlockPos> logs;
		private BlockPos stand;
		private int stage, climbs, stuck, lastFeetY = Integer.MIN_VALUE, approaches, intoTries;
		private boolean leavesTried, counted, cleaned;

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

		/** Standing right next to the trunk (sideways or diagonally), on the ground or up on a pillar. */
		private boolean beside(LocalPlayer p) {
			for (BlockPos b : tree.bases()) {
				double dx = p.getX() - (b.getX() + 0.5), dz = p.getZ() - (b.getZ() + 0.5);
				if (Math.abs(dx) <= 1.6 && Math.abs(dz) <= 1.6) return true;
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
					pushFront(new WalkAction(stand, true));
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
							pushFront(new WalkAction(near, true));
							return RUN;
						}
					}
					if (into == null && beside(p) && left.stream().anyMatch(b -> b.getY() + 0.5 > p.getEyeY() - 1)) {
						// The trunk is still whole at the bottom (a slope, or a big 2x2 trunk): climb up right beside it.
						status("높은 나무에 올라가는 중");
						stage = 2;
						return RUN;
					}
					if (into == null) {
						note(tree, beside(p) ? "no way into the trunk to climb" : "could not get next to it", left.size());
						stage = 4;
						return RUN;
					}
					status("높은 나무에 올라가는 중");
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
					if (!left.isEmpty() && above && !inTrunk(p) && !beside(p) && intoTries++ < 2) {
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
							note(tree, "stuck climbing", left.size());
							stage = 3;
							return RUN;
						}
					} else {
						stuck = 0;
					}
					lastFeetY = p.blockPosition().getY();
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
						status("나무에서 내려오는 중");
						pushFront(new DescendAction(groundY));
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
							status("묘목을 얻으려고 나뭇잎 치는 중");
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
		private int pre, post = -1, limit, clears;

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
			return "도끼가 없어요";
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (Blocks2.isOpen(pos) || Blocks2.unbreakable(pos)) return DONE;
					if (eyeDist(p, pos) > REACH + 0.3) return FAIL;
					if (kind != null && !Inv.selectAxe(mc, p)) {
						stop("도끼가 없어요", "쓸 수 있는 도끼를 인벤토리에 넣어주세요", true);
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
		private int stage, slot, wait, startY, tries;

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
						Alerts.actionBar("올라갈 때 쓸 흙·돌이 없어서 높은 부분은 남겨둬요", 0xFFFFAA00);
						return FAIL;
					}
					Inv.select(p, slot);
					Body.newTurn();
					startY = p.blockPosition().getY();
					stage = 1;
					return RUN;
				}
				case 1 -> {
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
		private int wait, fails, lastY = Integer.MIN_VALUE;

		DescendAction(int groundY) {
			this.groundY = groundY;
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
