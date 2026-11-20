package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.FilterChests;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Smoke test of the ore search in the real ground (CI, after the other ore test): a station on the surface of the
 * generated world, with a small area, and the mine going all the way down to the bedrock like in a player's world.
 * The robots must dig the staircase through dirt, stone, caves, water and deepslate down to the deepest level and
 * start the tunnels there.
 */
public final class DeepOreSmokeTest {
	private static final int TIMEOUT = 24000;
	/** The smallest range a station has (smaller ones count as this), so the test looks at the same job as the robots. */
	private static final int RANGE = 16;
	private static final int ROBOTS = 8;

	private static ServerLevel level;
	private static BlockPos station;
	private static final List<RobotEntity> robots = new ArrayList<>();
	private static int ticks;
	private static int deepest;
	private static int reachedAt = -1;
	private static RobotEntity trapped;
	private static int trappedAt;
	private static final java.util.Map<RobotEntity, Integer> idle = new java.util.HashMap<>();
	/** A robot with a pickaxe that waits this long while there is still work has lost its way. */
	private static final int IDLE_LIMIT = 2400;
	private static Consumer<List<String>> done;
	private static boolean running;

	private DeepOreSmokeTest() {
	}

	public static void start(MinecraftServer server, Consumer<List<String>> onDone) {
		done = onDone;
		level = server.overworld();
		String dim = Rooms.id(level);
		System.clearProperty("storagedim.stripBottom");
		station = null;

		// somewhere on dry land, away from the other tests high up in the air
		for (int i = 0; i < 64 && station == null; i++) {
			int x = 400 + (i % 8) * 96;
			int z = 400 + (i / 8) * 96;

			for (int cx = -1; cx <= 1; cx++) {
				for (int cz = -1; cz <= 1; cz++) {
					level.setChunkForced((x >> 4) + cx, (z >> 4) + cz, true);
					level.getChunk((x >> 4) + cx, (z >> 4) + cz);
				}
			}

			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos ground = new BlockPos(x, y - 1, z);

			if (level.getFluidState(ground).isEmpty() && level.getFluidState(ground.above()).isEmpty() && level.getBlockState(ground).isFaceSturdy(level, ground, net.minecraft.core.Direction.UP)) {
				station = ground.above();
			}
		}

		if (station == null) {
			done.accept(List.of("Tiefe Erzsuche: kein trockenes Land für die Station gefunden"));
			return;
		}

		level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
		level.setBlockAndUpdate(station.east(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.EAST));
		StorageData data = StorageData.get();
		UUID player = UUID.randomUUID();
		BlockPos filterPos = station.west();
		level.setBlockAndUpdate(filterPos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.WEST));
		FilterChests.FilterChest filterChest = FilterChests.FilterChest.empty(player);
		filterChest.filters().set(0, new ItemStack(Items.DIAMOND_PICKAXE));
		data.setFilterChest(dim, filterPos, filterChest);

		for (int i = 0; i < ROBOTS; i++) {
			data.networkFor(player).insert(new ItemStack(Items.DIAMOND_PICKAXE));
		}

		data.setStation(dim, station, data.station(dim, station).withRange(RANGE).withName("Tief").withStationName("Tiefgrube"));
		StationMining.toggle(level, dim, station);
		StationMining.Result result = StationMining.toggle(level, dim, station);
		RobotConfig config = data.station(dim, station);
		StorageDimensionMod.LOGGER.info("SMOKETEST Tiefe Erzsuche bei {}: {}", station, result.message().getString());

		if (!result.ok() || !config.oreSearch()) {
			done.accept(List.of("Tiefe Erzsuche ließ sich nicht einschalten: " + result.message().getString()));
			return;
		}

		for (int i = 0; i < ROBOTS; i++) {
			RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			int x = station.getX() + (i % 2 == 0 ? -3 : 3);
			int z = station.getZ() + (i % 4 < 2 ? -3 : 3) + i / 4;
			robot.snapTo(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5, 0, 0);
			robot.setup(null, station);
			robot.applyConfig(config);
			level.addFreshEntity(robot);
			robots.add(robot);
		}

		deepest = Integer.MAX_VALUE;

