package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;

/**
 * Smoke test of the ore search (CI, after the mining test): high above the overworld a station on a block of stone
 * with a few ores inside it. The robots have to dig down the staircase and tunnels to the iron, the diamond and both
 * coal ores (a vein of two) and to an iron ore further out and deep below (the way there runs through the rock, so a
 * robot must find a path to blocks with rock above them), bring home only the ores (no stone), and leave the gold ore
 * next to a pocket of lava alone. Switched back to collecting, the tools go back into the tool chest.
 * <p>
 * A probe checks that the search goes on past chunks that are not loaded: an ore in a loaded chunk behind unloaded
 * ones must still be found.
 */
public final class OreSmokeTest {
	private static final int TIMEOUT = 9600;
	/** Far from everything: only the chunk of this spot and one three chunks east of it are loaded, for the search probe. */
	private static final BlockPos PROBE = new BlockPos(2008, 100, 2008);
	private static final BlockPos PROBE_ORE = PROBE.offset(48, -30, 0);
	private static boolean probeDone;
	private static final int Y = 290;

	private static ServerLevel level;
	private static BlockPos station;
	private static final List<RobotEntity> robots = new ArrayList<>();
	private static final List<BlockPos> ores = new ArrayList<>();
	private static BlockPos gold;
	private static BlockPos lava;
	private static int ticks;
	private static int stage;
	private static Consumer<List<String>> done;
	private static boolean running;

	private OreSmokeTest() {
	}

	public static void start(MinecraftServer server, Consumer<List<String>> onDone) {
		done = onDone;
		level = server.overworld();
		station = new BlockPos(104, Y, 8);
		String dim = Rooms.id(level);

		for (int cx = -2; cx <= 2; cx++) {
			for (int cz = -2; cz <= 2; cz++) {
				level.setChunkForced((station.getX() >> 4) + cx, (station.getZ() >> 4) + cz, true);
			}
		}

		level.setChunkForced(PROBE.getX() >> 4, PROBE.getZ() >> 4, true);
		level.setChunkForced(PROBE_ORE.getX() >> 4, PROBE_ORE.getZ() >> 4, true);

		// a block of stone under the station, 29 wide and 14 deep
		for (int dx = -14; dx <= 14; dx++) {
			for (int dz = -14; dz <= 14; dz++) {
				for (int dy = -14; dy <= -1; dy++) {
					level.setBlockAndUpdate(station.offset(dx, dy, dz), Blocks.STONE.defaultBlockState());
				}
			}
		}

		ore(station.offset(4, -3, 0), Blocks.IRON_ORE);
		ore(station.offset(-3, -6, 2), Blocks.DIAMOND_ORE);
		ore(station.offset(0, -4, -4), Blocks.COAL_ORE);
		ore(station.offset(0, -4, -5), Blocks.COAL_ORE);
		ore(station.offset(-13, -11, 1), Blocks.IRON_ORE);
		gold = station.offset(4, -2, 4);
		lava = station.offset(5, -2, 4);
		level.setBlockAndUpdate(gold, Blocks.GOLD_ORE.defaultBlockState());
		level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());

		level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
		level.setBlockAndUpdate(station.east(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.EAST));

		StorageData data = StorageData.get();
		data.setStation(dim, station, data.station(dim, station).withRange(16).withName("Erzi").withStationName("Erzgrube"));
		List<String> errors = new ArrayList<>();
		// collecting, then mining (the tool chest is set up), then the ore search
		StationMining.toggle(level, dim, station);
		StationMining.Result result = StationMining.toggle(level, dim, station);
		RobotConfig config = data.station(dim, station);
		StorageDimensionMod.LOGGER.info("SMOKETEST Erzsuche: {}", result.message().getString());

		if (!result.ok() || !config.mining() || !config.oreSearch() || config.toolChest().isEmpty()) {
			errors.add("Erzsuche ließ sich nicht einschalten: " + result.message().getString());
			done.accept(errors);
			return;
		}

		Container tools = (Container) level.getBlockEntity(config.toolChest().get());

		for (int i = 0; i < 2; i++) {
			tools.setItem(i * 3, new ItemStack(Items.IRON_PICKAXE));
			tools.setItem(i * 3 + 1, new ItemStack(Items.IRON_AXE));
			tools.setItem(i * 3 + 2, new ItemStack(Items.IRON_SHOVEL));
		}

		tools.setChanged();

		for (int i = 0; i < 2; i++) {
			RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			robot.snapTo(station.getX() - 2.5 + i * 5, Y, station.getZ() + 3.5, 0, 0);
			robot.setup(null, station);
			robot.applyConfig(config);
			level.addFreshEntity(robot);
			robots.add(robot);
		}

