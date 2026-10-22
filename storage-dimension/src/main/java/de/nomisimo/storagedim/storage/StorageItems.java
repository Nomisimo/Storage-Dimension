package de.nomisimo.storagedim.storage;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

public final class StorageItems {
	public static final String CHEST_TAG = "storagedim_chest";

	private StorageItems() {
	}

	/** Same result as the crafting recipe in data/storagedim/recipe/storage_chest.json. */
	public static ItemStack storageChest() {
		ItemStack stack = new ItemStack(Items.CHEST);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(CHEST_TAG, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.ITEM_NAME, Component.literal("Dimensionskiste"));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Öffnen bringt dich in deine Storage Dimension").withStyle(ChatFormatting.GRAY),
				Component.literal("Hopper liefern direkt in den Speicher").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	public static boolean isStorageChest(ItemStack stack) {
		if (!stack.is(Items.CHEST)) {
			return false;
		}

		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(CHEST_TAG);
	}
}