		if (!running) {
			running = true;
			ServerTickEvents.END_SERVER_TICK.register(s -> {
				try {
					tick();
				} catch (Throwable t) {
					StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme im Test der tiefen Erzsuche", t);

					if (done != null) {
						finish(List.of(t.toString()));
					}
				}
			});
		}
	}

	private static OreJob job() {
		int[] square = RobotEntity.chunkSquare(station, RANGE);
		return OreJob.get(Rooms.id(level), station, square[0], square[1], square[2], square[3], level.getMinY(), level.getGameTime());
	}

	private static void tick() {
		if (done == null) {
			return;
		}

		ticks++;
		OreJob job = job();
		int bottom = job.levels()[0];

		for (RobotEntity robot : robots) {
			deepest = Math.min(deepest, robot.blockPosition().getY());
			idle.merge(robot, 1, Integer::sum);

			if (!robot.describeMining().contains("idle=true") || robot.tool(0).isEmpty()) {
				idle.put(robot, 0);
			}

			if (idle.get(robot) > IDLE_LIMIT) {
				pathProbe();
				finish(List.of("Tiefe Erzsuche: Roboter wartet seit " + IDLE_LIMIT + " Ticks, obwohl noch Arbeit da ist: " + robot.describeMining() + " "
						+ robot.status().getString() + " at " + robot.blockPosition(), "Stand: " + job.describe(level)));
				return;
			}
		}

		if (ticks % 400 == 0) {
			StorageDimensionMod.LOGGER.info("SMOKETEST Tief {}: {}", ticks, job.describe(level));

			for (RobotEntity robot : robots) {
				StorageDimensionMod.LOGGER.info("SMOKETEST Tief-Roboter {}: {} {} at {}", ticks, robot.describeMining(), robot.status().getString(), robot.blockPosition());
			}

			pathProbe();
		}

		// the staircase goes all the way down while the robots work on the tunnels of the levels above
		if (reachedAt < 0 && job.staircaseOpen(level) && deepest <= bottom + 8) {
			StorageDimensionMod.LOGGER.info("SMOKETEST Tiefe Erzsuche: Treppe bis zur untersten Ebene y={} offen nach {} Ticks", bottom, ticks);
			reachedAt = ticks;
		}

		long below = robots.stream().filter(r -> !r.tool(0).isEmpty() && r.getY() < bottom + 8).count();

		if (trapped != null) {
			// the robot walled in deep down digs itself out and comes back to the station; it may go straight back to
			// work down the staircase from there, so it counts as home anywhere in the column over the staircase (the
			// pocket is 8 blocks off to the side, well outside it), once it has stopped digging its own way
			if (Math.abs(trapped.getX() - station.getX() - 0.5) < 5 && Math.abs(trapped.getZ() - station.getZ() - 0.5) < 5 && trapped.getY() > station.getY() - 12
					&& !trapped.rescuing()) {
				StorageDimensionMod.LOGGER.info("SMOKETEST Tiefe Erzsuche: eingemauerter Roboter nach {} Ticks frei und an der Treppe bei {}", ticks - trappedAt, trapped.blockPosition());
				StationMining.toggle(level, Rooms.id(level), station);
				finish(List.of());
			} else if (ticks - trappedAt > 3000) {
				finish(List.of("Tiefe Erzsuche: eingemauerter Roboter kam nicht frei: " + trapped.describeMining() + " " + trapped.status().getString() + " at " + trapped.blockPosition()));
			}

			if ((ticks - trappedAt) % 200 == 0) {
				StorageDimensionMod.LOGGER.info("SMOKETEST Tief-Eingemauert {}: {} {} at {}", ticks - trappedAt, trapped.describeMining(), trapped.status().getString(), trapped.blockPosition());
			}

			return;
		}

		if (reachedAt >= 0 && job.tunnelsDone() >= 6) {
			StorageDimensionMod.LOGGER.info("SMOKETEST Tiefe Erzsuche: {} Gänge fertig nach {} Ticks, {} Roboter unten", job.tunnelsDone(), ticks, below);
			// then one robot walled in, 12 blocks down and 8 to the side: it has to dig itself out
			trapped = robots.stream().filter(r -> !r.tool(0).isEmpty()).findFirst().orElse(robots.get(0));
			BlockPos pocket = station.offset(8, -12, 8);

			for (BlockPos pos : BlockPos.betweenClosed(pocket.offset(-1, -1, -1), pocket.offset(1, 2, 1))) {
				level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
			}

			level.setBlockAndUpdate(pocket, Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(pocket.above(), Blocks.AIR.defaultBlockState());
			trapped.snapTo(pocket.getX() + 0.5, pocket.getY(), pocket.getZ() + 0.5, 0, 0);
			trappedAt = ticks;
			return;
		} else if (!robots.isEmpty() && robots.stream().allMatch(r -> !r.isAlive())) {
			finish(List.of("Tiefe Erzsuche: alle Roboter sind weg (gestorben?)"));
		} else if (ticks > TIMEOUT) {
			List<String> errors = new ArrayList<>();
			errors.add("Tiefe Erzsuche nach " + ticks + " Ticks nicht unten (Ebene y=" + bottom + ", tiefster Roboter y=" + deepest + ")");
			errors.add("Stand: " + job.describe(level));

			for (RobotEntity robot : robots) {
				errors.add("Roboter: " + robot.describeMining() + " " + robot.status().getString() + " " + robot.describe() + " at " + robot.blockPosition());
			}

			finish(errors);
		}
	}

	/** For each waiting robot: the ways to the blocks handed out, home, and over the staircase, and what is around it. */
	private static void pathProbe() {
		OreJob job = job();

		for (RobotEntity robot : robots) {
			if (robot.tool(0).isEmpty() || !robot.describeMining().contains("idle=true")) {
				continue;
			}

			BlockPos at = robot.blockPosition();
			StringBuilder out = new StringBuilder();
			out.append(" onGround=").append(robot.onGround()).append(" unten=").append(name(at.below()));

			for (BlockPos pos : job.candidates(level, at, robot.getUUID(), 4, level.getGameTime())) {
				out.append(" grab ").append(pos.toShortString()).append(describe(robot.getNavigation().createPath(java.util.Set.of(pos), 4)));
			}

			out.append(" heim").append(describe(robot.getNavigation().createPath(station, 2)));

			for (BlockPos waypoint : job.waypoints(level, at, station.getY())) {
				out.append(" hoch ").append(waypoint.toShortString()).append(describe(robot.getNavigation().createPath(waypoint, 1)));
			}

			for (BlockPos waypoint : job.waypoints(level, at, job.levels()[0])) {
				out.append(" runter ").append(waypoint.toShortString()).append(describe(robot.getNavigation().createPath(waypoint, 1)));
			}

			StorageDimensionMod.LOGGER.info("SMOKETEST Tief-Weg {} at {}:{}", robot.getUUID().toString().substring(0, 4), at, out);

			for (int dy = 2; dy >= -2; dy--) {
				StringBuilder row = new StringBuilder();

				for (int dz = -2; dz <= 2; dz++) {
					for (int dx = -2; dx <= 2; dx++) {
						row.append(name(at.offset(dx, dy, dz))).append(dx == 2 ? " | " : ",");
					}
				}

				StorageDimensionMod.LOGGER.info("SMOKETEST Tief-Umgebung dy={} (Zeilen dz -2..2, je dx -2..2): {}", dy, row);
			}
		}
	}

	private static String name(BlockPos pos) {
		String id = level.getBlockState(pos).getBlock().getDescriptionId().replace("block.minecraft.", "").replace("block.storagedim.", "");
		return pos.getX() == station.getX() && pos.getZ() == station.getZ() ? id.toUpperCase(java.util.Locale.ROOT) : id;
	}

	private static String describe(net.minecraft.world.level.pathfinder.Path path) {
		return path == null ? "->null" : "->" + (path.canReach() ? "ok" : "nein") + "/" + path.getNodeCount() + "@" + path.getEndNode().asBlockPos().toShortString();
	}

	private static void finish(List<String> errors) {
		Consumer<List<String>> callback = done;
		done = null;
		callback.accept(errors);
	}
}
