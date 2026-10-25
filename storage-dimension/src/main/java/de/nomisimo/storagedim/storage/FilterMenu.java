package de.nomisimo.storagedim.storage;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Settings screen of a filter chest: seven ghost slots. Clicking with an item sets that slot to a copy of it,
 * clicking with an empty hand clears it, shift-clicking in the own inventory fills the next free slot.
 * No real items ever move.
 */
public class FilterMenu extends ChestMenu {
	private final SimpleContainer display;
	private final FilterChests.FilterChest chest;
	private final Player viewer;

	private FilterMenu(int containerId, Inventory inventory, SimpleContainer display, FilterChests.FilterChest chest) {
		super(MenuType.GENERIC_9x1, containerId, inventory, display, 1);
		this.display = display;
		this.chest = chest;
		this.viewer = inventory.player;
		refresh();
	}

	public static void open(ServerPlayer player, FilterChests.FilterChest chest) {
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new FilterMenu(id, inventory, new SimpleContainer(9), chest),
				Component.literal("Filter einstellen")));
	}

	private void refresh() {
		for (int i = 0; i < FilterChests.SLOTS; i++) {
			ItemStack filter = chest.filters().get(i);

			if (filter.isEmpty()) {
				display.setItem(i, ItemStack.EMPTY);
			} else {
				ItemStack icon = filter.copyWithCount(1);
				icon.set(DataComponents.LORE, new ItemLore(List.of(
						Component.literal("Klick mit leerer Hand: entfernen").withStyle(ChatFormatting.GRAY))));
				display.setItem(i, icon);
			}
		}

		ItemStack help = new ItemStack(Items.ENDER_EYE);
		help.set(DataComponents.CUSTOM_NAME, Component.literal("Filter").withStyle(s -> s.withItalic(false)));
		help.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Item auf einen Platz legen = Filter setzen").withStyle(ChatFormatting.GRAY),
				Component.literal("Die Kiste zeigt nur diese Items aus dem ME-Speicher").withStyle(ChatFormatting.GRAY))));
		display.setItem(7, help);
		ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
		filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
		display.setItem(8, filler);
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (slotIndex >= 0 && slotIndex < FilterChests.SLOTS) {
			ItemStack carried = getCarried();
			chest.filters().set(slotIndex, carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
			StorageData.markDirty();
		} else if (slotIndex >= 9 && input == ContainerInput.QUICK_MOVE) {
			ItemStack stack = slots.get(slotIndex).getItem();

			if (!stack.isEmpty() && !FilterChests.matches(chest.filters(), stack)) {
				int free = -1;

				for (int i = 0; i < FilterChests.SLOTS && free < 0; i++) {
					if (chest.filters().get(i).isEmpty()) {
						free = i;
					}
				}

				if (free >= 0) {
					chest.filters().set(free, stack.copyWithCount(1));
					StorageData.markDirty();
				}
			}
		} else if (slotIndex >= 9 && input != ContainerInput.PICKUP_ALL && input != ContainerInput.QUICK_CRAFT) {
			super.clicked(slotIndex, buttonNum, input, player);
		}

		refresh();

		if (viewer instanceof ServerPlayer) {
			sendAllDataToRemote();
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);

		for (int i = 0; i < 9; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}
	}
}
