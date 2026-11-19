package de.nomisimo.storagedim.robot;

import java.util.ArrayDeque;
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
 * top of the ground down to the bottom of the world (a quarry; the station stays on its pillar). It is dug from the top down: for every column of the area the job knows its
 * highest block still to dig, and the robots take the blocks of the highest layers first, each the one closest to
 * itself that no other robot has taken.
 * <p>
 * A spiral staircase around a pillar stays standing, so the station can always be reached: the pillar is the column
 * of the station (or, when the station sits at a corner of its antenna box, the column diagonally inside the box),
 * and of the eight columns around it each keeps one block every eight blocks of height, one higher than the one
 * before. Sand or gravel under a step keeps the block it rests on. Blocks with contents (chests, furnaces, ...),
 * the station, antennas, robot barriers, lava and unbreakable blocks are never dug. Water is pumped away (the robot
 * takes it out like a block, in no time), so lakes and flooded caves in the area are emptied too.
 */
public final class MiningJob implements DigJob {
	/** The eight columns around the pillar, in walking order; corners are even, so no step sits right on a chest beside the station. */
	/** How far the platform reaches from the station. */
	static final int PLATFORM = 4;
	static final int[][] RING = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
	/** How long a block a robot found no way to is left out for it (ticks). */
	private static final int SKIP_TICKS = 1200;
	/** Once this many robots found no way to a block, it is left out for all of them. */
	private static final int SKIP_ROBOTS = 3;
	/** A job nobody asked for in this long is forgotten. */
	private static final int FORGET_TICKS = 6000;
	/** Block checks per call while the columns are looked at for the first time. */
	private static final int SCAN_BUDGET = 6000;
	/** Layers below the highest one that robots may already take blocks from. */
	private static final int LAYER_BAND = 2;
	private static final int CLEARED = Integer.MIN_VALUE;
	/** Water that is back this often: its cobblestone is left as a wall. */
	private static final int MAX_PUMPS = 3;

	private static final Map<String, MiningJob> JOBS = new HashMap<>();

	private final String dim;
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
	/** Columns that were not loaded when their turn came: looked at again on every call until they are. */
	private final ArrayDeque<Long> unloaded = new ArrayDeque<>();
	/** Blocks a robot is on its way to or digging (packed position to robot). */
	private final Map<Long, UUID> claimed = new HashMap<>();
	/** When each robot last asked for work: what a robot that went away (picked up, unloaded) had taken is free again. */
	private final Map<UUID, Long> seen = new HashMap<>();
	private static final int CLAIM_TICKS = 1200;
	/** Blocks left out for all robots, until when. */
	private final Map<Long, Long> skipped = new HashMap<>();
	/** Blocks single robots found no way to: the robot and until when. One robot standing in a hole does not keep the others away from them. */
	private final Map<Long, Map<UUID, Long>> failed = new HashMap<>();
	/** How often water at a place was turned into cobblestone: where it keeps coming back, the cobblestone stays as a wall. */
	private final Map<Long, Integer> pumped = new HashMap<>();
	private long lastUsed;
	/** The other stations of the station's group: their platforms stay as well. */
	private List<BlockPos> group = List.of();
	/** Blocks from the highest one to dig of each column down to the bottom, when it was first looked at: the work at the start. */
	private long initial;
	/** The same for the columns now: what is left. */
	private long remaining;
	/** When the progress was last saved for the station screen. */
	private long progressTick = Long.MIN_VALUE;

	private MiningJob(String dim, BlockPos station, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		this.dim = dim;
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
			job = new MiningJob(dim, station, minX, minY, minZ, maxX, maxY, maxZ);
			JOBS.put(key, job);
		}

