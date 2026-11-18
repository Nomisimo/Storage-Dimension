package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.FilterChests;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import de.nomisimo.storagedim.storage.StorageNetwork;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;

/**
 * Smoke test of the strip mine (CI, after the mining test): high above the overworld a station on a block of stone 35
 * wide and 14 deep; the mine's bottom is set to the bottom of the stone, so the deepest mining level is 8 below the
 * station. The robots dig the staircase down and the grid of tunnels, and get the ores that show in the tunnels'
 * walls, floor and ceiling (iron, diamond, a vein of two coal ores, a far iron, a gold ore behind a pocket of lava),
 * but not an emerald ore deep in the rock between the tunnels: they cannot see through the rock. The lava next to a
 * tunnel and a pocket of water become cobblestone. Only the ores come home (no stone), torches go up in the tunnels.
 * Their only tools are six diamond pickaxes, taken with the torches from a filter chest west of the station (out of its ME
 * storage). Switched back to collecting, the pickaxes go back into the ME storage.
 */
public final class OreSmokeTest {
	private static final int TIMEOUT = 16000;
	/** The levels are dug from the top down; the ores are on the deepest one, so there are a few robots. */
	private static final int ROBOTS = 6;
	private static StorageNetwork network;
	private static final int Y = 290;

	private static ServerLevel level;
	private static BlockPos station;
	private static final List<RobotEntity> robots = new ArrayList<>();
	private static final List<BlockPos> ores = new ArrayList<>();
	private static BlockPos hidden;
	private static BlockPos lava;
	private static BlockPos water;
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

		System.setProperty("storagedim.stripBottom", Integer.toString(Y - 14));

		// a block of stone under the station, 14 deep, as wide as the chunks the robots dig
		int[] square = RobotEntity.chunkSquare(station, 16);

		for (int x = square[0]; x <= square[2]; x++) {
			for (int z = square[1]; z <= square[3]; z++) {
				for (int dy = -14; dy <= -1; dy++) {
					level.setBlockAndUpdate(new BlockPos(x, Y + dy, z), Blocks.STONE.defaultBlockState());
				}
			}
		}

		// the deepest level: feet at Y - 8, head at Y - 7; the main tunnel north and south at x 0, side ones every 4 blocks of z
		ore(station.offset(5, -7, 1), Blocks.IRON_ORE);
		ore(station.offset(-6, -9, 4), Blocks.DIAMOND_ORE);
		ore(station.offset(0, -6, -7), Blocks.COAL_ORE);
		ore(station.offset(0, -5, -7), Blocks.COAL_ORE);
		ore(station.offset(-13, -7, 8), Blocks.IRON_ORE);
		// in the wall of the tunnel at z -4, behind it lava right next to that tunnel
		ore(station.offset(5, -7, -5), Blocks.GOLD_ORE);
		lava = station.offset(4, -7, -5);
		level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
		// water beside the tunnel west at z 0
		water = station.offset(-4, -7, -1);
		level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
		// below the deepest level's floor: no robot can see it
		hidden = station.offset(8, -12, 8);
		level.setBlockAndUpdate(hidden, Blocks.EMERALD_ORE.defaultBlockState());

		level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
		level.setBlockAndUpdate(station.east(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.EAST));

