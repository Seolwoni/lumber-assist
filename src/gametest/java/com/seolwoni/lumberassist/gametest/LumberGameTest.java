package com.seolwoni.lumberassist.gametest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.lumberassist.config.LumberConfig;
import com.seolwoni.lumberassist.hud.LumberStats;
import com.seolwoni.lumberassist.lumber.Lumberjack;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One round in a 16x16 area with four trees: a short oak, a birch one block too tall to reach,
 * a 14-high spruce (needs climbing) and a 2x2 dark oak. Then the storage trip.
 */
public class LumberGameTest implements FabricClientGameTest {
	private static final int Y = 101;
	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			buildWorld(sp);
			context.waitTicks(40);
			sp.getConnection().waitForChunksRender();

			context.runOnClient(mc -> {
				LumberConfig c = LumberConfig.get();
				c.modEnabled = true;
				c.waitMinutes = 5;
				c.stopOnMob = false;
				c.replant = true;
				c.collectDrops = true;
				c.trees = new ArrayList<>(LumberConfig.TREES);
				c.areas.clear();
				c.spots.clear();
				LumberConfig.AreaEntry a = new LumberConfig.AreaEntry();
				a.name = "구역 1";
				a.x1 = 0;
				a.z1 = 0;
				a.x2 = 15;
				a.z2 = 15;
				a.y = Y;
				c.areas.add(a);
				c.storeEnabled = true;
				c.chests.clear();
				ChestEntry ch = new ChestEntry();
				ch.x = -2;
				ch.y = Y;
				ch.z = 7;
				c.chests.add(ch);
				mc.player.setYRot(0f);
				if (!Lumberjack.start(mc)) failures.add("Lumberjack.start returned false");
			});
			context.takeScreenshot("lumber-00-start");
			log.add(context.computeOnClient(mc -> {
				BlockPos b = new BlockPos(3, Y, 3);
				return "debug: trees found=" + com.seolwoni.lumberassist.lumber.Trees.find(LumberConfig.get().areas.get(0)).size()
						+ " isLog=" + com.seolwoni.lumberassist.lumber.Trees.isLog(b) + " kind=" + com.seolwoni.lumberassist.lumber.Trees.kind(b)
						+ " soilBelow=" + com.seolwoni.lumberassist.lumber.Trees.soil(b.below()) + " below=" + mc.level.getBlockState(b.below())
						+ " state=" + mc.level.getBlockState(b) + " logsTag=" + mc.level.getBlockState(b).is(BlockTags.LOGS);
			}));