		job.lastUsed = now;
		return job;
	}

	/** The stations of the group this job's station leads (its own included), whose platforms stay. */
	public void setGroup(List<BlockPos> members) {
		if (!group.equals(members)) {
			group = List.copyOf(members);
		}
	}

	/** Mining switched off or on again at a station: what was known is looked at afresh (blocks may have been placed since). */
	public static void reset(String dim, BlockPos station) {
		JOBS.remove(dim + "@" + station.asLong());
	}

	/** The job of a station while its robots are at it, or null; for the station screen. */
	public static MiningJob peek(String dim, BlockPos station) {
		return JOBS.get(dim + "@" + station.asLong());
	}

	/** Which quarry this is, for the saved progress: a changed area starts again at zero. */
	private String key() {
		return "mine:" + minX + "," + minY + "," + minZ + "," + maxX + "," + maxY + "," + maxZ;
	}

	/**
	 * How far the quarry is, in tenths of a percent: of all the blocks from the top of each column down to the
	 * bottom, how many are gone. -1 while not every column has been looked at yet. The work at the start is kept
	 * with the world, so after a restart the count goes on from there.
	 */
	int progress() {
		if (finished()) {
			return 1000;
		}

		if (scanned < order.length || !unloaded.isEmpty()) {
			return -1;
		}

		StationProgress.Entry saved = StationProgress.get(dim, station);
		long total = saved != null && saved.key().equals(key()) ? Math.max(saved.total(), initial) : initial;
		return total <= 0 ? 0 : (int) Math.clamp((total - remaining) * 1000 / total, 0, 999);
	}

	private void saveProgress(long now) {
		if (now - progressTick < 100) {
			return;
		}

		progressTick = now;
		int permille = progress();

		if (permille >= 0) {
			StationProgress.Entry saved = StationProgress.get(dim, station);
			long total = saved != null && saved.key().equals(key()) ? Math.max(saved.total(), initial) : initial;
			StationProgress.put(dim, station, new StationProgress.Entry(key(), total, permille));
		}
	}

	public BlockPos pillar() {
		return pillar;
	}

	public boolean contains(BlockPos pos) {
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	/** Every column has been looked at and none has anything left to dig. */
	@Override
	public boolean finished() {
		return scanned >= order.length && unloaded.isEmpty() && layers.isEmpty();
	}

	/** How many columns still have something to dig, and how many have not been looked at yet. */
	public int columnsLeft() {
		return tops.size() + order.length - scanned + unloaded.size();
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

	/**
	 * The platform around the station: {@value #PLATFORM} blocks all round (9 by 9), one block thick under the
	 * station's floor, and everything on it (chests, a house, waystones) stays. Only the way down to the staircase is
	 * opened: the ring around the pillar in the platform itself.
	 */
	public boolean onPlatform(BlockPos pos) {
		int dx = pos.getX() - station.getX();
		int dz = pos.getZ() - station.getZ();

		if (Math.abs(dx) > PLATFORM || Math.abs(dz) > PLATFORM || pos.getY() < station.getY() - 1) {
			// in a station group the same platform round each further station, without a way down
			for (BlockPos member : group) {
				if (!member.equals(station) && Math.abs(pos.getX() - member.getX()) <= PLATFORM && Math.abs(pos.getZ() - member.getZ()) <= PLATFORM
						&& pos.getY() >= member.getY() - 1 && pos.getY() >= station.getY() - 1) {
					return true;
				}
			}

			return false;
		}

		boolean ring = Math.abs(pos.getX() - pillar.getX()) <= 1 && Math.abs(pos.getZ() - pillar.getZ()) <= 1;
		return !(ring && pos.getY() == station.getY() - 1);
	}

	/** A block the robots never dig: the staircase and what holds it up, storage and other block entities, mod blocks. */
	public boolean isProtected(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.hasBlockEntity() || state.is(RobotStationBlock.BLOCK) || state.is(AntennaBlock.BLOCK) || state.is(RobotBarrierBlock.BLOCK)) {
			return true;
		}

		if (isStair(pos) || onPlatform(pos)) {
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

	/**
	 * Water the robots get rid of: still and flowing water and bubble columns. It is not taken away (a pond of two by
	 * two would only fill up again) but turned into cobblestone, which is then dug like any other block. (Waterlogged
	 * blocks and plants under water are dug as usual; the water they leave goes the same way.)
	 */
	public static boolean isWater(BlockState state) {
		return state.is(Blocks.WATER) || state.is(Blocks.BUBBLE_COLUMN);
	}

	/** To dig or pump away: a block that may be dug, or water anywhere in the area (even where a step goes). */
	private boolean toClear(ServerLevel level, BlockPos pos, BlockState state) {
		if (isWater(state)) {
			return true;
		}

		// where water kept coming back (from a sea beside the area) the cobblestone stays as a wall
		return diggable(level, pos, state) && !isProtected(level, pos, state) && pumped.getOrDefault(pos.asLong(), 0) < MAX_PUMPS;
	}

	@Override
	public boolean wanted(ServerLevel level, BlockPos pos) {
		return contains(pos) && toClear(level, pos, level.getBlockState(pos));
	}

	/**
	 * A solid block right beside (or below) still standing water of the area: left until that water is filled, or the
	 * water would run into the hole again and again. Water from outside the area does not count; where it keeps
	 * coming back the block stays as a wall after a few tries.
	 */
	private boolean besideWater(ServerLevel level, BlockPos pos) {
		if (isWater(level.getBlockState(pos))) {
			return false;
		}

		for (Direction side : Direction.values()) {
			if (side == Direction.DOWN) {
				continue;
			}

			BlockPos next = pos.relative(side);

			if (contains(next) && level.isLoaded(next) && level.getFluidState(next).isSource() && isWater(level.getBlockState(next))) {
				return true;
			}
		}

		return false;
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

			if (toClear(level, pos, state)) {
				return y;
			}
		}

		return CLEARED;
	}

	private void setTop(long column, int y) {
		Integer old = tops.remove(column);

		if (old != null) {
			remaining -= old - minY + 1;
			Set<Long> layer = layers.get(old);

			if (layer != null) {
				layer.remove(column);

				if (layer.isEmpty()) {
					layers.remove(old);
				}
			}
		}

		if (y != CLEARED) {
			remaining += y - minY + 1;
			tops.put(column, y);
			layers.computeIfAbsent(y, k -> new LinkedHashSet<>()).add(column);
		}
	}

	/**
	 * Looks at more columns for the first time, closest to the station first. A column that is not loaded yet goes
	 * to the back and is looked at again on the next calls, so the ones behind it are not held up. (They used to be
	 * swapped with the last column, which bounced two unloaded columns back and forth and stopped the scan there for
	 * good: the robots only ever knew the columns closest to the station.)
	 */
	private void scan(ServerLevel level) {
		int[] budget = {0};

		while (scanned < order.length && budget[0] < SCAN_BUDGET) {
			long column = order[scanned++];
			Integer top = findTop(level, columnX(column), columnZ(column), surface(level, column), budget);

			if (top == null) {
				unloaded.add(column);
				budget[0] += 16;
			} else {
				setTop(column, top);
				initial += top == CLEARED ? 0 : top - minY + 1;
			}
		}

		for (int n = unloaded.size(); n > 0 && budget[0] < SCAN_BUDGET; n--) {
			long column = unloaded.poll();
			Integer top = findTop(level, columnX(column), columnZ(column), surface(level, column), budget);

			if (top == null) {
				unloaded.add(column);
				budget[0] += 16;
			} else {
				setTop(column, top);
				initial += top == CLEARED ? 0 : top - minY + 1;
			}
		}
	}

	/** The highest block of a column (from the height map), so the air above the ground is not looked through block by block. */
	private int surface(ServerLevel level, long column) {
		if (!level.isLoaded(new BlockPos(columnX(column), minY, columnZ(column)))) {
			// not loaded: the height map would load it; findTop sees that and tries again later
			return maxY;
		}

		int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, columnX(column), columnZ(column)) - 1;
		return Math.min(maxY, Math.max(top, minY));
	}

	/** A block was dug (or turned out to be gone): its column looks further down. */
	/** Water was turned into cobblestone there. */
	public void pumped(BlockPos pos) {
		pumped.merge(pos.asLong(), 1, Integer::sum);
	}

	@Override
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
	@Override
	public List<BlockPos> candidates(ServerLevel level, BlockPos from, UUID robot, int count, long now) {
		scan(level);
		saveProgress(now);
		forget(now);
		claimed.values().removeIf(robot::equals);
		seen.put(robot, now);
		seen.values().removeIf(last -> now - last > CLAIM_TICKS);
		claimed.values().removeIf(owner -> !seen.containsKey(owner));
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

				if (claimed.containsKey(pos.asLong()) || skippedFor(pos, robot)) {
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

				if (!exposed(level, pos) || besideWater(level, pos)) {
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
	@Override
	public List<BlockPos> nearby(ServerLevel level, BlockPos from, UUID robot, int count, long now) {
		forget(now);
		List<BlockPos> found = new ArrayList<>();

		for (BlockPos pos : BlockPos.betweenClosed(from.offset(-6, -1, -6), from.offset(6, 5, 6))) {
			if (!contains(pos) || claimed.containsKey(pos.asLong()) && !robot.equals(claimed.get(pos.asLong())) || skippedFor(pos, robot)) {
				continue;
			}

			if (wanted(level, pos) && exposed(level, pos) && !besideWater(level, pos)) {
				found.add(pos.immutable());
			}
		}

		// what it reaches from where it stands first (needs no way: a robot shut in by leaves digs itself free),
		// then from the top down
		found.sort(Comparator.<BlockPos>comparingInt(pos -> pos.distSqr(from) <= 12 && pos.getY() >= from.getY() ? 0 : 1).thenComparingInt(pos -> -pos.getY())
				.thenComparingDouble(pos -> pos.distSqr(from)));
		return found.size() > count ? new ArrayList<>(found.subList(0, count)) : found;
	}

	@Override
	public void claim(BlockPos pos, UUID robot) {
		claimed.put(pos.asLong(), robot);
	}

	@Override
	public void release(BlockPos pos, UUID robot) {
		claimed.remove(pos.asLong(), robot);
	}

	/**
	 * The robot found no way there: left out for it for a minute, so it goes on with others. Once several robots
	 * found none, it is left out for all of them, so they go on below.
	 */
	@Override
	public void skip(BlockPos pos, UUID robot, long now) {
		Map<UUID, Long> robots = failed.computeIfAbsent(pos.asLong(), k -> new HashMap<>());
		robots.put(robot, now + SKIP_TICKS);

		if (robots.size() >= SKIP_ROBOTS) {
			skipped.put(pos.asLong(), now + SKIP_TICKS);
			failed.remove(pos.asLong());
		}

		claimed.remove(pos.asLong());
	}

	private boolean skippedFor(BlockPos pos, UUID robot) {
		if (skipped.containsKey(pos.asLong())) {
			return true;
		}

		Map<UUID, Long> robots = failed.get(pos.asLong());
		return robots != null && robots.containsKey(robot);
	}

	private void forget(long now) {
		skipped.values().removeIf(until -> until < now);

		for (Iterator<Map<UUID, Long>> it = failed.values().iterator(); it.hasNext(); ) {
			Map<UUID, Long> robots = it.next();
			robots.values().removeIf(until -> until < now);

			if (robots.isEmpty()) {
				it.remove();
			}
		}
	}

	/** For the smoke test: the layers and their columns. */
	NavigableMap<Integer, Set<Long>> layers() {
		return layers;
	}
}
