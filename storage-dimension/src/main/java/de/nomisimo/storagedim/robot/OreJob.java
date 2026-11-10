package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The ore search of a station's mining robots: they sense the ores in the ground below the station (the station's
 * area, or the square of its range, from the station down to the bottom of the world), dig a spiral staircase down
 * around the station's column and from it a tunnel to each ore, nearest (and most valuable) first. Once at an ore
 * they follow its vein. Only ores are brought home; the stone of the tunnels is not kept.
 * <p>
 * The staircase is the one of {@link MiningJob}: the eight columns around the station's column, one step every eight
 * blocks in each, so the robots walk down in a spiral. A tunnel starts on the step at the ore's height (or a few
 * blocks above it, so it leaves the staircase towards the ore) and is two blocks high, so players can walk it too.
 * Missing steps and floor over caves are filled with cobblestone. Nothing next to lava is dug, and an ore that can
 * only be got at through lava (or unbreakable blocks) is left.
 */
public final class OreJob implements DigJob {
	private static final int[][] RING = MiningJob.RING;
	private static final int SKIP_TICKS = 1200;
	private static final int FORGET_TICKS = 6000;
	/** Block reads per call while the ground is searched for ores. */
	private static final int SCAN_BUDGET = 20000;
	/** How far around a robot the rest of a vein is looked for. */
	private static final int VEIN = 5;
	/** Ores looked at per call when a robot needs a new one. */
	private static final int PICK_CHECKS = 2000;
	/** A tunnel this long is given up. */
	private static final int MAX_TUNNEL = 400;

	private static final Map<String, OreJob> JOBS = new HashMap<>();

	private final BlockPos station;
	private final int minX;
	private final int minY;
	private final int minZ;
	private final int maxX;
	private final int maxY;
	private final int maxZ;
	private final BlockPos pillar;
	/** Chunk columns (x, z packed) in the order they are searched: closest to the station first. */
	private final long[] chunks;
	private int searched;
	/** Ores found, best first. */
	private final TreeSet<long[]> ores = new TreeSet<>(Comparator.<long[]>comparingLong(o -> o[1]).thenComparingLong(o -> o[0]));
	private final Set<Long> known = new HashSet<>();
	/** Ores that cannot be got at (lava, bedrock, ...). */
	private final Set<Long> bad = new HashSet<>();
	private final Map<UUID, Tunnel> tunnels = new HashMap<>();
	private final Map<Long, UUID> claimed = new HashMap<>();
	private final Map<Long, Long> skipped = new HashMap<>();
	/** No ore left that a robot could go for. */
	private boolean exhausted;
	private long lastUsed;

	/** The way from the staircase to one ore. */
	private static final class Tunnel {
		final BlockPos ore;
		/** The robot stands here (on a step of the staircase) where the tunnel begins. */
		final BlockPos start;
		/** The axis it first leaves the staircase along, until two blocks away from the pillar. */
		final Direction.Axis out;
		/** Where a robot stands along the tunnel, in order; the last is how far it has got. */
		final List<BlockPos> cells = new ArrayList<>();

		Tunnel(BlockPos ore, BlockPos start, Direction.Axis out) {
			this.ore = ore;
			this.start = start;
			this.out = out;
		}

		BlockPos head() {
			return cells.isEmpty() ? start : cells.get(cells.size() - 1);
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
		List<long[]> columns = new ArrayList<>();
		int scx = station.getX() >> 4;
		int scz = station.getZ() >> 4;

		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				long dx = cx - scx;
				long dz = cz - scz;
				columns.add(new long[] {column(cx, cz), dx * dx + dz * dz});
			}
		}

