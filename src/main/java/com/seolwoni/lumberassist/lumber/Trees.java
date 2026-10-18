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
			case "oak" -> "참나무";
			case "spruce" -> "가문비나무";
			case "birch" -> "자작나무";
			case "jungle" -> "정글나무";
			case "acacia" -> "아카시아나무";
			case "dark_oak" -> "짙은 참나무";
			case "mangrove" -> "맹그로브나무";
			case "cherry" -> "벚나무";
			case "pale_oak" -> "창백한 참나무";
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
		return s.is(BlockTags.LOGS_THAT_BURN);
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

	public static boolean soil(BlockPos p) {
		BlockState s = Blocks2.state(p);
		return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND);
	}

	/** Trunk bottoms on the area's ground layer, grouped into trees. */
	public static List<Tree> find(AreaEntry a) {
		LumberConfig cfg = LumberConfig.get();
		Set<BlockPos> used = new HashSet<>();
		List<Tree> out = new ArrayList<>();
		for (int x = a.minX(); x <= a.maxX(); x++) {
			for (int z = a.minZ(); z <= a.maxZ(); z++) {
				BlockPos p = new BlockPos(x, a.y, z);
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
		return out;
	}

	private static boolean isBase(BlockPos p) {
		return isLog(p) && !isLog(p.below()) && soil(p.below());
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
