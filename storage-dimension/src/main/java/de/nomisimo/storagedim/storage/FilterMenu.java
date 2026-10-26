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
 * Settings screen of a filter chest: seven ghost slots, fourteen for a double chest. Clicking with an item sets that slot to a copy of it,
 * clicking with an empty hand clears it, shift-clicking in the own inventory fills the next free slot.
 * No real items ever move.
 */
public class FilterMenu extends ChestMenu {
	private final SimpleContainer display;
	private final FilterChests.FilterChest chest;
	private final Player viewer;
	private final int size;
	private final int menuSlots;

	private FilterMenu(int containerId, Inventory inventory, SimpleContainer display, FilterChests.FilterChest chest, int rows) {
		super(rows == 2 ? MenuType.GENERIC_9x2 : MenuType.GENERIC_9x1, containerId, inventory, display, rows);
		this.display = display;
		this.chest = chest;
		this.viewer = inventory.player;
		this.size = chest.filters().size();
		this.menuSlots = rows * 9;
		refresh();
	}

	public static void open(ServerPlayer player, FilterChests.FilterChest chest) {
		int rows = chest.filters().size() > 8 ? 2 : 1;
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new FilterMenu(id, inventory, new SimpleContainer(rows * 9), chest, rows),
				Component.literal(rows == 2 ? "Filter einstellen (Doppelkiste)" : "Filter einstellen")));
	}

	private void refresh() {
		for (int i = 0; i < size; i++) {
			// the list shrinks when one half of a double chest is broken while this screen is open
			ItemStack filter = i < chest.filters().size() ? chest.filters().get(i) : ItemStack.EMPTY;

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
		display.setItem(size, help);

		for (int i = size + 1; i < menuSlots; i++) {
			ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
			filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
			display.setItem(i, filler);
		}
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (slotIndex >= 0 && slotIndex < size) {
			if (slotIndex >= chest.filters().size()) {
				return;
			}

			ItemStack carried = getCarried();
			chest.filters().set(slotIndex, carried.isEmpty() ? ItemStack.EMPTY : carried.copyWithCount(1));
			StorageData.markDirty();
		} else if (slotIndex >= menuSlots && input == ContainerInput.QUICK_MOVE) {
			ItemStack stack = slots.get(slotIndex).getItem();

			if (!stack.isEmpty() && !FilterChests.matches(chest.filters(), stack)) {
				int free = -1;

				for (int i = 0; i < chest.filters().size() && free < 0; i++) {
					if (chest.filters().get(i).isEmpty()) {
						free = i;
					}
				}

				if (free >= 0) {
					chest.filters().set(free, stack.copyWithCount(1));
					StorageData.markDirty();
				}
			}
		} else if (slotIndex >= menuSlots && input != ContainerInput.PICKUP_ALL && input != ContainerInput.QUICK_CRAFT) {
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

		for (int i = 0; i < menuSlots; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}
	}
}
