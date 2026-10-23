package com.seolwoni.lumberassist.lumber;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.seolwoni.assistcore.util.Blocks2;
import com.seolwoni.lumberassist.config.LumberConfig;
import com.seolwoni.lumberassist.config.LumberConfig.AreaEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Finding trees in an area and working out which logs belong to them. */
public final class Trees {
	private Trees() {
	}

	/** A tree: its trunk bottom block(s) (1, or 4 for 2x2 trees) and its kind. */
	public record Tree(List<BlockPos> bases, String kind) {
		public BlockPos first() {
			return bases.get(0);
		}
	}

	public static String label(String kind) {
		return switch (kind) {
			case "oak" -> com.seolwoni.assistcore.i18n.Tr.t("참나무");
			case "spruce" -> com.seolwoni.assistcore.i18n.Tr.t("가문비나무");
			case "birch" -> com.seolwoni.assistcore.i18n.Tr.t("자작나무");
			case "jungle" -> com.seolwoni.assistcore.i18n.Tr.t("정글나무");
			case "acacia" -> com.seolwoni.assistcore.i18n.Tr.t("아카시아나무");
			case "dark_oak" -> com.seolwoni.assistcore.i18n.Tr.t("짙은 참나무");
			case "mangrove" -> com.seolwoni.assistcore.i18n.Tr.t("맹그로브나무");
			case "cherry" -> com.seolwoni.assistcore.i18n.Tr.t("벚나무");
			case "pale_oak" -> com.seolwoni.assistcore.i18n.Tr.t("창백한 참나무");
			default -> kind;
		};
	}

	public static int color(String kind) {
		return switch (kind) {
			case "oak" -> 0xFFB8945F;
			case "spruce" -> 0xFF7A5A3A;
			case "birch" -> 0xFFE6DFC8;
			case "jungle" -> 0xFFB88764;
			case "acacia" -> 0xFFD0743E;
			case "dark_oak" -> 0xFF5C4128;
			case "mangrove" -> 0xFFA0433E;
			case "cherry" -> 0xFFF0B8C8;
			case "pale_oak" -> 0xFFE8E2DC;
			default -> 0xFFFFFFFF;
		};
	}

	public static boolean isLog(BlockPos p) {
		BlockState s = Blocks2.state(p);
		if (!s.is(BlockTags.LOGS)) return false;
		// Nether "trees" (stems, hyphae) are not ours.
		String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		return !path.endsWith("_stem") && !path.endsWith("_hyphae");
	}

	public static boolean isLeaves(BlockPos p) {
		return Blocks2.state(p).is(BlockTags.LEAVES);
	}

	/** "oak" for oak_log / oak_wood / stripped_oak_log, ... */
	public static String kind(BlockPos p) {
		String path = BuiltInRegistries.BLOCK.getKey(Blocks2.state(p).getBlock()).getPath();
		path = path.replace("stripped_", "");
		for (String suffix : new String[] {"_log", "_wood"}) {
			if (path.endsWith(suffix)) return path.substring(0, path.length() - suffix.length());
		}
		return path;
	}

	public static Item sapling(String kind) {
		if (kind.equals("mangrove")) return Items.MANGROVE_PROPAGULE;
		Item i = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(kind + "_sapling"));
		return i == Items.AIR ? null : i;
	}

	/** Trees that only grow back from four saplings in a square. */
	public static boolean needsFour(String kind) {
		return kind.equals("dark_oak") || kind.equals("pale_oak");
	}

	/** Ground a sapling can grow on. */
	public static boolean soil(BlockPos p) {
		BlockState s = Blocks2.state(p);
		return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT)
				|| s.is(Blocks.PODZOL) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT) || s.is(Blocks.MYCELIUM)
				|| s.is(Blocks.MOSS_BLOCK) || s.is(Blocks.MUD) || s.is(Blocks.MUDDY_MANGROVE_ROOTS) || s.is(Blocks.PALE_MOSS_BLOCK);
	}

	/** How far above and below the marked ground heights tree bottoms are still looked for (hills, slopes). */
	public static final int MARGIN = 8;

	/** Trunk bottoms on the area's ground (within {@link #MARGIN} of the marked heights), grouped into trees. */
	public static List<Tree> find(AreaEntry a) {
		LumberConfig cfg = LumberConfig.get();
		Set<BlockPos> used = new HashSet<>();
		List<Tree> out = new ArrayList<>();
		for (int x = a.minX(); x <= a.maxX(); x++) {
			for (int z = a.minZ(); z <= a.maxZ(); z++) {
				for (int y = a.minY() - MARGIN; y <= a.maxY() + MARGIN; y++) {
				BlockPos p = new BlockPos(x, y, z);
				if (used.contains(p) || !isBase(p)) continue;
				String kind = kind(p);
				if (!cfg.trees.contains(kind)) continue;
				List<BlockPos> bases = new ArrayList<>();
				bases.add(p);
				// 2x2 trunk: this is its north-west corner when the three others are trunk bottoms too.
				BlockPos e = p.east(), s = p.south(), se = p.south().east();
				if (isBase(e) && isBase(s) && isBase(se)) {
					bases.add(e);
					bases.add(s);
					bases.add(se);
				}
				used.addAll(bases);
				out.add(new Tree(bases, kind));
				}
			}
		}
		return out;
	}

	private static boolean isBase(BlockPos p) {
		// Not a branch resting on leaves (the ground may now be a few blocks higher or lower than marked).
		return isLog(p) && !isLog(p.below()) && !isLeaves(p.below()) && (soil(p.below()) || Blocks2.isSolid(p.below()));
	}

	/**
	 * Logs connected to the trunk (26-neighbourhood), within {@code radius} blocks sideways and
	 * {@code maxHeight} up. Lowest first.
	 */
	public static List<BlockPos> logs(Tree t, int radius, int maxHeight) {
		BlockPos o = t.first();
		Set<BlockPos> seen = new HashSet<>(t.bases());
		Deque<BlockPos> open = new ArrayDeque<>(t.bases());
		List<BlockPos> out = new ArrayList<>();
		while (!open.isEmpty() && out.size() < 600) {
			BlockPos c = open.poll();
			if (!isLog(c)) continue;
			out.add(c);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos q = c.offset(dx, dy, dz);
						if (seen.contains(q)) continue;
						if (Math.abs(q.getX() - o.getX()) > radius || Math.abs(q.getZ() - o.getZ()) > radius) continue;
						if (q.getY() - o.getY() > maxHeight) continue;
						seen.add(q);
						if (isLog(q)) open.add(q);
					}
				}
			}
		}
		out.sort(Comparator.comparingInt(BlockPos::getY));
		return out;
	}
}
