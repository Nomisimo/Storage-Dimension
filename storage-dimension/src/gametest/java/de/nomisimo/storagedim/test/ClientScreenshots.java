package de.nomisimo.storagedim.test;

import de.nomisimo.storagedim.robot.AntennaBlock;
import de.nomisimo.storagedim.robot.AntennaItem;
import de.nomisimo.storagedim.robot.RobotBarrierBlock;
import de.nomisimo.storagedim.robot.RobotConfig;
import de.nomisimo.storagedim.robot.RobotStationBlock;
import de.nomisimo.storagedim.robot.RobotEntity;
import de.nomisimo.storagedim.robot.Robots;
import de.nomisimo.storagedim.robot.StationMenu;
import de.nomisimo.storagedim.storage.FilterChests;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import de.nomisimo.storagedim.storage.StorageItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Builds a small scene with every special chest and a robot, and takes screenshots of it. */
public class ClientScreenshots implements FabricClientGameTest {
	private static ItemStack upgrade = ItemStack.EMPTY;

	/** Opens a crafting table for the player with this grid (row by row, null = empty) and photographs it. */
	private static void recipe(ClientGameTestContext context, TestSingleplayerContext world, String name, ItemStack... grid) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			BlockPos table = player.blockPosition().offset(0, 0, 1);
			player.level().setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
			player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, p) -> new net.minecraft.world.inventory.CraftingMenu(id, inventory,
					net.minecraft.world.inventory.ContainerLevelAccess.create(player.level(), table)), net.minecraft.network.chat.Component.translatable("container.crafting")));

			for (int i = 0; i < grid.length; i++) {
				if (grid[i] != null) {
					player.containerMenu.getSlot(1 + i).set(grid[i].copy());
				}
			}

			player.containerMenu.broadcastChanges();
		});
		context.waitTicks(10);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);

			if ("me_upgrade".equals(name)) {
				upgrade = player.containerMenu.getSlot(0).getItem().copy();
			}
		});
		context.takeScreenshot("recipe-" + name);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);

			for (int i = 1; i <= 9; i++) {
				player.containerMenu.getSlot(i).set(ItemStack.EMPTY);
			}

			player.closeContainer();
		});
		context.waitTicks(5);
	}

	/** A hopper on top of an ME terminal feeds it: the diamonds end up in ME storage, the hopper runs empty. */
	private static void hopperIntoTerminal(ClientGameTestContext context, TestSingleplayerContext world) {
		long[] before = new long[1];
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = (ServerLevel) player.level();
			BlockPos terminal = player.blockPosition().offset(2, 0, 3);
			level.setBlockAndUpdate(terminal, Blocks.ENDER_CHEST.defaultBlockState());
			StorageData.get().setTerminal(Rooms.id(level), terminal, true, player.getUUID());
			level.setBlockAndUpdate(terminal.above(), Blocks.HOPPER.defaultBlockState()
					.setValue(net.minecraft.world.level.block.HopperBlock.FACING, Direction.DOWN));

			if (level.getBlockEntity(terminal.above()) instanceof net.minecraft.world.level.block.entity.HopperBlockEntity hopper) {
				// a full hopper: all of it goes in at once, not one item every 8 ticks
				for (int i = 0; i < hopper.getContainerSize(); i++) {
					hopper.setItem(i, new ItemStack(Items.DIAMOND, 64));
				}
			}

			before[0] = de.nomisimo.storagedim.api.StorageApi.count(player, new ItemStack(Items.DIAMOND));
		});
		context.waitTicks(25);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			BlockPos terminal = player.blockPosition().offset(2, 0, 3);
			long after = de.nomisimo.storagedim.api.StorageApi.count(player, new ItemStack(Items.DIAMOND));
			int left = player.level().getBlockEntity(terminal.above()) instanceof net.minecraft.world.level.block.entity.HopperBlockEntity hopper
					? java.util.stream.IntStream.range(0, hopper.getContainerSize()).map(i -> hopper.getItem(i).getCount()).sum() : -1;
			System.out.println("STORAGEDIM hopper into terminal: diamonds before=" + before[0] + " after=" + after + " left in hopper=" + left);
		});
	}

	private static ItemStack of(net.minecraft.world.item.Item item) {
		return new ItemStack(item);
	}

	private static void logRobots(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				for (RobotEntity robot : level.getEntities(Robots.ROBOT, entity -> true)) {
					System.out.println("STORAGEDIM ROBOT " + robot.describe() + (robot.isPassenger() ? " riding" : ""));
				}
			}
		});
	}

	/**
	 * A robot station in front of the camera, set up through its screen; two robots set up there and placed with the item,
	 * one far away. They should walk to the station, split the dropped items between them, then sit at the station.
	 * A third robot belongs to a station without any chest in range and should sit sadly. Then: the robot list in the
	 * station screen, the recall button (a walled-in robot teleports), rain (umbrellas), diving and the Jade tooltip.
	 */
	/** Where the player stood when the station scene started; the station is two blocks north of it. */
	private static BlockPos stationBase;

	private static void stationTest(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			BlockPos base = player.blockPosition();
			// robots bump into the player now and then; the scene keeps to where it started
			stationBase = base;
			BlockPos station = base.offset(0, 0, -2);
			level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
			StationMenu.open(player, station);
			StationMenu menu = (StationMenu) player.containerMenu;
			menu.action(StationMenu.ACTION_STATION_NAME, 0, "Nordlager");
			menu.action(StationMenu.ACTION_ROBOT_NAME, 0, "Quak");
			menu.action(StationMenu.ACTION_RANGE, -1, "");  // one step smaller: 8 chunks
			menu.action(StationMenu.ACTION_COLOR, 15, "");  // red
			menu.setCarried(new ItemStack(Robots.ROBOT_ITEM, 3));
			menu.clicked(StationMenu.SLOT_INPUT, 0, ContainerInput.PICKUP, player);  // three robots in

			// a plain robot cannot be placed
			ItemStack plain = new ItemStack(Robots.ROBOT_ITEM);
			player.setItemInHand(InteractionHand.OFF_HAND, plain);
			System.out.println("STORAGEDIM PLACE plain=" + plain.useOn(new UseOnContext(player, InteractionHand.OFF_HAND,
					new BlockHitResult(Vec3.atCenterOf(base.offset(2, -1, 2)), Direction.UP, base.offset(2, -1, 2), false))) + " left=" + plain.getCount());
			player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
		});
		context.waitTicks(10);
		context.takeScreenshot("station-menu");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			BlockPos base = stationBase;
			ItemStack configured = player.containerMenu.getSlot(StationMenu.SLOT_OUTPUT).getItem().copy();
			player.containerMenu.getSlot(StationMenu.SLOT_OUTPUT).set(ItemStack.EMPTY);
			player.closeContainer();
			RobotConfig config = RobotConfig.read(configured, level.registryAccess());
			System.out.println("STORAGEDIM STATION item=" + configured + " config=" + config + " lore="
					+ configured.get(net.minecraft.core.component.DataComponents.LORE).lines().stream().map(Component::getString).toList());

			// placed like a player does: one next to the scene, one 30 blocks away (walks to the station first),
			// and one walled in with glass far away, for the recall
			player.setItemInHand(InteractionHand.OFF_HAND, configured);

			for (BlockPos ground : List.of(base.offset(3, -1, 2), base.offset(30, -1, 2), base.offset(20, -1, -10),
					base.offset(24, -1, -10), base.offset(28, -1, -10))) {
				System.out.println("STORAGEDIM PLACE configured=" + configured.useOn(new UseOnContext(player, InteractionHand.OFF_HAND,
						new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false))));
			}

			player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);

			// changing the station afterwards only affects robots set up from then on: a black "Rabe" next to the red Quaks
			BlockPos stationPos = base.offset(0, 0, -2);
			StationMenu.open(player, stationPos);
			StationMenu other = (StationMenu) player.containerMenu;
			other.action(StationMenu.ACTION_COLOR, 1, "");
			other.action(StationMenu.ACTION_ROBOT_NAME, 0, "Rabe");
			other.setCarried(new ItemStack(Robots.ROBOT_ITEM));
			other.clicked(StationMenu.SLOT_INPUT, 0, ContainerInput.PICKUP, player);
			ItemStack blue = other.getSlot(StationMenu.SLOT_OUTPUT).getItem().copy();
			other.getSlot(StationMenu.SLOT_OUTPUT).set(ItemStack.EMPTY);
			player.closeContainer();
			player.setItemInHand(InteractionHand.OFF_HAND, blue);
			System.out.println("STORAGEDIM PLACE blue=" + blue.useOn(new UseOnContext(player, InteractionHand.OFF_HAND,
					new BlockHitResult(Vec3.atCenterOf(base.offset(-3, -1, 2)), Direction.UP, base.offset(-3, -1, 2), false))));
			player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
			// three walled-in robots: all teleport back on the recall and must land on different spots
			for (int cx : new int[] {20, 24, 28}) {
				BlockPos cage = base.offset(cx, 0, -10);

				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						for (int dy = 0; dy <= 2; dy++) {
							if (dx != 0 || dz != 0 || dy == 2) {
								level.setBlockAndUpdate(cage.offset(dx, dy, dz), Blocks.GLASS.defaultBlockState());
							}
						}
					}
				}
			}

			// a lonely station far away with no chest in its range
			BlockPos lonely = base.offset(40, 0, 0);
			level.setBlockAndUpdate(lonely.below(), Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(lonely, RobotStationBlock.BLOCK.defaultBlockState());
			String dim = Rooms.id(level);
			StorageData.get().setStation(dim, lonely, StorageData.get().station(dim, lonely).withRange(16).withName("Pieps").withStationName("Einsam"));
			RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			robot.snapTo(lonely.getX() + 2.5, lonely.getY(), lonely.getZ() + 0.5, 0, 0);
			robot.setup(player.getUUID(), lonely);
			robot.applyConfig(StorageData.get().station(dim, lonely));
			level.addFreshEntity(robot);

			for (int i = 0; i < 4; i++) {
				drop(level, base.getX() - 3.5 + i * 2.5, base.getY() + 0.2, base.getZ() + 4.5, new ItemStack(Items.OAK_PLANKS, 8));
			}
		});

		for (int t = 0; t < 8; t++) {
			context.waitTicks(60);
			logRobots(world);
		}

		context.takeScreenshot("station-idle");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			BlockPos base = stationBase;
			System.out.println("STORAGEDIM PLANKS left " + player.level().getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(base).inflate(20),
					item -> item.getItem().is(Items.OAK_PLANKS)).size());
			System.out.println("STORAGEDIM STATION robots=" + StorageData.get().stationRobots(Rooms.id(player.level()), base.offset(0, 0, -2)));
			// the station screen with its robot list
			StationMenu.open(player, base.offset(0, 0, -2));
		});
		context.waitTicks(30);
		context.takeScreenshot("station-list");

		// recall: everybody sets off at once ("auf dem Weg"), the walled-in robot has no way and teleports
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			BlockPos base = stationBase;
			drop(player.level(), base.getX() - 6.5, base.getY() + 0.2, base.getZ() + 6.5, new ItemStack(Items.OAK_PLANKS, 8));
			((StationMenu) player.containerMenu).action(StationMenu.ACTION_RECALL, 0, "");
		});

		context.waitTicks(15);
		context.takeScreenshot("station-recall-walking");
		logRobots(world);

		for (int t = 0; t < 6; t++) {
			context.waitTicks(60);
			logRobots(world);
		}

		context.takeScreenshot("station-recall");
		// after the recall they sit at the station and watch the player who called them
		Vec3[] standing = new Vec3[1];
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			player.closeContainer();
			standing[0] = player.position();
			BlockPos station = stationBase.offset(0, 0, -2);
			lookAt(player, Vec3.atBottomCenterOf(station), 5);
		});
		context.waitTicks(40);
		context.takeScreenshot("station-recall-watching");
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
			List<RobotEntity> robots = level.getEntitiesOfClass(RobotEntity.class, new net.minecraft.world.phys.AABB(stationBase).inflate(8));
			java.util.Set<BlockPos> spots = new java.util.HashSet<>();
			robots.forEach(robot -> spots.add(robot.blockPosition()));
			System.out.println("STORAGEDIM RECALL SPREAD robots=" + robots.size() + " spots=" + spots.size() + " "
					+ robots.stream().map(robot -> robot.getName().getString() + "@" + robot.blockPosition().toShortString()).toList());
		});
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			// back to where the scene expects the player
			player.teleportTo(player.level(), standing[0].x, standing[0].y, standing[0].z, java.util.Set.of(), 0, 0, true);
		});

		// Jade tooltip of a robot at the station, then rain: umbrellas out in the open
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			RobotEntity robot = player.level().getEntities(Robots.ROBOT, r -> "Quak1".equals(r.getCustomName() == null ? null : r.getCustomName().getString()))
					.stream().findFirst().orElse(null);

			if (robot != null) {
				lookAt(player, robot.position().add(0, 0.3, 0), 2.2);
			}
		});
		context.waitTicks(10);
		context.takeScreenshot("robot-jade");
		world.getServer().runOnServer(server -> {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather rain");
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			RobotEntity robot = player.level().getEntities(Robots.ROBOT, r -> "Quak2".equals(r.getCustomName() == null ? null : r.getCustomName().getString()))
					.stream().findFirst().orElse(null);

			if (robot != null) {
				lookAt(player, robot.position().add(0, 0.6, 0), 2.6);
			}
		});
		context.waitTicks(40);
		context.takeScreenshot("robot-umbrella");
		world.getServer().runOnServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear"));

		// diving: a glass tank on the ground with a robot inside, seen through the glass
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			BlockPos station = stationBase.offset(0, 0, -2);
			BlockPos column = station.offset(-8, 0, 6);
			BlockPos pool = new BlockPos(column.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ()), column.getZ());

			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					level.setBlockAndUpdate(pool.offset(dx, -1, dz), Blocks.GLASS.defaultBlockState());

					for (int dy = 0; dy <= 2; dy++) {
						level.setBlockAndUpdate(pool.offset(dx, dy, dz), wall ? Blocks.GLASS.defaultBlockState()
								: dy < 2 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
					}
				}
			}

			RobotEntity robot = player.level().getEntities(Robots.ROBOT, r -> r.station() != null).stream().findFirst().orElse(null);

			if (robot != null) {
				robot.teleportTo(pool.getX() + 0.5, pool.getY(), pool.getZ() + 1.5);
				// facing the camera, so the helmet can be seen
				robot.setYRot(0);
				robot.setYHeadRot(0);
			}

			drop(level, pool.getX() - 0.5, pool.getY() + 0.5, pool.getZ() - 0.5, new ItemStack(Items.KELP, 3));
			// in front of the tank's south wall, looking north through the glass
			player.teleportTo(level, pool.getX() + 0.5, pool.getY(), pool.getZ() + 3.1, java.util.Set.of(), 180, 18, true);
		});

		for (int t = 0; t < 4; t++) {
			context.waitTicks(10);
			context.takeScreenshot("robot-diving-" + t);
		}

		logRobots(world);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			// close up on the robot without a chest: its name between two error icons
			RobotEntity robot = player.level().getEntities(Robots.ROBOT, r -> "Pieps".equals(r.getCustomName() == null ? null : r.getCustomName().getString()))
					.stream().findFirst().orElse(null);

			if (robot != null) {
				// aimed a little beside it, so the Jade tooltip does not cover the name tag
				lookAt(player, robot.position().add(1.2, 0.8, -0.6), 3.5);
			}
		});
		context.waitTicks(40);
		context.takeScreenshot("robot-sad");
		logRobots(world);
	}

	private static void logBarrel(TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
			var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(stationBase.offset(0, 0, -3));
			StringBuilder text = new StringBuilder();

			for (int i = 0; i < barrel.getContainerSize(); i++) {
				if (!barrel.getItem(i).isEmpty()) {
					text.append(barrel.getItem(i)).append(' ');
				}
			}

			System.out.println("STORAGEDIM BARREL " + text);
		});
	}

	/**
	 * The lonely station gets a full chest and an item lies next to it: Pieps sits sadly ("Kisten voll"). Once a slot is
	 * free again it fetches the item and puts it in.
	 */
	private static void fullChestTest(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos lonely = stationBase.offset(40, 0, 0);
		BlockPos chest = lonely.north();
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			level.setBlockAndUpdate(chest.below(), Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
			var container = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest);

			for (int i = 0; i < container.getContainerSize(); i++) {
				container.setItem(i, new ItemStack(Items.STONE, 64));
			}

			drop(level, lonely.getX() + 3.5, lonely.getY() + 0.2, lonely.getZ() + 2.5, new ItemStack(Items.DIRT, 5));
		});
		context.waitTicks(80);
		logRobots(world);
		context.takeScreenshot("robot-chests-full");
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
			((net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest)).setItem(13, ItemStack.EMPTY);
		});
		context.waitTicks(160);
		logRobots(world);
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
			System.out.println("STORAGEDIM FULL slot13=" + ((net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest)).getItem(13));
		});

		// a little pond by the station: Pieps fetches the dirt floating in it, then climbs out and waits on dry ground
		BlockPos pond = lonely.offset(2, -1, 2);
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.getPlayerList().getPlayers().get(0).level();

			for (int dx = 0; dx <= 2; dx++) {
				for (int dz = 0; dz <= 2; dz++) {
					level.setBlockAndUpdate(pond.offset(dx, 0, dz), Blocks.WATER.defaultBlockState());
					level.setBlockAndUpdate(pond.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
				}
			}

			RobotEntity pieps = level.getEntities(Robots.ROBOT, r -> r.getCustomName() != null && "Pieps".equals(r.getCustomName().getString())).stream().findFirst().orElse(null);

			if (pieps != null) {
				pieps.teleportTo(pond.getX() + 0.5, pond.getY(), pond.getZ() + 0.5);
			}

			drop(level, pond.getX() + 2.5, pond.getY() + 0.5, pond.getZ() + 2.5, new ItemStack(Items.DIRT, 3));
		});

		for (int t = 0; t < 4; t++) {
			context.waitTicks(10);
			logRobots(world);
		}

		context.takeScreenshot("robot-pond");
		context.waitTicks(200);
		logRobots(world);
		context.takeScreenshot("robot-pond-after");
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
			System.out.println("STORAGEDIM POND slot13=" + ((net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest)).getItem(13)
					+ " left=" + level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pond).inflate(4)).size());
		});

		// robots standing on a fence post and on a wall: feet right on the visible top, not half a block above it
		BlockPos posts = lonely.offset(-6, 0, 3);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			level.setBlockAndUpdate(posts, Blocks.OAK_FENCE.defaultBlockState());
			level.setBlockAndUpdate(posts.east(2), Blocks.COBBLESTONE_WALL.defaultBlockState());

			for (BlockPos post : List.of(posts, posts.east(2))) {
				RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
				robot.snapTo(post.getX() + 0.5, post.getY() + 1.5, post.getZ() + 0.5, 0, 0);
				robot.setup(player.getUUID(), post.above());
				level.addFreshEntity(robot);
			}

			// from the side, a little above the posts
			player.teleportTo(level, posts.getX() + 1.5, posts.getY() + 0.6, posts.getZ() + 4.5, java.util.Set.of(), 180, 12, true);
		});
		context.waitTicks(20);
		context.takeScreenshot("robot-on-posts");

		// an egg at the bottom of a deep glass tank (six blocks of water, a stone ramp up to its rim): Pieps climbs up,
		// dives down, brings it up and puts it into the barrel on top of its station
		BlockPos pit = lonely.offset(7, 0, -3);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			level.setBlockAndUpdate(lonely.above(), Blocks.BARREL.defaultBlockState());

			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					level.setBlockAndUpdate(pit.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());

					for (int dy = 0; dy <= 5; dy++) {
						level.setBlockAndUpdate(pit.offset(dx, dy, dz), wall ? Blocks.GLASS.defaultBlockState() : Blocks.WATER.defaultBlockState());
					}
				}
			}

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 3; dy++) {
					level.setBlockAndUpdate(pit.offset(dx, dy, -3), Blocks.STONE.defaultBlockState());
				}

				for (int dy = 0; dy <= 1; dy++) {
					level.setBlockAndUpdate(pit.offset(dx, dy, -4), Blocks.STONE.defaultBlockState());
				}
			}

			drop(level, pit.getX() + 0.5, pit.getY() + 0.2, pit.getZ() + 0.5, new ItemStack(Items.EGG));
			// looking at the tank's glass side from the east
			player.teleportTo(level, pit.getX() + 7.5, pit.getY() + 3, pit.getZ() + 0.5, java.util.Set.of(), 90, 10, true);
		});

		for (int t = 0; t < 6; t++) {
			context.waitTicks(20);
			logRobots(world);

			if (t == 2) {
				context.takeScreenshot("robot-deep-dive");
			}
		}

		context.waitTicks(200);
		logRobots(world);
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
			var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(lonely.above());
			System.out.println("STORAGEDIM DEEP barrel=" + barrel.getItem(0) + " eggsLeft="
					+ level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pit).inflate(3, 8, 3)).size());
			// kelp floating on top of the deep water, far above the bottom: it swims up to it too
			drop(level, pit.getX() + 1.5, pit.getY() + 5.6, pit.getZ() + 1.5, new ItemStack(Items.KELP, 3));
		});

		for (int t = 0; t < 4; t++) {
			context.waitTicks(20);
			logRobots(world);

			if (t == 1) {
				context.takeScreenshot("robot-kelp-surface");
			}
		}

		context.waitTicks(200);
		logRobots(world);
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
			var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(lonely.above());
			System.out.println("STORAGEDIM KELP barrel=" + barrel.getItem(0) + "," + barrel.getItem(1) + " left="
					+ level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pit).inflate(3, 8, 3)).size());
		});

		// an egg tucked into the corner of a fenced pen, between the arms of the corner post: Pieps hops in and gets it
		BlockPos pen = lonely.offset(-9, 0, -9);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();

			for (int dx = 0; dx <= 4; dx++) {
				for (int dz = 0; dz <= 4; dz++) {
					if (dx == 0 || dz == 0 || dx == 4 || dz == 4) {
						level.setBlockAndUpdate(pen.offset(dx, 0, dz), Blocks.OAK_FENCE.defaultBlockState());
					}
				}
			}

			// fences connect once all of them stand
			for (int dx = 0; dx <= 4; dx++) {
				for (int dz = 0; dz <= 4; dz++) {
					BlockPos fence = pen.offset(dx, 0, dz);
					level.updateNeighborsAt(fence, Blocks.OAK_FENCE);
				}
			}

			ItemEntity egg = new ItemEntity(level, pen.getX() + 0.85, pen.getY() + 0.1, pen.getZ() + 0.85, new ItemStack(Items.EGG));
			egg.setDeltaMovement(Vec3.ZERO);
			egg.setNoPickUpDelay();
			level.addFreshEntity(egg);
			player.teleportTo(level, pen.getX() + 2.0, pen.getY() + 3.5, pen.getZ() + 7.5, java.util.Set.of(), 170, 32, true);
		});

		for (int t = 0; t < 6; t++) {
			context.waitTicks(20);
			logRobots(world);

			if (t == 3 || t == 4) {
				context.takeScreenshot("robot-fence-corner-" + t);
			}
		}

		context.waitTicks(240);
		logRobots(world);
		world.getServer().runOnServer(server -> {
			ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
			var barrel = (net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(lonely.above());
			System.out.println("STORAGEDIM CORNER barrel=" + barrel.getItem(0) + "," + barrel.getItem(1) + " left="
					+ level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(pen).inflate(5, 2, 5)).size());
		});
	}

	/** Puts the player {@code distance} blocks south-east of the point and turns them to look at it. */
	/** Mining mode: robots with helmets dig a small hill, by day and at night (the glowing helmet lamps). */
	private static void miningTest(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos station = stationBase.offset(0, 0, -120);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			player.teleportTo(level, station.getX() + 0.5, station.getY(), station.getZ() + 9.5, java.util.Set.of(), 180, 20, true);
			level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
			level.setBlockAndUpdate(station.east(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));

			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = 3; dz <= 6; dz++) {
					for (int dy = 0; dy < 3; dy++) {
						level.setBlockAndUpdate(station.offset(dx, dy, -dz), dy == 2 ? Blocks.GRASS_BLOCK.defaultBlockState()
								: dy == 1 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
					}
				}
			}

			String dim = Rooms.id(level);
			StorageData.get().setStation(dim, station, StorageData.get().station(dim, station).withRange(16).withName("Kumpel").withColor(0xF9801D));
			StationMenu.open(player, station);
			((StationMenu) player.containerMenu).action(StationMenu.ACTION_WORK_MODE, 0, "");
		});
		context.waitTicks(15);
		context.takeScreenshot("station-mining-menu");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			player.closeContainer();
			ServerLevel level = player.level();
			String dim = Rooms.id(level);
			RobotConfig config = StorageData.get().station(dim, station);
			System.out.println("STORAGEDIM MINING CHECK toolChest=" + config.toolChest() + " mining=" + config.mining());
			var tools = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(config.toolChest().orElse(station));

			for (int i = 0; i < 3; i++) {
				tools.setItem(i * 3, new ItemStack(Items.IRON_PICKAXE));
				tools.setItem(i * 3 + 1, new ItemStack(Items.IRON_AXE));
				tools.setItem(i * 3 + 2, new ItemStack(Items.IRON_SHOVEL));
				RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
				robot.snapTo(station.getX() - 1.5 + i * 1.5, station.getY(), station.getZ() + 2.5, 180, 0);
				robot.setup(player.getUUID(), station);
				robot.applyConfig(config);
				level.addFreshEntity(robot);
			}
		});
		context.waitTicks(160);
		context.takeScreenshot("robot-mining");
		world.getServer().runOnServer(server -> {
			for (RobotEntity robot : server.getPlayerList().getPlayers().get(0).level().getEntities(Robots.ROBOT, r -> r.isMining())) {
				System.out.println("STORAGEDIM MINING " + robot.describeMining() + " " + robot.status().getString());
			}

			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set midnight");
		});
		context.waitTicks(40);
		context.takeScreenshot("robot-mining-night");
		world.getServer().runOnServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set day"));
		context.waitTicks(5);
	}

	private static void lookAt(ServerPlayer player, Vec3 target, double distance) {
		Vec3 eye = target.add(distance * 0.55, distance * 0.6, distance * 0.6);
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		player.teleportTo(player.level(), eye.x, eye.y - player.getEyeHeight(), eye.z, java.util.Set.of(), yaw, pitch, true);
	}

	private static void drop(ServerLevel level, double x, double y, double z, ItemStack stack) {
		ItemEntity item = new ItemEntity(level, x, y, z, stack, 0, 0, 0);
		item.setNoPickUpDelay();
		level.addFreshEntity(item);
	}

	/** Large amounts in the terminal, drawn in small digits by the client. */
	private static void terminalCounts(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			network.insert(new ItemStack(Items.STONE, 1_234_567));
			network.insert(new ItemStack(Items.DIRT, 45_000));
			network.insert(new ItemStack(Items.DIAMOND, 150));
			network.insert(new ItemStack(Items.IRON_SWORD));
			network.insert(new ItemStack(Items.IRON_SWORD));
			de.nomisimo.storagedim.storage.StorageMenu.open(player, network);
		});
		context.waitTicks(15);
		context.takeScreenshot("terminal-counts");
		world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0).closeContainer());
		context.waitTicks(5);
	}

	/**
	 * A chest standing on slabs on a pool, like in Momo's test world: the robot starts below the slabs, fetches an egg
	 * from the pool and must climb onto the slabs to put it in, not hand it up through the slab from below.
	 */
	private static void slabChestTest(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos pool = stationBase.offset(-60, 0, 0);
		BlockPos chest = pool.offset(3, 0, 3);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();

			for (int dx = 0; dx <= 6; dx++) {
				for (int dz = 0; dz <= 6; dz++) {
					level.setBlockAndUpdate(pool.offset(dx, -4, dz), Blocks.STONE.defaultBlockState());

					for (int dy = -3; dy <= -1; dy++) {
						level.setBlockAndUpdate(pool.offset(dx, dy, dz), Blocks.WATER.defaultBlockState());
					}

					if (dx >= 2 && dx <= 4 && dz >= 2 && dz <= 4) {
						level.setBlockAndUpdate(pool.offset(dx, -1, dz), Blocks.OAK_SLAB.defaultBlockState()
								.setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true));
					}
				}
			}

			level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
			BlockPos station = chest.east();
			level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
			String dim = Rooms.id(level);
			StorageData.get().setStation(dim, station, StorageData.get().station(dim, station).withRange(16).withName("Platsch").withStationName("Steg"));
			RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
			// it starts swimming right below the chest, under the slabs
			robot.snapTo(chest.getX() + 0.5, chest.getY() - 2.6, chest.getZ() + 0.5, 0, 0);
			robot.setup(player.getUUID(), station);
			robot.applyConfig(StorageData.get().station(dim, station));
			level.addFreshEntity(robot);
			ItemEntity egg = new ItemEntity(level, pool.getX() + 0.5, pool.getY() - 1.5, pool.getZ() + 5.5, new ItemStack(Items.EGG));
			egg.setDeltaMovement(Vec3.ZERO);
			egg.setNoPickUpDelay();
			level.addFreshEntity(egg);
			player.teleportTo(level, pool.getX() + 9.5, pool.getY() + 1.5, pool.getZ() + 3.5, java.util.Set.of(), 90, 25, true);
		});
		for (int t = 0; t < 15; t++) {
			context.waitTicks(20);
			int tick = t;
			world.getServer().runOnServer(server -> {
				ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
				var box = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(chest);
				RobotEntity platsch = level.getEntities(Robots.ROBOT, r -> r.getCustomName() != null && "Platsch".equals(r.getCustomName().getString()))
						.stream().findFirst().orElse(null);
				System.out.println("STORAGEDIM SLAB t=" + tick + " chest=" + box.getItem(0) + " eggs=" + level.getEntitiesOfClass(ItemEntity.class,
						new net.minecraft.world.phys.AABB(chest).inflate(4, 5, 4)).stream().map(e -> e.position().subtract(Vec3.atLowerCornerOf(chest)).toString()).toList()
						+ " robot=" + (platsch == null ? "-" : platsch.describe()));
			});

			if (t == 6) {
				context.takeScreenshot("robot-slab-chest");
			}
		}
	}

	/**
	 * The library: many kinds stored, the player enters the hall, looks down an aisle, uses the catalogue to get to the
	 * cobblestone chests, opens one (dirt must not go in, cobblestone must), and a kind stored while standing there gets
	 * a new chest.
	 */
	private static void islandTest(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			int n = 0;

			for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
				if (item != Items.AIR && n++ % 7 == 0 && n < 900) {
					network.insert(new ItemStack(item, 1 + n % 50));
				}
			}

			network.insert(new ItemStack(Items.COBBLESTONE, 4321));
			ItemStack worn = new ItemStack(Items.DIAMOND_PICKAXE);
			worn.setDamageValue(500);
			network.insert(worn);
			network.insert(new ItemStack(Items.DIAMOND_PICKAXE));
			player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
			de.nomisimo.storagedim.storage.StorageEvents.enterRoom(player, network);
			System.out.println("STORAGEDIM LIBRARY chests=" + network.library.size() + " aisles="
					+ network.aisles.stream().map(a -> a.category.title + "@" + a.x + "/" + a.length()).toList()
					+ " cobbleChests=" + network.library.stream().filter(c -> c.key().is(Items.COBBLESTONE)).count());
		});
		context.waitTicks(60);
		world.getClientLevel().waitForChunksDownload();
		context.waitTicks(20);
		context.takeScreenshot("library-hall");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			BlockPos centre = de.nomisimo.storagedim.storage.Library.centre(network.room);
			// under the skylight, looking up north at the chest moon
			player.teleportTo((ServerLevel) player.level(), centre.getX() + 0.5, centre.getY() + 1, centre.getZ() + 6.5, java.util.Set.of(), 180f, -62f, true);
			net.minecraft.world.entity.Entity moon = ((ServerLevel) player.level()).getEntity(de.nomisimo.storagedim.storage.Library.moonId(network));
			System.out.println("STORAGEDIM LIBRARY moon=" + (moon == null ? "missing" : moon.position()) + " skylight="
					+ player.level().getBlockState(centre.offset(0, 6, 4)).getBlock());
		});
		context.waitTicks(20);
		context.takeScreenshot("library-moon");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			de.nomisimo.storagedim.storage.Library.teleportToAisle(player, network, network.aisles.get(0));
		});
		context.waitTicks(30);
		context.takeScreenshot("library-aisle");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			de.nomisimo.storagedim.storage.CatalogMenu.open(player, network);
		});
		context.waitTicks(15);
		context.takeScreenshot("library-catalog");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			player.closeContainer();
			var network = StorageData.get().networkFor(player.getUUID());
			var cobble = network.library.stream().filter(c -> c.key().is(Items.COBBLESTONE)).findFirst().orElseThrow();
			de.nomisimo.storagedim.storage.Library.teleportToShelf(player, network, cobble);
		});
		context.waitTicks(30);
		context.takeScreenshot("library-shelf");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			var cobble = network.library.stream().filter(c -> c.key().is(Items.COBBLESTONE)).findFirst().orElseThrow();
			BlockPos chest = de.nomisimo.storagedim.storage.Library.centre(network.room).offset(cobble.dx(), cobble.dy(), cobble.dz());
			player.getInventory().setItem(9, new ItemStack(Items.DIRT, 32));
			player.getInventory().setItem(10, new ItemStack(Items.COBBLESTONE, 16));
			de.nomisimo.storagedim.storage.Library.use(player, (ServerLevel) player.level(), chest);
			net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;
			// 27 chest slots, then the inventory from slot 9 on
			menu.clicked(27, 0, ContainerInput.QUICK_MOVE, player);
			menu.clicked(28, 0, ContainerInput.QUICK_MOVE, player);
			long afterIn = network.find(new ItemStack(Items.COBBLESTONE)).count;
			// take the first stack out
			menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
			System.out.println("STORAGEDIM LIBRARY chest " + menu.getClass().getSimpleName() + " dirtLeft=" + player.getInventory().getItem(9).getCount()
					+ " cobbleLeft=" + player.getInventory().getItem(10).getCount() + " storedAfterIn=" + afterIn
					+ " storedAfterOut=" + network.find(new ItemStack(Items.COBBLESTONE)).count + " slot0=" + menu.getSlot(0).getItem().getCount());
		});
		context.waitTicks(15);
		context.takeScreenshot("library-chest");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			player.closeContainer();
			var network = StorageData.get().networkFor(player.getUUID());
			int before = network.library.size();
			network.insert(new ItemStack(Items.DRAGON_EGG));
			System.out.println("STORAGEDIM LIBRARY grow before=" + before);
		});
		context.waitTicks(50);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			System.out.println("STORAGEDIM LIBRARY grow after=" + network.library.size() + " egg="
					+ network.library.stream().anyMatch(c -> c.key().is(Items.DRAGON_EGG)));
		});
		context.takeScreenshot("library-chest-closed");
	}

	/**
	 * The dragon egg altar (the egg went into ME storage at the end of the library test) and the trading hall with
	 * villagers from buckets, their stalls and the signs with their best trade.
	 */
	private static void extrasTest(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());

			for (String profession : java.util.List.of("librarian", "armorer", "farmer", "cleric", "toolsmith")) {
				network.insert(villagerBucket("minecraft:" + profession));
			}

			BlockPos centre = de.nomisimo.storagedim.storage.Library.centre(network.room);
			player.teleportTo((ServerLevel) player.level(), centre.getX() + 0.5, centre.getY() + 1, centre.getZ() + 6.5, java.util.Set.of(), 180, 12, true);
		});
		context.waitTicks(60);
		world.getClientLevel().waitForChunksDownload();
		context.waitTicks(10);
		context.takeScreenshot("library-altar");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			BlockPos centre = de.nomisimo.storagedim.storage.Library.centre(network.room);
			ServerLevel level = (ServerLevel) player.level();
			System.out.println("STORAGEDIM EXTRAS altar=" + level.getBlockState(centre.offset(0, 1, 2)).is(net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN)
					+ " rows=" + network.tradeHallRows + " traders=" + level.getEntities((net.minecraft.world.entity.Entity) null,
							new net.minecraft.world.phys.AABB(centre).inflate(60), e -> e.entityTags().contains(de.nomisimo.storagedim.storage.LibraryExtras.TRADER_TAG)).size());

			for (int stall = 0; stall < 6; stall++) {
				BlockPos sign = centre.offset(1 + (stall % 2 == 0 ? -1 : 1), 1, 11 + 2 * (stall / 2));

				if (level.getBlockEntity(sign) instanceof net.minecraft.world.level.block.entity.SignBlockEntity entity) {
					StringBuilder text = new StringBuilder();

					for (int i = 0; i < 4; i++) {
						text.append(entity.getFrontText().getMessage(i, false).getString()).append(" | ");
					}

					System.out.println("STORAGEDIM EXTRAS sign " + stall + ": " + text);
				}
			}

			// in the door of the trading hall, looking down the corridor
			player.teleportTo(level, centre.getX() + 1.5, centre.getY() + 1, centre.getZ() + 9.5, java.util.Set.of(), 0, 12, true);
		});
		context.waitTicks(40);
		context.takeScreenshot("trading-hall");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			BlockPos centre = de.nomisimo.storagedim.storage.Library.centre(network.room);
			// in front of the first stall, looking at the sign and the villager behind it
			player.teleportTo((ServerLevel) player.level(), centre.getX() + 2.2, centre.getY() + 1, centre.getZ() + 11.5, java.util.Set.of(), 90, 28, true);
		});
		context.waitTicks(30);
		context.takeScreenshot("trading-hall-sign");
	}

	/** A bucket with a villager in it, the way bucket mods keep it: the saved villager in the item's data. */
	private static ItemStack villagerBucket(String profession) {
		net.minecraft.nbt.CompoundTag villagerData = new net.minecraft.nbt.CompoundTag();
		villagerData.putString("profession", profession);
		villagerData.putString("type", "minecraft:plains");
		villagerData.putInt("level", 3);
		net.minecraft.nbt.CompoundTag entity = new net.minecraft.nbt.CompoundTag();
		entity.putString("id", "minecraft:villager");
		entity.put("VillagerData", villagerData);
		net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
		root.put("EntityTag", entity);
		ItemStack stack = new ItemStack(Items.BUCKET);
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(root));
		return stack;
	}

	private static void logMenu(ServerPlayer player, String what) {
		var menu = player.containerMenu;
		StringBuilder line = new StringBuilder("STORAGEDIM PROJECT " + what + ": " + menu.getClass().getSimpleName() + " [");

		for (int i = 0; i < 54 && i < menu.slots.size(); i++) {
			ItemStack stack = menu.getSlot(i).getItem();

			if (!stack.isEmpty() && !stack.is(Items.GRAY_STAINED_GLASS_PANE)) {
				line.append(i).append('=').append(stack.getHoverName().getString()).append("; ");
			}
		}

		System.out.println(line.append(']'));
	}

	/** Moves the mouse over the first slot of the open screen showing this item. */
	private static void hoverItem(ClientGameTestContext context, Item item) {
		double[] at = context.computeOnClient(client -> {
			var menu = client.player.containerMenu;

			for (int i = 0; i < menu.slots.size(); i++) {
				if (menu.getSlot(i).getItem().is(item)) {
					try {
						java.lang.reflect.Field left = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("leftPos");
						java.lang.reflect.Field top = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("topPos");
						left.setAccessible(true);
						top.setAccessible(true);
						var slot = menu.getSlot(i);
						double scale = client.getWindow().getGuiScale();
						return new double[] {(left.getInt(client.screen) + slot.x + 8) * scale, (top.getInt(client.screen) + slot.y + 8) * scale};
					} catch (ReflectiveOperationException e) {
						throw new RuntimeException(e);
					}
				}
			}

			return new double[] {0, 0};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(10);
	}

	/** Types into the anvil text field that opened for a name, an amount or a search, and confirms it. */
	private static void typeInAnvil(ServerPlayer player, String text) {
		if (player.containerMenu instanceof net.minecraft.world.inventory.AnvilMenu anvil) {
			anvil.setItemName(text);
			anvil.clicked(2, 0, ContainerInput.PICKUP, player);
		} else {
			System.out.println("STORAGEDIM PROJECT no text field, open: " + player.containerMenu.getClass().getSimpleName());
		}
	}

	/**
	 * Momo's project chest: placed, set up through its settings (an item laid on a slot, + 64, shift-click from the
	 * inventory, the search, a typed amount and name, blocking on), then its item view, the blocked items in the normal
	 * terminal (with the tooltip) and the project chest standing in the world.
	 */
	private static void projectTest(ClientGameTestContext context, TestSingleplayerContext world) {
		BlockPos[] chestPos = new BlockPos[1];
		context.getInput().setCursorPos(0, 0);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			BlockPos base = stationBase.offset(0, 0, 12);
			player.teleportTo(level, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, java.util.Set.of(), 180, 20, true);
			var network = StorageData.get().networkFor(player.getUUID());
			network.insert(new ItemStack(Items.OAK_PLANKS, 300));
			network.insert(new ItemStack(Items.STONE_BRICKS, 90));
			network.insert(new ItemStack(Items.GLASS_PANE, 40));
			BlockPos pos = base.offset(0, 0, -2);
			chestPos[0] = pos;
			level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
			var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
			level.setBlockAndUpdate(pos, state);
			de.nomisimo.storagedim.storage.StorageEvents.onPlaced(level, pos, state, player, StorageItems.projectChest(null));
			var project = StorageData.get().projectChest(Rooms.id(level), pos);
			System.out.println("STORAGEDIM PROJECT placed=" + (project != null) + " name=" + (project == null ? "-" : project.name));

			de.nomisimo.storagedim.storage.ProjectSettingsMenu.open(player, project);
			var menu = (de.nomisimo.storagedim.storage.ProjectSettingsMenu) player.containerMenu;
			// lay planks into the empty row: the project needs 64, then + 64
			menu.setCarried(new ItemStack(Items.OAK_PLANKS, 64));
			menu.action(de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_SET_ITEM, 0, 0, "", ItemStack.EMPTY);
			menu.setCarried(ItemStack.EMPTY);
			menu.action(de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_ADJUST, 0, 64, "", ItemStack.EMPTY);
			// shift-click stone bricks in the own inventory
			player.getInventory().setItem(9, new ItemStack(Items.STONE_BRICKS, 32));
			int brickSlot = -1;

			for (int i = 2; i < menu.slots.size(); i++) {
				if (menu.getSlot(i).getItem().is(Items.STONE_BRICKS)) {
					brickSlot = i;
				}
			}

			menu.clicked(brickSlot, 0, ContainerInput.QUICK_MOVE, player);
			player.getInventory().setItem(9, ItemStack.EMPTY);
			// glass panes from the search, then amounts, name and blocking
			menu.action(de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_ADD_STACK, 0, 0, "", new ItemStack(Items.GLASS_PANE));
			menu.action(de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_SET_AMOUNT, 2, 64, "", ItemStack.EMPTY);
			menu.action(de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_SET_AMOUNT, 0, 200, "", ItemStack.EMPTY);
			menu.action(de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_NAME, 0, 0, "Burg", ItemStack.EMPTY);
			menu.action(de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_BLOCK, 0, 0, "", ItemStack.EMPTY);
			// more items, so the list scrolls
			for (Item item : List.of(Items.OAK_LOG, Items.TORCH, Items.LANTERN, Items.OAK_DOOR)) {
				menu.action(de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_ADD_STACK, 0, 0, "", new ItemStack(item));
			}

			// a book and quill gets the recipe
			menu.getSlot(de.nomisimo.storagedim.storage.ProjectSettingsMenu.SLOT_BOOK_IN).set(new ItemStack(Items.WRITABLE_BOOK));
			menu.action(de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_ADJUST, 1, 0, "", ItemStack.EMPTY);
			ItemStack book = menu.getSlot(de.nomisimo.storagedim.storage.ProjectSettingsMenu.SLOT_BOOK_OUT).getItem();
			System.out.println("STORAGEDIM PROJECT book=" + book.getHoverName().getString() + " pages="
					+ book.getOrDefault(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
							net.minecraft.world.item.component.WritableBookContent.EMPTY).pages().stream().map(page -> page.raw()).toList());
			StringBuilder entries = new StringBuilder();
			project.entries.forEach(e -> entries.append(e.item.getHoverName().getString()).append('=').append(e.target).append(' '));
			System.out.println("STORAGEDIM PROJECT settings name=" + project.name + " block=" + project.block + " entries=" + entries);
		});
		context.waitTicks(10);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot("project-settings");
		// drag bricks from JEI's list onto the project list
		context.runOnClient(client -> {
			if (client.screen instanceof de.nomisimo.storagedim.client.ProjectScreen screen) {
				System.out.println("STORAGEDIM PROJECT jei targets=" + de.nomisimo.storagedim.client.StorageJeiPlugin.dropOnProject(screen, new ItemStack(Items.BRICKS)));
			}
		});
		context.waitTicks(10);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var project = ((de.nomisimo.storagedim.storage.ProjectSettingsMenu) player.containerMenu).project();
			System.out.println("STORAGEDIM PROJECT after jei drop: bricks=" + project.wants(new ItemStack(Items.BRICKS)) + " entries=" + project.entries.size());
		});
		context.runOnClient(client -> {
			if (client.screen instanceof de.nomisimo.storagedim.client.ProjectScreen screen) {
				screen.setSearch("glass");
			}
		});
		context.waitTicks(5);
		context.takeScreenshot("project-search");
		context.runOnClient(client -> {
			if (client.screen instanceof de.nomisimo.storagedim.client.ProjectScreen screen) {
				screen.setSearch("");
			}
		});
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			((de.nomisimo.storagedim.storage.ProjectSettingsMenu) player.containerMenu).action(
					de.nomisimo.storagedim.storage.ProjectSettingsMenu.ACTION_ITEMS, 0, 0, "", ItemStack.EMPTY);
			logMenu(player, "items");
		});
		context.waitTicks(10);
		context.getInput().setCursorPos(0, 0);
		context.waitTicks(2);
		context.takeScreenshot("project-items");
		hoverItem(context, Items.GLASS_PANE);
		context.takeScreenshot("project-items-tooltip");
		context.getInput().setCursorPos(0, 0);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			var network = StorageData.get().networkFor(player.getUUID());
			var project = StorageData.get().projectChest(Rooms.id(player.level()), chestPos[0]);
			ItemStack planks = new ItemStack(Items.OAK_PLANKS);
			ItemStack free = network.extract(planks, 300);
			ItemStack own = network.extract(planks, 300, project);
			System.out.println("STORAGEDIM PROJECT blocked: terminal took=" + free.getCount() + " project took=" + own.getCount()
					+ " available=" + de.nomisimo.storagedim.api.StorageApi.count(player, planks));
			network.insert(free);
			network.insert(own);
			player.closeContainer();
			de.nomisimo.storagedim.storage.StorageMenu.open(player, network);
			logMenu(player, "terminal");
		});
		context.waitTicks(15);
		// hover the planks in the terminal for the tooltip with the projects that block them
		double[] at = context.computeOnClient(client -> {
			var menu = client.player.containerMenu;

			for (int i = 0; i < 45; i++) {
				if (menu.getSlot(i).getItem().is(Items.OAK_PLANKS)) {
					try {
						java.lang.reflect.Field left = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("leftPos");
						java.lang.reflect.Field top = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class.getDeclaredField("topPos");
						left.setAccessible(true);
						top.setAccessible(true);
						var slot = menu.getSlot(i);
						double scale = client.getWindow().getGuiScale();
						System.out.println("STORAGEDIM PROJECT tooltip " + slot.getItem().getOrDefault(net.minecraft.core.component.DataComponents.LORE,
								net.minecraft.world.item.component.ItemLore.EMPTY).lines().stream().map(Component::getString).toList());
						return new double[] {(left.getInt(client.screen) + slot.x + 8) * scale, (top.getInt(client.screen) + slot.y + 8) * scale};
					} catch (ReflectiveOperationException e) {
						throw new RuntimeException(e);
					}
				}
			}

			return new double[] {0, 0};
		});
		context.getInput().setCursorPos(at[0], at[1]);
		context.waitTicks(10);
		context.takeScreenshot("project-blocked-tooltip");
		context.getInput().setCursorPos(0, 0);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			player.closeContainer();
			lookAt(player, Vec3.atCenterOf(chestPos[0]), 3.5);
		});
		context.waitTicks(20);
		context.takeScreenshot("project-chest");
	}

	/** Drags a stack across two inventory slots and a display slot in the open terminal, then double-click collects it again. */
	private static void dragInTerminal(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			de.nomisimo.storagedim.storage.StorageMenu.open(player, StorageData.get().networkFor(player.getUUID()));
		});
		context.waitTicks(10);
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			net.minecraft.world.inventory.AbstractContainerMenu menu = player.containerMenu;
			player.getInventory().setItem(9, new ItemStack(Items.DIRT, 64));
			menu.clicked(54, 0, ContainerInput.PICKUP, player);
			menu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, player);
			menu.clicked(55, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, player);
			menu.clicked(0, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, player);
			menu.clicked(56, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, player);
			menu.clicked(-999, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, player);
			String drag = "carried=" + menu.getCarried().getCount() + " inv10=" + player.getInventory().getItem(10).getCount()
					+ " inv11=" + player.getInventory().getItem(11).getCount();
			menu.clicked(55, 0, ContainerInput.PICKUP, player);
			menu.clicked(55, 0, ContainerInput.PICKUP_ALL, player);
			String collect = "carried=" + menu.getCarried().getCount() + " inv11=" + player.getInventory().getItem(11).getCount();
			System.out.println("STORAGEDIM DRAG " + menu.getClass().getSimpleName() + " drag[" + drag + "] collect[" + collect + "]");
			player.getInventory().add(menu.getCarried());
			menu.setCarried(ItemStack.EMPTY);
			player.closeContainer();
		});
		context.waitTicks(5);
	}

	private static BlockPos antennaStation;

	/** Antennas on a station and at the opposite corner, the outlines with the remote in hand, the remote's screen. */
	private static void antennaTest(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			BlockPos station = player.blockPosition().offset(40, 0, 0);
			antennaStation = station;
			String dim = Rooms.id(level);

			// clear ground for the scene
			for (BlockPos pos : BlockPos.betweenClosed(station.offset(-3, 0, -3), station.offset(10, 8, 10))) {
				level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
			}

			level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
			level.setBlockAndUpdate(station.west(), Blocks.CHEST.defaultBlockState());

			for (int y = 0; y < 3; y++) {
				level.setBlockAndUpdate(station.offset(6, y, 5), Blocks.STONE.defaultBlockState());
			}

			StationMenu.open(player, station);
			StationMenu menu = (StationMenu) player.containerMenu;
			menu.action(StationMenu.ACTION_STATION_NAME, 0, "Südlager");
			menu.action(StationMenu.ACTION_AREA_NAME, 0, "Garten");
			menu.action(StationMenu.ACTION_COLOR, 6, "");
			menu.setCarried(new ItemStack(Robots.ROBOT_ITEM, 2));
			menu.clicked(StationMenu.SLOT_INPUT, 0, ContainerInput.PICKUP, player);
			ItemStack robots = menu.getSlot(StationMenu.SLOT_OUTPUT).getItem().copy();
			menu.getSlot(StationMenu.SLOT_OUTPUT).set(ItemStack.EMPTY);
			menu.setCarried(new ItemStack(AntennaBlock.ITEM, 2));
			menu.clicked(StationMenu.SLOT_INPUT, 0, ContainerInput.PICKUP, player);
			ItemStack antennas = menu.getSlot(StationMenu.SLOT_OUTPUT).getItem().copy();
			menu.getSlot(StationMenu.SLOT_OUTPUT).set(ItemStack.EMPTY);
			player.closeContainer();

			// an unpaired antenna cannot be placed
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AntennaBlock.ITEM));
			BlockPos ground = station.offset(3, -1, 3);
			System.out.println("STORAGEDIM ANTENNA unpaired=" + player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false))));

			player.setItemInHand(InteractionHand.MAIN_HAND, antennas);
			System.out.println("STORAGEDIM ANTENNA paired=" + AntennaItem.link(antennas));
			System.out.println("STORAGEDIM ANTENNA base=" + antennas.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(station), Direction.UP, station, false))));
			BlockPos pillar = station.offset(6, 2, 5);
			System.out.println("STORAGEDIM ANTENNA corner=" + antennas.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(pillar), Direction.UP, pillar, false))));
			System.out.println("STORAGEDIM ANTENNA area=" + StorageData.get().stationArea(dim, station));

			player.setItemInHand(InteractionHand.MAIN_HAND, robots);
			BlockPos spot = station.offset(4, -1, 2);
			robots.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(spot), Direction.UP, spot, false)));
			drop(level, station.getX() + 3.5, station.getY() + 0.2, station.getZ() + 4.5, new ItemStack(Items.WHEAT, 5));
			drop(level, station.getX() - 2.5, station.getY() + 0.2, station.getZ() - 2.5, new ItemStack(Items.CARROT, 5));
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			// the menu closes again when the player is far from the station
			player.teleportTo(level, station.getX() + 0.5, station.getY(), station.getZ() + 3.5, java.util.Set.of(), 180, 30, true);
			StationMenu.open(player, station);
		});
		context.waitTicks(15);
		context.takeScreenshot("antenna-station-menu");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			player.closeContainer();
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(de.nomisimo.storagedim.robot.RemoteItem.ITEM));
			// flying, so the view from above stays put while the outlines arrive
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			lookAt(player, Vec3.atCenterOf(antennaStation.offset(3, 1, 3)), 12);
		});
		context.waitTicks(80);
		context.takeScreenshot("antenna-outlines");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			System.out.println("STORAGEDIM ANTENNA list=" + de.nomisimo.storagedim.robot.Areas.list(player));
			de.nomisimo.storagedim.robot.Areas.open(player, InteractionHand.MAIN_HAND);
		});
		context.waitTicks(15);
		context.takeScreenshot("antenna-remote-screen");
		context.runOnClient(client -> client.setScreen(null));
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			String dim = Rooms.id(player.level());
			StorageData data = StorageData.get();
			data.setStationArea(dim, antennaStation, data.stationArea(dim, antennaStation).withEnabled(false));
		});
		context.waitTicks(100);
		context.takeScreenshot("antenna-area-off");
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			System.out.println("STORAGEDIM ANTENNA robots off=" + player.level().getEntitiesOfClass(RobotEntity.class,
					new net.minecraft.world.phys.AABB(antennaStation).inflate(12)).stream().map(r -> r.status().getString()).toList());
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksDownload();
			world.getServer().runCommand("time set day");
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				ServerLevel level = player.level();
				BlockPos base = player.blockPosition();
				StorageData data = StorageData.get();
				String dim = Rooms.id(level);
				// facing north (negative z); everything stands in front of the player
				player.teleportTo(level, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, java.util.Set.of(), 180, 25, true);

				BlockPos storage = base.offset(-3, 0, -4);
				level.setBlockAndUpdate(storage, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.SOUTH));
				data.setChest(dim, storage, player.getUUID());

				BlockPos filter = base.offset(-1, 0, -4);
				level.setBlockAndUpdate(filter, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.SOUTH));
				data.setFilterChest(dim, filter, FilterChests.FilterChest.empty(player.getUUID()));

				BlockPos left = base.offset(1, 0, -4);
				BlockPos right = base.offset(2, 0, -4);
				level.setBlockAndUpdate(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.RIGHT));
				level.setBlockAndUpdate(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, net.minecraft.core.Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.LEFT));
				data.setFilterChest(dim, left, FilterChests.FilterChest.empty(player.getUUID()));
				data.setFilterChest(dim, right, FilterChests.FilterChest.empty(player.getUUID()));

				BlockPos terminal = base.offset(4, 0, -4);
				level.setBlockAndUpdate(terminal, Blocks.ENDER_CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.SOUTH));
				data.setTerminal(dim, terminal, true);

				// the robots fill the storage right next to their station (here: where the station will stand)
				level.setBlockAndUpdate(base.offset(0, 0, -3), Blocks.BARREL.defaultBlockState().setValue(net.minecraft.world.level.block.BarrelBlock.FACING, net.minecraft.core.Direction.UP));

				RobotEntity robot = Robots.ROBOT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
				robot.snapTo(base.getX() - 1.5, base.getY(), base.getZ() - 1.5, 30, 0);
				robot.setup(player.getUUID(), base.offset(0, 0, -2));
				level.addFreshEntity(robot);

				for (int i = 0; i < 4; i++) {
					ItemEntity item = new ItemEntity(level, base.getX() - 3 + i * 2, base.getY() + 0.2, base.getZ() - 2.5, new ItemStack(i % 2 == 0 ? Items.COBBLESTONE : Items.OAK_LOG, 16));
					item.setNoPickUpDelay();
					level.addFreshEntity(item);
				}

				player.getInventory().add(StorageItems.storageChest());
				player.getInventory().add(StorageItems.filterChest());
				player.getInventory().add(StorageItems.terminal());
				player.getInventory().add(new ItemStack(Robots.ROBOT_ITEM));
			});
			world.getClientLevel().waitForChunksRender();
			context.waitTicks(10);
			context.takeScreenshot("storagedim-1-scene");

			for (int i = 2; i <= 9; i++) {
				context.waitTicks(15);
				context.takeScreenshot("storagedim-" + i + "-robot");
				logRobots(world);
			}

			context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
			context.waitTicks(10);
			context.takeScreenshot("storagedim-inventory");
			context.setScreen(() -> null);

			ItemStack a = of(Items.AMETHYST_SHARD), r = of(Items.REDSTONE), f = of(Items.ITEM_FRAME), i = of(Items.IRON_INGOT), n = of(Items.IRON_NUGGET);
			recipe(context, world, "storage_chest", a, of(Items.ENDER_PEARL), a, of(Items.ENDER_PEARL), of(Items.CHEST), of(Items.ENDER_PEARL), a, of(Items.ENDER_PEARL), a);
			recipe(context, world, "me_terminal", r, a, r, a, of(Items.ENDER_CHEST), a, r, a, r);
			recipe(context, world, "filter_chest", f, f, f, f, of(Items.CHEST), f, f, of(Items.ENDER_EYE), f);
			recipe(context, world, "project_chest", StorageItems.filterChest(), of(Items.BOOK));
			recipe(context, world, "robot", i, of(Items.PUMPKIN), i, r, of(Items.HOPPER), r, n, null, n);
			recipe(context, world, "me_upgrade", of(Items.ENDER_EYE), a, a, of(Items.REDSTONE_BLOCK), of(Items.PAPER));
			recipe(context, world, "me_backpack", of(Items.BUNDLE), upgrade);
			ItemStack g = of(Items.GLASS);
			recipe(context, world, "robot_barrier", g, g, g, g, of(Items.AMETHYST_SHARD), g, g, g, g);
			ItemStack ironIngot = of(Items.IRON_INGOT);
			recipe(context, world, "robot_station", null, of(Items.LIGHTNING_ROD), null, ironIngot, of(Items.REDSTONE_BLOCK), ironIngot,
					ironIngot, of(Items.CHEST), ironIngot);
			ItemStack copper = of(Items.COPPER_INGOT);
			recipe(context, world, "antenna", of(Items.LIGHTNING_ROD), copper, copper, r);
			recipe(context, world, "remote", null, r, null, copper, of(Items.GLASS_PANE), copper, copper, of(Items.ENDER_PEARL), copper);

			// the Traveler's Backpack add-on, when the test runs with it
			Item blank = BuiltInRegistries.ITEM.getValue(Identifier.parse("travelersbackpack:blank_upgrade"));

			if (blank != Items.AIR) {
				recipe(context, world, "tb_me_upgrade", of(Items.ENDER_EYE), new ItemStack(blank), a, a, of(Items.REDSTONE_BLOCK));
			}

			hopperIntoTerminal(context, world);
			dragInTerminal(context, world);
			terminalCounts(context, world);

			// Filter on cobblestone; one cobblestone lies walled in glass right next to it, one further away in the open.
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				ServerLevel level = player.level();
				BlockPos base = player.blockPosition();

				for (RobotEntity robot : level.getEntities(Robots.ROBOT, entity -> true)) {
					robot.filter().add(new ItemStack(Items.COBBLESTONE));
				}

				BlockPos cage = base.offset(-4, 0, -1);

				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						for (int dz = -1; dz <= 1; dz++) {
							level.setBlockAndUpdate(cage.offset(dx, dy, dz), dx == 0 && dy == 0 && dz == 0 ? Blocks.AIR.defaultBlockState() : Blocks.GLASS.defaultBlockState());
						}
					}
				}

				drop(level, cage.getX() + 0.5, cage.getY(), cage.getZ() + 0.5, new ItemStack(Items.COBBLESTONE, 5));

				// a ring of robot barriers around another cobblestone, and a barrier wall behind the chests for the picture
				BlockPos ring = base.offset(-2, 0, 3);

				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx != 0 || dz != 0) {
							level.setBlockAndUpdate(ring.offset(dx, 0, dz), RobotBarrierBlock.BLOCK.defaultBlockState());
						}
					}
				}

				drop(level, ring.getX() + 0.5, ring.getY() + 0.2, ring.getZ() + 0.5, new ItemStack(Items.COBBLESTONE, 3));

				for (int dx = -4; dx <= 5; dx++) {
					for (int dy = 0; dy <= 1; dy++) {
						level.setBlockAndUpdate(base.offset(dx, dy, -6), RobotBarrierBlock.BLOCK.defaultBlockState());
					}
				}

				// cobblestone behind a fence ring and a wall ring: robots hop over both
				BlockPos fence = base.offset(4, 0, 3);
				BlockPos wall = base.offset(7, 0, 0);

				for (int dx = -1; dx <= 1; dx++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx != 0 || dz != 0) {
							level.setBlockAndUpdate(fence.offset(dx, 0, dz), Blocks.OAK_FENCE.defaultBlockState());
							level.setBlockAndUpdate(wall.offset(dx, 0, dz), Blocks.COBBLESTONE_WALL.defaultBlockState());
						}
					}
				}

				drop(level, fence.getX() + 0.5, fence.getY() + 0.2, fence.getZ() + 0.5, new ItemStack(Items.COBBLESTONE, 2));
				drop(level, wall.getX() + 0.5, wall.getY() + 0.2, wall.getZ() + 0.5, new ItemStack(Items.COBBLESTONE, 4));

				// a robot put into a boat climbs out again
				net.minecraft.world.entity.Entity boat = net.minecraft.world.entity.EntityType.OAK_BOAT.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
				boat.snapTo(base.getX() + 1.5, base.getY(), base.getZ() + 3.5, 0, 0);
				level.addFreshEntity(boat);

				for (RobotEntity robot : level.getEntities(Robots.ROBOT, entity -> true)) {
					System.out.println("STORAGEDIM BOAT ride=" + robot.startRiding(boat) + " passenger=" + robot.isPassenger());
					break;
				}

				player.getInventory().add(new ItemStack(RobotBarrierBlock.ITEM, 16));
				drop(level, base.getX() + 4.5, base.getY() + 0.2, base.getZ() - 1.5, new ItemStack(Items.COBBLESTONE, 7));
				drop(level, base.getX() + 3.5, base.getY() + 0.2, base.getZ() - 1.5, new ItemStack(Items.OAK_LOG, 3));
			});

			for (int t = 0; t < 4; t++) {
				context.waitTicks(100);
				logRobots(world);
			}

			context.takeScreenshot("storagedim-idle");
			world.getServer().runOnServer(server -> {
				ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
				BlockPos base = server.getPlayerList().getPlayers().get(0).blockPosition();
				System.out.println("STORAGEDIM ITEMS left " + level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(base).inflate(20))
						.stream().map(item -> item.getItem() + "@" + item.blockPosition().toShortString()).toList());
			});

			stationTest(context, world);
			fullChestTest(context, world);
			logBarrel(world);
			slabChestTest(context, world);
			miningTest(context, world);
			projectTest(context, world);
			antennaTest(context, world);
			islandTest(context, world);
			extrasTest(context, world);
		}
	}
}
