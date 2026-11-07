package de.nomisimo.storagedim.storage;

import java.util.List;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

public final class StorageItems {
	public static final String CHEST_TAG = "storagedim_chest";
	public static final String TERMINAL_TAG = "storagedim_terminal";
	public static final String BACKPACK_TAG = "storagedim_backpack";
	public static final String UPGRADE_TAG = "storagedim_upgrade";
	public static final String FILTER_TAG = "storagedim_filter";

	private StorageItems() {
	}

	/** Same result as the crafting recipe in data/storagedim/recipe/storage_chest.json. */
	public static ItemStack storageChest() {
		ItemStack stack = new ItemStack(Items.CHEST);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(CHEST_TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal("Dimensionskiste"));
		stack.set(DataComponents.ITEM_MODEL, StorageDimensionMod.id("dimension_chest"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Öffnen bringt dich in deine Storage Dimension").withStyle(ChatFormatting.GRAY),
				Component.literal("Hopper liefern direkt in den Speicher").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	/** Same result as data/storagedim/recipe/me_terminal.json. */
	public static ItemStack terminal() {
		ItemStack stack = new ItemStack(Items.ENDER_CHEST);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TERMINAL_TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal("ME-Terminal"));
		stack.set(DataComponents.ITEM_MODEL, StorageDimensionMod.id("me_terminal"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Öffnet den ME-Speicher direkt").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	/** Same result as data/storagedim/recipe/filter_chest.json. */
	public static ItemStack filterChest() {
		ItemStack stack = new ItemStack(Items.CHEST);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(FILTER_TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal("Filterkiste"));
		stack.set(DataComponents.ITEM_MODEL, StorageDimensionMod.id("filter_chest"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Zeigt bis zu 7 gewählte Items aus dem ME-Speicher").withStyle(ChatFormatting.GRAY),
				Component.literal("Schleichen + Rechtsklick: Filter einstellen").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	/**
	 * Same result as data/storagedim/recipe/project_chest.json when {@code project} is null; a broken project chest
	 * drops one that keeps its project.
	 */
	public static ItemStack projectChest(Projects.Project project) {
		ItemStack stack = new ItemStack(Items.CHEST);
		CompoundTag tag = new CompoundTag();
		tag.putString(Projects.ID_KEY, project == null ? "" : project.id.toString());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal(project == null ? "Projektkiste" : "Projektkiste: " + project.name));
		stack.set(DataComponents.ITEM_MODEL, StorageDimensionMod.id("project_chest"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Ein Bauprojekt: Items und Mengen festlegen").withStyle(ChatFormatting.GRAY),
				Component.literal("Schleichen + Rechtsklick: Projekt einstellen").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	/** Same result as data/storagedim/recipe/me_upgrade.json (the paper version, without the Traveler's Backpack add-on). */
	public static ItemStack upgrade() {
		ItemStack stack = new ItemStack(Items.PAPER);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(UPGRADE_TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal("ME-Upgrade"));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.RARITY, Rarity.RARE);
		stack.set(DataComponents.MAX_STACK_SIZE, 16);
		stack.set(DataComponents.ITEM_MODEL, StorageDimensionMod.id("me_upgrade"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Mit einem Bündel kombinieren: ME-Rucksack").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	/** Same result as data/storagedim/recipe/me_backpack.json. */
	public static ItemStack backpack() {
		ItemStack stack = new ItemStack(Items.BUNDLE);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(BACKPACK_TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal("ME-Rucksack"));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.ITEM_MODEL, StorageDimensionMod.id("me_backpack"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Rechtsklick öffnet den ME-Speicher, überall").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	/**
	 * Which of our items a vanilla stack is ("storage", "filter", ...), or null for a plain vanilla item.
	 * All project chests count as one, whatever project they hold. JEI uses it to tell them from plain chests, bundles and paper.
	 */
	public static String variant(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return null;
		}

		CompoundTag tag = data.copyTag();

		if (tag.contains(CHEST_TAG)) {
			return "storage";
		} else if (tag.contains(FILTER_TAG)) {
			return "filter";
		} else if (tag.contains(Projects.ID_KEY)) {
			return "project";
		} else if (tag.contains(TERMINAL_TAG)) {
			return "terminal";
		} else if (tag.contains(BACKPACK_TAG)) {
			return "backpack";
		} else if (tag.contains(UPGRADE_TAG)) {
			return "upgrade";
		}

		return null;
	}

	public static boolean isProjectChest(ItemStack stack) {
		return stack.is(Items.CHEST) && hasTag(stack, Projects.ID_KEY);
	}

	public static boolean isFilterChest(ItemStack stack) {
		return stack.is(Items.CHEST) && hasTag(stack, FILTER_TAG);
	}

	public static boolean isStorageChest(ItemStack stack) {
		return stack.is(Items.CHEST) && hasTag(stack, CHEST_TAG);
	}

	public static boolean isTerminal(ItemStack stack) {
		return stack.is(Items.ENDER_CHEST) && hasTag(stack, TERMINAL_TAG);
	}

	/** A bundle with the ME upgrade, see data/storagedim/recipe/me_backpack.json. */
	public static boolean isBackpack(ItemStack stack) {
		return hasTag(stack, BACKPACK_TAG);
	}

	public static boolean isUpgrade(ItemStack stack) {
		return hasTag(stack, UPGRADE_TAG);
	}

	private static boolean hasTag(ItemStack stack, String key) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(key);
	}
}
