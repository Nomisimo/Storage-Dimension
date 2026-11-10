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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Smoke test of the mining mode (CI, -Dstoragedim.smoketest=true): high above the overworld a station with a chest,
 * two robots and a few blocks around it. Mining is switched on (the tool chest has to appear), the robots fetch their
 * tools, dig everything except the staircase, a worn-out pickaxe breaks and is replaced from the tool chest, all
 * drops end up in the chest; switched off, the tools go back into the tool chest.
 */
public final class MiningSmokeTest {
	private static final int TIMEOUT = 9000;
	private static final int Y = 280;

	private static ServerLevel level;
	private static BlockPos station;
	private static final List<RobotEntity> robots = new ArrayList<>();
	private static final List<BlockPos> toDig = new ArrayList<>();
	private static final List<BlockPos> stairs = new ArrayList<>();
	private static int ticks;
	private static int toolsLeft;
	private static int stage;
	private static Consumer<List<String>> done;
	private static boolean running;

	private MiningSmokeTest() {
	}

	/** Builds the scene and calls {@code done} with the errors once the robots are through (or the time is up). */
	public static void start(MinecraftServer server, Consumer<List<String>> onDone) {
		done = onDone;
		level = server.overworld();
		station = new BlockPos(8, Y, 8);
		String dim = Rooms.id(level);

		for (int cx = -2; cx <= 2; cx++) {
			for (int cz = -2; cz <= 2; cz++) {
				level.setChunkForced((station.getX() >> 4) + cx, (station.getZ() >> 4) + cz, true);
			}
		}

		for (int dx = -9; dx <= 9; dx++) {
			for (int dz = -9; dz <= 9; dz++) {
				level.setBlockAndUpdate(station.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}

		level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
		level.setBlockAndUpdate(station.east(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.EAST));

		// a little hill to dig: stone on top, then dirt and logs, ores at the bottom
		Block[] bottom = {Blocks.IRON_ORE, Blocks.COAL_ORE, Blocks.STONE};

		for (int dx = 3; dx <= 5; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				put(station.offset(dx, 0, dz), bottom[Math.floorMod(dx + dz, 3)]);
				put(station.offset(dx, 1, dz), (dx + dz) % 2 == 0 ? Blocks.DIRT : Blocks.OAK_LOG);
				put(station.offset(dx, 2, dz), Blocks.STONE);
			}
		}

		// sand that falls when the block below it goes
		put(station.offset(-4, 0, 3), Blocks.COBBLESTONE);
		put(station.offset(-4, 1, 3), Blocks.SAND);
		put(station.offset(-4, 2, 3), Blocks.SAND);

		// the staircase: two rings of stone around the station and one block on top of it
		for (int dy = 1; dy <= 2; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos pos = station.offset(dx, dy, dz);
					level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
				}
			}
		}

		StorageData data = StorageData.get();
		data.setStation(dim, station, data.station(dim, station).withRange(16).withName("Kumpel").withStationName("Grube"));
		StationMining.Result result = StationMining.toggle(level, dim, station);
		RobotConfig config = data.station(dim, station);
		List<String> errors = new ArrayList<>();
		StorageDimensionMod.LOGGER.info("SMOKETEST Abbau: {}", result.message().getString());

		if (!result.ok() || !config.mining() || config.toolChest().isEmpty()) {
			errors.add("Abbau-Modus ließ sich nicht einschalten: " + result.message().getString());
			done.accept(errors);
			return;
		}

		MiningJob job = MiningJob.get(dim, station, station.getX() - 16, Y, station.getZ() - 16, station.getX() + 16, Y + 15, station.getZ() + 16, 0);

