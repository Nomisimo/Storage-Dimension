package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the mining robots of one station still have to dig, shared by all of them so up to 32 work together without
 * getting in each other's way.
 * <p>
 * The area is the station's antenna box, or without antennas a square of its range around the station, from the
 * station's height up by the range. It is dug from the top down: for every column of the area the job knows its
 * highest block still to dig, and the robots take the blocks of the highest layers first, each the one closest to
 * itself that no other robot has taken.
 * <p>
 * A spiral staircase around a pillar stays standing, so the station can always be reached: the pillar is the column
 * of the station (or, when the station sits at a corner of its antenna box, the column diagonally inside the box),
 * and of the eight columns around it each keeps one block every eight blocks of height, one higher than the one
 * before. Sand or gravel under a step keeps the block it rests on. Blocks with contents (chests, furnaces, ...),
 * the station, antennas, robot barriers, liquids and unbreakable blocks are never dug.
 */
public final class MiningJob {
	/** The eight columns around the pillar, in walking order; corners are even, so no step sits right on a chest beside the station. */
	private static final int[][] RING = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
	/** How long a block no robot found a way to is left out (ticks). */
	private static final int SKIP_TICKS = 1200;
	/** A job nobody asked for in this long is forgotten. */
	private static final int FORGET_TICKS = 6000;
	/** Block checks per call while the columns are looked at for the first time. */
	private static final int SCAN_BUDGET = 6000;
	/** Layers below the highest one that robots may already take blocks from. */
	private static final int LAYER_BAND = 2;
	private static final int CLEARED = Integer.MIN_VALUE;

	private static final Map<String, MiningJob> JOBS = new HashMap<>();

	private final BlockPos station;
	private final int minX;
	private final int minY;
	private final int minZ;
	private final int maxX;
	private final int maxY;
	private final int maxZ;
	/** The column the staircase winds around. */
	private final BlockPos pillar;
	/** Column (x, z packed) to the height of its highest block still to dig. */
	private final Map<Long, Integer> tops = new HashMap<>();
	/** The same, by height: the columns whose highest block to dig is at that height. */
	private final TreeMap<Integer, Set<Long>> layers = new TreeMap<>();
	/** Columns in the order they are looked at the first time: closest to the station first. */
	private final long[] order;
	private int scanned;
	/** Blocks a robot is on its way to or digging (packed position to robot). */
	private final Map<Long, UUID> claimed = new HashMap<>();
	private final Map<Long, Long> skipped = new HashMap<>();
	private long lastUsed;

