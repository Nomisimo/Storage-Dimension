package de.nomisimo.storagedim.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;
import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.mixin.DisplayAccessor;
import de.nomisimo.storagedim.mixin.ItemDisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The chest island: every network has its own island in the storage dimension, shaped like the End island and
 * made of barrels, with one chest per kind of stored item. Island n sits around x = n * {@link #SPACING}.
 * <p>
 * Chests stand in rows sorted by mod and name. A chest opens the ME terminal limited to its item, so it holds any
 * amount and takes nothing else. The layout is redone when somebody enters an island nobody is on, which drops the
 * chests of items that ran out; while players are there, new items get a chest at the edge and empty chests stay.
 */
public final class Islands {
	public static final int SPACING = 10_000;
	public static final int FLOOR_Y = 100;
	/** Half size of the free square in the middle where players arrive. */
	private static final int SPAWN = 2;
	/** The lodestone that leads back out, relative to the island centre (one above the floor). */
	private static final int EXIT_DX = 0;
	private static final int EXIT_DZ = 2;
	/** Tag on every display entity of an island, so stale ones can be found again. */
	public static final String TAG = "storagedim_island";
	private static final int STARS = 140;
	private static final double SKY_RADIUS = 92;

	/** One chest of an island: its column relative to the centre and the item kind it holds (count 1). */
	public record IslandChest(int dx, int dz, ItemStack key) {
	}

	/** Players looking into an island chest, per chest position, to keep its lid open. */
	private static final Map<Long, Integer> LIDS = new HashMap<>();

	private Islands() {
	}

	// ---- item kinds ----

	/** The kind an item is stored under: tools and other damageable items share one chest per item, everything else needs the exact components. */
	public static ItemStack groupKey(ItemStack stack) {
		return stack.isDamageableItem() ? new ItemStack(stack.getItem()) : stack.copyWithCount(1);
	}

	public static boolean inGroup(ItemStack key, ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		return key.isDamageableItem() ? ItemStack.isSameItem(key, stack) : ItemStack.isSameItemSameComponents(key, stack);
	}

	/** Sorted by mod, then item id, then name (e.g. the kinds of enchanted books). */
	private static final Comparator<ItemStack> ORDER = Comparator
			.comparing((ItemStack s) -> BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals("minecraft") ? "" : BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace())
			.thenComparing(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())
			.thenComparing(s -> s.getHoverName().getString())
			.thenComparing(s -> s.getComponentsPatch().toString());

	/** The kinds of items the network holds right now, in island order. */
	static List<ItemStack> kinds(StorageNetwork network) {
		Map<Integer, List<ItemStack>> buckets = new HashMap<>();
		List<ItemStack> kinds = new ArrayList<>();

		for (StorageNetwork.StoredItem entry : network.entries()) {
			if (entry.count <= 0) {
				continue;
			}

			ItemStack key = groupKey(entry.prototype);
			List<ItemStack> bucket = buckets.computeIfAbsent(ItemStack.hashItemAndComponents(key), h -> new ArrayList<>());

			if (bucket.stream().noneMatch(k -> ItemStack.isSameItemSameComponents(k, key))) {
				bucket.add(key);
				kinds.add(key);
			}
		}

		kinds.sort(ORDER);
		return kinds;
	}

	// ---- layout ----

	public static BlockPos centre(int room) {
		return new BlockPos(room * SPACING, FLOOR_Y, 0);
	}

	/** The island (room index) a position in the storage dimension belongs to. */
	public static int roomAt(BlockPos pos) {
		return Math.floorDiv(pos.getX() + SPACING / 2, SPACING);
	}

	/** Chests stand in pairs of rows back to back, with an aisle after every pair and a cross aisle every nine blocks. */
	private static boolean isChestSpot(int dx, int dz) {
		if (Math.abs(dx) <= SPAWN && Math.abs(dz) <= SPAWN) {
			return false;
		}

		return Math.floorMod(dz, 3) != 2 && Math.floorMod(dx, 9) != 0;
	}

	/** The front of a chest faces the aisle next to it. */
	private static Direction facing(int dz) {
		return Math.floorMod(dz, 3) == 0 ? Direction.NORTH : Direction.SOUTH;
	}

	/** Chest spots within radius r, in reading order (north to south, west to east). */
	private static List<int[]> spots(int r) {
		List<int[]> list = new ArrayList<>();

		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				if (dx * dx + dz * dz <= r * r && isChestSpot(dx, dz)) {
					list.add(new int[] {dx, dz});
				}
			}
		}

		return list;
	}

	/** Lays out all kinds anew in the smallest round island that fits them. */
	private static void layout(StorageNetwork network, List<ItemStack> kinds) {
		int r = SPAWN + 1;
		List<int[]> spots = spots(r);

		while (spots.size() < kinds.size()) {
			spots = spots(++r);
		}

		network.island.clear();

		for (int i = 0; i < kinds.size(); i++) {
			network.island.add(new IslandChest(spots.get(i)[0], spots.get(i)[1], kinds.get(i)));
		}
	}

	/** The free spot closest to the centre, for a kind that comes in while players are on the island. */
	private static int[] nextSpot(StorageNetwork network) {
		Set<Long> taken = new HashSet<>();
		network.island.forEach(c -> taken.add(BlockPos.asLong(c.dx(), 0, c.dz())));

		for (int r = SPAWN + 1; ; r++) {
			int[] best = null;

			for (int[] spot : spots(r)) {
				if (!taken.contains(BlockPos.asLong(spot[0], 0, spot[1]))
						&& (best == null || spot[0] * spot[0] + spot[1] * spot[1] < best[0] * best[0] + best[1] * best[1])) {
					best = spot;
				}
			}

			if (best != null) {
				return best;
			}
		}
	}

	private static int chestRadius(StorageNetwork network) {
		int r = SPAWN + 1;

		for (IslandChest chest : network.island) {
			r = Math.max(r, (int) Math.ceil(Math.sqrt(chest.dx() * chest.dx() + chest.dz() * chest.dz())));
		}

		return r;
	}

	private static boolean sameLayout(StorageNetwork network, List<ItemStack> kinds) {
		if (network.island.size() != kinds.size()) {
			return false;
		}

		for (int i = 0; i < kinds.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(network.island.get(i).key(), kinds.get(i))) {
				return false;
			}
		}

		return true;
	}

	public static IslandChest chestAt(StorageNetwork network, BlockPos pos) {
		if (pos.getY() != FLOOR_Y + 1) {
			return null;
		}

		int dx = pos.getX() - network.room * SPACING;

		for (IslandChest chest : network.island) {
			if (chest.dx() == dx && chest.dz() == pos.getZ()) {
				return chest;
			}
		}

		return null;
	}

	public static boolean isExit(BlockPos pos) {
		return pos.getY() == FLOOR_Y + 1 && pos.getZ() == EXIT_DZ && pos.getX() - roomAt(pos) * SPACING == EXIT_DX;
	}

	// ---- shape ----

	/** Where the island edge runs in the direction of a column: a little wavy, like the End island. */
	private static double edge(int dx, int dz, int radius, int room) {
		double angle = Math.atan2(dz, dx);
		return radius + 0.9 * Math.sin(3 * angle + room * 1.7) + 0.6 * Math.sin(7 * angle + room);
	}

	private static int depth(int dx, int dz, double edge, double dist) {
		long h = (dx * 3129871L) ^ (dz * 116129781L);
		h = h * h * 42317861L + h * 11L;
		double noise = ((h >> 16) & 15) / 15.0;
		return 1 + (int) Math.max(0, (edge - dist) * 0.75 + noise * 1.5);
	}

	// ---- building ----

	/**
	 * Gets the island ready for the first player to step on it: rebuilt when the stored kinds changed since the last
	 * build, so chests of items that ran out disappear and the rest are sorted again.
	 */
	public static void prepare(ServerLevel level, StorageNetwork network) {
		clearOldRooms(level);
		List<ItemStack> kinds = kinds(network);

		if (isBuilt(level, network) && sameLayout(network, kinds)) {
			return;
		}

		clear(level, network);
		layout(network, kinds);
		network.islandBuild++;
		build(level, network);
		StorageData.markDirty();
	}

	/** New kinds stored while players are on the island get a chest right away; the island grows at its edge. */
	public static void grow(ServerLevel level, StorageNetwork network) {
		List<ItemStack> missing = new ArrayList<>();

		for (ItemStack kind : kinds(network)) {
			if (network.island.stream().noneMatch(c -> ItemStack.isSameItemSameComponents(c.key(), kind))) {
				missing.add(kind);
			}
		}

		if (missing.isEmpty()) {
			return;
		}

		for (ItemStack kind : missing) {
			int[] spot = nextSpot(network);
			network.island.add(new IslandChest(spot[0], spot[1], kind));
		}

		build(level, network);
		StorageData.markDirty();
	}

	private static boolean isBuilt(ServerLevel level, StorageNetwork network) {
		return network.islandRadius > 0 && level.getBlockState(centre(network.room)).is(Blocks.BARREL);
	}

	/** Places what is missing: floor, chests, labels, exit and sky. Safe to call again. */
	private static void build(ServerLevel level, StorageNetwork network) {
		BlockPos centre = centre(network.room);
		int radius = chestRadius(network) + 2;
		BlockState top = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
		BlockState under = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.DOWN);
		int reach = radius + 2;

		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				double dist = Math.sqrt(dx * dx + dz * dz);
				double edge = Math.max(edge(dx, dz, radius, network.room), radius - 1);

				if (dist > edge) {
					continue;
				}

				int depth = depth(dx, dz, edge, dist);

				for (int y = 0; y < depth; y++) {
					BlockPos pos = centre.offset(dx, -y, dz);

					if (level.getBlockState(pos).isAir()) {
						level.setBlock(pos, y == 0 ? top : under, Block.UPDATE_CLIENTS);
					}
				}
			}
		}

		for (IslandChest chest : network.island) {
			BlockPos pos = centre.offset(chest.dx(), 1, chest.dz());
			BlockState state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing(chest.dz()));

			if (!level.getBlockState(pos).is(Blocks.CHEST)) {
				level.setBlock(pos, state, Block.UPDATE_CLIENTS);
			}

			spawnDisplay(level, labelId(network, chest), chest.key(), pos.getX() + 0.5, pos.getY() + 1.3, pos.getZ() + 0.5, 0.55f,
					Display.BillboardConstraints.VERTICAL, false);
		}

		level.setBlock(centre.offset(EXIT_DX, 1, EXIT_DZ), Blocks.LODESTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
		network.islandRadius = Math.max(network.islandRadius, radius);
		buildSky(level, network);
	}

	/**
	 * The sky: the End sky of the dimension, with small white and grey chests as stars and one big chest as moon.
	 * They are display entities on a dome around the island.
	 */
	private static void buildSky(ServerLevel level, StorageNetwork network) {
		BlockPos centre = centre(network.room);
		Random random = new Random(network.room * 31L + 7);

		for (int i = 0; i < STARS; i++) {
			double yaw = random.nextDouble() * Math.PI * 2;
			double pitch = Math.toRadians(14 + random.nextDouble() * 70);
			double x = Math.cos(pitch) * Math.cos(yaw) * SKY_RADIUS;
			double y = Math.sin(pitch) * SKY_RADIUS;
			double z = Math.cos(pitch) * Math.sin(yaw) * SKY_RADIUS;
			ItemStack star = new ItemStack(random.nextInt(3) == 0 ? Items.LIGHT_GRAY_SHULKER_BOX : Items.WHITE_SHULKER_BOX);
			float size = 0.8f + random.nextFloat() * 1.4f;
			spawnDisplay(level, skyId(network, "star" + i), star, centre.getX() + 0.5 + x, centre.getY() + y, centre.getZ() + 0.5 + z, size,
					Display.BillboardConstraints.CENTER, true);
		}

		double pitch = Math.toRadians(38);
		spawnDisplay(level, skyId(network, "moon"), new ItemStack(Items.CHEST), centre.getX() + 0.5, centre.getY() + Math.sin(pitch) * SKY_RADIUS,
				centre.getZ() + 0.5 - Math.cos(pitch) * SKY_RADIUS, 22f, Display.BillboardConstraints.CENTER, true);
	}

	private static void spawnDisplay(ServerLevel level, UUID id, ItemStack item, double x, double y, double z, float scale,
			Display.BillboardConstraints billboard, boolean glow) {
		if (level.getEntity(id) != null) {
			return;
		}

		Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		display.setUUID(id);
		display.setPos(x, y, z);
		display.addTag(TAG);
		((ItemDisplayAccessor) display).storagedim$setItemStack(item.copyWithCount(1));
		DisplayAccessor accessor = (DisplayAccessor) display;
		accessor.storagedim$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
		accessor.storagedim$setBillboardConstraints(billboard);

		if (glow) {
			accessor.storagedim$setBrightnessOverride(new Brightness(15, 15));
			accessor.storagedim$setViewRange(6f);
		}

		level.addFreshEntity(display);
	}

	private static UUID labelId(StorageNetwork network, IslandChest chest) {
		return id("label:" + network.room + ":" + network.islandBuild + ":" + chest.dx() + ":" + chest.dz());
	}

	private static UUID skyId(StorageNetwork network, String name) {
		return id("sky:" + network.room + ":" + name);
	}

	private static UUID id(String name) {
		return UUID.nameUUIDFromBytes(("storagedim:" + name).getBytes(StandardCharsets.UTF_8));
	}

	/** Takes the built island down again (blocks and labels), before it is laid out anew. */
	private static void clear(ServerLevel level, StorageNetwork network) {
		if (network.islandRadius <= 0) {
			return;
		}

		BlockPos centre = centre(network.room);
		int reach = network.islandRadius + 3;
		int deepest = (int) (reach * 0.75) + 4;

		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				for (int y = 2; y >= -deepest; y--) {
					BlockPos pos = centre.offset(dx, y, dz);
					BlockState state = level.getBlockState(pos);

					if (state.is(Blocks.BARREL) || state.is(Blocks.CHEST) || state.is(Blocks.LODESTONE)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
					}
				}
			}
		}

		removeStale(level, network, Set.of());
		network.islandRadius = 0;
	}

	/**
	 * Removes labels left over from an older build, which may still sit in chunks saved earlier. Only entities
	 * whose id is in {@code keep} stay.
	 */
	private static void removeStale(ServerLevel level, StorageNetwork network, Set<UUID> keep) {
		BlockPos centre = centre(network.room);
		double reach = network.islandRadius + 4;
		AABB box = new AABB(centre.getX() - reach, centre.getY() - 4, centre.getZ() - reach, centre.getX() + reach + 1, centre.getY() + 4,
				centre.getZ() + reach + 1);

		for (Entity entity : level.getEntities((Entity) null, box, e -> e instanceof Display.ItemDisplay)) {
			if (!keep.contains(entity.getUUID())) {
				entity.discard();
			}
		}
	}

	/** Called every second for islands with players on them. */
	static void tickIsland(ServerLevel level, StorageNetwork network) {
		grow(level, network);
		Set<UUID> keep = new HashSet<>();
		network.island.forEach(chest -> keep.add(labelId(network, chest)));
		removeStale(level, network, keep);
	}

	// ---- players ----

	public static void teleportToSpawn(ServerPlayer player, StorageNetwork network) {
		ServerLevel level = Rooms.level(player.level().getServer());

		if (level == null) {
			StorageDimensionMod.LOGGER.error("Storage-Dimension fehlt, ist das Datapack geladen?");
			return;
		}

		if (playersOn(level, network.room).stream().noneMatch(p -> p != player)) {
			prepare(level, network);
		}

		BlockPos centre = centre(network.room);
		player.resetFallDistance();
		// Look north, along the first rows.
		player.teleportTo(level, centre.getX() + 0.5, centre.getY() + 1, centre.getZ() + 0.5, Set.<Relative>of(), 180f, 0f, true);
	}

	static List<ServerPlayer> playersOn(ServerLevel level, int room) {
		return level.players().stream().filter(p -> roomAt(p.blockPosition()) == room).toList();
	}

	/** True while a player stands on (or just above) their island. */
	static boolean isOnIsland(ServerPlayer player, StorageNetwork network) {
		BlockPos centre = centre(network.room);
		double dx = player.getX() - centre.getX() - 0.5;
		double dz = player.getZ() - centre.getZ() - 0.5;
		double reach = network.islandRadius + 6;
		return network.islandRadius > 0 && dx * dx + dz * dz <= reach * reach && player.getY() > FLOOR_Y - 24 && player.getY() < FLOOR_Y + 64;
	}

	// ---- chests ----

	/** Right click on a block of the storage dimension. Only the chests and the exit do something. */
	public static InteractionResult use(ServerPlayer player, ServerLevel level, BlockPos pos) {
		StorageNetwork network = StorageData.get().networkByRoom(roomAt(pos));

		if (isExit(pos)) {
			StorageEvents.leaveRoom(player);
			return InteractionResult.SUCCESS;
		}

		IslandChest chest = network == null ? null : chestAt(network, pos);

		if (chest == null || !network.canAccess(player.getUUID()) || !level.getBlockState(pos).is(Blocks.CHEST)) {
			return InteractionResult.FAIL;
		}

		setLid(level, pos, true);
		ItemStack key = chest.key();
		StorageMenu.open(player, network, stack -> inGroup(key, stack), key.getHoverName().getString(), () -> setLid(level, pos, false));
		return InteractionResult.SUCCESS;
	}

	private static void setLid(ServerLevel level, BlockPos pos, boolean open) {
		long key = pos.asLong();
		int before = LIDS.getOrDefault(key, 0);
		int now = Math.max(0, before + (open ? 1 : -1));

		if (now == 0) {
			LIDS.remove(key);
		} else {
			LIDS.put(key, now);
		}

		if ((before == 0) == (now == 0)) {
			return;
		}

		level.blockEvent(pos, Blocks.CHEST, 1, now);
		level.playSound(null, pos, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f,
				level.getRandom().nextFloat() * 0.1f + 0.9f);
	}

	// ---- older versions ----

	/** Versions before the island had a small room per network near x = 0; their blocks go, anything left in them is stored. */
	private static void clearOldRooms(ServerLevel level) {
		StorageData data = StorageData.get();

		if (data.oldRoomsCleared) {
			return;
		}

		for (int room = 0; room < data.nextRoom; room++) {
			Rooms.clearOldRoom(level, room, data.networkByRoom(room));
		}

		data.oldRoomsCleared = true;
		StorageData.markDirty();
	}
}
