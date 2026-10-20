package com.seolwoni.lumberassist.lumber;

import com.seolwoni.assistcore.engine.Action;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.lumberassist.config.LumberConfig;
import com.seolwoni.lumberassist.util.Inv;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

/** Eating and "inventory full" trips between trees. */
final class Chores {
	private Chores() {
	}

	static boolean plan(Minecraft mc, LocalPlayer p, LumberConfig cfg) {
		if (cfg.autoEat && p.getFoodData().getFoodLevel() <= cfg.eatThreshold && Inv.count(p, Inv::isFood) > 0) {
			Lumberjack.status("먹는 중");
			Lumberjack.add(new EatAction());
			return true;
		}
		if (Inv.freeSlots(p) <= 1) {
			// A shulker box or an ender chest carried along: come down and store right here.
			var box = cfg.storeEnabled ? com.seolwoni.assistcore.storage.PortableStorage.trip("lumber", p, cfg.storeMode, i -> !Storage.keepSet(p).contains(i), null, true) : null;
			if (box != null) {
				Lumberjack.status(box.label());
				Lumberjack.addComeDown(p);
				Lumberjack.add(box);
			} else if (cfg.storeEnabled && !cfg.chests.isEmpty()) {
				Lumberjack.status("인벤토리가 차서 보관하러 가는 중");
				Lumberjack.addComeDown(p);
				Lumberjack.add(new Storage.TripAction());
			} else if (cfg.storeEnabled && cfg.storeMode != com.seolwoni.assistcore.storage.PortableStorage.CHEST) {
				Lumberjack.stop("인벤토리 가득 참!", com.seolwoni.assistcore.storage.PortableStorage.whyNone(p, cfg.storeMode), true);
			} else {
				Lumberjack.stop("인벤토리 가득 참!", "보관 상자를 지정하면 자동으로 보관해요", true);
			}
			return true;
		}
		return false;
	}

	static final class EatAction implements Action {
		private int stage, delay, ticks, bites, slot;

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					slot = Inv.bringToHotbar(mc, p, Inv::isFood);
					if (slot == -1) return DONE;
					delay = Human.delay(2, 8);
					stage = 1;
					return RUN;
				}
				case 1 -> {
					if (delay-- > 0) return RUN;
					Inv.select(p, slot);
					delay = Human.delay(2, 6);
					stage = 2;
					return RUN;
				}
				case 2 -> {
					if (delay-- > 0) return RUN;
					mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
					mc.options.keyUse.setDown(true);
					ticks = 0;
					stage = 3;
					return RUN;
				}
				default -> {
					ticks++;
					if (!((ticks > 5 && !p.isUsingItem()) || ticks > 80)) return RUN;
					mc.options.keyUse.setDown(false);
					if (++bites < 3 && p.getFoodData().getFoodLevel() <= LumberConfig.get().eatThreshold && Inv.isFood(p.getMainHandItem())) {
						delay = Human.delay(3, 8);
						stage = 2;
						return RUN;
					}
					return DONE;
				}
			}
		}
	}
}