	private MiningJob(BlockPos station, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		this.station = station.immutable();
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		int px = station.getX();
		int pz = station.getZ();

		// a station at the edge of its box: the staircase moves inside, next to it
		if (px - 1 < minX) {
			px++;
		} else if (px + 1 > maxX) {
			px--;
		}

		if (pz - 1 < minZ) {
			pz++;
		} else if (pz + 1 > maxZ) {
			pz--;
		}

		this.pillar = new BlockPos(px, station.getY(), pz);
		List<long[]> columns = new ArrayList<>();

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				long dx = x - station.getX();
				long dz = z - station.getZ();
				columns.add(new long[] {column(x, z), dx * dx + dz * dz});
			}
		}

		columns.sort(Comparator.comparingLong(c -> c[1]));
		order = columns.stream().mapToLong(c -> c[0]).toArray();
	}

	/**
	 * The job of a station's area (inclusive block bounds). A job whose area changed starts afresh; one with the
	 * same area keeps what it knows.
	 */
	public static MiningJob get(String dim, BlockPos station, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long now) {
		JOBS.values().removeIf(job -> now - job.lastUsed > FORGET_TICKS);
		String key = dim + "@" + station.asLong();
		MiningJob job = JOBS.get(key);

		if (job == null || job.minX != minX || job.minY != minY || job.minZ != minZ || job.maxX != maxX || job.maxY != maxY || job.maxZ != maxZ) {
			job = new MiningJob(station, minX, minY, minZ, maxX, maxY, maxZ);
			JOBS.put(key, job);
		}

		job.lastUsed = now;
		return job;
	}

	/** Mining switched off or on again at a station: what was known is looked at afresh (blocks may have been placed since). */
	public static void reset(String dim, BlockPos station) {
		JOBS.remove(dim + "@" + station.asLong());
	}

	public BlockPos pillar() {
		return pillar;
	}

	public boolean contains(BlockPos pos) {
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	/** Every column has been looked at and none has anything left to dig. */
	public boolean finished() {
		return scanned >= order.length && layers.isEmpty();
	}

	/** How many columns still have something to dig, and how many have not been looked at yet. */
	public int columnsLeft() {
		return tops.size() + order.length - scanned;
	}

	public int highestLayer() {
		return layers.isEmpty() ? CLEARED : layers.lastKey();
	}

	// ---- what may be dug ----

	/** Part of the staircase: the pillar, or a step of the spiral around it. */
	public boolean isStair(BlockPos pos) {
		int dx = pos.getX() - pillar.getX();
		int dz = pos.getZ() - pillar.getZ();

		if (dx == 0 && dz == 0) {
			return true;
		}

		if (Math.max(Math.abs(dx), Math.abs(dz)) != 1) {
			return false;
		}

		int index = 0;

		for (int i = 0; i < RING.length; i++) {
			if (RING[i][0] == dx && RING[i][1] == dz) {
				index = i;
			}
		}

		// one step per column every eight blocks, each one higher than the one before it in the ring
		return Math.floorMod(pos.getY() - station.getY() - 1, RING.length) == index;
	}

	/** A block the robots never dig: the staircase and what holds it up, storage and other block entities, mod blocks. */
	public boolean isProtected(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.hasBlockEntity() || state.is(RobotStationBlock.BLOCK) || state.is(AntennaBlock.BLOCK) || state.is(RobotBarrierBlock.BLOCK)) {
			return true;
		}

		if (isStair(pos)) {
			return true;
		}

		// sand or gravel resting on it would fall: it stays when such a block of the staircase is above
		BlockPos.MutableBlockPos above = pos.mutable();

		for (int i = 0; i < 16; i++) {
			above.move(Direction.UP);

			if (!(level.getBlockState(above).getBlock() instanceof FallingBlock)) {
				return false;
			}

			if (isStair(above)) {
				return true;
			}
		}

		return false;
	}

	/** Something a robot can dig: a solid or loose block, not air, liquid, fire or something unbreakable. */
	public static boolean diggable(ServerLevel level, BlockPos pos, BlockState state) {
		return !state.isAir() && !(state.getBlock() instanceof LiquidBlock) && !(state.getBlock() instanceof BaseFireBlock)
				&& !state.is(Blocks.BUBBLE_COLUMN) && state.getDestroySpeed(level, pos) >= 0;
	}

	public boolean wanted(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return contains(pos) && diggable(level, pos, state) && !isProtected(level, pos, state);
	}

	/** A robot can get at it: one of its sides is open (air, water or a block without collision). */
	private static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction side : Direction.values()) {
			BlockPos next = pos.relative(side);

			if (level.getBlockState(next).getCollisionShape(level, next).isEmpty()) {
				return true;
			}
		}

		return false;
	}

	// ---- columns ----

	private static long column(int x, int z) {
		return (long) x << 32 | z & 0xFFFFFFFFL;
	}

	private static int columnX(long column) {
		return (int) (column >> 32);
	}

	private static int columnZ(long column) {
		return (int) column;
	}

	/**
	 * The highest block to dig in a column, looking down from {@code fromY}; {@link #CLEARED} when there is none,
	 * null when part of it is not loaded. Counts the blocks looked at into {@code budget[0]}.
	 */
	private Integer findTop(ServerLevel level, int x, int z, int fromY, int[] budget) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, fromY, z);

		if (!level.isLoaded(pos)) {
			return null;
		}

		for (int y = Math.min(fromY, maxY); y >= minY; y--) {
			pos.setY(y);
			budget[0]++;
			BlockState state = level.getBlockState(pos);

			if (diggable(level, pos, state) && !isProtected(level, pos, state)) {
				return y;
			}
		}

		return CLEARED;
	}

	private void setTop(long column, int y) {
		Integer old = tops.remove(column);

		if (old != null) {
			Set<Long> layer = layers.get(old);

			if (layer != null) {
				layer.remove(column);

				if (layer.isEmpty()) {
					layers.remove(old);
				}
			}
		}

		if (y != CLEARED) {
			tops.put(column, y);
			layers.computeIfAbsent(y, k -> new LinkedHashSet<>()).add(column);
		}
	}

	/** Looks at more columns for the first time, closest to the station first. */
	private void scan(ServerLevel level) {
		int[] budget = {0};

		while (scanned < order.length && budget[0] < SCAN_BUDGET) {
			long column = order[scanned];
			Integer top = findTop(level, columnX(column), columnZ(column), maxY, budget);

			if (top == null) {
				// not loaded (yet): looked at again once the others are done
				if (scanned == order.length - 1) {
					break;
				}

				order[scanned] = order[order.length - 1];
				order[order.length - 1] = column;
				budget[0] += 16;
				continue;
			}

			setTop(column, top);
			scanned++;
		}
	}

	/** A block was dug (or turned out to be gone): its column looks further down. */
	public void dug(ServerLevel level, BlockPos pos) {
		long column = column(pos.getX(), pos.getZ());
		Integer top = tops.get(column);

		if (top != null) {
			Integer next = findTop(level, pos.getX(), pos.getZ(), top, new int[1]);

			if (next != null) {
				setTop(column, next);
			}
		}

		claimed.remove(pos.asLong());
	}

	// ---- handing out blocks ----

	/**
	 * Blocks for a robot standing at {@code from}, best first: from the highest layers that still have blocks to
	 * dig, those closest to the robot. Blocks another robot took or none could get to are left out.
	 */
	public List<BlockPos> candidates(ServerLevel level, BlockPos from, UUID robot, int count, long now) {
		scan(level);
		skipped.values().removeIf(until -> until < now);
		claimed.values().removeIf(robot::equals);
		List<BlockPos> found = new ArrayList<>();
		List<long[]> moved = new ArrayList<>();
		Integer firstLayer = null;
		int checks = 0;

		for (Map.Entry<Integer, Set<Long>> layer : layers.descendingMap().entrySet()) {
			int y = layer.getKey();

			if (firstLayer != null && y < firstLayer - LAYER_BAND) {
				break;
			}

			for (Iterator<Long> it = layer.getValue().iterator(); it.hasNext() && checks < 40000; ) {
				long column = it.next();
				BlockPos pos = new BlockPos(columnX(column), y, columnZ(column));
				checks++;

				if (claimed.containsKey(pos.asLong()) || skipped.containsKey(pos.asLong())) {
					continue;
				}

				if (!level.isLoaded(pos)) {
					continue;
				}

				if (!wanted(level, pos)) {
					// dug by a player or fallen away: the column is looked at again below
					moved.add(new long[] {column, y});
					continue;
				}

				if (!exposed(level, pos)) {
					continue;
				}

				found.add(pos);
			}

			if (!found.isEmpty() && firstLayer == null) {
				firstLayer = y;
			}
		}

		for (long[] m : moved) {
			Integer next = findTop(level, columnX(m[0]), columnZ(m[0]), (int) m[1], new int[1]);

			if (next != null) {
				setTop(m[0], next);
			}
		}

		// the highest first, within those the closest; a little height counts like a little distance
		int base = firstLayer == null ? 0 : firstLayer;
		found.sort(Comparator.comparingDouble(pos -> pos.distSqr(from) - (pos.getY() - base) * 6.0));
		return found.size() > count ? new ArrayList<>(found.subList(0, count)) : found;
	}

	/**
	 * Blocks right around a robot, for when no highest block of a column can be got at (the area is roofed over, say,
	 * with the station down below): from its floor up as far as it reaches, the highest first. Digging these opens the
	 * staircase upwards step by step.
	 */
	public List<BlockPos> nearby(ServerLevel level, BlockPos from, UUID robot, int count, long now) {
		skipped.values().removeIf(until -> until < now);
		List<BlockPos> found = new ArrayList<>();

		for (BlockPos pos : BlockPos.betweenClosed(from.offset(-6, -1, -6), from.offset(6, 5, 6))) {
			if (!contains(pos) || claimed.containsKey(pos.asLong()) && !robot.equals(claimed.get(pos.asLong())) || skipped.containsKey(pos.asLong())) {
				continue;
			}

			if (wanted(level, pos) && exposed(level, pos)) {
				found.add(pos.immutable());
			}
		}

		found.sort(Comparator.<BlockPos>comparingInt(pos -> -pos.getY()).thenComparingDouble(pos -> pos.distSqr(from)));
		return found.size() > count ? new ArrayList<>(found.subList(0, count)) : found;
	}

	public void claim(BlockPos pos, UUID robot) {
		claimed.put(pos.asLong(), robot);
	}

	public void release(BlockPos pos, UUID robot) {
		claimed.remove(pos.asLong(), robot);
	}

	/** No robot found a way there: left out for a minute, so the others go on below. */
	public void skip(BlockPos pos, long now) {
		skipped.put(pos.asLong(), now + SKIP_TICKS);
		claimed.remove(pos.asLong());
	}

	/** For the smoke test: the layers and their columns. */
	NavigableMap<Integer, Set<Long>> layers() {
		return layers;
	}
}