			int ticks = 0, shots = 1;
			final int limit = 20 * 60 * 12;
			boolean running = true, waiting = false;
			while (running && !waiting && ticks < limit) {
				context.waitTicks(40);
				ticks += 40;
				final int t = ticks;
				log.add(context.computeOnClient(mc -> "t=" + t + " pos=" + mc.player.blockPosition().toShortString()
						+ " status=" + Lumberjack.status() + " trees=" + Lumberjack.treesThisRound()));
				running = context.computeOnClient(mc -> LumberConfig.get().running);
				waiting = context.computeOnClient(mc -> Lumberjack.waitSecondsLeft() >= 0);
				if (ticks % 600 == 0 && shots < 25) context.takeScreenshot(String.format("lumber-%02d-t%d", shots++, ticks));
			}
			context.takeScreenshot("lumber-99-end");
			String stop = context.computeOnClient(mc -> Lumberjack.lastStop);
			log.add("ended after " + ticks + " ticks, running=" + running + " waiting=" + waiting + " lastStop=" + stop);
			if (!running) failures.add("stopped by itself: " + stop);
			else if (!waiting) failures.add("round not finished within " + limit + " ticks");
			final boolean stillRunning = running;
			context.runOnClient(mc -> {
				LumberStats st = LumberStats.session;
				log.add("trees=" + st.trees + " planted=" + st.planted + " logs=" + st.totalLogs() + " trips=" + st.trips);
				for (Map.Entry<String, LumberStats.Entry> e : st.logs.entrySet()) log.add("  logs " + e.getKey() + ": " + e.getValue().count);
				log.add("player at " + mc.player.blockPosition().toShortString() + " health " + mc.player.getHealth());
				if (st.trees < 4) failures.add("felled only " + st.trees + " of 4 trees");
				if (stillRunning) Lumberjack.stop(null, null, false);
			});
			checkWorld(sp);
		} catch (Throwable t) {
			failures.add("exception: " + t);
			throw t;
		} finally {
			writeReport();
		}
		if (!failures.isEmpty()) throw new AssertionError("Lumber Assist test failed: " + String.join("; ", failures));
	}

	private static void cmd(TestSingleplayerContext sp, String c) {
		sp.getServer().runCommand(c);
	}

	private void buildWorld(TestSingleplayerContext sp) {
		cmd(sp, "forceload add -8 -8 24 24");
		cmd(sp, "difficulty peaceful");
		cmd(sp, "time set noon");
		cmd(sp, "gamerule randomTickSpeed 0");
		cmd(sp, "gamerule random_tick_speed 0");
		cmd(sp, "fill -6 96 -6 21 99 21 minecraft:stone");
		cmd(sp, "fill -6 100 -6 21 100 21 minecraft:grass_block");
		cmd(sp, "fill -6 101 -6 21 125 21 minecraft:air");
		// Short oak: 6 logs, all reachable from the ground.
		cmd(sp, "fill 1 104 1 5 107 5 minecraft:oak_leaves[persistent=true]");
		cmd(sp, "fill 3 101 3 3 106 3 minecraft:oak_log");
		// Birch: 7 logs, the top one needs one step up.
		cmd(sp, "fill 10 105 1 14 108 5 minecraft:birch_leaves[persistent=true]");
		cmd(sp, "fill 12 101 3 12 107 3 minecraft:birch_log");
		// Tall spruce: 14 logs, needs climbing.
		cmd(sp, "fill 2 106 10 4 115 12 minecraft:spruce_leaves[persistent=true]");
		cmd(sp, "fill 3 101 11 3 114 11 minecraft:spruce_log");
		// 2x2 dark oak: 6 high.
		cmd(sp, "fill 9 104 9 14 107 14 minecraft:dark_oak_leaves[persistent=true]");
		cmd(sp, "fill 11 101 11 12 106 12 minecraft:dark_oak_log");
		// storage chest
		cmd(sp, "setblock -2 101 7 minecraft:chest[facing=east]");

		cmd(sp, "gamemode survival @a");
		cmd(sp, "clear @a");
		cmd(sp, "give @a minecraft:iron_axe");
		cmd(sp, "give @a minecraft:dirt 32");
		// No saplings in hand: they have to come from the chest (the test leaves never drop any).
		cmd(sp, "item replace block -2 101 7 container.0 with minecraft:oak_sapling 8");
		cmd(sp, "item replace block -2 101 7 container.1 with minecraft:birch_sapling 8");
		cmd(sp, "item replace block -2 101 7 container.2 with minecraft:spruce_sapling 8");
		cmd(sp, "item replace block -2 101 7 container.3 with minecraft:dark_oak_sapling 8");
		cmd(sp, "give @a minecraft:bread 16");
		cmd(sp, "effect give @a minecraft:saturation infinite 0 true");
		cmd(sp, "tp @a 7.5 101 7.5 0 20");
	}

	private void checkWorld(TestSingleplayerContext sp) {
		List<String> out = sp.getServer().computeOnServer(server -> {
			List<String> o = new ArrayList<>();
			ServerLevel level = server.overworld();
			int logs = 0, dirt = 0;
			for (int x = -1; x <= 16; x++) {
				for (int z = -1; z <= 16; z++) {
					for (int y = Y; y <= 120; y++) {
						BlockState s = level.getBlockState(new BlockPos(x, y, z));
						if (s.is(BlockTags.LOGS)) logs++;
						if (s.is(Blocks.DIRT)) dirt++;
					}
				}
			}
			o.add("info: logs left = " + logs + ", pillar dirt left = " + dirt);
			if (logs > 0) o.add(logs + " logs left standing");
			if (dirt > 0) o.add(dirt + " pillar blocks left behind");
			check(o, level, new BlockPos(3, Y, 3), Blocks.OAK_SAPLING, "oak");
			check(o, level, new BlockPos(12, Y, 3), Blocks.BIRCH_SAPLING, "birch");
			check(o, level, new BlockPos(3, Y, 11), Blocks.SPRUCE_SAPLING, "spruce");
			for (BlockPos b : List.of(new BlockPos(11, Y, 11), new BlockPos(12, Y, 11), new BlockPos(11, Y, 12), new BlockPos(12, Y, 12))) {
				check(o, level, b, Blocks.DARK_OAK_SAPLING, "dark oak");
			}
			int stored = 0;
			if (level.getBlockEntity(new BlockPos(-2, Y, 7)) instanceof Container ct) {
				for (int i = 0; i < ct.getContainerSize(); i++) stored += ct.getItem(i).getCount();
			}
			o.add("info: items in chest = " + stored);
			if (stored < 40) o.add("only " + stored + " items in the chest (logs should have been stored)");
			return o;
		});
		for (String p : out) {
			if (p.startsWith("info: ")) log.add(p.substring(6));
			else failures.add(p);
		}
	}

	private static void check(List<String> o, ServerLevel level, BlockPos p, net.minecraft.world.level.block.Block sapling, String name) {
		if (!level.getBlockState(p).is(sapling)) o.add(name + " sapling not planted at " + p.toShortString() + " (" + level.getBlockState(p).getBlock() + ")");
	}

	private void writeReport() {
		try {
			List<String> out = new ArrayList<>();
			out.add(failures.isEmpty() ? "RESULT: PASS" : "RESULT: FAIL (" + failures.size() + ")");
			for (String f : failures) out.add("  FAIL " + f);
			out.add("");
			out.addAll(log);
			Path p = FabricLoader.getInstance().getGameDir().resolve("lumberassist-test-result.txt");
			Files.write(p, out, StandardCharsets.UTF_8);
		} catch (Exception ignored) {
		}
	}
}
