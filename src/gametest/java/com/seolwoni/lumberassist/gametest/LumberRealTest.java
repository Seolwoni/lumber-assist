package com.seolwoni.lumberassist.gametest;

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
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real-map tests for Lumber Assist (generated world, fixed seed, peaceful): one forest each.
 * real-taiga: old growth spruce taiga (big 2x2 spruces). real-darkforest: dark oaks. real-jungle: big jungle trees.
 * The test picks a 16x16 patch with the most trees near the biome, marks it corner to corner like a player
 * (each corner at its own ground height) and puts a chest at the edge.
 */
public class LumberRealTest implements FabricClientGameTest {
	private static final int SIZE = 16;
	private final List<String> log = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();
	private final RealWorld.Timing timing = new RealWorld.Timing();

	@Override
	public void runTest(ClientGameTestContext context) {
		String suite = RealWorld.suite();
		ResourceKey<Biome> biome = switch (suite) {
			case "real-taiga" -> Biomes.OLD_GROWTH_SPRUCE_TAIGA;
			case "real-darkforest" -> Biomes.DARK_FOREST;
			case "real-jungle" -> Biomes.JUNGLE;
			default -> null;
		};
		if (biome == null) return;
		try (TestSingleplayerContext sp = RealWorld.create(context)) {
			sp.getConnection().waitForChunksRender();
			RealWorld.setup(sp);
			// Saplings must not grow back into trees during the test (that would look like trees left standing).
			RealWorld.cmd(sp, "gamerule random_tick_speed 0");
			RealWorld.cmd(sp, "gamerule randomTickSpeed 0");
			// Like a player looking for a good patch of this forest: try a few places, keep one with a big tree.
			BlockPos b = null;
			int[] best = null;
			BlockPos[] origins = {BlockPos.ZERO, new BlockPos(700, 64, 700), new BlockPos(-700, 64, 700), new BlockPos(700, 64, -700)};
			for (BlockPos o : origins) {
				BlockPos cand = RealWorld.findBiome(sp, biome, o);
				if (cand == null) continue;
				RealWorld.cmd(sp, "forceload remove all");
				RealWorld.goTo(context, sp, cand.getX(), cand.getZ(), 48);
				context.waitTicks(20);
				int[] w = pickWindow(sp, cand, biome);
				log.add("biome " + biome + " at " + cand.getX() + ", " + cand.getZ() + ": best patch score " + w[2]);
				if (best == null || w[2] > best[2]) {
					best = w;
					b = cand;
				}
				if (w[2] >= 10) break; // has a big 2x2 tree
			}
			if (b == null) {
				failures.add("biome not found: " + biome);
				throw new AssertionError("biome not found: " + biome);
			}
			RealWorld.cmd(sp, "forceload remove all");
			RealWorld.goTo(context, sp, b.getX(), b.getZ(), 48);
			context.waitTicks(40);

			int x1 = best[0], z1 = best[1], x2 = x1 + SIZE - 1, z2 = z1 + SIZE - 1;
			int y1 = sp.getServer().computeOnServer(server -> ground(server.overworld(), x1, z1));
			int y2 = sp.getServer().computeOnServer(server -> ground(server.overworld(), x2, z2));
			log.add("area x " + x1 + ".." + x2 + ", z " + z1 + ".." + z2 + ", corner ground heights " + y1 + " and " + y2 + " (trunk columns " + best[2] + ")");
			int[] before = count(sp, x1, z1, x2, z2);
			log.add("before: logs=" + before[0] + " (" + before[1] + " trunk columns), ground from y " + before[2] + " to " + before[3]);

			// Chest on a flat, free spot just west of the area (two level ground cells: chest and where the player stands).
			int[] spot = sp.getServer().computeOnServer(server -> {
				ServerLevel level = server.overworld();
				int mid = ground(level, (x1 + x2) / 2, (z1 + z2) / 2);
				for (int d = 0; d < 16; d++) {
					for (int sign : new int[] {1, -1}) {
						int z = (z1 + z2) / 2 + sign * d;
						for (int x = x1 - 2; x >= x1 - 6; x--) {
							int a = ground(level, x, z), b2 = ground(level, x + 1, z);
							if (a != b2 || Math.abs(a - mid) > 3) continue;
							boolean free = true;
							for (int dy = 0; dy < 3; dy++) {
								if (!level.getBlockState(new BlockPos(x, a + dy, z)).getCollisionShape(level, new BlockPos(x, a + dy, z)).isEmpty()) free = false;
								if (!level.getBlockState(new BlockPos(x + 1, a + dy, z)).getCollisionShape(level, new BlockPos(x + 1, a + dy, z)).isEmpty()) free = false;
							}
							if (free) return new int[] {x, a, z};
						}
					}
				}
				return new int[] {x1 - 2, ground(level, x1 - 2, (z1 + z2) / 2), (z1 + z2) / 2};
			});
			int cx = spot[0], cy = spot[1], cz = spot[2];
			// Make room like a player would (cut away leaves or a bush where the chest and the player go).
			RealWorld.cmd(sp, "fill " + cx + " " + cy + " " + cz + " " + (cx + 1) + " " + (cy + 2) + " " + cz + " minecraft:air");
			RealWorld.cmd(sp, "setblock " + cx + " " + cy + " " + cz + " minecraft:chest[facing=east]");
			RealWorld.cmd(sp, "tp @a " + (cx + 1.5) + " " + cy + " " + (cz + 0.5) + " -90 10");
			log.add("chest at " + cx + ", " + cy + ", " + cz);
			RealWorld.cmd(sp, "give @a minecraft:iron_axe");
			RealWorld.cmd(sp, "give @a minecraft:iron_axe");
			RealWorld.cmd(sp, "give @a minecraft:dirt 64");
			RealWorld.cmd(sp, "give @a minecraft:bread 16");
			RealWorld.cmd(sp, "effect give @a minecraft:saturation infinite 0 true");
			context.waitTicks(40);
			sp.getConnection().waitForChunksRender();
			BlockPos chest = new BlockPos(cx, cy, cz);

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
				a.x1 = x1;
				a.z1 = z1;
				a.x2 = x2;
				a.z2 = z2;
				a.y = Math.min(y1, y2);
				a.y2 = Math.max(y1, y2);
				c.areas.add(a);
				c.storeEnabled = true;
				c.chests.clear();
				ChestEntry ch = new ChestEntry();
				ch.x = chest.getX();
				ch.y = chest.getY();
				ch.z = chest.getZ();
				c.chests.add(ch);
				log.add("trees found at the start: " + com.seolwoni.lumberassist.lumber.Trees.find(a).size());
				if (!Lumberjack.start(mc)) failures.add("Lumberjack.start returned false");
			});
			context.takeScreenshot(suite + "-00-start");