		columns.sort(Comparator.comparingLong(c -> c[1]));
		chunks = columns.stream().mapToLong(c -> c[0]).toArray();
	}

	/** The ore search below a station: the footprint of its area (inclusive bounds), from the station down to {@code bottom}. */
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

	/** Ores of the game and of other mods (the common ore tag), ancient debris included. */
	public static boolean isOre(BlockState state) {
		return state.is(ConventionalBlockTags.ORES) || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
				|| state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES) || state.is(BlockTags.DIAMOND_ORES)
				|| state.is(BlockTags.EMERALD_ORES) || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.ANCIENT_DEBRIS);
	}

	/** Rare ores count as closer, so the robots go for diamonds before the coal right next to them. */
	private static int value(BlockState state) {
		if (state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES) || state.is(Blocks.ANCIENT_DEBRIS)) {
			return 4;
		}

		if (state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)) {
			return 2;
		}

		return 1;
	}

	@Override
	public boolean keepsDrops(BlockState state) {
		return isOre(state);
	}

	// ---- what may be dug ----

	private boolean contains(BlockPos pos) {
		// the tunnels may start on the top step, one block higher than the deepest ore searched for
		return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY + 2 && pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	private boolean inShaft(int x, int z) {
		return Math.abs(x - pillar.getX()) <= 1 && Math.abs(z - pillar.getZ()) <= 1;
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

	private static boolean exposed(ServerLevel level, BlockPos pos) {
		for (Direction side : Direction.values()) {
			BlockPos next = pos.relative(side);

			if (level.getBlockState(next).getCollisionShape(level, next).isEmpty()) {
				return true;
			}
		}

		return false;
	}

	/** Fills a hole (air, water, plants) with cobblestone, for a step or a tunnel floor. */
	private void fill(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);

		if (contains(pos) && level.isLoaded(pos) && state.canBeReplaced() && !lava(level, pos)) {
			level.setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());
		}
	}

	// ---- searching the ground ----

	private static long column(int x, int z) {
		return (long) x << 32 | z & 0xFFFFFFFFL;
	}

	private void scan(ServerLevel level) {
		int budget = 0;
		int deferred = 0;

		while (searched < chunks.length && budget < SCAN_BUDGET) {
			long column = chunks[searched];
			int cx = (int) (column >> 32);
			int cz = (int) column;
			LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);

			if (chunk == null) {
				// not loaded (yet): searched once the others are done
				if (searched == chunks.length - 1 || ++deferred > 64) {
					break;
				}

				chunks[searched] = chunks[chunks.length - 1];
				chunks[chunks.length - 1] = column;
				continue;
			}

			int x0 = Math.max(minX, cx << 4);
			int x1 = Math.min(maxX, (cx << 4) + 15);
			int z0 = Math.max(minZ, cz << 4);
			int z1 = Math.min(maxZ, (cz << 4) + 15);
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

			for (int sy = maxY >> 4; sy >= minY >> 4; sy--) {
				int index = chunk.getSectionIndexFromSectionY(sy);

				if (index < 0 || index >= chunk.getSections().length) {
					continue;
				}

				LevelChunkSection section = chunk.getSection(index);
				budget++;

				if (section.hasOnlyAir() || !section.maybeHas(OreJob::isOre)) {
					continue;
				}

				int y0 = Math.max(minY, sy << 4);
				int y1 = Math.min(maxY, (sy << 4) + 15);

				for (int y = y1; y >= y0; y--) {
					for (int x = x0; x <= x1; x++) {
						for (int z = z0; z <= z1; z++) {
							budget++;
							BlockState state = section.getBlockState(x & 15, y & 15, z & 15);

							if (isOre(state) && !inShaft(x, z)) {
								pos.set(x, y, z);
								add(pos.immutable(), state);
							}
						}
					}
				}
			}

			searched++;
		}
	}

	private void add(BlockPos pos, BlockState state) {
		if (known.add(pos.asLong())) {
			long dx = pos.getX() - station.getX();
			long dy = pos.getY() - station.getY();
			long dz = pos.getZ() - station.getZ();
			ores.add(new long[] {pos.asLong(), (dx * dx + dz * dz + dy * dy) / value(state)});
			exhausted = false;
		}
	}

	@Override
	public boolean finished() {
		return searched >= chunks.length && exhausted && tunnels.isEmpty();
	}

	// ---- handing out blocks ----

	@Override
	public List<BlockPos> candidates(ServerLevel level, BlockPos from, UUID robot, int count, long now) {
		scan(level);
		skipped.values().removeIf(until -> until < now);
		claimed.values().removeIf(robot::equals);
		List<BlockPos> found = vein(level, from);

		if (found.isEmpty()) {
			Tunnel tunnel = tunnel(level, robot);

			if (tunnel != null) {
				List<BlockPos> shaft = shaft(level, tunnel.start.getY());
				found = shaft != null ? shaft : next(level, robot, tunnel);
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

	/** Ores right around the robot it can get at: the rest of the vein it is at. */
	private List<BlockPos> vein(ServerLevel level, BlockPos from) {
		List<BlockPos> found = new ArrayList<>();

		for (BlockPos pos : BlockPos.betweenClosed(from.offset(-VEIN, -VEIN, -VEIN), from.offset(VEIN, VEIN, VEIN))) {
			if (free(pos) && isOre(level.getBlockState(pos)) && wanted(level, pos) && exposed(level, pos)) {
				found.add(pos.immutable());
			}
		}

		return found;
	}

	/**
	 * The robot got the ore its tunnel led to, and none of the vein is left around it: time to take the ores home
	 * (the next call hands it a new tunnel).
	 */
	public boolean veinDone(ServerLevel level, UUID robot, BlockPos from) {
		Tunnel tunnel = tunnels.get(robot);

		if (tunnel == null || !level.isLoaded(tunnel.ore) || isOre(level.getBlockState(tunnel.ore)) && !bad.contains(tunnel.ore.asLong())) {
			return false;
		}

		if (!vein(level, from).isEmpty()) {
			return false;
		}

		tunnels.remove(robot);
		return true;
	}

	/** The robot's tunnel, or a new one to the best ore no other robot goes for; null when there is none. */
	private Tunnel tunnel(ServerLevel level, UUID robot) {
		Tunnel tunnel = tunnels.get(robot);

		if (tunnel != null && level.isLoaded(tunnel.ore) && (!isOre(level.getBlockState(tunnel.ore)) || bad.contains(tunnel.ore.asLong()))) {
			// dug (by it, another robot or a player), or given up
			tunnels.remove(robot);
			tunnel = null;
		}

		if (tunnel != null) {
			return tunnel;
		}

		Set<Long> taken = new HashSet<>();

		for (Tunnel other : tunnels.values()) {
			taken.add(other.ore.asLong());
		}

		int checks = 0;
		boolean left = false;

		for (Iterator<long[]> it = ores.iterator(); it.hasNext() && checks < PICK_CHECKS; checks++) {
			long[] ore = it.next();
			BlockPos pos = BlockPos.of(ore[0]);

			if (!level.isLoaded(pos)) {
				left = true;
				continue;
			}

			if (!isOre(level.getBlockState(pos)) || bad.contains(ore[0])) {
				it.remove();
				known.remove(ore[0]);
				continue;
			}

			if (taken.contains(ore[0]) || !free(pos)) {
				left = true;
				continue;
			}

			tunnel = plan(pos);

			if (tunnel == null) {
				bad.add(ore[0]);
				continue;
			}

			tunnels.put(robot, tunnel);
			return tunnel;
		}

		exhausted = !left && checks < PICK_CHECKS;
		return null;
	}

	/**
	 * Where the tunnel to an ore leaves the staircase: on the step at the ore's height or a little above, on the
	 * side of the staircase facing the ore. Prefers heights from which the tunnel can come down to the ore by the
	 * time it gets there.
	 */
	private Tunnel plan(BlockPos ore) {
		int dx = ore.getX() - pillar.getX();
		int dz = ore.getZ() - pillar.getZ();
		int distance = Math.abs(dx) + Math.abs(dz);
		Tunnel fallback = null;

		for (int level = ore.getY(); level <= Math.min(ore.getY() + 16, station.getY()); level++) {
			if (level - 1 < minY) {
				continue;
			}

			// the robot stands on the step below its feet
			int[] cell = RING[stepIndex(level - 1)];
			Direction.Axis out = null;

			if (cell[0] != 0 && Integer.signum(dx) == cell[0] && Math.abs(dx) >= 2) {
				out = Direction.Axis.X;
			} else if (cell[1] != 0 && Integer.signum(dz) == cell[1] && Math.abs(dz) >= 2) {
				out = Direction.Axis.Z;
			}

			if (out == null) {
				continue;
			}

			Tunnel tunnel = new Tunnel(ore, new BlockPos(pillar.getX() + cell[0], level, pillar.getZ() + cell[1]), out);

			if (level - ore.getY() <= distance - 2) {
				return tunnel;
			}

			if (fallback == null) {
				fallback = tunnel;
			}
		}

		return fallback;
	}

	/**
	 * The staircase down to a robot standing at height {@code bottom}: the highest blocks of it still to dig, the
	 * ones it can get at; null when it is open all the way down.
	 */
	private List<BlockPos> shaft(ServerLevel level, int bottom) {
		List<BlockPos> found = new ArrayList<>();
		boolean left = false;

		for (int y = station.getY() - 1; y >= bottom && found.isEmpty(); y--) {
			for (int[] cell : RING) {
				BlockPos pos = new BlockPos(pillar.getX() + cell[0], y, pillar.getZ() + cell[1]);

				if (!level.isLoaded(pos)) {
					continue;
				}

				if (isStair(pos)) {
					fill(level, pos);
				} else if (!clear(level, pos) && wanted(level, pos)) {
					left = true;

					if (free(pos) && exposed(level, pos)) {
						found.add(pos);
					}
				}
			}
		}

		return left ? found : null;
	}

	/** Blocks a robot stands in at a place along the tunnel: its feet and head, and above its head when it climbs there. */
	private static List<BlockPos> space(BlockPos cell, BlockPos before) {
		List<BlockPos> blocks = new ArrayList<>(List.of(cell, cell.above()));

		if (cell.getY() > before.getY()) {
			blocks.add(before.above(2));
		}

		return blocks;
	}

	/** The next blocks of the robot's tunnel: what fell back into it first, then the next place on the way to the ore. */
	private List<BlockPos> next(ServerLevel level, UUID robot, Tunnel tunnel) {
		List<BlockPos> found = new ArrayList<>();
		BlockPos before = tunnel.start;

		// sand, gravel or a player's block in the tunnel so far
		for (BlockPos cell : tunnel.cells) {
			for (BlockPos pos : space(cell, before)) {
				if (!clear(level, pos) && free(pos) && wanted(level, pos) && exposed(level, pos)) {
					found.add(pos);
				}
			}

			before = cell;
		}

		if (!found.isEmpty()) {
			return found;
		}

		for (int steps = 0; steps < 32; steps++) {
			BlockPos head = tunnel.head();

			if (head.equals(tunnel.ore) || tunnel.cells.size() >= MAX_TUNNEL) {
				// there and the ore is still there (inside a block it cannot dig?), or lost: given up
				giveUp(robot, tunnel);
				return found;
			}

			BlockPos cell = step(head, tunnel);
			boolean ready = true;

			for (BlockPos pos : space(cell, head)) {
				if (clear(level, pos)) {
					continue;
				}

				if (!level.isLoaded(pos)) {
					return found;
				}

				if (!wanted(level, pos)) {
					// lava, bedrock, a chest, outside the area: no way to this ore
					giveUp(robot, tunnel);
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
			if (!cell.equals(tunnel.ore)) {
				fill(level, cell.below());
			}

			tunnel.cells.add(cell);
		}

		return found;
	}

	private void giveUp(UUID robot, Tunnel tunnel) {
		bad.add(tunnel.ore.asLong());
		tunnels.remove(robot);
	}

	/** One place further towards the ore: away from the staircase first, then along the longer way, coming down (or up) one block each step. */
	private BlockPos step(BlockPos head, Tunnel tunnel) {
		BlockPos ore = tunnel.ore;
		int dx = ore.getX() - head.getX();
		int dz = ore.getZ() - head.getZ();
		int dy = Integer.signum(ore.getY() - head.getY());
		boolean leaving = inShaft(head.getX(), head.getZ()) || (tunnel.out == Direction.Axis.X ? Math.abs(head.getX() - pillar.getX()) : Math.abs(head.getZ() - pillar.getZ())) < 2;
		boolean alongX;

		if (leaving) {
			alongX = tunnel.out == Direction.Axis.X;
		} else if (dx == 0 && dz == 0) {
			// right above or below it
			return head.offset(0, dy, 0);
		} else {
			alongX = Math.abs(dx) >= Math.abs(dz);
		}

		int sx = alongX ? Integer.signum(dx) : 0;
		int sz = alongX ? 0 : Integer.signum(dz);
		return head.offset(sx, dy, sz);
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
	public void skip(BlockPos pos, long now) {
		skipped.put(pos.asLong(), now + SKIP_TICKS);
		claimed.remove(pos.asLong());
	}

	/** For the smoke test: ores found so far. */
	int oresKnown() {
		return ores.size();
	}
}
