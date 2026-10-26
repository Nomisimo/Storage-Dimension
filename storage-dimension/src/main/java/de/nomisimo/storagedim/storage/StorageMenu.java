package de.nomisimo.storagedim.storage;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The ME terminal: a 6-row chest screen showing the network contents page by page.
 * The top inventory is display-only; every click is handled here and then resynced.
 * A filter chest opens the same screen limited to its filter items.
 */
public class StorageMenu extends ChestMenu {
	private static final int ITEM_SLOTS = 45;
	private static final int SLOT_PREV = 45;
	private static final int SLOT_SEARCH = 46;
	private static final int SLOT_SORT = 47;
	private static final int SLOT_DEPOSIT = 48;
	private static final int SLOT_INFO = 49;
	private static final int SLOT_NEXT = 53;
	private static final int ICON_MAX = 99;
	/** Custom data key on a terminal icon with the exact stored amount. */
	public static final String COUNT_KEY = "storagedim_count";
	private static final Set<StorageMenu> OPEN = java.util.Collections.newSetFromMap(new WeakHashMap<>());
	/** Sort order per player, kept until the server restarts. The search starts empty every time. */
	private static final Map<UUID, Sort> SORTS = new HashMap<>();
	private static final int MAX_SEARCH = 50;

	private final StorageNetwork network;
	private final SimpleContainer display;
	private final Player viewer;
	/** Filter items of a filter chest, or null for the full terminal. */
	private final List<ItemStack> filter;
	/** The player has the mod on the client, which draws a search field and sort button instead of the bottom buttons. */
	private final boolean clientUi;
	private String search;
	private int pages = 1;
	private int kinds;
	private long total;
	private List<StorageNetwork.StoredItem> shown = new ArrayList<>();
	private int page;

	public enum Sort {
		AMOUNT("Menge"), NAME("Alphabet");

		final String label;

		Sort(String label) {
			this.label = label;
		}
	}

	private StorageMenu(int containerId, Inventory inventory, SimpleContainer display, StorageNetwork network, List<ItemStack> filter,
			String search, boolean clientUi) {
		super(MenuType.GENERIC_9x6, containerId, inventory, display, 6);
		this.network = network;
		this.display = display;
		this.viewer = inventory.player;
		this.filter = filter;
		this.search = search;
		this.clientUi = clientUi;
		refresh();
		OPEN.add(this);
	}

	public static void open(ServerPlayer player, StorageNetwork network) {
		open(player, network, null);
	}

	/** Opens the terminal; with a filter list only those items are shown and accepted. */
	public static void open(ServerPlayer player, StorageNetwork network, List<ItemStack> filter) {
		open(player, network, filter, "");
	}

