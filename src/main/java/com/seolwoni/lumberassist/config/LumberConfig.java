package com.seolwoni.lumberassist.config;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.lumberassist.LumberAssistClient;

import net.fabricmc.loader.api.FabricLoader;

/** All Lumber Assist settings. Saved to config/lumberassist.json. */
public class LumberConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("lumberassist.json");

	/** Tree kinds that can be switched on/off (the wood name in the block id, e.g. oak_log). */
	public static final List<String> TREES = List.of(
			"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak");

	// Keep after the static lists above: field initialisers below copy them.
	private static LumberConfig instance = new LumberConfig();

	public boolean modEnabled = true;
	public boolean running = false;

	// ---- rounds ----
	public int waitMinutes = 5;
	public boolean humanDelay = true;
	public boolean replant = true;
	public int saplingKeep = 16;
	public boolean collectDrops = true;
	/** Highest trunk we climb (blocks above the ground). */
	public int maxHeight = 32;

	// ---- trees ----
	public List<String> trees = new ArrayList<>(TREES);

	// ---- areas ----
	public List<AreaEntry> areas = new ArrayList<>();
	/** Where trees stood, so saplings can be put back even if the first try had none. */
	public List<Spot> spots = new ArrayList<>();

	// ---- axe ----
	public int protectDurability = 10;

	// ---- safety / food ----
	public boolean stopOnMob = true;
	/** Mobs: 0 = avoid them (Defense), 1 = avoid and fight back, 2 = stop the job, 3 = ignore. */
	public int mobMode = 0;
	public int mobRange = 8;
	public int minHealth = 8;
	public boolean autoEat = true;
	public int eatThreshold = 14;

	// ---- storage ----
	public boolean storeEnabled = true;
	/** 보관 방식: 0 상자, 1 엔더 상자, 2 셜커 상자, 3 자동 (see PortableStorage). */
	public int storeMode = 0;
	public List<ChestEntry> chests = new ArrayList<>();

	// ---- HUD ----
	public boolean statusHud = true;
	public float hudX = 0.01f, hudY = 0.2f, hudScale = 1.0f;
	public boolean statsHud = true;
	public float statsX = 0.01f, statsY = 0.45f, statsScale = 1.0f;
	public boolean statsTotals = false;
	public int hudBgAlpha = 110;

	public static class AreaEntry {
		/** Ground-layer corners: the block a sapling sits in. {@code y} is the lower corner's height. */
		public int x1, z1, x2, z2, y;
		/** The higher corner's height (uneven ground); null for areas marked before 1.3. */
		public Integer y2;
		public String name = "";

		public int minY() {
			return y2 == null ? y : Math.min(y, y2);
		}

		public int maxY() {
			return y2 == null ? y : Math.max(y, y2);
		}

		public int minX() {
			return Math.min(x1, x2);
		}

		public int maxX() {
			return Math.max(x1, x2);
		}

		public int minZ() {
			return Math.min(z1, z2);
		}

		public int maxZ() {
			return Math.max(z1, z2);
		}

		public int sizeX() {
			return maxX() - minX() + 1;
		}

		public int sizeZ() {
			return maxZ() - minZ() + 1;
		}

		public boolean contains(int x, int z, int margin) {
			return x >= minX() - margin && x <= maxX() + margin && z >= minZ() - margin && z <= maxZ() + margin;
		}
	}

	/** A trunk position and the tree kind that grew there. */
	public static class Spot {
		public int x, y, z;
		public String tree = "oak";
	}

	public static LumberConfig get() {
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(PATH)) {
				try (Reader r = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
					LumberConfig c = GSON.fromJson(r, LumberConfig.class);
					if (c != null) instance = c;
				}
			}
		} catch (Exception e) {
			LumberAssistClient.LOGGER.warn("Failed to load config", e);
		}
		LumberConfig c = instance;
		if (c.trees == null) c.trees = new ArrayList<>(TREES);
		if (c.areas == null) c.areas = new ArrayList<>();
		if (c.spots == null) c.spots = new ArrayList<>();
		if (c.chests == null) c.chests = new ArrayList<>();
		for (ChestEntry e : c.chests) if (e.items == null) e.items = new ArrayList<>();
		c.running = false;
		c.waitMinutes = Math.max(0, Math.min(120, c.waitMinutes));
		c.saplingKeep = Math.max(0, Math.min(256, c.saplingKeep));
		c.maxHeight = Math.max(4, Math.min(64, c.maxHeight));
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer w = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (Exception e) {
			LumberAssistClient.LOGGER.warn("Failed to save config", e);
		}
	}
}