		StorageData data = StorageData.get();
		// the player's filter chest for pickaxes behind the station, with two of them in the ME storage
		UUID player = UUID.randomUUID();
		BlockPos filterPos = station.west();
		level.setBlockAndUpdate(filterPos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.WEST));
		FilterChests.FilterChest filterChest = FilterChests.FilterChest.empty(player);
		filterChest.filters().set(0, new ItemStack(Items.DIAMOND_PICKAXE));
		filterChest.filters().set(1, new ItemStack(Items.TORCH));
		data.setFilterChest(dim, filterPos, filterChest);
		network = data.networkFor(player);
		for (int i = 0; i < ROBOTS; i++) {
			network.insert(new ItemStack(Items.DIAMOND_PICKAXE));
		}

		network.insert(new ItemStack(Items.TORCH, 64));
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

		if (!config.toolChest().get().equals(filterPos)) {
			errors.add("Die Filterkiste hinter der Station wird nicht als Werkzeugkiste benutzt: " + config.toolChest().get());
			done.accept(errors);
			return;
		}

		for (int i = 0; i < ROBOTS; i++) {
			RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			robot.snapTo(station.getX() - 2.5 + i % 2 * 5, Y, station.getZ() + 3.5 + i / 2, 0, 0);
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

		if (stage == 0 && ticks == 400 && robots.stream().anyMatch(r -> r.tool(0).isEmpty())) {
			finish(List.of("Roboter haben keine Spitzhacke aus der Filterkiste geholt: " + robots.get(0).describeMining()));
			return;
		}

		if (stage == 0 && dug() && count(loot, Items.RAW_IRON) > 0 && count(loot, Items.RAW_GOLD) > 0 && count(loot, Items.DIAMOND) > 0 && count(loot, Items.COAL) > 0) {
			for (Item spoil : List.of(Items.COBBLESTONE, Items.STONE)) {
				if (count(loot, spoil) > 0) {
					errors.add("Kiste hat " + spoil + ", die Erzsuche soll nur Erze heimbringen");
				}
			}

			if (!level.getBlockState(hidden).is(Blocks.EMERALD_ORE)) {
				errors.add("Smaragderz tief im Fels wurde abgebaut: die Roboter dürfen nicht durch den Fels sehen");
			}

			if (!level.getFluidState(lava).isEmpty()) {
				errors.add("Lava neben dem Gang wurde nicht zu Bruchstein: " + level.getBlockState(lava));
			}

			if (!level.getFluidState(water).isEmpty()) {
				errors.add("Wasser neben dem Gang wurde nicht zu Bruchstein: " + level.getBlockState(water));
			}

			int torchCount = 0;

			for (BlockPos pos : BlockPos.betweenClosed(station.offset(-17, -8, -17), station.offset(17, -8, 17))) {
				torchCount += level.getBlockState(pos).is(Blocks.TORCH) ? 1 : 0;
			}

			if (torchCount == 0) {
				errors.add("Keine Fackeln in den Gängen");
			}

			if (!level.getBlockState(station).is(RobotStationBlock.BLOCK) || level.getBlockState(station.below()).isAir()) {
				errors.add("Station oder die Säule darunter wurde abgebaut");
			}

			StorageDimensionMod.LOGGER.info("SMOKETEST Erzsuche fertig nach {} Ticks, {} Fackeln", ticks, torchCount);

			if (!errors.isEmpty()) {
				finish(errors);
				return;
			}

			// ore search, then collecting again: the tools go back
			StationMining.toggle(level, dim, station);
			stage = 1;
		} else if (stage == 1 && robots.stream().allMatch(r -> r.getMiningLook() == RobotEntity.MINING_LOOK_NONE)) {
			int pickaxes = 0;

			for (StorageNetwork.StoredItem entry : network.entries()) {
				pickaxes += entry.prototype.is(Items.DIAMOND_PICKAXE) ? (int) entry.count : 0;
			}

			if (pickaxes != ROBOTS) {
				errors.add("Nach der Erzsuche sind " + pickaxes + " statt " + ROBOTS + " Spitzhacken im ME-Speicher der Filterkiste");
			}

			StorageDimensionMod.LOGGER.info("SMOKETEST Erzsuche: Werkzeug zurück nach {} Ticks", ticks);
			finish(errors);
		} else if (ticks > TIMEOUT) {
			for (BlockPos pos : ores) {
				if (!gone(pos)) {
					errors.add("Erz nicht abgebaut: " + pos + " " + level.getBlockState(pos));
				}
			}

			errors.add("Kiste: " + describe(loot));

			for (RobotEntity robot : robots) {
				errors.add("Roboter: " + robot.describeMining() + " " + robot.status().getString() + " " + robot.describe() + " at " + robot.blockPosition());
			}

			errors.add(0, "Erzsuche-Test nach " + ticks + " Ticks nicht fertig (Stufe " + stage + ")");
			finish(errors);
		}
	}

	private static boolean dug() {
		for (BlockPos pos : ores) {
			if (!gone(pos)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * The ore there was dug: air, or cobblestone when it lay in a tunnel floor and the hole it left was filled again
	 * so the robots can walk over it.
	 */
	private static boolean gone(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.isAir() || state.is(Blocks.COBBLESTONE);
	}

	private static void finish(List<String> errors) {
		System.clearProperty("storagedim.stripBottom");
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
