package de.nomisimo.storagedim.test;

import de.nomisimo.storagedim.robot.RobotEntity;
import de.nomisimo.storagedim.robot.Robots;
import de.nomisimo.storagedim.storage.FilterChests;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import de.nomisimo.storagedim.storage.StorageItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Builds a small scene with every special chest and a robot, and takes screenshots of it. */
public class ClientScreenshots implements FabricClientGameTest {
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
				robot.snapTo(base.getX() + 0.5, base.getY(), base.getZ() - 2, 30, 0);
				robot.setup(player.getUUID(), base.offset(0, 0, -2));
				level.addFreshEntity(robot);

				for (int i = 0; i < 4; i++) {
					ItemEntity item = new ItemEntity(level, base.getX() - 4 + i * 2.5, base.getY() + 0.2, base.getZ() - 7, new ItemStack(i % 2 == 0 ? Items.COBBLESTONE : Items.OAK_LOG, 16));
					item.setNoPickUpDelay();
					level.addFreshEntity(item);
				}

				player.getInventory().add(StorageItems.storageChest());
				player.getInventory().add(StorageItems.filterChest());
				player.getInventory().add(StorageItems.terminal());
				player.getInventory().add(new ItemStack(Robots.ROBOT_ITEM));
			});
			world.getClientLevel().waitForChunksRender();
			context.waitTicks(30);
			context.takeScreenshot("storagedim-1-scene");
			context.waitTicks(15);
			context.takeScreenshot("storagedim-2-walking");
			context.waitTicks(40);
			context.takeScreenshot("storagedim-3-collecting");
			context.waitTicks(120);
			context.takeScreenshot("storagedim-4-later");
			context.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
			context.waitTicks(10);
			context.takeScreenshot("storagedim-5-inventory");
			context.setScreen(() -> null);
			context.waitTicks(260);
			context.takeScreenshot("storagedim-6-idle");
		}
	}
}