		if (!running) {
			running = true;
			ServerTickEvents.END_SERVER_TICK.register(s -> {
				try {
					tick();
				} catch (Throwable t) {
					StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme im Erzsuche-Test", t);

					if (done != null) {
						finish(List.of(t.toString()));
					}
				}
			});
		}
	}

	private static void ore(BlockPos pos, Block block) {
		level.setBlockAndUpdate(pos, block.defaultBlockState());
		ores.add(pos);
	}

	private static void tick() {
		if (done == null) {
			return;
		}

		ticks++;

		if (ticks % 200 == 0) {
			for (RobotEntity robot : robots) {
				StorageDimensionMod.LOGGER.info("SMOKETEST Erz-Roboter {}: {} {} at {}", ticks, robot.describeMining(), robot.status().getString(),
						robot.blockPosition());
			}
		}

		String dim = Rooms.id(level);
		Container loot = (Container) level.getBlockEntity(station.east());
		List<String> errors = new ArrayList<>();

		if (!probeDone && level.isLoaded(PROBE) && level.isLoaded(PROBE_ORE)) {
			probeDone = true;
			String problem = probe();

			if (problem != null) {
				finish(List.of(problem));
				return;
			}
		}

		if (stage == 0 && probeDone && dug() && count(loot, Items.RAW_IRON) > 0 && count(loot, Items.DIAMOND) > 0 && count(loot, Items.COAL) > 0) {
			for (Item spoil : List.of(Items.COBBLESTONE, Items.STONE)) {
				if (count(loot, spoil) > 0) {
					errors.add("Kiste hat " + spoil + ", die Erzsuche soll nur Erze heimbringen");
				}
			}

			if (!level.getBlockState(gold).is(Blocks.GOLD_ORE)) {
				errors.add("Golderz neben der Lava wurde abgebaut");
			}

			if (!level.getBlockState(lava).is(Blocks.LAVA)) {
				errors.add("Lava ist nicht mehr an ihrem Platz: " + level.getBlockState(lava));
			}

			if (!level.getBlockState(station).is(RobotStationBlock.BLOCK) || level.getBlockState(station.below()).isAir()) {
				errors.add("Station oder die Säule darunter wurde abgebaut");
			}

			StorageDimensionMod.LOGGER.info("SMOKETEST Erzsuche fertig nach {} Ticks", ticks);

			if (!errors.isEmpty()) {
				finish(errors);
				return;
			}

			// ore search, then collecting again: the tools go back
			StationMining.toggle(level, dim, station);
			stage = 1;
		} else if (stage == 1 && robots.stream().allMatch(r -> r.getMiningLook() == RobotEntity.MINING_LOOK_NONE)) {
			Container tools = (Container) level.getBlockEntity(StorageData.get().station(dim, station).toolChest().orElse(station));
			int count = 0;

			for (int slot = 0; slot < tools.getContainerSize(); slot++) {
				count += tools.getItem(slot).isEmpty() ? 0 : 1;
			}

			if (count != 6) {
				errors.add("Nach der Erzsuche liegen " + count + " statt 6 Werkzeuge in der Werkzeugkiste");
			}

			StorageDimensionMod.LOGGER.info("SMOKETEST Erzsuche: Werkzeug zurück nach {} Ticks", ticks);
			finish(errors);
		} else if (ticks > TIMEOUT) {
			for (BlockPos pos : ores) {
				if (!level.getBlockState(pos).isAir()) {
					errors.add("Erz nicht abgebaut: " + pos + " " + level.getBlockState(pos));
				}
			}

			errors.add("Kiste: " + describe(loot));

			for (RobotEntity robot : robots) {
				errors.add("Roboter: " + robot.describeMining() + " " + robot.status().getString() + " " + robot.describe() + " at " + robot.blockPosition());
			}

			if (!probeDone) {
				errors.add("Chunks für die Suchprobe nicht geladen");
			}

			errors.add(0, "Erzsuche-Test nach " + ticks + " Ticks nicht fertig (Stufe " + stage + ")");
			finish(errors);
		}
	}

	/**
	 * The search of an area three chunks around a spot where only its own chunk and one at the east edge are loaded:
	 * the ore in that one must be found although unloaded chunks come before it. Null when it is.
	 */
	private static String probe() {
		level.setBlockAndUpdate(PROBE_ORE, Blocks.DIAMOND_ORE.defaultBlockState());
		String dim = "smoketest-probe";
		OreJob job = OreJob.get(dim, PROBE, PROBE.getX() - 48, PROBE.getZ() - 48, PROBE.getX() + 48, PROBE.getZ() + 48, level.getMinY(),
				level.getGameTime());

		for (int i = 0; i < 200 && !job.knows(PROBE_ORE); i++) {
			job.scan(level);
		}

		boolean found = job.knows(PROBE_ORE);
		OreJob.reset(dim, PROBE);
		level.setChunkForced(PROBE.getX() >> 4, PROBE.getZ() >> 4, false);
		level.setChunkForced(PROBE_ORE.getX() >> 4, PROBE_ORE.getZ() >> 4, false);
		StorageDimensionMod.LOGGER.info("SMOKETEST Erzsuche-Probe: Erz hinter ungeladenen Chunks {}", found ? "gefunden" : "NICHT gefunden");
		return found ? null : "Erzsuche findet kein Erz hinter ungeladenen Chunks (Suche bleibt am ersten ungeladenen Chunk hängen)";
	}

	private static boolean dug() {
		for (BlockPos pos : ores) {
			if (!level.getBlockState(pos).isAir()) {
				return false;
			}
		}

		return true;
	}

	private static void finish(List<String> errors) {
		Consumer<List<String>> callback = done;
		done = null;
		callback.accept(errors);
	}

	private static int count(Container container, Item item) {
		int count = 0;

		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (container.getItem(slot).is(item)) {
				count += container.getItem(slot).getCount();
			}
		}

		return count;
	}

	private static String describe(Container container) {
		List<String> items = new ArrayList<>();

		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (!container.getItem(slot).isEmpty()) {
				items.add(container.getItem(slot).toString());
			}
		}

		return String.join(", ", items);
	}
}