	private static void open(ServerPlayer player, StorageNetwork network, List<ItemStack> filter, String search) {
		boolean clientUi = TerminalNetworking.hasClientUi(player);
		// With the client UI the search field sits where the title would be.
		Component title = clientUi ? Component.empty() : Component.literal(filter == null ? "ME-Speicher" : "Filterkiste");
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new StorageMenu(id, inventory, new SimpleContainer(54), network, filter, search, clientUi),
				title));

		if (clientUi && player.containerMenu instanceof StorageMenu menu) {
			TerminalNetworking.sendOpened(player, menu.containerId, menu.sort());
		}
	}

	private Sort sort() {
		return SORTS.getOrDefault(viewer.getUUID(), Sort.AMOUNT);
	}

	/** Search text typed into the client's search field. */
	public void setSearch(String text) {
		String clean = text.length() > MAX_SEARCH ? text.substring(0, MAX_SEARCH) : text;

		if (!clean.equals(search)) {
			search = clean;
			page = 0;
			refresh();
			sync();
		}
	}

	/** Switches the sort order and returns the new one. */
	public Sort toggleSort() {
		SORTS.put(viewer.getUUID(), next(sort()));
		page = 0;
		refresh();
		sync();
		return sort();
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

	private boolean accepts(ItemStack stack) {
		return filter == null || FilterChests.matches(filter, stack);
	}

	private boolean matchesSearch(ItemStack stack) {
		if (search.isBlank()) {
			return true;
		}

		String query = search.trim().toLowerCase(Locale.ROOT);
		return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)
				|| BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains(query);
	}

	private List<StorageNetwork.StoredItem> visible() {
		List<StorageNetwork.StoredItem> list = new ArrayList<>();

		for (StorageNetwork.StoredItem entry : network.items) {
			if (accepts(entry.prototype) && matchesSearch(entry.prototype)) {
				list.add(entry);
			}
		}

		if (sort() == Sort.NAME) {
			Collator collator = Collator.getInstance(Locale.GERMAN);
			list.sort(Comparator.comparing((StorageNetwork.StoredItem e) -> e.prototype.getHoverName().getString(), collator));
		} else {
			list.sort(Comparator.comparingLong((StorageNetwork.StoredItem e) -> e.count).reversed());
		}

		return list;
	}

	private void refresh() {
		List<StorageNetwork.StoredItem> all = visible();
		int pages = Math.max(1, (all.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
		page = Math.min(page, pages - 1);
		shown = all.subList(page * ITEM_SLOTS, Math.min(all.size(), (page + 1) * ITEM_SLOTS));

		for (int i = 0; i < 54; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}

		for (int i = 0; i < shown.size(); i++) {
			StorageNetwork.StoredItem entry = shown.get(i);
			display.setItem(i, icon(entry.prototype, entry.count));
		}

		this.pages = pages;
		kinds = all.size();
		total = all.stream().mapToLong(e -> e.count).sum();

		// the client mod draws real buttons in the bottom row instead
		if (clientUi) {
			return;
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

		{
			ItemStack searchButton = button(Items.OAK_SIGN, search.isEmpty() ? "Suchen" : "Suche: " + search);
			searchButton.set(DataComponents.LORE, new ItemLore(List.of(
					Component.literal("Linksklick: Suchbegriff eingeben").withStyle(ChatFormatting.GRAY),
					Component.literal("Rechtsklick: Suche löschen").withStyle(ChatFormatting.GRAY))));
			display.setItem(SLOT_SEARCH, searchButton);

			ItemStack sortButton = button(Items.COMPARATOR, "Sortierung: " + sort().label);
			sortButton.set(DataComponents.LORE, new ItemLore(List.of(
					Component.literal("Klick: nach " + next(sort()).label + " sortieren").withStyle(ChatFormatting.GRAY))));
			display.setItem(SLOT_SORT, sortButton);
		}

		display.setItem(SLOT_DEPOSIT, button(Items.HOPPER, filter == null
				? "Inventar einlagern (ohne Hotbar)" : "Passende Items einlagern (ohne Hotbar)"));
		display.setItem(SLOT_INFO, button(Items.ENDER_EYE, "Seite " + (page + 1) + "/" + pages + " · "
				+ all.size() + " Sorten · " + format(all.stream().mapToLong(e -> e.count).sum()) + " Items"));
	}

	/**
	 * The icon of a stored item, with the click help as lore. A slot can only show up to 99, so stackable items go up
	 * to 99 on the icon and the exact amount is always part of the name. It also carries the exact amount under
	 * {@link #COUNT_KEY}, which the client mod draws in small digits instead of the 99.
	 */
	public static ItemStack icon(ItemStack prototype, long count) {
		ItemStack icon = prototype.copyWithCount(1);
		int max = icon.getMaxStackSize();

		if (max > 1) {
			icon.set(DataComponents.MAX_STACK_SIZE, ICON_MAX);
			icon.setCount((int) Math.min(count, ICON_MAX));
		}

		icon.set(DataComponents.CUSTOM_NAME, Component.empty()
				.append(prototype.getHoverName())
				.append(Component.literal(" × " + format(count)).withStyle(ChatFormatting.GOLD))
				.withStyle(s -> s.withItalic(false)));
		CompoundTag tag = new CompoundTag();
		tag.putLong(COUNT_KEY, count);
		icon.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		icon.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Gespeichert: " + format(count)).withStyle(ChatFormatting.GOLD),
				Component.literal("Linksklick: Stapel nehmen").withStyle(ChatFormatting.GRAY),
				Component.literal("Rechtsklick: halben Stapel nehmen").withStyle(ChatFormatting.GRAY),
				Component.literal("Shift-Klick: ins Inventar").withStyle(ChatFormatting.GRAY))));
		return icon;
	}

	private static Sort next(Sort sort) {
		return sort == Sort.AMOUNT ? Sort.NAME : Sort.AMOUNT;
	}

	private static ItemStack button(net.minecraft.world.item.Item item, String name) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false)));
		return stack;
	}

	private static String format(long n) {
		return String.format(Locale.GERMANY, "%,d", n);
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (input == ContainerInput.PICKUP_ALL || input == ContainerInput.QUICK_CRAFT) {
			// Dragging and double-click collecting work in the own inventory; canDragTo and canTakeItemForPickAll keep them off the display.
			super.clicked(slotIndex, buttonNum, input, player);
		} else if (slotIndex == SLOT_SEARCH && !clientUi && player instanceof ServerPlayer serverPlayer) {
			if (buttonNum == 1) {
				search = "";
				page = 0;
			} else {
				SearchMenu.open(serverPlayer, search, text -> open(serverPlayer, network, filter, text));
				return;
			}
		} else if (slotIndex >= 0 && slotIndex < 54) {
			clickTop(slotIndex, buttonNum, input, player);
		} else if (slotIndex >= 54 && input == ContainerInput.QUICK_MOVE) {
			ItemStack stack = slots.get(slotIndex).getItem();

			if (accepts(stack)) {
				network.insert(stack);
				slots.get(slotIndex).setChanged();
			}
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

		if (slot == SLOT_SORT && !clientUi) {
			SORTS.put(player.getUUID(), next(sort()));
			page = 0;
			return;
		}

		if (slot == SLOT_DEPOSIT) {
			Inventory inv = player.getInventory();

			for (int i = 9; i < 36; i++) {
				if (accepts(inv.getItem(i))) {
					network.insert(inv.getItem(i));
				}
			}

			inv.setChanged();
			return;
		}

		if (slot >= ITEM_SLOTS) {
			return;
		}

		ItemStack carried = getCarried();

		if (input == ContainerInput.PICKUP && !carried.isEmpty()) {
			if (!accepts(carried)) {
				return;
			}

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
		if (viewer instanceof ServerPlayer player) {
			sendAllDataToRemote();

			if (clientUi) {
				TerminalNetworking.sendPage(player, containerId, page, pages, kinds, total);
			}
		}
	}

	/** A button of the client mod's bottom row: previous page, next page, deposit the inventory. */
	public void pressButton(int button) {
		int slot = switch (button) {
			case TerminalNetworking.BUTTON_PREV -> SLOT_PREV;
			case TerminalNetworking.BUTTON_NEXT -> SLOT_NEXT;
			case TerminalNetworking.BUTTON_DEPOSIT -> SLOT_DEPOSIT;
			default -> -1;
		};

		if (slot >= 0) {
			clickTop(slot, 0, ContainerInput.PICKUP, viewer);
			refreshAll(network);
			sync();
		}
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return slot.container != display;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return slot.container != display;
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
