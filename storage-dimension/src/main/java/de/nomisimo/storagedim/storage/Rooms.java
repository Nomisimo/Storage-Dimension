package de.nomisimo.storagedim.storage;

import java.util.Set;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Layout of the storage rooms. Room n sits at x = n * SPACING in the storage dimension.
 * Inside it looks like the inside of a chest: spruce walls, a terminal, a crafting table and an exit.
 */
public final class Rooms {
	public static final int SPACING = 64;
	public static final int SIZE = 12;   // shell from 0 to SIZE on x and z
	public static final int HEIGHT = 7;  // shell from 0 to HEIGHT on y
	public static final int BASE_Y = 100;

	// Fixtures, relative to the room origin.
	private static final BlockPos TERMINAL = new BlockPos(6, 1, 1);
	private static final BlockPos CRAFTING = new BlockPos(4, 1, 1);
	private static final BlockPos FURNACE = new BlockPos(8, 1, 1);
	private static final BlockPos EXIT = new BlockPos(6, 1, 11);

	private Rooms() {
	}

	public static BlockPos origin(int room) {
		return new BlockPos(room * SPACING, BASE_Y, 0);
	}

	/** Room index for a position in the storage dimension, or -1 when outside every room. */
	public static int roomAt(BlockPos pos) {
		int room = Math.floorDiv(pos.getX(), SPACING);
		BlockPos rel = pos.subtract(origin(room));

		if (rel.getX() < 0 || rel.getX() > SIZE || rel.getZ() < 0 || rel.getZ() > SIZE || rel.getY() < 0 || rel.getY() > HEIGHT) {
			return -1;
		}

		return room;
	}

	public static boolean isShell(BlockPos pos) {
		int room = roomAt(pos);

		if (room < 0) {
			return false;
		}

		BlockPos rel = pos.subtract(origin(room));
		return rel.getX() == 0 || rel.getX() == SIZE || rel.getZ() == 0 || rel.getZ() == SIZE || rel.getY() == 0 || rel.getY() == HEIGHT
				|| rel.equals(TERMINAL) || rel.equals(EXIT);
	}

	public static boolean isTerminal(BlockPos pos) {
		int room = roomAt(pos);
		return room >= 0 && pos.subtract(origin(room)).equals(TERMINAL);
	}

	public static boolean isExit(BlockPos pos) {
		int room = roomAt(pos);
		return room >= 0 && pos.subtract(origin(room)).equals(EXIT);
	}

	/** Interior box (without walls). */
	public static AABB interior(int room) {
		BlockPos o = origin(room);
		return new AABB(o.getX() + 1, o.getY() + 1, o.getZ() + 1, o.getX() + SIZE, o.getY() + HEIGHT, o.getZ() + SIZE);
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

	/** Builds the room if its floor is still missing. */
	public static void ensureBuilt(ServerLevel level, int room) {
		BlockPos o = origin(room);

		if (!level.getBlockState(o.offset(SIZE / 2, 0, SIZE / 2)).isAir()) {
			return;
		}

		BlockState wall = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState floor = Blocks.STRIPPED_SPRUCE_WOOD.defaultBlockState();
		BlockState light = Blocks.SEA_LANTERN.defaultBlockState();

		for (int x = 0; x <= SIZE; x++) {
			for (int z = 0; z <= SIZE; z++) {
				for (int y = 0; y <= HEIGHT; y++) {
					boolean edge = x == 0 || x == SIZE || z == 0 || z == SIZE;
					BlockState state;

					if (y == 0) {
						state = floor;
					} else if (y == HEIGHT) {
						state = (x % 3 == 0 && z % 3 == 0 && !edge) ? light : wall;
					} else if (edge) {
						state = wall;
					} else {
						state = Blocks.AIR.defaultBlockState();
					}

					level.setBlock(o.offset(x, y, z), state, Block.UPDATE_ALL);
				}
			}
		}

		level.setBlock(o.offset(TERMINAL), Blocks.ENDER_CHEST.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(o.offset(CRAFTING), Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(o.offset(FURNACE), Blocks.FURNACE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(o.offset(EXIT), Blocks.LODESTONE.defaultBlockState(), Block.UPDATE_ALL);
	}

	public static void teleportIntoRoom(ServerPlayer player, int room) {
		ServerLevel level = level(player.level().getServer());

		if (level == null) {
			StorageDimensionMod.LOGGER.error("Storage-Dimension fehlt, ist das Datapack geladen?");
			return;
		}

		ensureBuilt(level, room);
		BlockPos o = origin(room);
		// Face the terminal wall (north = 180 degrees).
		player.teleportTo(level, o.getX() + SIZE / 2.0 + 0.5, o.getY() + 1, o.getZ() + SIZE / 2.0 + 0.5, Set.<Relative>of(), 180f, 0f, true);
	}

	/** Spot high in a corner from where watchers look into the room. */
	public static void teleportToViewpoint(ServerPlayer player, int room) {
		ServerLevel level = level(player.level().getServer());
		BlockPos o = origin(room);
		player.teleportTo(level, o.getX() + 1.5, o.getY() + HEIGHT - 1.5, o.getZ() + SIZE - 1.5, Set.<Relative>of(), -135f, 35f, true);
	}
}
