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
	private static final int RANGE = 5;

	private static ServerLevel level;
	private static BlockPos station;
	private static final List<RobotEntity> robots = new ArrayList<>();
	private static int ticks;
	private static int deepest;
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
		for (int i = 0; i < 16 && station == null; i++) {
			int x = 400 + i * 48;
			int z = 400;

			for (int cx = -1; cx <= 1; cx++) {
				for (int cz = -1; cz <= 1; cz++) {
					level.setChunkForced((x >> 4) + cx, (z >> 4) + cz, true);
					level.getChunk((x >> 4) + cx, (z >> 4) + cz);
				}
			}

			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockPos ground = new BlockPos(x, y - 1, z);

			if (level.getFluidState(ground).isEmpty() && level.getFluidState(ground.above()).isEmpty() && level.getBlockState(ground).isSolidRender()) {
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

		for (int i = 0; i < 4; i++) {
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

		for (int i = 0; i < 4; i++) {
			RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			robot.snapTo(station.getX() + 0.5 + (i % 2 == 0 ? -2 : 2), station.getY(), station.getZ() + 0.5 + (i < 2 ? -2 : 2), 0, 0);
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
		return OreJob.get(Rooms.id(level), station, station.getX() - RANGE, station.getZ() - RANGE, station.getX() + RANGE, station.getZ() + RANGE,
				level.getMinY(), level.getGameTime());
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
		}

		if (ticks % 400 == 0) {
			StorageDimensionMod.LOGGER.info("SMOKETEST Tief {}: {}", ticks, job.describe(level));

			for (RobotEntity robot : robots) {
				StorageDimensionMod.LOGGER.info("SMOKETEST Tief-Roboter {}: {} {} at {}", ticks, robot.describeMining(), robot.status().getString(), robot.blockPosition());
			}
		}

		// the first place of the main tunnel north on the deepest level is open: the robots got all the way down
		BlockPos tunnel = new BlockPos(station.getX(), bottom, station.getZ() - 2);

		if (level.getBlockState(tunnel).isAir() && level.getBlockState(tunnel.above()).isAir() && deepest <= bottom + 1) {
			StorageDimensionMod.LOGGER.info("SMOKETEST Tiefe Erzsuche: unterste Ebene y={} nach {} Ticks erreicht", bottom, ticks);
			StationMining.toggle(level, Rooms.id(level), station);
			finish(List.of());
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

	private static void finish(List<String> errors) {
		Consumer<List<String>> callback = done;
		done = null;
		callback.accept(errors);
	}
}
