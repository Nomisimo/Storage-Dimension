package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Strip mining below a station, like a player would do it, without seeing through the rock: the robots dig a spiral
 * staircase down around the station's column to a mining level, and there a grid of tunnels over the whole area (the
 * station's square of its range, or its antenna box): a main tunnel north and south through the staircase, and from
 * it side tunnels east and west every {@value #SPACING} blocks. The levels are {@value #LEVEL_GAP} blocks apart and the
 * side tunnels of every other level are moved over by half their spacing, so each block of the ground is in the
 * wall, floor or ceiling of exactly one tunnel: every block is looked at, and only a quarter is dug. Ores show up in the walls, floor and ceiling of the tunnels; a robot digs those it sees (they touch an open
 * block) within a player's reach, and so follows each vein it comes across. Only the ores are brought home; the stone
 * of the tunnels is not kept.
 * <p>
 * The staircase and the tunnels are dug at the same time: a robot without a tunnel helps with the staircase where
 * there is a block of it to get at, otherwise it takes a tunnel on the highest level the staircase has reached, so
 * the work starts at the top and goes down level by level while the staircase goes on below. Each robot has a
 * tunnel of its own, up to 32 work at the same time; after each tunnel it takes its ores home. Missing steps and the floor over caves and lava are filled with cobblestone, and before a robot opens a block
 * of the staircase or a tunnel, water and lava in it and around it become cobblestone, so the mine is never flooded.
 * A torch goes up every {@value #TORCH_GAP} blocks of a tunnel when the robots have torches.
 */
public final class OreJob implements DigJob {
	private static final int[][] RING = MiningJob.RING;
	private static final int SKIP_TICKS = 1200;
	private static final int FORGET_TICKS = 6000;
	/** A robot that has not asked for work this long is gone; what it had taken is free again. */
	private static final int CLAIM_TICKS = 1200;
	/** Distance between two side tunnels of a level. */
	static final int SPACING = 4;
	/** Height between two mining levels; with the side tunnels moved by {@code SPACING / 2} each level, this covers every block. */
	static final int LEVEL_GAP = 2;
	/** The deepest level is this far above the bottom of the area (above the bedrock). */
	private static final int LEVEL_FLOOR = 6;
	/** How far around itself a robot looks for ores it can see. */
	private static final int SIGHT = 4;
	/** A torch every this many places along a tunnel. */
	static final int TORCH_GAP = 8;
	/** Tunnel places a robot goes through per call. */
	private static final int STEPS = 32;
	/** How far up or down the staircase a robot goes in one stretch when no way to its goal is found. */
	private static final int WAYPOINT_SPAN = 16;

	private static final Map<String, OreJob> JOBS = new HashMap<>();

	private final BlockPos station;
	private final int minX;
	private final int minY;
	private final int minZ;
	private final int maxX;
	private final int maxY;
	private final int maxZ;
	private final BlockPos pillar;
	/** The heights a robot stands at in the tunnels (feet), deepest first. */
	private final int[] levels;
	/** The tunnels of each level (by its index in {@link #levels}), planned once the staircase gets there. */
	private final Map<Integer, List<Tunnel>> plans = new HashMap<>();
	/** The staircase as it stands, worked out at most once a tick. */
	private Shaft shaft;
	private long shaftTick = Long.MIN_VALUE;
	private final Map<UUID, Tunnel> working = new HashMap<>();
	/** Robots whose tunnel is finished: they take their ores home before the next one. */
	private final Set<UUID> tunnelDone = new HashSet<>();
	private final Map<Long, UUID> claimed = new HashMap<>();
	/** When each robot last asked for work: claims of a robot that went away (picked up, unloaded) are let go. */
	private final Map<UUID, Long> seen = new HashMap<>();
	private final Map<Long, Long> skipped = new HashMap<>();
	/** Places in the tunnels that get a torch once a robot with torches comes by. */
	private final Set<Long> torchSpots = new HashSet<>();
	private long lastUsed;

	/** One straight tunnel of a level, two blocks high. */
	static final class Tunnel {
		final BlockPos start;
		final Direction dir;
		final int length;
		/** The tunnel it branches off from, and how far that one must be open first; null for those from the staircase. */
		final Tunnel parent;
		final int parentCell;
		/** Places from the start that are open. */
		int open;
		boolean done;

		Tunnel(BlockPos start, Direction dir, int length, Tunnel parent, int parentCell) {
			this.start = start;
			this.dir = dir;
			this.length = length;
			this.parent = parent;
			this.parentCell = parentCell;
		}

		BlockPos cell(int i) {
			return start.relative(dir, i);
		}
	}

	private OreJob(BlockPos station, int minX, int minY, int minZ, int maxX, int maxZ) {
		this.station = station.immutable();
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = station.getY() - 1;
		this.maxZ = maxZ;
		int px = station.getX();
		int pz = station.getZ();

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
		List<Integer> heights = new ArrayList<>();

		for (int y = minY + LEVEL_FLOOR; y <= station.getY() - 4; y += LEVEL_GAP) {
			// on a height where the step above the robot's head is on a corner of the staircase, never in a tunnel's mouth
			int feet = Math.floorMod(y - station.getY(), 2) == 0 ? y : y + 1;

			if (feet <= station.getY() - 4) {
				heights.add(feet);
			}
		}

		levels = heights.stream().mapToInt(Integer::intValue).toArray();
	}

	/** The strip mine below a station: the footprint of its area (inclusive bounds), from the station down to {@code bottom}. */
	public static OreJob get(String dim, BlockPos station, int minX, int minZ, int maxX, int maxZ, int bottom, long now) {
		JOBS.values().removeIf(job -> now - job.lastUsed > FORGET_TICKS);
		String key = dim + "@" + station.asLong();
		OreJob job = JOBS.get(key);

		if (job == null || job.minX != minX || job.minZ != minZ || job.maxX != maxX || job.maxZ != maxZ || job.minY != bottom) {
			job = new OreJob(station, minX, bottom, minZ, maxX, maxZ);
			JOBS.put(key, job);
		}

		job.lastUsed = now;
		return job;
	}

	public static void reset(String dim, BlockPos station) {
		JOBS.remove(dim + "@" + station.asLong());
	}

	// ---- what an ore is ----

	/**
	 * Ores of the game and of other mods (the common ore tag {@code c:ores}), ancient debris included, and amethyst:
	 * the clusters and amethyst blocks of geodes. Budding amethyst is left (it drops nothing and would no longer grow
	 * new buds), and so are the buds that have not grown into clusters yet.
	 */
	public static boolean isOre(BlockState state) {
		return state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.AMETHYST_BLOCK) || state.is(ConventionalBlockTags.ORES) || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
				|| state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.DIAMOND_ORES)
				|| state.is(BlockTags.EMERALD_ORES) || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.ANCIENT_DEBRIS);
	}

	@Override
	public boolean keepsDrops(BlockState state) {
		return isOre(state);
	}

	// ---- what may be dug ----

	private boolean contains(BlockPos pos) {
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY + 2 && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	private int stepIndex(int y) {
		return Math.floorMod(y - station.getY() - 1, RING.length);
	}

	/** A step of the staircase below the station, or the pillar under the station. */
	public boolean isStair(BlockPos pos) {
		if (pos.getY() >= station.getY()) {
			return false;
		}

		int dx = pos.getX() - pillar.getX();
		int dz = pos.getZ() - pillar.getZ();

		if (dx == 0 && dz == 0) {
			return true;
		}

		if (Math.max(Math.abs(dx), Math.abs(dz)) != 1) {
			return false;
		}

		int[] step = RING[stepIndex(pos.getY())];
		return step[0] == dx && step[1] == dz;
	}

	private boolean isProtected(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.hasBlockEntity() || state.is(RobotStationBlock.BLOCK) || state.is(AntennaBlock.BLOCK) || state.is(RobotBarrierBlock.BLOCK) || isStair(pos)) {
			return true;
		}

		// sand or gravel of a step resting on it would fall
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

	private static boolean lava(ServerLevel level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.LAVA);
	}

	/** Lava in it or right next to it: digging there would let it in. */
	private static boolean nearLava(ServerLevel level, BlockPos pos) {
		if (lava(level, pos)) {
			return true;
		}

		for (Direction side : Direction.values()) {
			if (lava(level, pos.relative(side))) {
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean wanted(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return contains(pos) && MiningJob.diggable(level, pos, state) && !isProtected(level, pos, state) && !nearLava(level, pos);
	}

	/** Nothing in the way of a robot there. */
	private static boolean clear(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && !lava(level, pos);
	}

	/** Can be seen: one of its sides is open. */
	private static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction side : Direction.values()) {
			BlockPos next = pos.relative(side);

			if (level.getBlockState(next).getCollisionShape(level, next).isEmpty()) {
				return true;
			}
		}

		return false;
	}

	/** Fills a hole (air, water, lava, plants) with cobblestone, for a step or a tunnel floor: a bridge over caves and lava. */
	private void fill(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);

		if (contains(pos) && level.isLoaded(pos) && state.canBeReplaced()) {
			level.setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());
		}
	}

	/** Water or lava as a block of its own (not a waterlogged block). */
	private static boolean liquid(BlockState state) {
		return state.getBlock() instanceof LiquidBlock;
	}

	/**
	 * Seals the blocks a robot is about to open: water and lava in them and all around them become cobblestone, so
	 * nothing runs into the mine. The cobblestone in the way is dug like stone, which takes the water away.
	 */
	private void seal(ServerLevel level, List<BlockPos> blocks) {
		for (BlockPos pos : blocks) {
			sealOne(level, pos);

			for (Direction side : Direction.values()) {
				sealOne(level, pos.relative(side));
			}
		}
	}

	private void sealOne(ServerLevel level, BlockPos pos) {
		if (contains(pos) && level.isLoaded(pos) && liquid(level.getBlockState(pos))) {
			level.setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());
		}
	}

	// ---- the tunnels of a level ----

	/**
	 * The tunnels at a height, the main ones and those closest to the staircase first; the side tunnels are
	 * {@code shift} blocks of z off the ones through the staircase.
	 */
	private List<Tunnel> planLevel(int y, int shift) {
		List<Tunnel> tunnels = new ArrayList<>();
		int px = pillar.getX();
		int pz = pillar.getZ();
		Tunnel north = add(tunnels, new BlockPos(px, y, pz - 2), Direction.NORTH, pz - 2 - minZ + 1, null, 0);
		Tunnel south = add(tunnels, new BlockPos(px, y, pz + 2), Direction.SOUTH, maxZ - (pz + 2) + 1, null, 0);
		if (shift == 0) {
			add(tunnels, new BlockPos(px + 2, y, pz), Direction.EAST, maxX - (px + 2) + 1, null, 0);
			add(tunnels, new BlockPos(px - 2, y, pz), Direction.WEST, px - 2 - minX + 1, null, 0);
		}

		for (int d = shift == 0 ? SPACING : shift; pz - d >= minZ || pz + d <= maxZ; d += SPACING) {
			for (Tunnel main : new Tunnel[] {north, south}) {
				if (main == null || d - 2 >= main.length) {
					continue;
				}

				int z = main.dir == Direction.NORTH ? pz - d : pz + d;
				add(tunnels, new BlockPos(px + 1, y, z), Direction.EAST, maxX - (px + 1) + 1, main, d - 2);
				add(tunnels, new BlockPos(px - 1, y, z), Direction.WEST, px - 1 - minX + 1, main, d - 2);
			}
		}

		return tunnels;
	}

	private static Tunnel add(List<Tunnel> tunnels, BlockPos start, Direction dir, int length, Tunnel parent, int parentCell) {
		if (length <= 0) {
			return null;
		}

		Tunnel tunnel = new Tunnel(start, dir, length, parent, parentCell);
		tunnels.add(tunnel);
		return tunnel;
	}

	/** The tunnels of a level, planned the first time they are asked for. */
	private List<Tunnel> plan(int index) {
		return plans.computeIfAbsent(index, i -> planLevel(levels[i], i % 2 == 0 ? 0 : SPACING / 2));
	}

	/** Its branch point is open. */
	private static boolean ready(Tunnel tunnel) {
		return tunnel.parent == null || tunnel.parent.open > tunnel.parentCell;
	}

	/**
	 * A tunnel for a robot that has none: on the highest level the staircase has reached that still has one ready
	 * and free, so the robots start at the top and work their way down while the staircase goes on below them.
	 */
	private Tunnel newTunnel(UUID robot, Shaft shaft, BlockPos from) {
		for (int i = levels.length - 1; i >= 0; i--) {
			if (levels[i] <= shaft.blockedY) {
				// the staircase does not reach this level yet, nor the ones below
				break;
			}

			Tunnel best = null;

			for (Tunnel next : plan(i)) {
				if (!next.done && ready(next) && !working.containsValue(next) && free(next.cell(Math.min(next.open, next.length - 1)))
						&& (best == null || next.cell(next.open).distSqr(from) < best.cell(best.open).distSqr(from))) {
					best = next;
				}
			}

			if (best != null) {
				working.put(robot, best);
				return best;
			}
		}

		return null;
	}

	// ---- handing out blocks ----

	@Override
	public List<BlockPos> candidates(ServerLevel level, BlockPos from, UUID robot, int count, long now) {
		skipped.values().removeIf(until -> until < now);
		claimed.values().removeIf(robot::equals);
		seen.put(robot, now);
		seen.values().removeIf(last -> now - last > CLAIM_TICKS);
		claimed.values().removeIf(owner -> !seen.containsKey(owner));
		working.keySet().removeIf(owner -> !seen.containsKey(owner));
		List<BlockPos> found = seenOres(level, from);

		if (found.isEmpty()) {
			Tunnel own = working.get(robot);

			if (own != null && !own.done) {
				found = next(level, robot, own);
			}

			if (found.isEmpty() && (own == null || working.get(robot) == own)) {
				// no tunnel, or nothing of its own one it can take now (a block of it no way was found to, say): it
				// lets that one go and looks for other work, instead of waiting for it
				working.remove(robot);
				// without a tunnel it helps with the staircase first, as far as there are blocks to get at there and
				// room for one more robot; otherwise it takes a tunnel on a level the staircase has reached
				Shaft shaft = shaft(level);
				int onShaft = 0;

				for (BlockPos pos : shaft.blocks) {
					if (claimed.containsKey(pos.asLong())) {
						onShaft++;
					}
				}

				for (BlockPos pos : shaft.blocks) {
					if (free(pos) && onShaft < SHAFT_ROBOTS) {
						found.add(pos);
					}
				}

				if (found.isEmpty()) {
					Tunnel tunnel = newTunnel(robot, shaft, from);

					if (tunnel != null) {
						found = next(level, robot, tunnel);
					}
				}
			}
		}

		found.sort(Comparator.comparingDouble(pos -> pos.distSqr(from)));
		return found.size() > count ? new ArrayList<>(found.subList(0, count)) : found;
	}

	@Override
	public List<BlockPos> nearby(ServerLevel level, BlockPos from, UUID robot, int count, long now) {
		return new ArrayList<>();
	}

	private boolean free(BlockPos pos) {
		return !claimed.containsKey(pos.asLong()) && !skipped.containsKey(pos.asLong());
	}

	/** Ores around the robot that it can see (they touch an open block): in the walls of its tunnel, and the rest of a vein. */
	private List<BlockPos> seenOres(ServerLevel level, BlockPos from) {
		List<BlockPos> found = new ArrayList<>();

		for (BlockPos pos : BlockPos.betweenClosed(from.offset(-SIGHT, -SIGHT, -SIGHT), from.offset(SIGHT, SIGHT + 1, SIGHT))) {
			if (free(pos) && isOre(level.getBlockState(pos)) && wanted(level, pos) && exposed(level, pos)) {
				found.add(pos.immutable());
			}
		}

		return found;
	}

	/** Its tunnel is finished (once per tunnel): time to take the ores home before the next one. */
	public boolean tunnelDone(UUID robot) {
		return tunnelDone.remove(robot);
	}

	/**
	 * The staircase as it stands: the blocks of it to dig now (the highest few layers of it, those a robot can get
	 * at), and how far down it is open.
	 */
	private static final class Shaft {
		final List<BlockPos> blocks = new ArrayList<>();
		/**
		 * The highest height where the way over the steps is still to dig; the levels above it can be reached.
		 * Below the deepest level when the staircase is open all the way down.
		 */
		int blockedY = Integer.MIN_VALUE;
	}

	/** Layers of the staircase that are dug at the same time, so more robots can help with it. */
	private static final int SHAFT_LAYERS = 3;
	/** Robots digging the staircase at the same time; there is not room for more. */
	private static final int SHAFT_ROBOTS = 4;


	private Shaft shaft(ServerLevel level) {
		long now = level.getGameTime();

		if (shaft != null && shaftTick == now) {
			return shaft;
		}

		Shaft result = new Shaft();
		int bottom = levels.length == 0 ? station.getY() : levels[0];
		int layers = 0;

		for (int y = station.getY() - 1; y >= bottom; y--) {
			// the highest layers still to dig are worked on: sealed, steps filled, blocks handed out
			boolean active = layers < SHAFT_LAYERS;
			boolean any = false;

			for (int[] cell : RING) {
				BlockPos pos = new BlockPos(pillar.getX() + cell[0], y, pillar.getZ() + cell[1]);

				if (!level.isLoaded(pos)) {
					continue;
				}

				if (active) {
					if (!isStair(pos) && !clear(level, pos) || liquid(level.getBlockState(pos))) {
						// a cave with water or lava next to the staircase: walled off before it is opened
						seal(level, List.of(pos));
					}

					if (isStair(pos)) {
						fill(level, pos);
					}
				}

				if (isStair(pos) || clear(level, pos) || !wanted(level, pos)) {
					continue;
				}

				// the staircase is open once there is room to walk over every step; the rest of the ring around the
				// pillar is dug where a robot gets at it, but a block left there (no way to it, out of sight) does
				// not hold up the tunnels
				if (walkway(pos) && result.blockedY == Integer.MIN_VALUE) {
					result.blockedY = y;
				}

				if (active && exposed(level, pos) && !skipped.containsKey(pos.asLong())) {
					result.blocks.add(pos);
					any = true;
				}
			}

			if (any) {
				layers++;
			}

			if (layers >= SHAFT_LAYERS && result.blockedY != Integer.MIN_VALUE) {
				break;
			}
		}

		shaft = result;
		shaftTick = now;
		return result;
	}

	/** Where a robot walks down the staircase: the two blocks above a step. */
	private boolean walkway(BlockPos pos) {
		return isStair(pos.below()) && !isStair(pos) || isStair(pos.below(2)) && !isStair(pos.below());
	}

	/** The next blocks of the robot's tunnel: what fell back into it first, then the next place along it. */
	private List<BlockPos> next(ServerLevel level, UUID robot, Tunnel tunnel) {
		List<BlockPos> found = new ArrayList<>();

		// sand, gravel or a player's block in the tunnel so far
		for (int i = 0; i < tunnel.open; i++) {
			for (BlockPos pos : List.of(tunnel.cell(i), tunnel.cell(i).above())) {
				if (!clear(level, pos) && free(pos) && wanted(level, pos) && exposed(level, pos)) {
					found.add(pos);
				}
			}
		}

		if (!found.isEmpty()) {
			return found;
		}

		for (int steps = 0; steps < STEPS; steps++) {
			if (tunnel.open >= tunnel.length) {
				finish(robot, tunnel);
				return found;
			}

			BlockPos cell = tunnel.cell(tunnel.open);

			if (!level.isLoaded(cell) || !level.isLoaded(cell.relative(tunnel.dir))) {
				return found;
			}

			// water and lava around it become cobblestone first, so the tunnel goes on through or past them
			seal(level, List.of(cell, cell.above()));
			boolean ready = true;

			for (BlockPos pos : List.of(cell, cell.above())) {
				if (clear(level, pos)) {
					continue;
				}

				if (!wanted(level, pos)) {
					// bedrock, a chest: the tunnel ends here
					finish(robot, tunnel);
					return found;
				}

				ready = false;

				if (free(pos) && exposed(level, pos)) {
					found.add(pos);
				}
			}

			if (!ready) {
				return found;
			}

			// open: floor over a cave, then on to the next place
			fill(level, cell.below());

			if (tunnel.open % TORCH_GAP == TORCH_GAP / 2) {
				torchSpots.add(cell.asLong());
			}

			tunnel.open++;
		}

		return found;
	}

	/**
	 * A place in a tunnel within reach of the robot's eye that gets a torch now: still open, on solid floor, no torch
	 * there yet. Null when there is none.
	 */
	public BlockPos torchSpot(ServerLevel level, net.minecraft.world.phys.Vec3 eye, double reach) {
		for (java.util.Iterator<Long> it = torchSpots.iterator(); it.hasNext(); ) {
			BlockPos pos = BlockPos.of(it.next());

			if (!level.isLoaded(pos) || eye.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > reach * reach) {
				continue;
			}

			it.remove();
			BlockState state = level.getBlockState(pos);

			if (state.isAir() && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
				return pos;
			}
		}

		return null;
	}

	private void finish(UUID robot, Tunnel tunnel) {
		tunnel.done = true;
		working.remove(robot);
		tunnelDone.add(robot);
	}

	@Override
	public boolean finished() {
		if (shaft == null || shaft.blockedY != Integer.MIN_VALUE) {
			return false;
		}

		for (int i = 0; i < levels.length; i++) {
			if (plan(i).stream().anyMatch(t -> !t.done)) {
				return false;
			}
		}

		return true;
	}

	@Override
	public void dug(ServerLevel level, BlockPos pos) {
		claimed.remove(pos.asLong());
	}

	@Override
	public void claim(BlockPos pos, UUID robot) {
		claimed.put(pos.asLong(), robot);
	}

	@Override
	public void release(BlockPos pos, UUID robot) {
		claimed.remove(pos.asLong(), robot);
	}

	@Override
	public void skip(BlockPos pos, UUID robot, long now) {
		skipped.put(pos.asLong(), now + SKIP_TICKS);
		claimed.remove(pos.asLong());
	}

	/** For the smoke test: where the work stands, and the highest blocks of the staircase still to dig. */
	String describe(ServerLevel level) {
		Shaft shaft = shaft(level);
		StringBuilder out = new StringBuilder("ebenen=" + levels.length + " treppe offen bis über y=" + shaft.blockedY + " treppe frei=" + shaft.blocks.size()
				+ " working=" + working.size() + " claimed=" + claimed.size() + " skipped=" + skipped.size());

		for (int i = levels.length - 1; i >= 0; i--) {
			List<Tunnel> tunnels = plans.get(i);

			if (tunnels != null && tunnels.stream().anyMatch(t -> t.open > 0)) {
				out.append(" y=").append(levels[i]).append(':').append(tunnels.stream().filter(t -> t.done).count()).append('/').append(tunnels.size())
						.append('/').append(tunnels.stream().filter(working::containsValue).count());
			}
		}

		if (shaft.blockedY != Integer.MIN_VALUE) {
			int y = shaft.blockedY;
			StringBuilder row = new StringBuilder();

			for (int[] cell : RING) {
				BlockPos pos = new BlockPos(pillar.getX() + cell[0], y, pillar.getZ() + cell[1]);

				if (!isStair(pos) && !clear(level, pos)) {
					BlockState state = level.getBlockState(pos);
					row.append(' ').append(state.getBlock().getDescriptionId().replace("block.minecraft.", "")).append(wanted(level, pos) ? "" : "(nicht gewollt)")
							.append(exposed(level, pos) ? "" : "(verdeckt)").append(free(pos) ? "" : skipped.containsKey(pos.asLong()) ? "(übersprungen)" : "(vergeben)");
				}
			}

			out.append(" treppe y=").append(y).append(':').append(row);
		}

		return out.toString();
	}

	/**
	 * Places to stand on the staircase on the way from {@code from} up or down to the height {@code toY}, the
	 * farthest (up to {@value #WAYPOINT_SPAN} blocks) first; none when it is close by in height.
	 */
	public List<BlockPos> waypoints(ServerLevel level, BlockPos from, int toY) {
		List<BlockPos> found = new ArrayList<>();
		int dy = toY - from.getY();

		if (Math.abs(dy) <= 6) {
			return found;
		}

		int dir = Integer.signum(dy);
		int far = from.getY() + dir * Math.min(Math.abs(dy) - 1, WAYPOINT_SPAN);

		for (int y = far; (y - from.getY()) * dir > 0 && found.size() < 4; y -= dir) {
			if (y >= station.getY() || y < minY) {
				continue;
			}

			BlockPos stepPos = step(y);
			BlockPos feet = stepPos.above();

			if (level.isLoaded(stepPos) && level.getBlockState(stepPos).isFaceSturdy(level, stepPos, Direction.UP) && clear(level, feet) && clear(level, feet.above())
					&& !liquid(level.getBlockState(feet))) {
				found.add(feet);
				y -= dir * 3;
			}
		}

		return found;
	}

	/** The step of the staircase at a height. */
	BlockPos step(int y) {
		int[] cell = RING[stepIndex(y)];
		return new BlockPos(pillar.getX() + cell[0], y, pillar.getZ() + cell[1]);
	}

	/** For the smoke test: the way over the steps is open down to the deepest level. */
	boolean staircaseOpen(ServerLevel level) {
		return shaft(level).blockedY == Integer.MIN_VALUE;
	}

	/** For the smoke test: tunnels finished, on all levels. */
	int tunnelsDone() {
		return plans.values().stream().mapToInt(tunnels -> (int) tunnels.stream().filter(t -> t.done).count()).sum();
	}

	/** For the smoke test: the heights of the mining levels, deepest first. */
	int[] levels() {
		return levels.clone();
	}
}
