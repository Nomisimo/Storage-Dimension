package de.nomisimo.storagedim.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.math.Transformation;
import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.mixin.DisplayAccessor;
import de.nomisimo.storagedim.mixin.ItemDisplayAccessor;
import de.nomisimo.storagedim.mixin.ItemFrameAccessor;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Brightness;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The library: every network has its own library in the storage dimension, at x = room * {@link #SPACING}. Players
 * arrive in the entrance hall; from there long aisles in the style of an old library lead north, one per
 * {@link Category} (stone, wood, ores …). The aisle walls are bookshelves, and in them stand plain chests, three high.
 * <p>
 * Every chest shows real stacks of up to 64 (or whatever the item stacks to) of one kind, 27 stacks per chest, so a
 * kind takes as many chests as it needs (up to {@link #MAX_PARTS}). Taking out or putting in changes ME storage. A
 * lectern in the hall and at the end of each aisle opens the catalogue, which teleports to an aisle or to the chests
 * of an item. Project chests stand in the hall.
 * <p>
 * The layout is redone when somebody enters a library nobody is in: chests of items that ran out go, every kind
 * gets as many chests as it now needs. While players are inside, new kinds get chests at the end of their aisle.
 */
public final class Library {
	public static final int SPACING = 10_000;
	public static final int FLOOR_Y = 100;
	/** Tag on every display entity of a library, so stale ones can be found again. */
	public static final String TAG = "storagedim_island";
	/** Tag on the item frames on the chests. */
	static final String FRAME_TAG = "storagedim_chest_frame";
	/** Stacks per chest. */
	public static final int CHEST_SLOTS = 27;
	/** Most chests one kind gets; what goes beyond is still in ME storage, just not in a chest. */
	public static final int MAX_PARTS = 64;

	/** Inside of the hall: z = 0 (where the aisles start) to HALL_DEPTH; the south wall is one further. */
	private static final int HALL_DEPTH = 8;
	private static final int SPAWN_Z = 5;
	private static final int CATALOG_DX = -2;
	private static final int EXIT_DX = 2;
	/** Distance between the middles of two aisles; neighbours share their wall. */
	private static final int AISLE_PITCH = 6;
	/** Every seventh slice of an aisle is a pillar with a lantern instead of chests. */
	private static final int PILLAR_EVERY = 7;
	private static final int MIN_LENGTH = 8;
	private static final int CEILING = 6;
	/** The skylight in the hall ceiling: glass over x -3..3, z 3..5, between the lanterns, so the moon can be seen. */
	private static final int SKYLIGHT_HALF_WIDTH = 3;
	private static final int SKYLIGHT_MIN_Z = 3;
	private static final int SKYLIGHT_MAX_Z = 5;
	/** The chest moon: this far from the hall (close enough not to vanish in the fog), this high over the horizon (north), this big. */
	private static final double MOON_DISTANCE = 40;
	private static final double MOON_PITCH = 70;
	private static final float MOON_SCALE = 11f;
	/** Item model of the moon: a flat chest front, drawn like the sun instead of as a block. */
	private static final Identifier MOON_MODEL = StorageDimensionMod.id("sky_moon");

	/** One chest: its block relative to the library centre, the kind it holds (count 1) and which of the kind's chests it is. */
	public record Shelf(int dx, int dy, int dz, ItemStack key, int part, Direction facing) {
	}

	/** One aisle: its category, the x of its middle relative to the centre, and how many chest places are used. */
	public static final class Aisle {
		public final Category category;
		public final int x;
		public int used;

		public Aisle(Category category, int x, int used) {
			this.category = category;
			this.x = x;
			this.used = used;
		}

		/** Slices from the hall to the lectern at the end. */
		public int length() {
			return used == 0 ? MIN_LENGTH : Math.max(MIN_LENGTH, slice(used - 1) + 2);
		}
	}

	/** The departments of the library, in aisle order from west to east. */
	public enum Category {
		STONE("Stein", Items.STONE_BRICKS),
		WOOD("Holz", Items.OAK_LOG),
		NATURE("Natur", Items.GRASS_BLOCK),
		ORES("Erze & Metalle", Items.DIAMOND),
		FOOD("Essen", Items.BREAD),
		MOBS("Mob-Drops", Items.BONE),
		REDSTONE("Redstone", Items.REDSTONE),
		TOOLS("Werkzeuge & Waffen", Items.IRON_PICKAXE),
		ARMOR("Rüstung", Items.IRON_CHESTPLATE),
		MAGIC("Tränke & Magie", Items.ENCHANTED_BOOK),
		DECOR("Farben & Deko", Items.RED_WOOL),
		MISC("Sonstiges", Items.CHEST);

		public final String title;
		public final Item icon;

		Category(String title, Item icon) {
			this.title = title;
			this.icon = icon;
		}
	}

	/** Players looking into a chest, per chest position, to keep its lid open. */
	private static final Map<Long, Integer> LIDS = new HashMap<>();

	private Library() {
	}

	// ---- item kinds ----

	/** The kind an item is stored under: tools and other damageable items share one kind per item, everything else needs the exact components. */
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

	/** The kinds of items the network holds right now, sorted. */
	static List<ItemStack> kinds(StorageNetwork network) {
		Map<Integer, List<ItemStack>> buckets = new HashMap<>();
		List<ItemStack> kinds = new ArrayList<>();

		for (StorageNetwork.StoredItem entry : network.entries()) {
			// what projects block lies in their chests
			if (entry.count <= 0 || Projects.available(network, entry, null) <= 0) {
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

	/** The project chests the hall needs. */
	static List<ItemStack> projects(StorageNetwork network) {
		List<ItemStack> list = new ArrayList<>();
		Projects.of(network).forEach(project -> list.add(Projects.islandKey(project)));
		return list;
	}

	public static boolean isProject(ItemStack key) {
		return key.is(Items.WRITABLE_BOOK) && Projects.idOf(key) != null;
	}

	/** Which aisle an item belongs in, guessed from its id and components so it also works for items of other mods. */
	public static Category category(ItemStack stack) {
		String p = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();

		if (has(p, "spawn_egg")) {
			return Category.MOBS;
		}

		if (stack.has(DataComponents.POTION_CONTENTS) || stack.has(DataComponents.STORED_ENCHANTMENTS)
				|| has(p, "enchanted_book", "experience_bottle", "brewing_stand", "cauldron", "nether_wart", "blaze_powder", "glistering",
						"fermented_spider_eye", "magma_cream", "dragon_breath", "totem", "enchanting_table", "writable_book", "written_book", "ominous_bottle")
				|| p.equals("book")) {
			return Category.MAGIC;
		}

		if (stack.isDamageableItem() && stack.has(DataComponents.EQUIPPABLE)
				|| has(p, "helmet", "chestplate", "leggings", "boots", "horse_armor", "elytra", "shield", "harness")) {
			return Category.ARMOR;
		}

		if (stack.isDamageableItem() || stack.has(DataComponents.TOOL)) {
			return Category.TOOLS;
		}

		if (has(p, "redstone", "repeater", "comparator", "piston", "observer", "hopper", "dropper", "dispenser", "rail", "lever", "button",
				"pressure_plate", "tripwire", "daylight_detector", "target", "lamp", "tnt", "minecart", "note_block", "crafter", "sculk_sensor",
				"lightning_rod", "trapped_chest", "jukebox", "bell")) {
			return Category.REDSTONE;
		}

		if (stack.has(DataComponents.FOOD) && !has(p, "rotten_flesh", "spider_eye")
				|| has(p, "wheat", "sugar", "cocoa", "cake", "milk", "honey_bottle")) {
			return Category.FOOD;
		}

		if (has(p, "bone", "string", "spider_eye", "gunpowder", "ender_pearl", "ender_eye", "blaze_rod", "slime", "leather", "feather",
				"ink_sac", "phantom", "ghast", "shulker_shell", "prismarine_shard", "prismarine_crystals", "rabbit", "scute", "honeycomb",
				"breeze_rod", "heavy_core", "nautilus", "heart_of_the_sea", "echo_shard", "turtle", "_head", "skull", "nether_star", "wind_charge",
				"rotten_flesh", "egg")) {
			return Category.MOBS;
		}

		if (has(p, "_ore", "raw_", "ingot", "nugget", "diamond", "emerald", "lapis", "coal", "copper", "iron", "gold", "netherite",
				"amethyst", "quartz", "ancient_debris", "resin")) {
			return Category.ORES;
		}

		if (has(p, "wool", "carpet", "glass", "concrete", "terracotta", "_dye", "banner", "_bed", "candle", "shulker_box", "painting",
				"item_frame", "flower_pot", "lantern", "torch", "pottery_sherd", "armor_stand")) {
			return Category.DECOR;
		}

		if (has(p, "log", "wood", "planks", "stem", "hyphae", "bamboo", "stick", "oak", "spruce", "birch", "jungle", "acacia", "mangrove",
				"cherry", "crimson", "warped", "chest", "barrel", "bookshelf", "crafting_table", "ladder", "sign", "fence", "boat", "scaffolding")) {
			return Category.WOOD;
		}

		if (has(p, "stone", "cobble", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "basalt", "blackstone", "brick",
				"prismarine", "purpur", "netherrack", "obsidian", "dripstone", "sandstone", "end_stone", "furnace", "smoker", "anvil", "slab",
				"stairs", "wall")) {
			return Category.STONE;
		}

		if (has(p, "dirt", "grass", "sand", "gravel", "clay", "mud", "moss", "snow", "ice", "leaves", "sapling", "seeds", "flower", "mushroom",
				"vine", "kelp", "coral", "cactus", "lily", "fern", "bush", "tulip", "rose", "daisy", "orchid", "allium", "bluet", "poppy",
				"dandelion", "sugar_cane", "pumpkin", "melon", "hay", "sponge", "root", "propagule", "azalea", "spore", "petals", "lichen",
				"sea_pickle", "seagrass", "cobweb", "podzol", "mycelium", "farmland", "nylium", "wart_block", "shroomlight", "frogspawn")) {
			return Category.NATURE;
		}

		return Category.MISC;
	}

	private static boolean has(String path, String... parts) {
		for (String part : parts) {
			if (path.contains(part)) {
				return true;
			}
		}

		return false;
	}

	/** How many chests a kind needs right now: one per 27 stacks of what can be taken out, at least one. */
	static int partsFor(StorageNetwork network, ItemStack key) {
		long stacks = 0;
		int max = Math.max(1, key.getMaxStackSize());

		for (StorageNetwork.StoredItem entry : network.entries()) {
			if (inGroup(key, entry.prototype)) {
				stacks += (Projects.available(network, entry, null) + max - 1) / max;
			}
		}

		return (int) Math.max(1, Math.min(MAX_PARTS, (stacks + CHEST_SLOTS - 1) / CHEST_SLOTS));
	}

	// ---- geometry ----

	public static BlockPos centre(int room) {
		return new BlockPos(room * SPACING, FLOOR_Y, 0);
	}

	/** The library (room index) a position in the storage dimension belongs to. */
	public static int roomAt(BlockPos pos) {
		return Math.floorDiv(pos.getX() + SPACING / 2, SPACING);
	}

	private static boolean isPillar(int slice) {
		return slice % PILLAR_EVERY == 0;
	}

	/** The slice of the n-th group of six chest places (the pillar slices are skipped). */
	private static int chestSlice(int group) {
		return group + group / (PILLAR_EVERY - 1) + 1;
	}

	/** The slice that chest place n of an aisle is in. */
	private static int slice(int place) {
		return chestSlice(place / 6);
	}

	private static int sliceZ(int slice) {
		return -1 - slice;
	}

	/** Chest place n of an aisle: left wall bottom to top, then right wall, slice by slice going north. */
	private static Shelf place(Aisle aisle, int place, ItemStack key, int part) {
		int j = place % 6;
		boolean left = j < 3;
		return new Shelf(aisle.x + (left ? -2 : 2), 1 + j % 3, sliceZ(slice(place)), key, part, left ? Direction.EAST : Direction.WEST);
	}

	private static int aisleX(int index, int count) {
		return index * AISLE_PITCH - (count - 1) * AISLE_PITCH / 2;
	}

	private static int hallMinX(StorageNetwork network) {
		int min = -5;

		for (Aisle aisle : network.aisles) {
			min = Math.min(min, aisle.x - 3);
		}

		return min;
	}

	private static int hallMaxX(StorageNetwork network) {
		int max = 5;

		for (Aisle aisle : network.aisles) {
			max = Math.max(max, aisle.x + 3);
		}

		return max;
	}

	private static int deepestZ(StorageNetwork network) {
		int z = -1;

		for (Aisle aisle : network.aisles) {
			z = Math.min(z, sliceZ(aisle.length()));
		}

		return z;
	}

	public static Aisle aisle(StorageNetwork network, Category category) {
		for (Aisle aisle : network.aisles) {
			if (aisle.category == category) {
				return aisle;
			}
		}

		return null;
	}

	public static Shelf shelfAt(StorageNetwork network, BlockPos pos) {
		BlockPos centre = centre(network.room);
		int dx = pos.getX() - centre.getX();
		int dy = pos.getY() - centre.getY();
		int dz = pos.getZ() - centre.getZ();

		for (Shelf shelf : network.library) {
			if (shelf.dx() == dx && shelf.dy() == dy && shelf.dz() == dz) {
				return shelf;
			}
		}

		return null;
	}

	public static boolean isExit(BlockPos pos) {
		int dx = pos.getX() - roomAt(pos) * SPACING;
		return pos.getY() == FLOOR_Y + 1 && pos.getZ() == SPAWN_Z && dx == EXIT_DX;
	}

	// ---- layout ----

	/** Lays out the whole library anew: one aisle per category that has items, every kind with as many chests as it needs. */
	private static void layout(StorageNetwork network) {
		Map<Category, List<ItemStack>> byCategory = new EnumMap<>(Category.class);

		for (ItemStack kind : kinds(network)) {
			byCategory.computeIfAbsent(category(kind), c -> new ArrayList<>()).add(kind);
		}

		network.library.clear();
		network.aisles.clear();
		int index = 0;

		for (Map.Entry<Category, List<ItemStack>> entry : byCategory.entrySet()) {
			Aisle aisle = new Aisle(entry.getKey(), aisleX(index++, byCategory.size()), 0);
			network.aisles.add(aisle);

			for (ItemStack kind : entry.getValue()) {
				addKind(network, aisle, kind);
			}
		}

		List<ItemStack> projects = projects(network);

		for (int i = 0; i < projects.size(); i++) {
			network.library.add(projectShelf(network, i, projects.get(i)));
		}
	}

	private static void addKind(StorageNetwork network, Aisle aisle, ItemStack kind) {
		int parts = partsFor(network, kind);

		for (int part = 0; part < parts; part++) {
			network.library.add(place(aisle, aisle.used++, kind, part));
		}
	}

	/** Project chests stand along the south wall of the hall, from the middle outwards, in two rows if needed. */
	private static Shelf projectShelf(StorageNetwork network, int index, ItemStack key) {
		int width = Math.max(1, (hallMaxX(network) - hallMinX(network) - 2) / 2);
		int row = index / width;
		int col = index % width;
		int dx = (col % 2 == 0 ? 1 : -1) * ((col + 1) / 2) * 2;
		return new Shelf(dx, 1, HALL_DEPTH - row, key, 0, Direction.NORTH);
	}

	/** True when the stored kinds and the chests they need are the same as in the built library. */
	private static boolean sameLayout(StorageNetwork network) {
		List<Shelf> before = new ArrayList<>(network.library);
		List<Aisle> aislesBefore = new ArrayList<>(network.aisles);
		layout(network);
		boolean same = before.size() == network.library.size() && aislesBefore.size() == network.aisles.size();

		for (int i = 0; same && i < before.size(); i++) {
			Shelf a = before.get(i);
			Shelf b = network.library.get(i);
			same = a.dx() == b.dx() && a.dy() == b.dy() && a.dz() == b.dz() && a.part() == b.part()
					&& ItemStack.isSameItemSameComponents(a.key(), b.key());
		}

		if (same) {
			// keep the old objects, nothing changes
			network.library.clear();
			network.library.addAll(before);
			network.aisles.clear();
			network.aisles.addAll(aislesBefore);
		}

		return same;
	}

	// ---- building ----

	/**
	 * Gets the library ready for the first player to step in: laid out anew when the stored kinds changed since the
	 * last build, so chests of items that ran out disappear and every kind gets the chests it needs.
	 */
	public static void prepare(ServerLevel level, StorageNetwork network) {
		clearOldRooms(level);
		clearOldIsland(level, network);
		boolean built = isBuilt(level, network);
		Box old = built ? box(network) : null;

		if (built && sameLayout(network)) {
			build(level, network, null);
			return;
		}

		if (!built) {
			layout(network);
		}

		network.libraryBuild++;
		build(level, network, old);
		removeStale(level, network, keep(network));
		StorageData.markDirty();
	}

	/** New kinds stored while players are inside get chests right away, at the end of their aisle. */
	public static void grow(ServerLevel level, StorageNetwork network) {
		boolean changed = false;

		for (ItemStack kind : kinds(network)) {
			if (network.library.stream().anyMatch(s -> ItemStack.isSameItemSameComponents(s.key(), kind))) {
				continue;
			}

			Category category = category(kind);
			Aisle aisle = aisle(network, category);

			if (aisle == null) {
				int x = network.aisles.isEmpty() ? 0 : network.aisles.stream().mapToInt(a -> a.x).max().getAsInt() + AISLE_PITCH;
				aisle = new Aisle(category, x, 0);
				network.aisles.add(aisle);
			}

			addKind(network, aisle, kind);
			changed = true;
		}

		List<ItemStack> projects = projects(network);
		int count = (int) network.library.stream().filter(s -> isProject(s.key())).count();

		for (ItemStack project : projects) {
			if (network.library.stream().noneMatch(s -> ItemStack.isSameItemSameComponents(s.key(), project))) {
				network.library.add(projectShelf(network, count++, project));
				changed = true;
			}
		}

		if (changed) {
			build(level, network, null);
			StorageData.markDirty();
		}
	}

	private static boolean isBuilt(ServerLevel level, StorageNetwork network) {
		return network.libraryBuild > 0 && level.getBlockState(centre(network.room)).is(Blocks.SPRUCE_PLANKS);
	}

	private record Box(int minX, int maxX, int minZ, int maxZ) {
	}

	private static Box box(StorageNetwork network) {
		return new Box(hallMinX(network), hallMaxX(network), deepestZ(network), HALL_DEPTH + 1);
	}

	/**
	 * Places every block of the library that is not there yet. With {@code old}, blocks of an earlier layout within
	 * that box that are no longer part of the library are removed first.
	 */
	private static void build(ServerLevel level, StorageNetwork network, Box old) {
		BlockPos centre = centre(network.room);
		LibraryExtras.State extras = LibraryExtras.state(level, network);
		Map<BlockPos, BlockState> plan = plan(network, extras);

		if (old != null) {
			for (int dx = old.minX(); dx <= old.maxX(); dx++) {
				for (int dz = old.minZ(); dz <= old.maxZ(); dz++) {
					for (int dy = 0; dy <= CEILING; dy++) {
						BlockPos rel = new BlockPos(dx, dy, dz);

						if (!plan.containsKey(rel)) {
							BlockPos pos = centre.offset(rel);

							if (!level.getBlockState(pos).isAir()) {
								level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
							}
						}
					}
				}
			}
		}

		for (Map.Entry<BlockPos, BlockState> entry : plan.entrySet()) {
			BlockPos pos = centre.offset(entry.getKey());

			if (!level.getBlockState(pos).equals(entry.getValue())) {
				level.setBlock(pos, entry.getValue(), Block.UPDATE_CLIENTS);
			}
		}

		for (Shelf shelf : network.library) {
			BlockPos pos = centre.offset(shelf.dx(), shelf.dy(), shelf.dz());

			if (isProject(shelf.key())) {
				spawnDisplay(level, labelId(network, shelf), shelf.key(), pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 0.4f);
				nameLabel(level, network, shelf);
			} else {
				spawnFrame(level, frameId(network, shelf), shelf.key(), pos, shelf.facing());
			}
		}

		for (Aisle aisle : network.aisles) {
			Entity sign = spawnDisplay(level, aisleId(network, aisle), new ItemStack(aisle.category.icon), centre.getX() + aisle.x + 0.5,
					centre.getY() + 4.3, centre.getZ() - 0.5, 0.7f);
			aisleLabel(network, aisle, sign);
		}

		if (network.tradeHallRows != extras.rows()) {
			network.tradeHallRows = extras.rows();
			StorageData.markDirty();
		}

		LibraryExtras.sync(level, network, extras);

		moon(level, network);
	}

	/**
	 * The chest moon in the End sky over the library, seen through the skylight of the hall. Like the sun of the
	 * overworld it is flat and always turns its face, the front of a chest, towards whoever looks at it.
	 */
	private static void moon(ServerLevel level, StorageNetwork network) {
		BlockPos centre = centre(network.room);
		double pitch = Math.toRadians(MOON_PITCH);
		ItemStack moon = new ItemStack(Items.CHEST);
		moon.set(DataComponents.ITEM_MODEL, MOON_MODEL);
		Entity display = spawnDisplay(level, moonId(network), moon, centre.getX() + 0.5, centre.getY() + Math.sin(pitch) * MOON_DISTANCE,
				centre.getZ() + (SKYLIGHT_MIN_Z + SKYLIGHT_MAX_Z) / 2.0 - Math.cos(pitch) * MOON_DISTANCE, MOON_SCALE,
				Display.BillboardConstraints.CENTER);
		// draw it even with a small entity distance setting
		((DisplayAccessor) display).storagedim$setViewRange(4f);
	}

	/** Every block of the library relative to its centre. */
	private static Map<BlockPos, BlockState> plan(StorageNetwork network, LibraryExtras.State extras) {
		Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
		BlockState floor = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		BlockState hallFloor = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState ceiling = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		BlockState glass = Blocks.GLASS.defaultBlockState();
		BlockState shelf = Blocks.BOOKSHELF.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState pillar = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
		BlockState lantern = Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true);
		BlockState carpet = Blocks.RED_CARPET.defaultBlockState();
		int minX = hallMinX(network);
		int maxX = hallMaxX(network);

		// the hall
		for (int dx = minX; dx <= maxX; dx++) {
			boolean inAisle = false;

			for (Aisle aisle : network.aisles) {
				inAisle |= Math.abs(dx - aisle.x) <= 3;
			}

			for (int dz = inAisle ? 0 : -1; dz <= HALL_DEPTH + 1; dz++) {
				boolean wall = dx == minX || dx == maxX || dz == -1 || dz == HALL_DEPTH + 1;
				plan.put(new BlockPos(dx, 0, dz), hallFloor);
				boolean skylight = Math.abs(dx) <= SKYLIGHT_HALF_WIDTH && dz >= SKYLIGHT_MIN_Z && dz <= SKYLIGHT_MAX_Z;
				plan.put(new BlockPos(dx, CEILING, dz), skylight ? glass : ceiling);

				for (int dy = 1; dy < CEILING; dy++) {
					plan.put(new BlockPos(dx, dy, dz), wall ? shelf : air);
				}

				if (!wall && Math.floorMod(dx, 4) == 0 && (dz == 2 || dz == 6)) {
					plan.put(new BlockPos(dx, CEILING - 1, dz), lantern);
				}
			}
		}

		for (int dz = 1; dz < HALL_DEPTH; dz++) {
			plan.put(new BlockPos(0, 1, dz), carpet);
		}

		plan.put(new BlockPos(CATALOG_DX, 1, SPAWN_Z), Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.EAST));
		plan.put(new BlockPos(EXIT_DX, 1, SPAWN_Z), Blocks.LODESTONE.defaultBlockState());

		// the aisles
		for (Aisle aisle : network.aisles) {
			int length = aisle.length();

			for (int slice = 0; slice <= length; slice++) {
				int dz = sliceZ(slice);
				boolean end = slice == length;

				for (int dx = aisle.x - 3; dx <= aisle.x + 3; dx++) {
					int side = Math.abs(dx - aisle.x);
					plan.put(new BlockPos(dx, 0, dz), floor);
					plan.put(new BlockPos(dx, CEILING, dz), ceiling);

					for (int dy = 1; dy < CEILING; dy++) {
						BlockState state;

						if (end || side == 3) {
							state = shelf;
						} else if (side == 2) {
							state = isPillar(slice) ? pillar : shelf;
						} else {
							state = air;
						}

						plan.put(new BlockPos(dx, dy, dz), state);
					}
				}

				if (!end) {
					plan.put(new BlockPos(aisle.x, 1, dz), slice == length - 1
							? Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.SOUTH) : carpet);

					if (isPillar(slice)) {
						plan.put(new BlockPos(aisle.x, CEILING - 1, dz), lantern);
					}
				}
			}
		}

		// the chests
		for (Shelf s : network.library) {
			Block block = isProject(s.key()) ? Blocks.TRAPPED_CHEST : Blocks.CHEST;
			plan.put(new BlockPos(s.dx(), s.dy(), s.dz()), block.defaultBlockState().setValue(ChestBlock.FACING, s.facing()));
		}

		// the dragon egg altar and the trading hall, while their items are stored
		LibraryExtras.plan(plan, network, extras);
		return plan;
	}

	static Entity spawnDisplay(ServerLevel level, UUID id, ItemStack item, double x, double y, double z, float scale) {
		return spawnDisplay(level, id, item, x, y, z, scale, Display.BillboardConstraints.VERTICAL);
	}

	private static Entity spawnDisplay(ServerLevel level, UUID id, ItemStack item, double x, double y, double z, float scale,
			Display.BillboardConstraints billboard) {
		Entity existing = level.getEntity(id);

		if (existing != null) {
			return existing;
		}

		Display.ItemDisplay display = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
		display.setUUID(id);
		display.setPos(x, y, z);
		display.addTag(TAG);
		((ItemDisplayAccessor) display).storagedim$setItemStack(item.copyWithCount(1));
		DisplayAccessor accessor = (DisplayAccessor) display;
		accessor.storagedim$setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
		accessor.storagedim$setBillboardConstraints(billboard);
		accessor.storagedim$setBrightnessOverride(new Brightness(15, 15));
		level.addFreshEntity(display);
		return display;
	}

	/**
	 * A glowing item frame on the front of a chest that shows what the chest holds. It is fixed: nobody can take the
	 * item out, turn it or knock the frame off, and a click on it opens the chest behind it.
	 */
	private static Entity spawnFrame(ServerLevel level, UUID id, ItemStack item, BlockPos chest, Direction facing) {
		Entity existing = level.getEntity(id);

		if (existing != null) {
			return existing;
		}

		ItemFrame frame = new GlowItemFrame(level, chest.relative(facing), facing);
		frame.setUUID(id);
		frame.addTag(TAG);
		frame.addTag(FRAME_TAG);
		frame.setItem(item.copyWithCount(1), false);
		((ItemFrameAccessor) frame).storagedim$setFixed(true);
		frame.setInvulnerable(true);
		frame.setSilent(true);
		level.addFreshEntity(frame);
		return frame;
	}

	/** True for the item frames on the library chests. Only works on the server, tags are not sent to clients. */
	static boolean isChestFrame(Entity entity) {
		return entity instanceof ItemFrame && entity.entityTags().contains(FRAME_TAG);
	}

	/** Clicks on a chest frame open the chest behind it; hitting it does nothing. */
	public static void register() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() || !isChestFrame(entity)) {
				return InteractionResult.PASS;
			}

			// a click sends two packets, the one with the hit point opens the chest; the other may not turn the frame
			if (hit == null || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.FAIL;
			}

			BlockPos chest = entity.blockPosition().relative(entity.getDirection().getOpposite());
			return use(serverPlayer, (ServerLevel) level, chest);
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> !level.isClientSide() && isChestFrame(entity)
				? InteractionResult.FAIL : InteractionResult.PASS);
	}

	/** The sign over an aisle shows its name and how many kinds it holds. */
	private static void aisleLabel(StorageNetwork network, Aisle aisle, Entity sign) {
		if (sign == null) {
			return;
		}

		long kinds = network.library.stream().filter(s -> s.part() == 0 && !isProject(s.key()) && Math.abs(s.dx() - aisle.x) == 2).count();
		String name = aisle.category.title + " · " + kinds + (kinds == 1 ? " Sorte" : " Sorten");

		if (sign.getCustomName() == null || !sign.getCustomName().getString().equals(name)) {
			sign.setCustomName(Component.literal(name).withStyle(ChatFormatting.GOLD));
			sign.setCustomNameVisible(true);
		}
	}

	/** The book over a project chest shows the project's name. */
	private static void nameLabel(ServerLevel level, StorageNetwork network, Shelf shelf) {
		Projects.Project project = Projects.get(Projects.idOf(shelf.key()));
		Entity label = level.getEntity(labelId(network, shelf));

		if (project == null || label == null) {
			return;
		}

		String name = "Projekt: " + project.name;

		if (label.getCustomName() == null || !label.getCustomName().getString().equals(name)) {
			label.setCustomName(Component.literal(name).withStyle(ChatFormatting.AQUA));
			label.setCustomNameVisible(true);
		}
	}

	private static UUID labelId(StorageNetwork network, Shelf shelf) {
		return id("shelf:" + network.room + ":" + network.libraryBuild + ":" + shelf.dx() + ":" + shelf.dy() + ":" + shelf.dz());
	}

	/** A new kind of id for the frames, so the floating items of older versions go as stale displays. */
	private static UUID frameId(StorageNetwork network, Shelf shelf) {
		return id("frame:" + network.room + ":" + network.libraryBuild + ":" + shelf.dx() + ":" + shelf.dy() + ":" + shelf.dz());
	}

	private static UUID aisleId(StorageNetwork network, Aisle aisle) {
		return id("aisle:" + network.room + ":" + network.libraryBuild + ":" + aisle.category.name() + ":" + aisle.x);
	}

	/** A new id for the flat moon, so the block-shaped moon of the old chest island goes as a stale display. */
	public static UUID moonId(StorageNetwork network) {
		return id("moon:" + network.room + ":flat");
	}

	static UUID id(String name) {
		return UUID.nameUUIDFromBytes(("storagedim:" + name).getBytes(StandardCharsets.UTF_8));
	}

	private static Set<UUID> keep(StorageNetwork network) {
		Set<UUID> keep = new HashSet<>();
		network.library.forEach(shelf -> keep.add(isProject(shelf.key()) ? labelId(network, shelf) : frameId(network, shelf)));
		network.aisles.forEach(aisle -> keep.add(aisleId(network, aisle)));
		// the extras remove these themselves when their items are gone
		keep.add(LibraryExtras.eggId(network));
		keep.add(LibraryExtras.doorId(network));
		keep.add(moonId(network));
		return keep;
	}

	/**
	 * Removes display entities of an older build (and the stars of the old chest island), which may still sit in
	 * chunks saved earlier. Only entities whose id is in {@code keep} stay.
	 */
	private static void removeStale(ServerLevel level, StorageNetwork network, Set<UUID> keep) {
		BlockPos centre = centre(network.room);
		double reach = Math.max(100, Math.max(hallMaxX(network), -hallMinX(network)) + 4);
		double depth = Math.max(100, -deepestZ(network) + 4);
		AABB box = new AABB(centre.getX() - reach, centre.getY() - 30, centre.getZ() - depth, centre.getX() + reach + 1, centre.getY() + 100,
				centre.getZ() + 100);

		for (Entity entity : level.getEntities((Entity) null, box, e -> e instanceof Display.ItemDisplay || isChestFrame(e))) {
			if (!keep.contains(entity.getUUID())) {
				entity.discard();
			}
		}
	}

	/** Called every second for libraries with players in them. */
	static void tick(ServerLevel level, StorageNetwork network) {
		grow(level, network);
		removeStale(level, network, keep(network));
		LibraryExtras.State extras = LibraryExtras.state(level, network);

		if (LibraryExtras.isBuilt(level, network, extras)) {
			LibraryExtras.sync(level, network, extras);
		} else {
			// the egg or a villager came or went: the altar or the trading hall changes
			build(level, network, null);
		}

		for (Shelf shelf : network.library) {
			if (isProject(shelf.key())) {
				nameLabel(level, network, shelf);
			}
		}

		for (Aisle aisle : network.aisles) {
			aisleLabel(network, aisle, level.getEntity(aisleId(network, aisle)));
		}

		ShelfMenu.refreshAll(network);
	}

	// ---- players ----

	public static void teleportToSpawn(ServerPlayer player, StorageNetwork network) {
		ServerLevel level = Rooms.level(player.level().getServer());

		if (level == null) {
			StorageDimensionMod.LOGGER.error("Storage-Dimension fehlt, ist das Datapack geladen?");
			return;
		}

		if (playersIn(level, network.room).stream().noneMatch(p -> p != player)) {
			prepare(level, network);
		}

		BlockPos centre = centre(network.room);
		player.resetFallDistance();
		// Look north, towards the aisles.
		player.teleportTo(level, centre.getX() + 0.5, centre.getY() + 1, centre.getZ() + SPAWN_Z + 0.5, Set.<Relative>of(), 180f, 0f, true);
	}

	/** To the start of an aisle, looking down it. */
	public static void teleportToAisle(ServerPlayer player, StorageNetwork network, Aisle aisle) {
		BlockPos centre = centre(network.room);
		player.resetFallDistance();
		player.teleportTo((ServerLevel) player.level(), centre.getX() + aisle.x + 0.5, centre.getY() + 1, centre.getZ() - 0.5, Set.<Relative>of(),
				180f, 0f, true);
	}

	/** In front of the first chest of a kind, looking at it. */
	public static void teleportToShelf(ServerPlayer player, StorageNetwork network, Shelf shelf) {
		BlockPos centre = centre(network.room);
		// stand in the middle of the aisle, two blocks in front of the chest
		double x = centre.getX() + shelf.dx() + 0.5 + shelf.facing().getStepX() * 2;
		double z = centre.getZ() + shelf.dz() + 0.5 + shelf.facing().getStepZ() * 2;
		float yaw = shelf.facing() == Direction.EAST ? 90f : shelf.facing() == Direction.WEST ? -90f : 0f;
		player.resetFallDistance();
		player.teleportTo((ServerLevel) player.level(), x, centre.getY() + 1, z, Set.<Relative>of(), yaw, 15f, true);
		long parts = network.library.stream().filter(s -> ItemStack.isSameItemSameComponents(s.key(), shelf.key())).count();
		player.connection.send(new ClientboundSetActionBarTextPacket(Component.empty().append(shelf.key().getHoverName())
				.append(": " + parts + (parts == 1 ? " Kiste" : " Kisten")).withStyle(ChatFormatting.GOLD)));
	}

	static List<ServerPlayer> playersIn(ServerLevel level, int room) {
		return level.players().stream().filter(p -> roomAt(p.blockPosition()) == room).toList();
	}

	/** True while a player is inside their library. */
	static boolean isInside(ServerPlayer player, StorageNetwork network) {
		if (network.libraryBuild <= 0) {
			return false;
		}

		BlockPos centre = centre(network.room);
		double dx = player.getX() - centre.getX();
		double dz = player.getZ() - centre.getZ();
		return dx >= hallMinX(network) - 3 && dx <= hallMaxX(network) + 4 && dz >= deepestZ(network) - 3
				&& dz <= Math.max(HALL_DEPTH + 5, LibraryExtras.southEnd(network) + 4)
				&& player.getY() > FLOOR_Y - 24 && player.getY() < FLOOR_Y + 64;
	}

	// ---- chests ----

	/** Right click on a block of the storage dimension. Only the chests, the lecterns, the trading hall's gate and the exit do something. */
	public static InteractionResult use(ServerPlayer player, ServerLevel level, BlockPos pos) {
		StorageNetwork network = StorageData.get().networkByRoom(roomAt(pos));

		if (isExit(pos)) {
			StorageEvents.leaveRoom(player);
			return InteractionResult.SUCCESS;
		}

		if (network == null || !network.canAccess(player.getUUID())) {
			return InteractionResult.FAIL;
		}

		boolean tradeHall = LibraryExtras.inTradeHall(network, pos.subtract(centre(network.room)));

		if (tradeHall && level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
			// the door of the trading hall
			return InteractionResult.PASS;
		}

		// the lecterns of the librarians in the trading hall are only workstations
		if (level.getBlockState(pos).is(Blocks.LECTERN) && !tradeHall) {
			CatalogMenu.open(player, network);
			return InteractionResult.SUCCESS;
		}

		Shelf shelf = shelfAt(network, pos);

		if (shelf == null || !level.getBlockState(pos).is(Blocks.CHEST) && !level.getBlockState(pos).is(Blocks.TRAPPED_CHEST)) {
			return InteractionResult.FAIL;
		}

		ItemStack key = shelf.key();

		if (isProject(key)) {
			Projects.Project project = Projects.get(Projects.idOf(key));
			return project == null ? InteractionResult.FAIL : StorageEvents.useProject(player, project);
		}

		setLid(level, pos, true);
		ShelfMenu.open(player, network, shelf, () -> setLid(level, pos, false));
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

		level.blockEvent(pos, level.getBlockState(pos).getBlock(), 1, now);
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

	/** Versions up to 0.7 had a round chest island made of barrels instead of the library; it goes before the library is built. */
	private static void clearOldIsland(ServerLevel level, StorageNetwork network) {
		if (network.oldIslandRadius <= 0) {
			return;
		}

		BlockPos centre = centre(network.room);
		int reach = network.oldIslandRadius + 3;
		int deepest = (int) (reach * 0.75) + 4;

		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				for (int y = 2; y >= -deepest; y--) {
					BlockPos pos = centre.offset(dx, y, dz);
					BlockState state = level.getBlockState(pos);

					if (state.is(Blocks.BARREL) || state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.LODESTONE)) {
						level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
					}
				}
			}
		}

		network.oldIslandRadius = 0;
		network.libraryBuild = 0;
		StorageData.markDirty();
	}
}
