package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.FilterChests;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Smoke test with a full station (CI, -Dstoragedim.swarmtest=true): 32 robots on the real ground of the generated
 * world, first in the mining mode, then in the ore search, each at a station of its own with a dimension chest and a
 * filter chest with pickaxes. Most of them must be at work all the time, and none may stand about for long while
 * there is still something to do (no "nothing to reach" with a full area, no sitting at the chest with its load).
 */
public final class SwarmSmokeTest {
	private static final int ROBOTS = 32;
	private static final int RANGE = 16;
	private static final int PHASE_TICKS = 6000;
	/** The first ticks, while they fetch their pickaxes and spread out, do not count. */
	private static final int WARMUP = 600;
	/** A robot that has done nothing (not digging, not getting anywhere) this long is stuck. */
	private static final int STUCK_LIMIT = 1200;
	/** At least this share of the robots must be at work on average. */
	private static final double MIN_WORKING = 0.6;
	/** More than this many standing about for long at the same time is a failure. */
	private static final int MAX_STUCK = 3;
	/** Longest all robots together may spend working out ways in one tick (the budget is 20 ms, plus one search running over it). */
	private static final long MAX_PATH_MILLIS = 150;

	private static ServerLevel level;
	private static BlockPos station;
	private static boolean ores;
	private static final List<RobotEntity> robots = new ArrayList<>();
	private static final Map<RobotEntity, Integer> stuckFor = new HashMap<>();
	private static final Map<RobotEntity, Vec3> lastPos = new HashMap<>();
	private static final List<String> errors = new ArrayList<>();
	private static final List<RobotEntity> reported = new ArrayList<>();
	private static int ticks;
	private static long maxPathNanos;
	private static long workingSum;
	private static long samples;
	private static Consumer<List<String>> done;
	private static boolean running;

	private SwarmSmokeTest() {
	}

	public static void start(MinecraftServer server, Consumer<List<String>> onDone) {
		done = onDone;
		level = server.overworld();
		System.clearProperty("storagedim.stripBottom");
		System.clearProperty("storagedim.mineBottom");
		startPhase(false, -800);

		if (!running) {
			running = true;
			ServerTickEvents.END_SERVER_TICK.register(s -> {
				try {
					maxPathNanos = Math.max(maxPathNanos, RobotEntity.pathTimeInTick(s.getTickCount()));
					tick();
				} catch (Throwable t) {
					StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme im Schwarm-Test", t);
					errors.add(t.toString());
					finish();
				}
			});
		}
	}

	private static String stuck0() {
		return "zu viele hängen: ";
	}

	private static String name() {
		return ores ? "Schwarm-Erzsuche" : "Schwarm-Abbau";
	}

	/** A station on dry land around {@code (x0, x0)}, with its chests and 32 robots. */
	private static void startPhase(boolean oreSearch, int x0) {
		ores = oreSearch;
		station = null;
		robots.clear();
		stuckFor.clear();
		lastPos.clear();
		reported.clear();
		ticks = 0;
		maxPathNanos = 0;
		workingSum = 0;
		samples = 0;
		String dim = Rooms.id(level);

		for (int i = 0; i < 64 && station == null; i++) {
			// a grid around (x0, x0), wide enough to get out of an ocean
			int x = x0 + (i % 8 - 4) * 160;
			int z = x0 + (i / 8 - 4) * 160;

			for (int cx = -2; cx <= 2; cx++) {
				for (int cz = -2; cz <= 2; cz++) {
					level.setChunkForced((x >> 4) + cx, (z >> 4) + cz, true);
					level.getChunk((x >> 4) + cx, (z >> 4) + cz);
				}
			}

			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos ground = new BlockPos(x, y - 1, z);

			if (y > level.getSeaLevel() && level.getFluidState(ground).isEmpty() && level.getFluidState(ground.above()).isEmpty()
					&& level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) {
				station = ground.above();
			} else {
				for (int cx = -2; cx <= 2; cx++) {
					for (int cz = -2; cz <= 2; cz++) {
						level.setChunkForced((x >> 4) + cx, (z >> 4) + cz, false);
					}
				}
			}
		}

		if (station == null) {
			errors.add(name() + ": kein trockenes Land für die Station gefunden");
			finish();
			return;
		}

		StorageData data = StorageData.get();
		UUID player = UUID.randomUUID();
		level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
		// a dimension chest for what they bring, a filter chest with the pickaxes
		BlockPos chest = station.east();
		level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
		level.setBlockAndUpdate(chest.above(), Blocks.AIR.defaultBlockState());
		data.setChest(dim, chest, player);
		BlockPos filterPos = station.west();
		level.setBlockAndUpdate(filterPos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST));
		level.setBlockAndUpdate(filterPos.above(), Blocks.AIR.defaultBlockState());
		FilterChests.FilterChest filterChest = FilterChests.FilterChest.empty(player);
		filterChest.filters().set(0, new ItemStack(Items.DIAMOND_PICKAXE));
		data.setFilterChest(dim, filterPos, filterChest);

