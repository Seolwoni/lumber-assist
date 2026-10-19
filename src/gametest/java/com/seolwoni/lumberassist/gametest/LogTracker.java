package com.seolwoni.lumberassist.gametest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.seolwoni.lumberassist.lumber.Lumberjack;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

/**
 * Diagnosis only (changes nothing in the game): follows every log item in and around the area from the moment it
 * appears until it is picked up, merges into another stack, despawns or the test ends, and notes what the
 * lumberjack was doing whenever something happened to it.
 */
final class LogTracker {
	static final class Drop {
		int id, count, born, gone = -1, age;
		String item, end = "";
		BlockPos cell;
		boolean onLeaves;
		final List<String> story = new ArrayList<>();
	}

	final Map<Integer, Drop> all = new LinkedHashMap<>();
	private final AABB box;

	LogTracker(int x1, int z1, int x2, int z2) {
		box = new AABB(x1 - 16, -64, z1 - 16, x2 + 17, 320, z2 + 17);
	}

	void scan(ClientGameTestContext context, TestSingleplayerContext sp, int tick) {
		String status = context.computeOnClient(mc -> Lumberjack.status());
		sp.getServer().runOnServer(server -> {
			var level = server.overworld();
			var pl = server.getPlayerList().getPlayers().get(0);
			java.util.Set<Integer> alive = new java.util.HashSet<>();
			Map<Integer, Integer> grew = new java.util.HashMap<>();
			for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
				if (!ie.getItem().is(net.minecraft.tags.ItemTags.LOGS)) continue;
				alive.add(ie.getId());
				BlockPos at = ie.blockPosition();
				String below = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(at.below()).getBlock()).getPath();
				boolean leaves = below.endsWith("leaves") || net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).getPath().endsWith("leaves");
				Drop d = all.get(ie.getId());
				if (d == null) {
					d = new Drop();
					d.id = ie.getId();
					d.item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()).getPath();
					d.count = ie.getItem().getCount();
					d.born = tick;
					d.cell = at;
					d.onLeaves = leaves;
					d.story.add("t" + tick + " appeared at " + at.toShortString() + " on " + below + " | player " + pl.blockPosition().toShortString() + " | " + status);
					all.put(d.id, d);
				} else {
					if (ie.getItem().getCount() > d.count) grew.put(d.id, ie.getItem().getCount() - d.count);
					d.count = ie.getItem().getCount();
					if (!at.equals(d.cell)) {
						d.story.add("t" + tick + " moved " + d.cell.toShortString() + " -> " + at.toShortString() + " on " + below + " | " + status);
						d.cell = at;
					}
					d.onLeaves = leaves;
				}
				d.age = ie.getAge();
			}
			for (Drop d : all.values()) {
				if (d.gone >= 0 || alive.contains(d.id)) continue;
				d.gone = tick;
				double dist = pl.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(d.cell));
				String into = null;
				for (Map.Entry<Integer, Integer> g : grew.entrySet()) {
					Drop o = all.get(g.getKey());
					if (o != null && o.cell.distManhattan(d.cell) <= 2) into = "merged into the stack at " + o.cell.toShortString();
				}
				if (into != null) d.end = "merged";
				else if (dist < 3.0) d.end = "picked up";
				else if (d.age >= 5900) d.end = "DESPAWNED (5 min on the ground)";
				else d.end = "gone " + String.format("%.1f", dist) + " blocks from the player";
				d.story.add("t" + tick + " " + (into != null ? into : d.end) + " | age " + d.age / 20 + " s | " + status);
			}
		});
	}

	void report(TestSingleplayerContext sp, List<String> log) {
		sp.getServer().runOnServer(server -> {
			for (Drop d : all.values()) {
				if (d.gone >= 0) continue;
				var e = server.overworld().getEntity(d.id);
				d.end = e != null && e.isAlive() ? "LOST (still lying there)" : "picked up";
			}
		});
		int picked = 0, lost = 0, despawned = 0, other = 0;
		Map<String, int[]> why = new TreeMap<>();
		for (Drop d : all.values()) {
			if (d.end.equals("picked up")) picked += d.count;
			else if (d.end.equals("merged")) continue;
			else {
				if (d.end.startsWith("LOST")) lost += d.count;
				else if (d.end.startsWith("DESPAWNED")) despawned += d.count;
				else other += d.count;
				String born = d.story.get(0);
				String what = born.substring(born.lastIndexOf('|') + 2);
				String where = born.contains(" on ") ? born.substring(born.indexOf(" on ") + 4, born.indexOf(" |")) : "?";
				why.computeIfAbsent(d.end.replaceAll(" \\d.*", "") + " / landed on " + (d.onLeaves ? "leaves" : where) + " / appeared while: " + what, k -> new int[1])[0] += d.count;
			}
		}
		log.add("LOGS: picked up " + picked + ", lost " + lost + " (lying), despawned " + despawned + ", other " + other);
		for (var e : why.entrySet()) log.add("  missing " + e.getValue()[0] + ": " + e.getKey());
		for (Drop d : all.values()) {
			if (d.end.equals("picked up") || d.end.equals("merged")) continue;
			log.add("  " + d.end + ": " + d.item + " x" + d.count);
			for (String s : d.story.subList(0, Math.min(d.story.size(), 12))) log.add("      " + s);
		}
	}
}
