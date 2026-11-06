package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.component.CustomData;

/**
 * Project chests: a filter chest with a book, holding a building project. It lists any number of items with the
 * amount the project needs. The chest shows those items from ME storage, and with "im ME blocken" on, the needed
 * amounts are kept for the project: everywhere else only what goes beyond them can be taken out.
 * <p>
 * Projects belong to the network of the player who placed the chest and also stand in the entrance hall of the
 * library. Their blocked items lie only there, not in the library's normal chests.
 */
public final class Projects {
	/** Custom data key of a project chest item, and of the island chest key, with the project id. */
	public static final String ID_KEY = "storagedim_project";
	public static final int MAX_NAME = 32;

	private Projects() {
	}

	public static final class Project {
		public final UUID id;
		/** The player who placed the chest first; the project uses their network. */
		public final UUID owner;
		public String name;
		public boolean block;
		public final List<Entry> entries = new ArrayList<>();

		public Project(UUID id, UUID owner, String name, boolean block) {
			this.id = id;
			this.owner = owner;
			this.name = name;
			this.block = block;
		}

		public StorageNetwork network() {
			return StorageData.get().networkFor(owner);
		}

		/** The entry for this item, or null. */
		public Entry entryFor(ItemStack stack) {
			for (Entry entry : entries) {
				if (entry.matches(stack)) {
					return entry;
				}
			}

			return null;
		}

		public boolean wants(ItemStack stack) {
			return entryFor(stack) != null;
		}

		/** Adds the item with this amount, or adds the amount to its entry. Returns the entry. */
		public Entry add(ItemStack stack, long amount) {
			Entry entry = entryFor(stack);

			if (entry == null) {
				entry = new Entry(FilterChests.matchKey(stack), 0);
				entries.add(entry);
			}

			entry.target = Math.max(1, entry.target + amount);
			StorageData.markDirty();
			StorageMenu.refreshAll(network());
			return entry;
		}
	}

	/** One item of a project and the amount it needs. */
	public static final class Entry {
		/** Count 1; matched like a filter item, see {@link FilterChests#matches}. */
		public final ItemStack item;
		public long target;

		public Entry(ItemStack item, long target) {
			this.item = item;
			this.target = target;
		}

		public boolean matches(ItemStack stack) {
			return FilterChests.matches(List.of(item), stack);
		}
	}

	// ---- lookup ----

	public static Project get(UUID id) {
		StorageData data = StorageData.get();
		return data == null || id == null ? null : data.projects.get(id);
	}

	/** The projects of a network, in the order they were made. */
	public static List<Project> of(StorageNetwork network) {
		StorageData data = StorageData.get();
		List<Project> list = new ArrayList<>();

		if (data == null) {
			return list;
		}

		for (Project project : data.projects.values()) {
			if (data.networkFor(project.owner) == network) {
				list.add(project);
			}
		}

		return list;
	}

	public static Project create(UUID owner) {
		StorageData data = StorageData.get();
		int number = 1;

		for (Project project : data.projects.values()) {
			if (project.owner.equals(owner)) {
				number++;
			}
		}

		Project project = new Project(UUID.randomUUID(), owner, "Projekt " + number, false);
		data.projects.put(project.id, project);
		StorageData.markDirty();
		return project;
	}

	/** The settings: the project screen with the client mod, otherwise the chest-style settings. */
	public static void openSettings(net.minecraft.server.level.ServerPlayer player, Project project) {
		if (TerminalNetworking.hasClientUi(player)) {
			ProjectSettingsMenu.open(player, project);
		} else {
			ProjectMenu.open(player, project);
		}
	}

	// ---- blocking ----

	/** How many of this item the blocking projects of the network keep, without {@code except}. */
	public static long reserved(StorageNetwork network, ItemStack prototype, Project except) {
		StorageData data = StorageData.get();

		if (data == null || data.projects.isEmpty()) {
			return 0;
		}

		long reserved = 0;

		for (Project project : data.projects.values()) {
			if (!project.block || project == except || data.networkFor(project.owner) != network) {
				continue;
			}

			for (Entry entry : project.entries) {
				if (entry.matches(prototype)) {
					reserved += entry.target;
				}
			}
		}

		return reserved;
	}

	/** What may be taken of a stored item: everything beyond what the blocking projects (other than {@code project}) keep. */
	public static long available(StorageNetwork network, StorageNetwork.StoredItem entry, Project project) {
		return Math.max(0, entry.count - reserved(network, entry.prototype, project));
	}

	/** Tooltip lines for an item blocked by projects: which projects keep how many. Empty when nothing is blocked. */
	public static List<Component> blockedLines(StorageNetwork network, ItemStack prototype, Project except) {
		StorageData data = StorageData.get();
		List<Component> lines = new ArrayList<>();

		if (data == null) {
			return lines;
		}

		for (Project project : data.projects.values()) {
			if (!project.block || project == except || data.networkFor(project.owner) != network) {
				continue;
			}

			long amount = 0;

			for (Entry entry : project.entries) {
				if (entry.matches(prototype)) {
					amount += entry.target;
				}
			}

			if (amount > 0) {
				lines.add(Component.literal("Geblockt für „" + project.name + "“: " + format(amount)).withStyle(ChatFormatting.RED));
			}
		}

		return lines;
	}

	// ---- items ----

	/**
	 * Every item of the game, for the search in the project settings, plus one of each potion (also splash, lingering
	 * and tipped arrows) and one enchanted book per enchantment and level, so a project can ask for exactly those.
	 */
	public static List<ItemStack> allItems(FeatureFlagSet features, HolderLookup.Provider registries) {
		List<ItemStack> list = new ArrayList<>();

		for (Item item : BuiltInRegistries.ITEM) {
			if (item != Items.AIR && item.isEnabled(features)) {
				list.add(new ItemStack(item));
			}
		}

		for (Holder.Reference<Potion> potion : BuiltInRegistries.POTION.listElements().toList()) {
			for (Item item : List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW)) {
				list.add(PotionContents.createItemStack(item, potion));
			}
		}

		registries.lookup(Registries.ENCHANTMENT).ifPresent(enchantments -> enchantments.listElements().forEach(enchantment -> {
			for (int level = 1; level <= enchantment.value().getMaxLevel(); level++) {
				ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
				stored.set(enchantment, level);
				ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
				book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
				list.add(book);
			}
		}));

		return list;
	}

	public static List<ItemStack> search(FeatureFlagSet features, HolderLookup.Provider registries, String text) {
		String query = ItemSearch.query(text);
		List<ItemStack> list = new ArrayList<>();

		for (ItemStack stack : allItems(features, registries)) {
			if (ItemSearch.matches(stack, query)) {
				list.add(stack);
			}
		}

		list.sort(Comparator.comparing(stack -> stack.getHoverName().getString()));
		return list;
	}

	/** The key of an island chest standing for a project: a book with the project id. */
	public static ItemStack islandKey(Project project) {
		ItemStack key = new ItemStack(Items.WRITABLE_BOOK);
		CompoundTag tag = new CompoundTag();
		tag.putString(ID_KEY, project.id.toString());
		key.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return key;
	}

	/** The project id in a project chest item or an island key, or null. */
	public static UUID idOf(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return null;
		}

		CompoundTag tag = data.copyTag();

		String id = tag.getStringOr(ID_KEY, "");

		try {
			return id.isEmpty() ? null : UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static String format(long n) {
		return String.format(Locale.GERMANY, "%,d", n);
	}
}
