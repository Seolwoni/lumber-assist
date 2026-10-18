package com.seolwoni.lumberassist.hud;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seolwoni.lumberassist.LumberAssistClient;
import com.seolwoni.lumberassist.config.LumberConfig;

import net.fabricmc.loader.api.FabricLoader;

/** Logs per tree kind, trees felled, saplings planted, rounds, trips and time. */
public class LumberStats {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lumberassist-stats.json");

	public static LumberStats session = new LumberStats();
	public static LumberStats total = new LumberStats();

	public static class Entry {
		public long count;
		public long lastMs;
	}

	public Map<String, Entry> logs = new LinkedHashMap<>();
	public long trees, planted, ticks, rounds, trips;

	public static LumberStats shown() {
		return LumberConfig.get().statsTotals ? total : session;
	}

	public long totalLogs() {
		long n = 0;
		for (Entry e : logs.values()) n += e.count;
		return n;
	}

	public void reset() {
		logs.clear();
		trees = planted = ticks = rounds = trips = 0;
	}

	public static void log(String kind) {
		long now = System.currentTimeMillis();
		for (LumberStats s : new LumberStats[] {session, total}) {
			Entry e = s.logs.computeIfAbsent(kind, k -> new Entry());
			e.count++;
			e.lastMs = now;
		}
	}

	public static void tree(String kind) {
		session.trees++;
		total.trees++;
	}

	public static void planted() {
		session.planted++;
		total.planted++;
	}

	public static void tick() {
		session.ticks++;
		total.ticks++;
		if (total.ticks % 1200 == 0) saveTotal();
	}

	public static void round() {
		session.rounds++;
		total.rounds++;
		saveTotal();
	}

	public static void trip() {
		session.trips++;
		total.trips++;
		saveTotal();
	}

	public static void loadTotal() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					LumberStats s = GSON.fromJson(r, LumberStats.class);
					if (s != null) {
						if (s.logs == null) s.logs = new LinkedHashMap<>();
						total = s;
					}
				}
			}
		} catch (Exception e) {
			LumberAssistClient.LOGGER.warn("Failed to load stats", e);
		}
	}

	public static void saveTotal() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(total, w);
			}
		} catch (Exception e) {
			LumberAssistClient.LOGGER.warn("Failed to save stats", e);
		}
	}
}
