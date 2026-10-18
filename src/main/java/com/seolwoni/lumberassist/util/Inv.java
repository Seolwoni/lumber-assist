package com.seolwoni.lumberassist.util;

import java.util.function.Predicate;

import com.seolwoni.assistcore.util.InvBase;
import com.seolwoni.lumberassist.config.LumberConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Lumber item kinds and axe choice on top of the shared inventory helpers. */
public final class Inv extends InvBase {
	private Inv() {
	}

	public static boolean isAxe(ItemStack s) {
		return !s.isEmpty() && s.is(ItemTags.AXES);
	}

	public static boolean usableAxe(ItemStack s) {
		return isAxe(s) && remaining(s) > LumberConfig.get().protectDurability;
	}

	public static boolean isSapling(ItemStack s) {
		return !s.isEmpty() && (s.is(ItemTags.SAPLINGS) || s.is(Items.MANGROVE_PROPAGULE));
	}

	public static boolean isLogItem(ItemStack s) {
		return !s.isEmpty() && s.is(ItemTags.LOGS);
	}

	/** Blocks we may stand on while climbing a tall trunk (placed and dug back out). */
	public static boolean isFiller(ItemStack s) {
		return !s.isEmpty() && (s.is(Items.DIRT) || s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE)
				|| s.is(Items.NETHERRACK) || s.is(Items.STONE) || s.is(Items.ANDESITE) || s.is(Items.DIORITE)
				|| s.is(Items.GRANITE) || s.is(Items.TUFF));
	}

	/** Brings a matching item to the hotbar without pushing out the axe or food. */
	public static int bringToHotbar(Minecraft mc, LocalPlayer p, Predicate<ItemStack> f) {
		return InvBase.bringToHotbar(mc, p, f, s -> isAxe(s) || isFood(s));
	}

	/** Puts the most worn usable axe in hand (keeps the good ones for later). False if there is none. */
	public static boolean selectAxe(Minecraft mc, LocalPlayer p) {
		Inventory inv = p.getInventory();
		int best = -1, bestRem = Integer.MAX_VALUE;
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (usableAxe(s) && remaining(s) < bestRem) {
				best = i;
				bestRem = remaining(s);
			}
		}
		if (best == -1) {
			best = bringToHotbar(mc, p, Inv::usableAxe);
			if (best == -1) return false;
		}
		select(p, best);
		return true;
	}
}
