package de.nomisimo.storagedim.storage;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Our chests, the terminal, the ME upgrade and the ME backpack as items of their own (storagedim:dimension_chest, ...),
 * registered like every other mod item. Recipes, the creative tab and JEI then just use their ids, the way every
 * other mod does it; nothing needs to tell them apart from plain chests, ender chests, paper or bundles any more.
 *
 * <p>The chests and the terminal place the vanilla chest and ender chest block (see {@link PlacingItem}); what makes
 * them special is still stored per position in {@link StorageData}.
 *
 * <p>Up to 0.7.3 these were vanilla items with a custom data tag. Such old stacks still work everywhere and are swapped
 * for the new items when their player joins ({@link #upgradeInventory}).
 */
public final class StorageItems {
	// custom data keys of the old items, still read so that old stacks keep working
	public static final String CHEST_TAG = "storagedim_chest";
	public static final String TERMINAL_TAG = "storagedim_terminal";
	/** Also marks bundles and Traveler's Backpacks that were linked with the ME upgrade (see Backpacks). */
	public static final String BACKPACK_TAG = "storagedim_backpack";
	/** Also on the Traveler's Backpack add-on's own ME upgrade. */
	public static final String UPGRADE_TAG = "storagedim_upgrade";
	public static final String FILTER_TAG = "storagedim_filter";

	public static final Item DIMENSION_CHEST = register("dimension_chest", properties -> new PlacingItem(Blocks.CHEST, properties), new Item.Properties(),
			"Öffnen bringt dich in deine Storage Dimension", "Hopper liefern direkt in den Speicher");
	public static final Item FILTER_CHEST = register("filter_chest", properties -> new PlacingItem(Blocks.CHEST, properties), new Item.Properties(),
			"Zeigt bis zu 7 gewählte Items aus dem ME-Speicher", "Schleichen + Rechtsklick: Filter einstellen");
	public static final Item PROJECT_CHEST = register("project_chest", properties -> new PlacingItem(Blocks.CHEST, properties), new Item.Properties(),
			"Ein Bauprojekt: Items und Mengen festlegen", "Schleichen + Rechtsklick: Projekt einstellen");
	public static final Item ME_TERMINAL = register("me_terminal", properties -> new PlacingItem(Blocks.ENDER_CHEST, properties), new Item.Properties(),
			"Öffnet den ME-Speicher direkt");
	public static final Item ME_UPGRADE = register("me_upgrade", Item::new, new Item.Properties()
					.stacksTo(16).rarity(Rarity.RARE).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true),
			"Mit einem Bündel kombinieren: ME-Rucksack");
	public static final Item ME_BACKPACK = register("me_backpack", BundleItem::new, new Item.Properties()
					.stacksTo(1).component(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true),
			"Rechtsklick öffnet den ME-Speicher, überall");

	private StorageItems() {
	}

	/** Loads the class, which registers the items. */
	public static void register() {
	}

	private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties, String... lore) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, StorageDimensionMod.id(name));
		List<Component> lines = java.util.Arrays.stream(lore)
				.map(line -> (Component) Component.literal(line).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))
				.toList();
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.component(DataComponents.LORE, new ItemLore(lines)).setId(key)));
	}

	/**
	 * Places a vanilla block (chest, ender chest) but is an item of its own. It stays out of the block-to-item map,
	 * so a plain chest block still is a plain chest item (pick block, drops, {@code Blocks.CHEST.asItem()}).
	 */
	public static final class PlacingItem extends BlockItem {
		public PlacingItem(Block block, Properties properties) {
			super(block, properties);
		}

		@Override
		public void registerBlocks(Map<Block, Item> map, Item item) {
		}
	}

	// ---- stacks ----

	public static ItemStack storageChest() {
		return new ItemStack(DIMENSION_CHEST);
	}

	public static ItemStack terminal() {
		return new ItemStack(ME_TERMINAL);
	}

	public static ItemStack filterChest() {
		return new ItemStack(FILTER_CHEST);
	}

	/** A new project chest when {@code project} is null; a broken project chest drops one that keeps its project. */
	public static ItemStack projectChest(Projects.Project project) {
		ItemStack stack = new ItemStack(PROJECT_CHEST);

		if (project != null) {
			CompoundTag tag = new CompoundTag();
			tag.putString(Projects.ID_KEY, project.id.toString());
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
			stack.set(DataComponents.ITEM_NAME, Component.translatable("item.storagedim.project_chest").append(": " + project.name));
		}

		return stack;
	}

	public static ItemStack upgrade() {
		return new ItemStack(ME_UPGRADE);
	}

	public static ItemStack backpack() {
		return new ItemStack(ME_BACKPACK);
	}

	// ---- telling them apart ----

	/** Which of our items a stack is ("storage", "filter", ...), or null. Old stacks with only the data tag count too. */
	public static String variant(ItemStack stack) {
		if (isStorageChest(stack)) {
			return "storage";
		} else if (isFilterChest(stack)) {
			return "filter";
		} else if (isProjectChest(stack)) {
			return "project";
		} else if (isTerminal(stack)) {
			return "terminal";
		} else if (isBackpack(stack)) {
			return "backpack";
		} else if (isUpgrade(stack)) {
			return "upgrade";
		}

		return null;
	}

	public static boolean isProjectChest(ItemStack stack) {
		return stack.is(PROJECT_CHEST) || stack.is(Items.CHEST) && hasTag(stack, Projects.ID_KEY);
	}

	public static boolean isFilterChest(ItemStack stack) {
		return stack.is(FILTER_CHEST) || stack.is(Items.CHEST) && hasTag(stack, FILTER_TAG);
	}

	public static boolean isStorageChest(ItemStack stack) {
		return stack.is(DIMENSION_CHEST) || stack.is(Items.CHEST) && hasTag(stack, CHEST_TAG);
	}

	public static boolean isTerminal(ItemStack stack) {
		return stack.is(ME_TERMINAL) || stack.is(Items.ENDER_CHEST) && hasTag(stack, TERMINAL_TAG);
	}

	/** The ME backpack, or a bundle or Traveler's Backpack linked with the ME upgrade (see Backpacks). */
	public static boolean isBackpack(ItemStack stack) {
		return stack.is(ME_BACKPACK) || hasTag(stack, BACKPACK_TAG);
	}

	/** Our ME upgrade, an old paper one, or the Traveler's Backpack add-on's. */
	public static boolean isUpgrade(ItemStack stack) {
		return stack.is(ME_UPGRADE) || hasTag(stack, UPGRADE_TAG);
	}

	private static boolean hasTag(ItemStack stack, String key) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(key);
	}

	// ---- old stacks ----

	/** Swaps old stacks (vanilla items with our tag) in the player's inventory for the new items. */
	public static void upgradeInventory(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		int swapped = 0;

		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack upgraded = upgradeLegacy(inventory.getItem(i));

			if (upgraded != null) {
				inventory.setItem(i, upgraded);
				swapped++;
			}
		}

		if (swapped > 0) {
			StorageDimensionMod.LOGGER.info("{}: {} alte Storage-Dimension-Items gegen die neuen getauscht", player.getName().getString(), swapped);
		}
	}

	/** The new item for an old stack, or null when the stack is not an old one of ours. */
	static ItemStack upgradeLegacy(ItemStack stack) {
		if (stack.isEmpty() || BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().startsWith(StorageDimensionMod.MOD_ID)) {
			return null;
		}

		ItemStack upgraded;

		if (stack.is(Items.CHEST) && hasTag(stack, CHEST_TAG)) {
			upgraded = storageChest();
		} else if (stack.is(Items.CHEST) && hasTag(stack, FILTER_TAG)) {
			upgraded = filterChest();
		} else if (stack.is(Items.CHEST) && hasTag(stack, Projects.ID_KEY)) {
			UUID id = Projects.idOf(stack);
			upgraded = new ItemStack(PROJECT_CHEST);

			if (id != null) {
				// keeps the project and its name
				upgraded.set(DataComponents.CUSTOM_DATA, stack.get(DataComponents.CUSTOM_DATA));
				Component name = stack.get(DataComponents.ITEM_NAME);

				if (name != null) {
					upgraded.set(DataComponents.ITEM_NAME, name);
				}
			}
		} else if (stack.is(Items.ENDER_CHEST) && hasTag(stack, TERMINAL_TAG)) {
			upgraded = terminal();
		} else if (stack.is(Items.PAPER) && hasTag(stack, UPGRADE_TAG)) {
			upgraded = upgrade();
		} else if (stack.is(Items.BUNDLE) && hasTag(stack, BACKPACK_TAG) && stack.get(DataComponents.ITEM_NAME) != null
				&& stack.get(DataComponents.ITEM_NAME).getString().equals("ME-Rucksack")) {
			// only crafted ME backpacks; a bundle the player linked by hand stays the bundle it is
			upgraded = backpack();
			BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);

			if (contents != null) {
				upgraded.set(DataComponents.BUNDLE_CONTENTS, contents);
			}
		} else {
			return null;
		}

		upgraded.setCount(stack.getCount());
		return upgraded;
	}
}