		for (int dy = 1; dy <= 2; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos pos = station.offset(dx, dy, dz);
					(job.isStair(pos) ? stairs : toDig).add(pos);
				}
			}
		}

		if (stairs.size() != 4) {
			// the pillar at two heights, one step at each
			errors.add("Treppe hat " + stairs.size() + " statt 4 Blöcke: " + stairs);
		}

		Container tools = (Container) level.getBlockEntity(config.toolChest().get());
		// both robots first get a pickaxe that breaks on the next block, then a fresh one
		for (int slot = 0; slot < 2; slot++) {
			ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
			worn.setDamageValue(worn.getMaxDamage() - 1);
			tools.setItem(slot, worn);
		}

		tools.setItem(2, new ItemStack(Items.IRON_PICKAXE));
		tools.setItem(3, new ItemStack(Items.IRON_PICKAXE));
		tools.setItem(4, new ItemStack(Items.IRON_AXE));
		tools.setItem(5, new ItemStack(Items.STONE_AXE));
		tools.setItem(6, new ItemStack(Items.IRON_SHOVEL));
		tools.setItem(7, new ItemStack(Items.WOODEN_SHOVEL));
		tools.setChanged();

		for (int i = 0; i < 2; i++) {
			RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			robot.snapTo(station.getX() - 2.5 + i * 5, Y, station.getZ() - 2.5, 0, 0);
			robot.setup(null, station);
			robot.applyConfig(config);
			level.addFreshEntity(robot);
			robots.add(robot);
		}

		if (!errors.isEmpty()) {
			done.accept(errors);
			return;
		}

		if (!running) {
			running = true;
			ServerTickEvents.END_SERVER_TICK.register(s -> {
				try {
					tick();
				} catch (Throwable t) {
					StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme im Abbau-Test", t);

					if (done != null) {
						finish(List.of(t.toString()));
					}
				}
			});
		}
	}

	private static void put(BlockPos pos, Block block) {
		level.setBlockAndUpdate(pos, block.defaultBlockState());
		toDig.add(pos);
	}

	private static void tick() {
		if (done == null) {
			return;
		}

		ticks++;

		if (ticks % 200 == 0) {
			for (RobotEntity robot : robots) {
				StorageDimensionMod.LOGGER.info("SMOKETEST Roboter {}: {} {}", ticks, robot.describeMining(), robot.status().getString());
			}
		}

		String dim = Rooms.id(level);
		Container tools = (Container) level.getBlockEntity(StorageData.get().station(dim, station).toolChest().orElse(station));
		List<String> errors = new ArrayList<>();

		if (stage == 0 && dug() && robots.stream().allMatch(RobotEntity::miningDone)) {
			Container loot = (Container) level.getBlockEntity(station.east());

			for (Item item : List.of(Items.COBBLESTONE, Items.DIRT, Items.OAK_LOG, Items.RAW_IRON, Items.COAL, Items.SAND)) {
				if (count(loot, item) == 0) {
					errors.add("Kiste an der Station hat kein " + item);
				}
			}

			for (BlockPos pos : stairs) {
				if (level.getBlockState(pos).isAir()) {
					errors.add("Treppenblock " + pos + " wurde abgebaut");
				}
			}

			if (!level.getBlockState(station).is(RobotStationBlock.BLOCK)) {
				errors.add("Station wurde abgebaut");
			}

			int pickaxes = countTag(tools, true);
			// four in the chest, two taken: fewer than two left means a broken one was replaced
			boolean replaced = robots.stream().allMatch(r -> !r.tool(0).isEmpty()) && pickaxes < 2;
			toolsLeft = 0;

			for (int slot = 0; slot < tools.getContainerSize(); slot++) {
				toolsLeft += tools.getItem(slot).isEmpty() ? 0 : 1;
			}
			StorageDimensionMod.LOGGER.info("SMOKETEST Abbau fertig nach {} Ticks, Werkzeugkiste noch {} Spitzhacken, Robot-Spitzhacken {} / {}",
					ticks, pickaxes, robots.get(0).tool(0), robots.get(1).tool(0));

			if (!replaced) {
				errors.add("Kaputte Spitzhacke wurde nicht aus der Werkzeugkiste ersetzt (noch " + pickaxes + " Spitzhacken in der Kiste)");
			}

			if (!errors.isEmpty()) {
				finish(errors);
				return;
			}

			// switched back to collecting (the button goes through the ore search): the robots bring their tools back
			StationMining.toggle(level, dim, station);

			if (!StorageData.get().station(dim, station).oreSearch()) {
				finish(List.of("Nach dem Abbauen kommt nicht die Erzsuche"));
				return;
			}

			StationMining.toggle(level, dim, station);
			stage = 1;
		} else if (stage == 1 && robots.stream().allMatch(r -> r.getMiningLook() == RobotEntity.MINING_LOOK_NONE)) {
			int count = 0;

			for (int slot = 0; slot < tools.getContainerSize(); slot++) {
				count += tools.getItem(slot).isEmpty() ? 0 : 1;
			}

			if (count != toolsLeft + 6) {
				errors.add("Nach dem Abschalten liegen " + count + " statt " + (toolsLeft + 6) + " Werkzeuge in der Werkzeugkiste");
			}

			StorageDimensionMod.LOGGER.info("SMOKETEST Abbau: Werkzeug zurück nach {} Ticks", ticks);
			finish(errors);
		} else if (ticks > TIMEOUT) {
			for (BlockPos pos : toDig) {
				BlockState state = level.getBlockState(pos);

				if (!state.isAir()) {
					errors.add("nicht abgebaut: " + pos + " " + state);
				}
			}

			for (RobotEntity robot : robots) {
				errors.add("Roboter: " + robot.describeMining() + " " + robot.status().getString() + " " + robot.describe());
			}

			errors.add(0, "Abbau-Test nach " + ticks + " Ticks nicht fertig (Stufe " + stage + ")");
			finish(errors);
		}
	}

	private static boolean dug() {
		for (BlockPos pos : toDig) {
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

	private static int countTag(Container container, boolean pickaxes) {
		int count = 0;

		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (container.getItem(slot).is(ItemTags.PICKAXES)) {
				count++;
			}
		}

		return count;
	}
}
