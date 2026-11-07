package de.nomisimo.storagedim;

import java.util.List;

import de.nomisimo.storagedim.robot.AntennaBlock;
import de.nomisimo.storagedim.robot.RemoteItem;
import de.nomisimo.storagedim.robot.RobotBarrierBlock;
import de.nomisimo.storagedim.robot.RobotStationBlock;
import de.nomisimo.storagedim.robot.Robots;
import de.nomisimo.storagedim.storage.StorageItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Our own tab in the creative inventory. JEI builds its item list from the creative tabs, so this is also what puts
 * the dimension chest, filter chest, terminal and the other chest-based items into JEI (see StorageJeiPlugin for the subtypes).
 */
public final class StorageTab {
	public static final ResourceKey<CreativeModeTab> KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB, StorageDimensionMod.id("main"));

	private StorageTab() {
	}

	public static void register() {
		// row null and column -1 is what Fabric API's own tab builder uses: Fabric puts the tab on its extra pages
		CreativeModeTab tab = CreativeModeTab.builder(null, -1)
				.title(Component.translatable("itemGroup.storagedim.main"))
				.icon(StorageItems::storageChest)
				.displayItems((parameters, output) -> {
					for (ItemStack stack : items()) {
						output.accept(stack);
					}
				})
				.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, KEY, tab);
	}

	/** Everything the mod adds, in the order of the README. */
	public static List<ItemStack> items() {
		ItemStack upgrade = BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath("storagedim_tb", "me_upgrade"))
				.map(ItemStack::new)
				.orElseGet(StorageTab::paperUpgrade);
		return List.of(
				StorageItems.storageChest(),
				StorageItems.terminal(),
				StorageItems.filterChest(),
				StorageItems.projectChest(null),
				upgrade,
				backpack(),
				new ItemStack(Robots.ROBOT_ITEM),
				new ItemStack(RobotStationBlock.ITEM),
				new ItemStack(RobotBarrierBlock.ITEM),
				new ItemStack(AntennaBlock.ITEM),
				new ItemStack(RemoteItem.ITEM));
	}

	/** Same result as data/storagedim/recipe/me_upgrade.json, used without the Traveler's Backpack add-on. */
	private static ItemStack paperUpgrade() {
		ItemStack stack = new ItemStack(Items.PAPER);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(StorageItems.UPGRADE_TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal("ME-Upgrade"));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.RARITY, Rarity.RARE);
		stack.set(DataComponents.MAX_STACK_SIZE, 16);
		stack.set(DataComponents.ITEM_MODEL, StorageDimensionMod.id("me_upgrade"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Mit einem Bündel kombinieren: ME-Rucksack").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))));
		return stack;
	}

	/** Same result as data/storagedim/recipe/me_backpack.json. */
	private static ItemStack backpack() {
		ItemStack stack = new ItemStack(Items.BUNDLE);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(StorageItems.BACKPACK_TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal("ME-Rucksack"));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.ITEM_MODEL, StorageDimensionMod.id("me_backpack"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Rechtsklick öffnet den ME-Speicher, überall").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))));
		return stack;
	}
}
