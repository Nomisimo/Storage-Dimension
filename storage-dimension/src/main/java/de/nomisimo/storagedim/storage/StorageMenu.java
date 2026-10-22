package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

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
 * The ME terminal: a 6-row chest screen showing the network contents page by page.
 * The top inventory is display-only; every click is handled here and then resynced.
 */
public class StorageMenu extends ChestMenu {
	private static final int ITEM_SLOTS = 45;
	private static final int SLOT_PREV = 45;
	private static final int SLOT_DEPOSIT = 48;
	private static final int SLOT_INFO = 49;
	private static final int SLOT_NEXT = 53;
	private static final Set<StorageMenu> OPEN = java.util.Collections.newSetFromMap(new WeakHashMap<>());

	private final StorageNetwork network;
	private final SimpleContainer display;
	private final Player viewer;
	private List<StorageNetwork.StoredItem> shown = new ArrayList<>();
	private int page;

	private StorageMenu(int containerId, Inventory inventory, SimpleContainer display, StorageNetwork network) {
		super(MenuType.GENERIC_9x6, containerId, inventory, display, 6);
		this.network = network;
		this.display = display;
		this.viewer = inventory.player;
		refresh();
		OPEN.add(this);
	}

	public static void open(ServerPlayer player, StorageNetwork network) {
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new StorageMenu(id, inventory, new SimpleContainer(54), network),
				Component.literal("ME-Speicher")));
	}

	/** Redraws every open terminal of this network, e.g. after a hopper delivered items. */
	public static void refreshAll(StorageNetwork network) {
		for (StorageMenu menu : new ArrayList<>(OPEN)) {
			if (menu.network == network) {
				menu.refresh();
				menu.sync();
			}
		}
	}

	private void refresh() {
		List<StorageNetwork.StoredItem> all = network.sorted();
		int pages = Math.max(1, (all.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
		page = Math.min(page, pages - 1);
		shown = all.subList(page * ITEM_SLOTS, Math.min(all.size(), (page + 1) * ITEM_SLOTS));

		for (int i = 0; i < 54; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}

		for (int i = 0; i < shown.size(); i++) {
			StorageNetwork.StoredItem entry = shown.get(i);
			ItemStack icon = entry.prototype.copyWithCount((int) Math.min(entry.count, entry.prototype.getMaxStackSize()));
			List<Component> lore = new ArrayList<>();
			lore.add(Component.literal("Gespeichert: " + format(entry.count)).withStyle(ChatFormatting.GOLD));
			lore.add(Component.literal("Linksklick: Stapel nehmen").withStyle(ChatFormatting.GRAY));
			lore.add(Component.literal("Rechtsklick: halben Stapel nehmen").withStyle(ChatFormatting.GRAY));
			lore.add(Component.literal("Shift-Klick: ins Inventar").withStyle(ChatFormatting.GRAY));
			icon.set(DataComponents.LORE, new ItemLore(lore));
			display.setItem(i, icon);
		}

		ItemStack filler = button(Items.GRAY_STAINED_GLASS_PANE, " ");

		for (int i = ITEM_SLOTS; i < 54; i++) {
			display.setItem(i, filler.copy());
		}

		if (page > 0) {
			display.setItem(SLOT_PREV, button(Items.ARROW, "Vorherige Seite"));
		}

		if (page < pages - 1) {
			display.setItem(SLOT_NEXT, button(Items.ARROW, "Nächste Seite"));
		}

		display.setItem(SLOT_DEPOSIT, button(Items.HOPPER, "Inventar einlagern (ohne Hotbar)"));
		display.setItem(SLOT_INFO, button(Items.ENDER_EYE, "Seite " + (page + 1) + "/" + pages + " · "
				+ network.typeCount() + " Sorten · " + format(network.totalCount()) + " Items"));
	}

	private static ItemStack button(net.minecraft.world.item.Item item, String name) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false)));
		return stack;
	}

	private static String format(long n) {
		return String.format(java.util.Locale.GERMANY, "%,d", n);
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (slotIndex >= 0 && slotIndex < 54) {
			clickTop(slotIndex, buttonNum, input, player);
		} else if (slotIndex >= 54 && input == ContainerInput.QUICK_MOVE) {
			ItemStack stack = slots.get(slotIndex).getItem();
			network.insert(stack);
			slots.get(slotIndex).setChanged();
		} else if (input == ContainerInput.PICKUP_ALL || input == ContainerInput.QUICK_CRAFT) {
			// Would pull display items or spread into the terminal; ignore.
		} else {
			super.clicked(slotIndex, buttonNum, input, player);
		}

		refreshAll(network);
		sync();
	}

	private void clickTop(int slot, int button, ContainerInput input, Player player) {
		if (slot == SLOT_PREV && page > 0) {
			page--;
			return;
		}

		if (slot == SLOT_NEXT) {
			page++;
			return;
		}

		if (slot == SLOT_DEPOSIT) {
			Inventory inv = player.getInventory();

			for (int i = 9; i < 36; i++) {
				network.insert(inv.getItem(i));
			}

			inv.setChanged();
			return;
		}

		if (slot >= ITEM_SLOTS) {
			return;
		}

		ItemStack carried = getCarried();

		if (input == ContainerInput.PICKUP && !carried.isEmpty()) {
			if (button == 1) {
				network.insert(carried.split(1));
			} else {
				network.insert(carried);
				setCarried(ItemStack.EMPTY);
			}

			return;
		}

		if (slot >= shown.size()) {
			return;
		}

		ItemStack prototype = shown.get(slot).prototype;
		int max = prototype.getMaxStackSize();

		if (input == ContainerInput.QUICK_MOVE) {
			ItemStack taken = network.extract(prototype, max);
			player.getInventory().add(taken);

			if (!taken.isEmpty()) {
				network.insert(taken);
			}
		} else if (input == ContainerInput.PICKUP && carried.isEmpty()) {
			setCarried(network.extract(prototype, button == 1 ? Math.max(1, max / 2) : max));
		}
	}

	private void sync() {
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
		OPEN.remove(this);
		// The display container must never drop its icons.
		for (int i = 0; i < 54; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}
	}
}