		for (int i = 0; i < ROBOTS; i++) {
			data.networkFor(player).insert(new ItemStack(Items.DIAMOND_PICKAXE));
		}

		data.setStation(dim, station, data.station(dim, station).withRange(RANGE).withName(oreSearch ? "Erzi" : "Kumpel").withStationName(name()));
		StationMining.Result result = StationMining.toggle(level, dim, station);

		if (oreSearch) {
			result = StationMining.toggle(level, dim, station);
		}

		RobotConfig config = data.station(dim, station);
		StorageDimensionMod.LOGGER.info("SMOKETEST {} bei {}: {}", name(), station, result.message().getString());
		// the area stays loaded like a player sets it up for the robots to go on alone; CI lets the server pause after
		// a minute without players, which it must not do now
		StationChunks.toggle(level, dim, station);

		if (!result.ok() || !config.mining() || config.oreSearch() != oreSearch) {
			errors.add(name() + " ließ sich nicht einschalten: " + result.message().getString());
			finish();
			return;
		}

		for (int i = 0; i < ROBOTS; i++) {
			RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			int x = station.getX() - 3 + i % 7;
			int z = station.getZ() - 3 + i / 7 % 7;
			robot.snapTo(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5, 0, 0);
			robot.setup(null, station);
			robot.applyConfig(config);
			level.addFreshEntity(robot);
			robots.add(robot);
		}
	}

	/** Busy: digging, on its way with tools or a load, or getting on towards a block. */
	private static boolean working(RobotEntity robot) {
		String status = robot.status().getString();
		Vec3 before = lastPos.get(robot);
		boolean moved = before != null && before.distanceTo(robot.position()) > 0.3;
		return status.startsWith("Baut") && (moved || robot.isDigging()) || status.startsWith("Holt Werkzeug") || status.startsWith("Bringt") && moved;
	}

	private static void tick() {
		if (done == null || station == null) {
			return;
		}

		ticks++;

		Map<RobotEntity, Boolean> busyNow = new HashMap<>();

		if (ticks % 20 == 0) {
			int working = 0;

			for (RobotEntity robot : robots) {
				boolean busy = working(robot);
				busyNow.put(robot, busy);
				working += busy ? 1 : 0;
				stuckFor.put(robot, busy ? 0 : stuckFor.getOrDefault(robot, 0) + 20);
				lastPos.put(robot, robot.position());

				if (ticks > WARMUP && stuckFor.get(robot) > STUCK_LIMIT && !reported.contains(robot) && !jobFinished()) {
					reported.add(robot);
					StorageDimensionMod.LOGGER.info("SMOKETEST {} hängt: {} {} at {} {}", name(), robot.status().getString(), robot.describeMining(),
							robot.blockPosition(), robot.describe());
					probe(robot);
				}
			}

			if (ticks > WARMUP) {
				workingSum += working;
				samples++;
				// a few may get stuck in odd places of the real ground; many at once is what the players saw
				long stuck = robots.stream().filter(r -> stuckFor.getOrDefault(r, 0) > STUCK_LIMIT).count();

				if (stuck > MAX_STUCK && !jobFinished() && errors.stream().noneMatch(e -> e.startsWith(name() + ": " + stuck0()))) {
					errors.add(name() + ": " + stuck0() + stuck + " Roboter stehen gleichzeitig seit " + STUCK_LIMIT + " Ticks (" + ticks + ")");
				}
			}
		}

		if (ticks % 400 == 0) {
			Map<String, Integer> histogram = new TreeMap<>();

			for (RobotEntity robot : robots) {
				String status = robot.status().getString();
				String key = status.startsWith("Baut") ? "Baut" : status.startsWith("Bringt") && !status.contains("Werkzeug") ? "Bringt" : status;
				histogram.merge(key + (busyNow.getOrDefault(robot, true) ? "" : " (steht)"), 1, Integer::sum);
			}

			long below = robots.stream().filter(r -> r.getY() < station.getY() - 4).count();
			StorageDimensionMod.LOGGER.info("SMOKETEST {} {}: {} unten={} tiefster={} {}", name(), ticks, histogram, below,
					robots.stream().mapToInt(r -> r.getBlockY()).min().orElse(0), jobInfo());
			int shown = 0;

			// the ones standing about, with the ways to what they would be handed
			for (RobotEntity robot : robots) {
				if (busyNow.getOrDefault(robot, true) || stuckFor.getOrDefault(robot, 0) < 200 || shown++ >= 6) {
					continue;
				}

				StorageDimensionMod.LOGGER.info("SMOKETEST {} steht {}: {} {} at {} {}", name(), stuckFor.get(robot), robot.status().getString(), robot.describeMining(),
						robot.blockPosition(), robot.describe());
			}
		}

		if (ticks >= PHASE_TICKS || !robots.isEmpty() && robots.stream().noneMatch(RobotEntity::isAlive)) {
			double share = samples == 0 ? 0 : workingSum / (double) samples / ROBOTS;
			StorageDimensionMod.LOGGER.info("SMOKETEST {}: im Schnitt {} % der Roboter bei der Arbeit, Wegsuche höchstens {} ms pro Tick", name(),
					Math.round(share * 100), maxPathNanos / 1_000_000);

			if (maxPathNanos / 1_000_000 > MAX_PATH_MILLIS) {
				errors.add(name() + ": Wegsuche brauchte in einem Tick " + maxPathNanos / 1_000_000 + " ms (erlaubt " + MAX_PATH_MILLIS + ")");
			}

			if (share < MIN_WORKING && !jobFinished()) {
				errors.add(name() + ": im Schnitt nur " + Math.round(share * 100) + " % der Roboter bei der Arbeit (" + jobInfo() + ")");
			}

			StationMining.toggle(level, Rooms.id(level), station);

			if (!ores) {
				StationMining.toggle(level, Rooms.id(level), station);
			}

			for (RobotEntity robot : robots) {
				robot.discard();
			}

			if (!ores) {
				startPhase(true, 1600);
			} else {
				finish();
			}
		}
	}

	/** What it would be handed and whether it finds a way there, home and down the staircase, and what is around it. */
	private static void probe(RobotEntity robot) {
		BlockPos at = robot.blockPosition();
		StringBuilder out = new StringBuilder(" onGround=" + robot.onGround());
		DigJob job = ores ? oreJob() : miningJob();

		for (BlockPos pos : job.candidates(level, at, robot.getUUID(), 4, level.getGameTime())) {
			out.append(" grab ").append(pos.toShortString()).append(way(robot.getNavigation().createPath(java.util.Set.of(pos), 4)));
		}

		out.append(" heim").append(way(robot.getNavigation().createPath(station, 2)));

		if (ores) {
			out.append(" stand ").append(oreJob().describe(level));
		}

		StorageDimensionMod.LOGGER.info("SMOKETEST {} Weg {} at {}:{}", name(), robot.getUUID().toString().substring(0, 4), at, out);

		for (int dy = 2; dy >= -2; dy--) {
			StringBuilder row = new StringBuilder();

			for (int dz = -2; dz <= 2; dz++) {
				for (int dx = -2; dx <= 2; dx++) {
					BlockPos pos = at.offset(dx, dy, dz);
					String id = level.getBlockState(pos).getBlock().getDescriptionId().replace("block.minecraft.", "").replace("block.storagedim.", "");
					row.append(pos.getX() == station.getX() && pos.getZ() == station.getZ() ? id.toUpperCase(java.util.Locale.ROOT) : id).append(dx == 2 ? " | " : ",");
				}
			}

			StorageDimensionMod.LOGGER.info("SMOKETEST {} Umgebung dy={}: {}", name(), dy, row);
		}
	}

	private static String way(net.minecraft.world.level.pathfinder.Path path) {
		return path == null ? "->null" : "->" + (path.canReach() ? "ok" : "nein") + "/" + path.getNodeCount() + "@" + path.getEndNode().asBlockPos().toShortString();
	}

	private static boolean jobFinished() {
		return ores ? oreJob().finished() : miningJob().finished();
	}

	private static String jobInfo() {
		if (ores) {
			return oreJob().describe(level);
		}

		MiningJob job = miningJob();
		return "Spalten übrig=" + job.columnsLeft() + " oberste Schicht=" + job.highestLayer() + " " + job.describePlan();
	}

	private static MiningJob miningJob() {
		int[] square = RobotEntity.chunkSquare(station, RANGE);
		return MiningJob.get(Rooms.id(level), station, square[0], level.getMinY(), square[1], square[2], level.getMaxY(), square[3], level.getGameTime());
	}

	private static OreJob oreJob() {
		int[] square = RobotEntity.chunkSquare(station, RANGE);
		return OreJob.get(Rooms.id(level), station, square[0], square[1], square[2], square[3], level.getMinY(), level.getGameTime());
	}

	private static void finish() {
		Consumer<List<String>> callback = done;
		done = null;
		station = null;

		if (callback != null) {
			callback.accept(new ArrayList<>(errors));
		}
	}
}
