package com.seolwoni.lumberassist.lumber;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.seolwoni.lumberassist.config.LumberConfig;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.assistcore.storage.Chests;
import com.seolwoni.lumberassist.hud.LumberStats;
import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.lumberassist.util.Inv;
import com.seolwoni.assistcore.engine.Action;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Marking storage chests (K) and the trip that stores the wood. */
public final class Storage {
	private Storage() {
	}

	public static void markLookedAt(Minecraft mc) {
		Chests.toggleLookedAt(mc, LumberConfig.get().chests, LumberConfig::save);
	}

	static BlockPos pos(ChestEntry e) {
		return e.pos();
	}

	/** Inventory indices to keep: axes, food, up to 64 climbing blocks and saplingKeep of each sapling. */
	static Set<Integer> keepSet(LocalPlayer p) {
		LumberConfig cfg = LumberConfig.get();
		Inventory inv = p.getInventory();
		Set<Integer> keep = new HashSet<>();
		keep.add(inv.getSelectedSlot());
		Map<Item, List<Integer>> saps = new HashMap<>();
		List<Integer> filler = new ArrayList<>();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			if (Inv.isSapling(s)) saps.computeIfAbsent(s.getItem(), k -> new ArrayList<>()).add(i);
			else if (Inv.isFiller(s)) filler.add(i);
			else if (Inv.isAxe(s) || Inv.isFood(s)) keep.add(i);
		}
		keepUpTo(inv, keep, filler, 64);
		for (List<Integer> list : saps.values()) keepUpTo(inv, keep, list, cfg.saplingKeep);
		return keep;
	}

	private static void keepUpTo(Inventory inv, Set<Integer> keep, List<Integer> slots, int amount) {
		slots.sort(Comparator.comparingInt((Integer i) -> inv.getItem(i).getCount()).reversed());
		int kept = 0;
		for (int i : slots) {
			if (kept >= amount) break;
			keep.add(i);
			kept += inv.getItem(i).getCount();
		}
	}

	static int target(String id, boolean[] full) {
		return Chests.target(LumberConfig.get().chests, id, full);
	}

	/** Saplings of the trees cut this round that we now have fewer than 4 of. */
	static List<Item> seedsWanted(LocalPlayer p) {
		List<Item> out = new ArrayList<>();
		if (!LumberConfig.get().replant) return out;
		for (String kind : Lumberjack.seenKinds) {
			Item it = Trees.sapling(kind);
			if (it != null && Inv.count(p, s -> s.is(it)) < 4) out.add(it);
		}
		return out;
	}

	static final class TripAction implements Action {
		private final List<Integer> order = new ArrayList<>();
		private boolean[] full;
		private int stage, idx, delay, waitTicks, moved, limit;
		private Vec3 aim;
		private Direction face;
		private String reason = "";

		@Override
		public int failMode() {
			return STOP;
		}

		@Override
		public String failReason() {
			return reason;
		}

		private ChestEntry chest() {
			return LumberConfig.get().chests.get(order.get(idx));
		}

		private boolean hasWork(LocalPlayer p, int chestIndex) {
			Set<Integer> keep = keepSet(p);
			for (int i = 0; i < 36; i++) {
				ItemStack s = p.getInventory().getItem(i);
				if (!s.isEmpty() && !keep.contains(i) && target(Inv.id(s), full) == chestIndex) return true;
			}
			return !seedsWanted(p).isEmpty() || (LumberConfig.get().autoEat && Inv.count(p, Inv::isFood) == 0);
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			LumberConfig cfg = LumberConfig.get();
			switch (stage) {
				case 0 -> {
					if (cfg.chests.isEmpty()) return DONE;
					full = new boolean[cfg.chests.size()];
					order.clear();
					for (int i = 0; i < cfg.chests.size(); i++) if (!cfg.chests.get(i).items.isEmpty()) order.add(i);
					for (int i = 0; i < cfg.chests.size(); i++) if (cfg.chests.get(i).items.isEmpty()) order.add(i);
					idx = 0;
					stage = 1;
					Lumberjack.status("상자에 보관하는 중");
					return RUN;
				}
				case 1 -> {
					while (idx < order.size() && !hasWork(p, order.get(idx))) idx++;
					if (idx >= order.size()) {
						if (Inv.freeSlots(p) <= 1) {
							Lumberjack.stop("보관 상자가 가득 찼어요", "상자를 비우거나 더 지정해주세요", true);
							return RUN;
						}
						Alerts.actionBar("보관 완료 (" + moved + "칸)", 0xFF55FF55);
						LumberStats.trip();
						return DONE;
					}
					BlockPos c = pos(chest());
					if (mc.level.getBlockEntity(c) == null) {
						full[order.get(idx)] = true;
						idx++;
						return RUN;
					}
					Body.resetWalk();
					Body.newTurn();
					limit = (int) (80 + Math.sqrt(p.blockPosition().distSqr(c)) * 14);
					stage = 2;
					return RUN;
				}
				case 2 -> {
					BlockPos c = pos(chest());
					if (p.getEyePosition().distanceTo(Vec3.atCenterOf(c)) <= 4.0) {
						Body.release(mc);
						face = com.seolwoni.assistcore.util.Aim.faceToward(p.getEyePosition(), c);
						aim = com.seolwoni.assistcore.util.Aim.facePoint(c, face, 0.1);
						delay = Human.delay(2, 6);
						stage = 3;
						return RUN;
					}
					Body.walkTo(p, c);
					if (Body.walkTicks() > limit) {
						Body.release(mc);
						reason = "보관 상자까지 갈 수 없어요";
						return FAIL;
					}
					return RUN;
				}
				case 3 -> {
					if (!Body.lookAt(p, aim, 3f) || delay-- > 0) return RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(aim, face, pos(chest()), false));
					p.swing(InteractionHand.MAIN_HAND);
					waitTicks = 40;
					stage = 4;
					return RUN;
				}
				case 4 -> {
					if (open(mc) != null) {
						delay = Human.delay(3, 8);
						stage = 5;
						return RUN;
					}
					if (--waitTicks <= 0) {
						reason = "상자를 열지 못했어요";
						return FAIL;
					}
					return RUN;
				}
				case 5 -> {
					AbstractContainerScreen<?> s = open(mc);
					if (s == null) {
						idx++;
						stage = 1;
						return RUN;
					}
					if (delay-- > 0) return RUN;
					AbstractContainerMenu menu = s.getMenu();
					if (depositOne(mc, p, menu) || takeOne(mc, p, menu)) {
						delay = Human.delay(1, 4);
						return RUN;
					}
					delay = Human.delay(3, 8);
					stage = 6;
					return RUN;
				}
				default -> {
					if (delay-- > 0) return RUN;
					if (open(mc) != null) p.closeContainer();
					idx++;
					stage = 1;
					return RUN;
				}
			}
		}

		private static AbstractContainerScreen<?> open(Minecraft mc) {
			return Chests.openScreen(mc);
		}

		private boolean depositOne(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
			int here = order.get(idx);
			Set<Integer> keep = keepSet(p);
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				if (!(slot.container instanceof Inventory)) continue;
				int inv = slot.getContainerSlot();
				if (inv >= 36 || keep.contains(inv)) continue;
				ItemStack s = slot.getItem();
				if (s.isEmpty() || target(Inv.id(s), full) != here) continue;
				int before = s.getCount();
				ItemStack copy = s.copy();
				mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
				ItemStack after = slot.getItem();
				if (!after.isEmpty() && after.getCount() == before && ItemStack.isSameItemSameComponents(after, copy)) {
					full[here] = true;
					return false;
				}
				moved++;
				return true;
			}
			return false;
		}

		private boolean takeOne(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
			if (Inv.freeSlots(p) <= 1) return false;
			List<java.util.function.Predicate<ItemStack>> wants = new ArrayList<>();
			for (Item it : seedsWanted(p)) wants.add(s -> s.is(it));
			if (LumberConfig.get().autoEat && Inv.count(p, Inv::isFood) == 0) wants.add(Inv::isFood);
			for (java.util.function.Predicate<ItemStack> want : wants) {
				for (int i = 0; i < menu.slots.size(); i++) {
					Slot slot = menu.slots.get(i);
					if (slot.container instanceof Inventory || !want.test(slot.getItem())) continue;
					mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
					return true;
				}
			}
			return false;
		}
	}
}
