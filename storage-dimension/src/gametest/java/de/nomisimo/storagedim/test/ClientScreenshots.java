package de.nomisimo.storagedim.test;

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
	private static void stationTest(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			ServerLevel level = player.level();
			BlockPos base = player.blockPosition();
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
			BlockPos base = player.blockPosition();
			ItemStack configured = player.containerMenu.getSlot(StationMenu.SLOT_OUTPUT).getItem().copy();
			player.containerMenu.getSlot(StationMenu.SLOT_OUTPUT).set(ItemStack.EMPTY);
			player.closeContainer();
			RobotConfig config = RobotConfig.read(configured, level.registryAccess());
			System.out.println("STORAGEDIM STATION item=" + configured + " config=" + config + " lore="
					+ configured.get(net.minecraft.core.component.DataComponents.LORE).lines().stream().map(Component::getString).toList());

			// placed like a player does: one next to the scene, one 30 blocks away (walks to the station first),
			// and one walled in with glass far away, for the recall
			player.setItemInHand(InteractionHand.OFF_HAND, configured);

			for (BlockPos ground : List.of(base.offset(3, -1, 2), base.offset(30, -1, 2), base.offset(20, -1, -10))) {
				System.out.println("STORAGEDIM PLACE configured=" + configured.useOn(new UseOnContext(player, InteractionHand.OFF_HAND,
						new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP, ground, false))));
			}

			player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
			BlockPos cage = base.offset(20, 0, -10);

			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int dy = 0; dy <= 2; dy++) {
						if (dx != 0 || dz != 0 || dy == 2) {
							level.setBlockAndUpdate(cage.offset(dx, dy, dz), Blocks.GLASS.defaultBlockState());
						}
					}
				}
			}

			// a lonely station far away with no chest in its range
			BlockPos lonely = base.offset(40, 0, 0);
			level.setBlockAndUpdate(lonely.below(), Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(lonely, RobotStationBlock.BLOCK.defaultBlockState());
			String dim = Rooms.id(level);
			StorageData.get().setStation(dim, lonely, StorageData.get().station(dim, lonely).withRange(16).withName("Traurig").withStationName("Einsam"));
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
			BlockPos base = player.blockPosition();
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
			BlockPos base = player.blockPosition();
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
		world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0).closeContainer());

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
			BlockPos station = player.blockPosition().offset(0, 0, -2);
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
			// look at the sad robot
			BlockPos base = player.blockPosition();
			player.teleportTo(player.level(), base.getX() + 42.5, base.getY(), base.getZ() + 4.5, java.util.Set.of(), 180, 30, true);
		});
		context.waitTicks(40);
		context.takeScreenshot("robot-sad");
		logRobots(world);
	}

	/** Puts the player {@code distance} blocks south-east of the point and turns them to look at it. */
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
			recipe(context, world, "robot", i, of(Items.PUMPKIN), i, r, of(Items.HOPPER), r, n, null, n);
			recipe(context, world, "me_upgrade", of(Items.ENDER_EYE), a, a, of(Items.REDSTONE_BLOCK), of(Items.PAPER));
			recipe(context, world, "me_backpack", of(Items.BUNDLE), upgrade);
			ItemStack g = of(Items.GLASS);
			recipe(context, world, "robot_barrier", g, g, g, g, of(Items.AMETHYST_SHARD), g, g, g, g);
			ItemStack ironIngot = of(Items.IRON_INGOT);
			recipe(context, world, "robot_station", null, of(Items.LIGHTNING_ROD), null, ironIngot, of(Items.REDSTONE_BLOCK), ironIngot,
					ironIngot, of(Items.CHEST), ironIngot);

			// the Traveler's Backpack add-on, when the test runs with it
			Item blank = BuiltInRegistries.ITEM.getValue(Identifier.parse("travelersbackpack:blank_upgrade"));

			if (blank != Items.AIR) {
				recipe(context, world, "tb_me_upgrade", of(Items.ENDER_EYE), new ItemStack(blank), a, a, of(Items.REDSTONE_BLOCK));
			}

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
		}
	}
}
