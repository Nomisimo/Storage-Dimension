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
 * top of the ground down to the bottom of the world (a quarry; the station stays on its pillar).
 * <p>
 * Once the station has looked at every column it makes a fixed plan, which the robots work through like a machine
 * through its program: the area is cut into levels three blocks high, starting at its highest block (trees and
 * plants left out), and each level is dug ring by ring from the staircase outwards (from the corner the station is
 * in, or around the station). A level may only start where the one above it is done two rings further out, and at
 * most three levels are dug at the same time, so the quarry goes down in terraces and the part already dug always
 * reaches the staircase. Trees and plants are taken whenever a robot gets at them (a log fells the whole tree).
 * Robots that cannot walk up to a block above them build themselves a pillar of cobblestone up to it.
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
		int pillarX = px;
		int pillarZ = pz;

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				int dx = x - pillarX;
				int dz = z - pillarZ;
				// the plan: ring by ring around the staircase (from the corner the station is in, or around it), each ring
				// walked round once, so the part already dug always reaches the staircase
				long angle = Math.round((Math.atan2(dz, dx) + Math.PI) * 1000);
				columns.add(new long[] {column(x, z), (long) Math.max(Math.abs(dx), Math.abs(dz)) << 16 | angle});
			}
		}

		columns.sort(Comparator.comparingLong(c -> c[1]));
		order = columns.stream().mapToLong(c -> c[0]).toArray();

		for (int i = 0; i < order.length; i++) {
			planIndex.put(order[i], i);
		}
	}

	// ---- the plan ----

	/** Plan step of each column: ring by ring around the staircase. */
	private final Map<Long, Integer> planIndex = new HashMap<>();
	/** The highest block of the area (trees and plants left out) when the plan was made: the first level starts there. */
	private Integer planTop;
	/** Height of one level: a robot standing on its floor reaches all of it. */
	static final int LEVEL = 3;
	/** Levels being dug at the same time, each a little behind the one above it. */
	private static final int MAX_LEVELS = 3;
	/** How many rings each level stays behind the one above it. */
	private static final int LAG = 2;
	/** A block all robots gave up on this many times is left for good. */
	private static final int GIVE_UP_ROUNDS = 3;
	private final Map<Long, Integer> skipRounds = new HashMap<>();
	private final Set<Long> givenUp = new java.util.HashSet<>();
	/** Given up since the last call: their columns look further down. */
	private final List<BlockPos> pendingGiveUp = new ArrayList<>();
	private long frontierTick = Long.MIN_VALUE;
	private int firstLevel;
	/** For each level, the innermost ring that still has work at that level or above (Integer.MAX_VALUE when none). */
	private int[] frontier = new int[0];

	/** Ring of a column around the staircase. */
	private int ring(long column) {
		return Math.max(Math.abs(columnX(column) - pillar.getX()), Math.abs(columnZ(column) - pillar.getZ()));
	}

	/** Which level of the plan a height is in: 0 is the top one. */
	int level(int y) {
		return planTop == null || y >= planTop ? 0 : (planTop - y) / LEVEL;
	}

	/** The plan is made once every column has been looked at. */
	public boolean planned() {
		return planTop != null;
	}

	/** For the station screen and the tests: the level the robots are on, and how many there are. */
	public String describePlan() {
		if (planTop == null) {
			return "Plan wird erstellt";
		}

		return "Ebene " + (firstLevel + 1) + " von " + (level(minY) + 1) + " (oben bei Y " + planTop + ")";
	}

	/** Trees and plants: dug wherever they are (a log fells the whole tree), they do not hold up the levels. */
	private static boolean vegetation(ServerLevel level, BlockPos pos, BlockState state) {
		return state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES) || state.getCollisionShape(level, pos).isEmpty()
				&& !isWater(state);
	}

	/** Makes the plan: the highest block (below trees and plants) is where the first level starts. */
	private void makePlan(ServerLevel level) {
		int top = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (Map.Entry<Long, Integer> entry : tops.entrySet()) {
			pos.set(columnX(entry.getKey()), entry.getValue(), columnZ(entry.getKey()));

			// down through the tree or the grass on top
			while (pos.getY() > minY && pos.getY() > top) {
				BlockState state = level.getBlockState(pos);

				if (!state.isAir() && !vegetation(level, pos, state)) {
					break;
				}

				pos.move(Direction.DOWN);
			}

			top = Math.max(top, pos.getY());
		}

		planTop = top == Integer.MIN_VALUE ? minY : top;
		frontierTick = Long.MIN_VALUE;
	}

	/** Works out, once a tick, which level is on top and how far each level has got. */
	private void updateFrontier(ServerLevel level, long now) {
		if (frontierTick == now || planTop == null) {
			return;
		}

		frontierTick = now;
		int levels = level(minY) + 1;
		int[] innermost = new int[levels];
		java.util.Arrays.fill(innermost, Integer.MAX_VALUE);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (Map.Entry<Long, Integer> entry : tops.entrySet()) {
			pos.set(columnX(entry.getKey()), entry.getValue(), columnZ(entry.getKey()));

			// a block nobody gets to for now, and trees, do not hold the others up
			if (skipped.containsKey(pos.asLong()) || !level.isLoaded(pos) || vegetation(level, pos, level.getBlockState(pos))) {
				continue;
			}

			int l = Math.min(levels - 1, level(entry.getValue()));
			innermost[l] = Math.min(innermost[l], ring(entry.getKey()));
		}

		firstLevel = levels;

		for (int l = 0; l < levels; l++) {
			if (innermost[l] != Integer.MAX_VALUE && firstLevel == levels) {
				firstLevel = l;
			}

			if (l > 0) {
				innermost[l] = Math.min(innermost[l], innermost[l - 1]);
			}
		}

		frontier = innermost;
	}

	/**
	 * May the block be dug now, by the plan? The top level always; the next ones only where the level above is done
	 * {@value #LAG} rings further out, so every level is dug from the staircase outwards, three at most at the same
	 * time. Trees and plants always.
	 */
	private boolean allowed(ServerLevel level, long column, BlockPos pos) {
		if (planTop == null) {
			return false;
		}

		int l = level(pos.getY());

		if (l <= firstLevel || vegetation(level, pos, level.getBlockState(pos))) {
			return true;
		}

		if (l >= firstLevel + MAX_LEVELS || l - 1 >= frontier.length) {
			return false;
		}

		return frontier[l - 1] > ring(column) + LAG;
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
	 * with the world, so after a restart the count goes on from there. It never goes back: see {@link #total()}.
	 */
	int progress() {
		if (finished()) {
			return 1000;
		}

		if (scanned < order.length || !unloaded.isEmpty()) {
			return -1;
		}

		long total = total();
		int permille = total <= 0 ? 0 : (int) Math.clamp((total - remaining) * 1000 / total, 0, 999);
		StationProgress.Entry saved = saved();
		return saved == null ? permille : Math.max(permille, Math.min(999, saved.permille()));
	}

	/** What was saved for this quarry, or null (none yet, or saved for another area). */
	private StationProgress.Entry saved() {
		StationProgress.Entry saved = StationProgress.get(dim, station);
		return saved != null && saved.key().equals(key()) ? saved : null;
	}

	/**
	 * All the blocks to dig, for the progress. A job that is looked at afresh (after a restart, or when its robots
	 * left it alone for a while) can find more to dig than was left before: water that ran into the pit, the
	 * cobblestone walls against a sea, sand that fell in. The bar used to jump back then (9.9 % to 6.2 %, say).
	 * Instead the total grows by so much that the bar stays where it was and goes on from there.
	 */
	private long total() {
		StationProgress.Entry saved = saved();

		if (saved == null) {
			return initial;
		}

		long total = Math.max(saved.total(), initial);
		int floor = Math.clamp(saved.permille(), 0, 999);

		if (floor > 0 && (total - remaining) * 1000 < (long) floor * total) {
			// rounded up: (total - remaining) / total is then at least floor / 1000
			total = (remaining * 1000 + (1000 - floor) - 1) / (1000 - floor);
		}

		return total;
	}

	private void saveProgress(long now) {
		if (now - progressTick < 100) {
			return;
		}

		progressTick = now;
		int permille = progress();

		if (permille >= 0) {
			StationProgress.put(dim, station, new StationProgress.Entry(key(), total(), permille));
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

	/** The step of the staircase at a height: one of the eight columns around the pillar. */
	public BlockPos step(int y) {
		int[] cell = RING[Math.floorMod(y - station.getY() - 1, RING.length)];
		return new BlockPos(pillar.getX() + cell[0], y, pillar.getZ() + cell[1]);
	}

	/** How far up or down the staircase one way goes at most. */
	private static final int WAYPOINT_SPAN = 16;

	/**
	 * Places along the central staircase between a robot and where it wants to go (up to the chests at the station, or
	 * down into the quarry), the farthest first: a way all the way up out of a deep quarry is often more than the game
	 * finds at once, so it goes up the staircase a stretch at a time instead of digging a staircase of its own.
	 */
	public List<BlockPos> waypoints(ServerLevel level, BlockPos from, int toY) {
		List<BlockPos> found = new ArrayList<>();
		int dy = toY - from.getY();

		if (Math.abs(dy) <= 3 || !contains(from) && !contains(new BlockPos(from.getX(), toY, from.getZ()))) {
			return found;
		}

		int dir = Integer.signum(dy);
		int far = from.getY() + dir * Math.min(Math.abs(dy) - 1, WAYPOINT_SPAN);

		for (int y = far; (y - from.getY()) * dir >= 0 && found.size() < 5; y -= dir) {
			if (y >= station.getY() || y <= minY) {
				continue;
			}

			BlockPos stepPos = step(y);
			BlockPos feet = stepPos.above();

			if (level.isLoaded(stepPos) && level.getBlockState(stepPos).isFaceSturdy(level, stepPos, Direction.UP) && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
					&& level.getFluidState(feet).isEmpty()) {
				found.add(feet);
				y -= dir * 3;
			}
		}

		return found;
	}

	/** Down to here the central staircase has been checked for missing steps. */
	private int stairsCheckedTo = Integer.MAX_VALUE;
	private long stairsTick = Long.MIN_VALUE;

	/**
	 * The central staircase gets a cobblestone step wherever one is missing (a cave, water, air above the ground),
	 * as soon as the quarry around it has got down to that height: then every robot can always walk up to the
	 * station by it and none has to dig a way of its own.
	 */
	private void repairStairs(ServerLevel level, long now) {
		if (now - stairsTick < 40) {
			return;
		}

		stairsTick = now;
		int from = Math.min(stairsCheckedTo, station.getY() - 1);

		for (int y = from; y > minY; y--) {
			BlockPos stepPos = step(y);

			if (!level.isLoaded(stepPos)) {
				return;
			}

			// the ring around the staircase is dug down to here?
			int ox = Integer.signum(stepPos.getX() - pillar.getX());
			int oz = Integer.signum(stepPos.getZ() - pillar.getZ());
			BlockPos outside = stepPos.offset(ox, 0, oz);
			Integer top = tops.get(column(outside.getX(), outside.getZ()));

			if (contains(outside) && (top == null && scanned < order.length || top != null && top >= y)) {
				return;
			}

			BlockState state = level.getBlockState(stepPos);

			if (state.getCollisionShape(level, stepPos).isEmpty() || isWater(state) || state.getBlock() instanceof FallingBlock) {
				level.setBlockAndUpdate(stepPos, Blocks.COBBLESTONE.defaultBlockState());
			}

			stairsCheckedTo = y - 1;
		}
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
		return diggable(level, pos, state) && !isProtected(level, pos, state) && pumped.getOrDefault(pos.asLong(), 0) < MAX_PUMPS && !givenUp.contains(pos.asLong());
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

		if (planTop == null && scanned >= order.length) {
			makePlan(level);
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
	 * The next steps of the plan for a robot: the blocks the plan allows now (see {@link #allowed}), the highest level
	 * first, within it ring by ring from the staircase outwards, so the robots work through the plan in its order like
	 * a machine through its program. Blocks another robot took or none could get to are left out.
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

		if (planTop == null) {
			// the station is still looking at the area
			return new ArrayList<>();
		}

		for (BlockPos pos : pendingGiveUp) {
			long column = column(pos.getX(), pos.getZ());
			Integer top = tops.get(column);

			if (top != null && top == pos.getY()) {
				Integer next = findTop(level, pos.getX(), pos.getZ(), top, new int[1]);

				if (next != null) {
					setTop(column, next);
				}
			}
		}

		pendingGiveUp.clear();

		updateFrontier(level, now);
		repairStairs(level, now);
		List<long[]> steps = new ArrayList<>();

		for (Map.Entry<Long, Integer> entry : tops.entrySet()) {
			long column = entry.getKey();
			int y = entry.getValue();
			long packed = BlockPos.asLong(columnX(column), y, columnZ(column));

			if (claimed.containsKey(packed) || skippedFor(BlockPos.of(packed), robot)) {
				continue;
			}

			// level, then step of the plan, then from the top down
			steps.add(new long[] {column, y, (long) level(y) << 40 | (long) planIndex.getOrDefault(column, 0) << 12 | (4095 - Math.clamp(y - minY, 0, 4095))});
		}

		steps.sort(Comparator.comparingLong(step -> step[2]));
		List<BlockPos> found = new ArrayList<>();
		List<long[]> moved = new ArrayList<>();
		int checks = 0;

		for (long[] step : steps) {
			if (found.size() >= count || checks++ > 4000) {
				break;
			}

			BlockPos pos = new BlockPos(columnX(step[0]), (int) step[1], columnZ(step[0]));

			if (!level.isLoaded(pos)) {
				continue;
			}

			if (!wanted(level, pos)) {
				// dug by a player or fallen away: the column is looked at again below
				moved.add(step);
				continue;
			}

			if (!allowed(level, step[0], pos) || !exposed(level, pos) || besideWater(level, pos)) {
				continue;
			}

			found.add(pos);
		}

		for (long[] m : moved) {
			Integer next = findTop(level, columnX(m[0]), columnZ(m[0]), (int) m[1], new int[1]);

			if (next != null) {
				setTop(m[0], next);
			}
		}

		return found;
	}

	/**
	 * Blocks right around a robot, for when no highest block of a column can be got at (the area is roofed over, say,
	 * with the station down below): from its floor up as far as it reaches, the highest first. Digging these opens the
	 * staircase upwards step by step.
	 */
	@Override
	public List<BlockPos> nearby(ServerLevel level, BlockPos from, UUID robot, int count, long now) {
		forget(now);
		updateFrontier(level, now);
		List<BlockPos> found = new ArrayList<>();

		for (BlockPos pos : BlockPos.betweenClosed(from.offset(-6, -1, -6), from.offset(6, 5, 6))) {
			if (!contains(pos) || claimed.containsKey(pos.asLong()) && !robot.equals(claimed.get(pos.asLong())) || skippedFor(pos, robot)) {
				continue;
			}

			boolean free = pos.distSqr(from) <= 12 && pos.getY() >= from.getY();

			// the plan holds here too: no holes into the levels below, except to get free
			if (wanted(level, pos) && exposed(level, pos) && !besideWater(level, pos) && (free || allowed(level, column(pos.getX(), pos.getZ()), pos))) {
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

			if (skipRounds.merge(pos.asLong(), 1, Integer::sum) >= GIVE_UP_ROUNDS) {
				// nobody gets there, again and again: left standing, the column goes on below it
				givenUp.add(pos.asLong());
				skipRounds.remove(pos.asLong());
				skipped.remove(pos.asLong());
				pendingGiveUp.add(pos.immutable());
			}
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
