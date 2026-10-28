package de.nomisimo.storagedim.storage;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Helpers for the storage dimension, which holds the chest islands (see {@link Islands}). */
public final class Rooms {
	// The small rooms of older versions: room n had its corner at (n * 64, 100, 0) and was 12 x 7 x 12 blocks.
	private static final int OLD_SPACING = 64;
	private static final int OLD_SIZE = 12;
	private static final int OLD_HEIGHT = 7;

	private Rooms() {
	}

	public static ServerLevel level(MinecraftServer server) {
		return server.getLevel(StorageDimensionMod.STORAGE_LEVEL);
	}

	public static ServerLevel levelById(MinecraftServer server, String id) {
		return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(id)));
	}

	public static String id(Level level) {
		return level.dimension().identifier().toString();
	}

	public static boolean isStorage(Level level) {
		return level.dimension().equals(StorageDimensionMod.STORAGE_LEVEL);
	}

	/** Removes a room of an older version. What players left in its furnace or chests goes into the network. */
	static void clearOldRoom(ServerLevel level, int room, StorageNetwork network) {
		BlockPos origin = new BlockPos(room * OLD_SPACING, Islands.FLOOR_Y, 0);

		for (int x = 0; x <= OLD_SIZE; x++) {
			for (int z = 0; z <= OLD_SIZE; z++) {
				for (int y = 0; y <= OLD_HEIGHT; y++) {
					BlockPos pos = origin.offset(x, y, z);

					if (level.getBlockState(pos).isAir()) {
						continue;
					}

					if (network != null && level.getBlockEntity(pos) instanceof Container container) {
						for (int i = 0; i < container.getContainerSize(); i++) {
							ItemStack stack = container.removeItemNoUpdate(i);

							if (!stack.isEmpty()) {
								network.insert(stack);
							}
						}
					}

					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		}
	}
}
