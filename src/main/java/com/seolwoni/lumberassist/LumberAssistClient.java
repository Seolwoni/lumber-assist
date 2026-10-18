package com.seolwoni.lumberassist;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.seolwoni.lumberassist.config.LumberConfig;
import com.seolwoni.lumberassist.hud.LumberHud;
import com.seolwoni.lumberassist.hud.LumberStats;
import com.seolwoni.lumberassist.lumber.Area;
import com.seolwoni.lumberassist.lumber.Lumberjack;
import com.seolwoni.lumberassist.lumber.Storage;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

public class LumberAssistClient implements ClientModInitializer {
	public static final String MOD_ID = "lumberassist";
	public static final Logger LOGGER = LoggerFactory.getLogger("Lumber Assist");

	public static KeyMapping markChestKey;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitializeClient() {
		LumberConfig.load();
		LumberStats.loadTotal();

		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		markChestKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.lumberassist.mark_chest", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (markChestKey.consumeClick()) {
				if (LumberConfig.get().modEnabled) Storage.markLookedAt(client);
			}
			if (LumberConfig.get().modEnabled) Lumberjack.tick(client);
		});

		// Axe + Shift + right-click marks an area corner, and does not strip the log.
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || player != Minecraft.getInstance().player) return InteractionResult.PASS;
			if (!LumberConfig.get().modEnabled || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			if (!player.isShiftKeyDown() || !player.getMainHandItem().is(ItemTags.AXES)) return InteractionResult.PASS;
			Area.click(hit.getBlockPos());
			// FAIL = handled here and nothing is sent to the server, so the log is not stripped.
			player.swing(hand);
			return InteractionResult.FAIL;
		});

		LumberHud.register();

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			if (LumberConfig.get().running) Lumberjack.stop(null, null, false);
			Area.cancelPending();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (LumberConfig.get().running) Lumberjack.stop(null, null, false);
			LumberConfig.save();
			LumberStats.saveTotal();
		});

		LOGGER.info("Lumber Assist loaded - made by seolwoni");
	}
}