			int ticks = 0, shots = 1;
			final int limit = 20 * 60 * 25;
			boolean running = true, waiting = false;
			LogTracker logs = new LogTracker(x1, z1, x2, z2);
			while (running && !waiting && ticks < limit) {
				long t0 = System.nanoTime();
				for (int k = 0; k < 8; k++) {
					context.waitTicks(5);
					logs.scan(context, sp, ticks + 5 * (k + 1));
				}
				timing.add(System.nanoTime() - t0);
				ticks += 40;
				final int t = ticks;
				log.add(context.computeOnClient(mc -> "t=" + t + " pos=" + mc.player.blockPosition().toShortString()
						+ " status=" + Lumberjack.status() + " trees=" + Lumberjack.treesThisRound()));
				running = context.computeOnClient(mc -> LumberConfig.get().running);
				waiting = context.computeOnClient(mc -> Lumberjack.waitSecondsLeft() >= 0);
				if (ticks % 600 == 0) context.takeScreenshot(String.format("%s-%03d-s%d", suite, shots++, ticks / 20));
			}
			context.takeScreenshot(suite + "-99-end");
			String stop = context.computeOnClient(mc -> Lumberjack.lastStop);
			log.add("ended after " + ticks + " ticks (" + ticks / 20 + " s), running=" + running + " waiting=" + waiting + " lastStop=" + stop);
			log.add(timing.summary() + ", server tick " + String.format("%.1f", RealWorld.mspt(sp)) + " ms");
			log.add(com.seolwoni.assistpath.AssistPath.Stats.summary());
			if (!running) failures.add("stopped by itself: " + stop);
			else if (!waiting) failures.add("round not finished within " + limit + " ticks");
			final boolean stillRunning = running;
			context.runOnClient(mc -> {
				LumberStats st = LumberStats.session;
				log.add("trees=" + st.trees + " planted=" + st.planted + " logs=" + st.totalLogs() + " trips=" + st.trips);
				for (Map.Entry<String, LumberStats.Entry> e : st.logs.entrySet()) log.add("  logs " + e.getKey() + ": " + e.getValue().count);
				log.add("player at " + mc.player.blockPosition().toShortString() + " health " + mc.player.getHealth());
				if (mc.player.getHealth() < 15) failures.add("got hurt: health " + mc.player.getHealth());
				if (stillRunning) Lumberjack.stop(null, null, false);
			});
			int[] after = count(sp, x1, z1, x2, z2);
			log.add("after: logs=" + after[0] + " (" + after[1] + " trunk columns), saplings=" + after[4] + ", pillar dirt left=" + after[5]);
			int stored = sp.getServer().computeOnServer(server -> {
				int n = 0;
				if (server.overworld().getBlockEntity(chest) instanceof Container ct) {
					for (int i = 0; i < ct.getContainerSize(); i++) if (ct.getItem(i).is(net.minecraft.tags.ItemTags.LOGS)) n += ct.getItem(i).getCount();
				}
				return n;
			});
			log.add("logs in chest = " + stored);
			logs.report(sp, log);
			// Loss count (measured only, not a failure): log items still lying around, and logs kept in the inventory.
			final int fx1 = x1, fz1 = z1, fx2 = x2, fz2 = z2;
			String loss = sp.getServer().computeOnServer(server -> {
				net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(fx1 - 12, -64, fz1 - 12, fx2 + 13, 320, fz2 + 13);
				int ground = 0, stacks = 0, saplings = 0;
				StringBuilder where = new StringBuilder();
				for (net.minecraft.world.entity.item.ItemEntity ie : server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
					if (ie.getItem().is(net.minecraft.tags.ItemTags.LOGS)) {
						ground += ie.getItem().getCount();
						stacks++;
						if (where.length() < 400) where.append(" ").append(ie.getItem().getCount()).append("@").append(ie.blockPosition().toShortString());
					} else if (ie.getItem().getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) saplings += ie.getItem().getCount();
				}
				return ground + " logs in " + stacks + " stacks on the ground" + (where.length() > 0 ? " [" + where.toString().trim() + "]" : "")
						+ ", saplings on the ground " + saplings;
			});
			int held = context.computeOnClient(mc -> {
				int n = 0;
				for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
					if (mc.player.getInventory().getItem(i).is(net.minecraft.tags.ItemTags.LOGS)) n += mc.player.getInventory().getItem(i).getCount();
				}
				return n;
			});
			int cut = before[0] - after[0];
			int missing = Math.max(0, cut - stored - held);
			// Logs that were cut but never reached the chest (despawned, stuck in the leaves, left lying).
			if (missing > Math.max(2, cut * 3 / 100)) failures.add(missing + " of " + cut + " cut logs never reached the chest");
			log.add("LOSS: cut " + cut + ", chest " + stored + ", inventory " + held + ", missing " + Math.max(0, cut - stored - held) + " (" + (cut > 0 ? String.format("%.1f", 100.0 * Math.max(0, cut - stored - held) / cut) : "0") + "%); " + loss);
			for (String n : context.computeOnClient(mc -> new ArrayList<>(Lumberjack.fellLog))) {
				log.add("  tree: " + n);
				// A tree left partly standing (or its top floating) is a failure, whatever the log count says.
				java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+) logs left\\)").matcher(n);
				if (m.find() && Integer.parseInt(m.group(1)) > 3 && !n.contains("max climb height")) failures.add("tree not finished: " + n);
			}
			if (after[1] > 0) {
				log.add("trunks left: " + sp.getServer().computeOnServer(server -> {
					ServerLevel level = server.overworld();
					StringBuilder sb = new StringBuilder();
					for (int x = x1; x <= x2; x++) {
						for (int z = z1; z <= z2; z++) {
							int g = ground(level, x, z);
							BlockPos p = new BlockPos(x, g, z);
							if (!level.getBlockState(p).is(BlockTags.LOGS)) continue;
							int h = 0;
							while (level.getBlockState(p.above(h)).is(BlockTags.LOGS)) h++;
							sb.append(p.toShortString()).append(" ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath())
									.append(" h=").append(h).append(" on ").append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(p.below()).getBlock()).getPath()).append("; ");
						}
					}
					return sb.toString();
				}));
			}
			if (after[1] > 0) failures.add(after[1] + " trunk columns still standing in the area");
			if (after[0] > before[0] * 0.3) failures.add(after[0] + " of " + before[0] + " logs left in the area");
			if (stored < (before[0] - after[0]) * 0.7) failures.add("only " + stored + " logs in the chest of " + (before[0] - after[0]) + " cut");
			if (after[4] == 0) failures.add("no saplings planted");
		} catch (Throwable t) {
			failures.add("exception: " + t);
			throw t;
		} finally {
			RealWorld.writeReport("lumberassist-" + suite, failures, log);
		}
		if (!failures.isEmpty()) throw new AssertionError("Lumber Assist " + suite + " failed: " + String.join("; ", failures));
	}

	/** The 16x16 patch with the most trunks (2x2 trunks count much more) around a spot of this biome: {x, z, score}. */
	private static int[] pickWindow(TestSingleplayerContext sp, BlockPos b, ResourceKey<Biome> biome) {
		return sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			int bx = b.getX(), bz = b.getZ(), bestScore = -1;
			for (int ox = -48; ox <= 32; ox += 8) {
				for (int oz = -48; oz <= 32; oz += 8) {
					int score = 0;
					BlockPos mid = new BlockPos(b.getX() + ox + SIZE / 2, b.getY(), b.getZ() + oz + SIZE / 2);
					if (!level.getBiome(mid.atY(ground(level, mid.getX(), mid.getZ()))).is(biome)) continue;
					for (int x = b.getX() + ox; x < b.getX() + ox + SIZE - 1; x++) {
						for (int z = b.getZ() + oz; z < b.getZ() + oz + SIZE - 1; z++) {
							int g = ground(level, x, z);
							if (!level.getBlockState(new BlockPos(x, g, z)).is(BlockTags.LOGS)) continue;
							score++;
							if (level.getBlockState(new BlockPos(x + 1, g, z)).is(BlockTags.LOGS) && level.getBlockState(new BlockPos(x, g, z + 1)).is(BlockTags.LOGS)
									&& level.getBlockState(new BlockPos(x + 1, g, z + 1)).is(BlockTags.LOGS)) score += 10;
						}
					}
					if (score > bestScore) {
						bestScore = score;
						bx = b.getX() + ox;
						bz = b.getZ() + oz;
					}
				}
			}
			return new int[] {bx, bz, bestScore};
		});
	}

	/** Height of the block a sapling would sit in at x/z: just above the topmost soil block, ignoring trees. */
	static int ground(ServerLevel level, int x, int z) {
		for (int y = 200; y > level.getMinY(); y--) {
			BlockState s = level.getBlockState(new BlockPos(x, y, z));
			BlockPos p = new BlockPos(x, y, z);
			// Skip trees and anything without a solid shape (grass, flowers, saplings, vines, water).
			if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.getCollisionShape(level, p).isEmpty() || s.is(Blocks.COCOA)) continue;
			return y + 1;
		}
		return level.getMinY();
	}

	/** logs, trunk columns (log on the ground), lowest/highest ground, saplings, dirt pillars above ground. */
	private static int[] count(TestSingleplayerContext sp, int x1, int z1, int x2, int z2) {
		return sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			int logs = 0, trunks = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, saplings = 0, dirt = 0;
			for (int x = x1; x <= x2; x++) {
				for (int z = z1; z <= z2; z++) {
					int g = ground(level, x, z);
					lo = Math.min(lo, g);
					hi = Math.max(hi, g);
					if (level.getBlockState(new BlockPos(x, g, z)).is(BlockTags.LOGS)) trunks++;
					if (level.getBlockState(new BlockPos(x, g, z)).getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) saplings++;
					for (int y = g; y < g + 60; y++) {
						BlockState s = level.getBlockState(new BlockPos(x, y, z));
						if (s.is(BlockTags.LOGS)) logs++;
						if (y > g && s.is(Blocks.DIRT)) dirt++;
					}
				}
			}
			return new int[] {logs, trunks, lo, hi, saplings, dirt};
		});
	}
}
