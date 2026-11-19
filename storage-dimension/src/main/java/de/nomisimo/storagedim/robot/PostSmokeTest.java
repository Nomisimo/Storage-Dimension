package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;

/**
 * Smoke test of the parcel post (CI, -Dstoragedim.smoketest=true): high above the overworld a station in courier
 * mode with one robot, a mailbox "Start" with a chest of 12 stacks sending to "Lager*", a mailbox "Lager Ost" with an
 * empty chest, and a mailbox "Lager West" further away. The robot has to carry all 12 stacks (two trips of up to 9)
 * into the chest of the closer matching mailbox; the wildcard and the closest-first rule are checked on the way.
 */
public final class PostSmokeTest {
	private static final int TIMEOUT = 6000;
	private static final int Y = 280;
	private static final int STACKS = 12;

	private static ServerLevel level;
	private static BlockPos station;
	private static BlockPos source;
	private static BlockPos target;
	private static BlockPos far;
	private static RobotEntity robot;
	private static int ticks;
	private static Consumer<List<String>> done;
	private static boolean running;

	private PostSmokeTest() {
	}

	public static void start(MinecraftServer server, Consumer<List<String>> onDone) {
		done = onDone;
		ticks = 0;
		level = server.overworld();
		station = new BlockPos(200, Y, 8);
		String dim = Rooms.id(level);

		for (int cx = -2; cx <= 2; cx++) {
			for (int cz = -2; cz <= 2; cz++) {
				level.setChunkForced((station.getX() >> 4) + cx, (station.getZ() >> 4) + cz, true);
			}
		}

		for (int dx = -14; dx <= 14; dx++) {
			for (int dz = -6; dz <= 6; dz++) {
				level.setBlockAndUpdate(station.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}

		level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
		level.setBlockAndUpdate(station.east(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
		StorageData data = StorageData.get();

		source = station.offset(-6, 0, 3);
		target = station.offset(2, 0, -3);
		far = station.offset(12, 0, 5);
		mailbox(dim, source, "Start", "Lager*");
		mailbox(dim, target, "Lager Ost", "");
		mailbox(dim, far, "Lager West", "");
		Container sourceChest = chest(source.south());
		chest(target.north());
		chest(far.north());

		for (int i = 0; i < STACKS; i++) {
			sourceChest.setItem(i, new ItemStack(i % 3 == 0 ? Items.IRON_INGOT : Items.COBBLESTONE, 64));
		}

		sourceChest.setChanged();
		data.setStation(dim, station, data.station(dim, station).withRange(16).withName("Postbote").withStationName("Post"));

		// the button goes through mining and the ore search to the courier
		List<String> errors = new ArrayList<>();

		for (int i = 0; i < 3; i++) {
			StationMining.toggle(level, dim, station);
		}

		RobotConfig config = data.station(dim, station);

		if (!config.courier() || config.mining()) {
			errors.add("Kurier-Modus ließ sich nicht einschalten");
			done.accept(errors);
			return;
		}

		if (!Mailbox.matches("lager*", "Lager Ost") || Mailbox.matches("Lager?", "Lager Ost") || !Mailbox.matches("Lager ?st", "Lager Ost")) {
			errors.add("Adressen mit Platzhaltern passen falsch");
		}

		robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		robot.snapTo(station.getX() - 1.5, Y, station.getZ() + 1.5, 0, 0);
		robot.setup(null, station);
		robot.applyConfig(config);
		level.addFreshEntity(robot);

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
					StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme im Post-Test", t);

					if (done != null) {
						finish(List.of(t.toString()));
					}
				}
			});
		}
	}

	private static void mailbox(String dim, BlockPos pos, String address, String destination) {
		level.setBlockAndUpdate(pos, MailboxBlock.BLOCK.defaultBlockState());
		StorageData.get().setMailbox(dim, pos, new Mailbox(address, destination));
	}

	private static Container chest(BlockPos pos) {
		level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
		return (Container) level.getBlockEntity(pos);
	}

	private static void tick() {
		if (done == null) {
			return;
		}

		ticks++;
		Container sourceChest = (Container) level.getBlockEntity(source.south());
		Container targetChest = (Container) level.getBlockEntity(target.north());
		Container farChest = (Container) level.getBlockEntity(far.north());
		int arrived = count(targetChest, Items.COBBLESTONE) + count(targetChest, Items.IRON_INGOT);
		int left = count(sourceChest, Items.COBBLESTONE) + count(sourceChest, Items.IRON_INGOT);

		if (ticks % 200 == 0) {
			StorageDimensionMod.LOGGER.info("SMOKETEST Post: {} angekommen, {} noch in der Start-Kiste, Roboter: {}", arrived, left, robot.status().getString());
		}

		if (arrived == STACKS * 64 && robot.getCarried().isEmpty()) {
			List<String> errors = new ArrayList<>();
			int wrong = count(farChest, Items.COBBLESTONE) + count(farChest, Items.IRON_INGOT);

			if (wrong > 0) {
				errors.add("Post landete im weiteren Briefkasten: " + wrong);
			}

			if (count(targetChest, Items.IRON_INGOT) != STACKS / 3 * 64) {
				errors.add("Falsche Menge Eisen angekommen: " + count(targetChest, Items.IRON_INGOT));
			}

			StorageDimensionMod.LOGGER.info("SMOKETEST Post: alle {} Stapel nach {} Ticks angekommen", STACKS, ticks);
			robot.discard();
			finish(errors);
		} else if (ticks > TIMEOUT) {
			robot.discard();
			finish(List.of("Post-Test nach " + ticks + " Ticks nicht fertig: " + arrived + " angekommen, " + left + " noch in der Start-Kiste, Roboter: "
					+ robot.status().getString() + " " + robot.describe()));
		}
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
}
