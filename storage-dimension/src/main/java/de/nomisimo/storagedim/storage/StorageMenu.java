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
import java.util.function.Predicate;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
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
 * The ME terminal: a 6-row chest screen showing the network contents page by page; with the client mod the mouse
 * wheel also scrolls through them row by row.
 * The top inventory is display-only; every click is handled here and then resynced.
 * A filter chest, and every chest of the chest island, opens the same screen limited to its items.
 */
public class StorageMenu extends ChestMenu {
	private static final int ITEM_SLOTS = 45;
	private static final int COLUMNS = 9;
	/** Rows the page buttons move. */
	private static final int PAGE_ROWS = ITEM_SLOTS / COLUMNS;
	private static final int SLOT_PREV = 45;
	private static final int SLOT_SEARCH = 46;
	private static final int SLOT_SORT = 47;
	private static final int SLOT_DEPOSIT = 48;
	private static final int SLOT_INFO = 49;
	private static final int SLOT_SETTINGS = 51;
	private static final int SLOT_NEXT = 53;
	private static final int ICON_MAX = 99;
	/** Custom data key on a terminal icon with the exact stored amount. */
	public static final String COUNT_KEY = "storagedim_count";
	/** Custom data key with the colour the client draws the amount in, e.g. green when a project has enough. */
	public static final String COLOR_KEY = "storagedim_count_color";
	private static final Set<StorageMenu> OPEN = java.util.Collections.newSetFromMap(new WeakHashMap<>());
	/** Sort order per player, kept until the server restarts. The search starts empty every time. */
	private static final Map<UUID, Sort> SORTS = new HashMap<>();
	private static final int MAX_SEARCH = 50;

	private final StorageNetwork network;
	private final SimpleContainer display;
	private final Player viewer;
	/** Items this screen shows and accepts, or null for the full terminal. */
	private final Predicate<ItemStack> filter;
	private final String title;
	/** Runs when the screen closes, e.g. to shut the lid of an island chest. */
	private final Runnable onClose;
	/** The player has the mod on the client, which draws a search field and sort button instead of the bottom buttons. */
	private final boolean clientUi;
	/** The project of a project chest, whose own blocked items may be taken here; null otherwise. */
	private final Projects.Project project;
	/** An island chest: shows only what is not blocked for a project, as those items lie in the project chests. */
	private final boolean freeOnly;
	private String search;
	private int pages = 1;
	/** The first shown row: the page buttons move 5 rows, the mouse wheel 1. */
	private int row;
	private int maxRow;
	private int kinds;
	private long total;
	private List<StorageNetwork.StoredItem> shown = new ArrayList<>();

	public enum Sort {
		AMOUNT("Menge"), NAME("Alphabet");

		final String label;

		Sort(String label) {
			this.label = label;
		}
	}

	private StorageMenu(int containerId, Inventory inventory, SimpleContainer display, StorageNetwork network, Predicate<ItemStack> filter,
			String title, Runnable onClose, String search, boolean clientUi, Projects.Project project, boolean freeOnly) {
		super(MenuType.GENERIC_9x6, containerId, inventory, display, 6);
		this.project = project;
		this.freeOnly = freeOnly;
		this.network = network;
		this.display = display;
		this.viewer = inventory.player;
		this.filter = filter;
		this.title = title;
		this.onClose = onClose;
		this.search = search;
		this.clientUi = clientUi;
		refresh();
		OPEN.add(this);
	}

	public static void open(ServerPlayer player, StorageNetwork network) {
		open(player, network, null, "ME-Speicher", null, "", null, false);
	}

	/** The items of a project chest: only the project's items, including what is blocked for it. */
	public static void openProject(ServerPlayer player, Projects.Project project) {
		open(player, project.network(), project::wants, project.name, null, "", project, false);
	}

	/** A chest of the chest island: only what is not blocked for a project. */
	public static void openIsland(ServerPlayer player, StorageNetwork network, Predicate<ItemStack> filter, String title, Runnable onClose) {
		open(player, network, filter, title, onClose, "", null, true);
	}

	/** Opens the terminal; with a filter list only those items are shown and accepted. */
	public static void open(ServerPlayer player, StorageNetwork network, List<ItemStack> filter) {
		open(player, network, stack -> FilterChests.matches(filter, stack), "Filterkiste", null, "", null, false);
	}

	/** Opens the terminal limited to the items {@code filter} accepts, e.g. for one chest of the chest island. */
	public static void open(ServerPlayer player, StorageNetwork network, Predicate<ItemStack> filter, String title, Runnable onClose) {
		open(player, network, filter, title, onClose, "", null, false);
	}

	private static void open(ServerPlayer player, StorageNetwork network, Predicate<ItemStack> filter, String title, Runnable onClose,
			String search, Projects.Project project, boolean freeOnly) {
		boolean clientUi = TerminalNetworking.hasClientUi(player);
		// With the client UI the search field sits where the title would be.
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new StorageMenu(id, inventory, new SimpleContainer(54), network, filter, title, onClose, search, clientUi, project, freeOnly),
				clientUi ? Component.empty() : Component.literal(title)));

		if (clientUi && player.containerMenu instanceof StorageMenu menu) {
			TerminalNetworking.sendOpened(player, menu.containerId, menu.sort(), project != null);
			menu.sendPage(player);
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
			row = 0;
			refresh();
			sync();
		}
	}

	/** Switches the sort order and returns the new one. */
	public Sort toggleSort() {
		SORTS.put(viewer.getUUID(), next(sort()));
		row = 0;
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

		ProjectMenu.refreshAll(network);
	}

	/** The amount this screen shows of a stored item, see {@link #project} and {@link #freeOnly}. */
	private long shownCount(StorageNetwork.StoredItem entry) {
		if (project != null) {
			return Projects.available(network, entry, project);
		}

		return freeOnly ? Projects.available(network, entry, null) : entry.count;
	}

	/** The icon with what projects add: in a project chest what the project needs, elsewhere which projects block how many. */
	private ItemStack iconFor(StorageNetwork.StoredItem entry) {
		long count = shownCount(entry);
		ItemStack icon = icon(entry.prototype, count);
		List<Component> extra = new ArrayList<>();

		if (project != null) {
			Projects.Entry wanted = project.entryFor(entry.prototype);

			if (wanted != null) {
				if (count >= wanted.target) {
					extra.add(Component.literal("✔ " + format(wanted.target) + " von " + format(wanted.target) + " da").withStyle(ChatFormatting.GREEN));
					// the client draws the amount green
					CompoundTag tag = icon.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
					tag.putInt(COLOR_KEY, 0xFF55FF55);
					icon.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
				} else {
					extra.add(Component.literal(format(count) + " von " + format(wanted.target) + " da").withStyle(ChatFormatting.AQUA));
					extra.add(Component.literal("Fehlen: " + format(wanted.target - count)).withStyle(ChatFormatting.RED));
				}
			}
		} else if (!freeOnly) {
			List<Component> blocked = Projects.blockedLines(network, entry.prototype, null);

			if (!blocked.isEmpty()) {
				extra.addAll(blocked);
				extra.add(Component.literal("Frei: " + format(Projects.available(network, entry, null))).withStyle(ChatFormatting.GREEN));
			}
		}

		if (!extra.isEmpty()) {
			List<Component> lore = new ArrayList<>(icon.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
			lore.addAll(1, extra);
			icon.set(DataComponents.LORE, new ItemLore(lore));
		}

		return icon;
	}

	private boolean accepts(ItemStack stack) {
		return !stack.isEmpty() && (filter == null || filter.test(stack));
	}

	private boolean matchesSearch(ItemStack stack) {
		if (search.isBlank()) {
			return true;
		}

		return ItemSearch.matches(stack, ItemSearch.query(search));
	}

	private List<StorageNetwork.StoredItem> visible() {
		List<StorageNetwork.StoredItem> list = new ArrayList<>();

		for (StorageNetwork.StoredItem entry : network.items) {
			if (accepts(entry.prototype) && matchesSearch(entry.prototype) && shownCount(entry) > 0) {
				list.add(entry);
			}
		}

		if (sort() == Sort.NAME) {
			Collator collator = Collator.getInstance(Locale.GERMAN);
			list.sort(Comparator.comparing((StorageNetwork.StoredItem e) -> e.prototype.getHoverName().getString(), collator));
		} else {
			list.sort(Comparator.comparingLong(this::shownCount).reversed());
		}

		return list;
	}

	private void refresh() {
		List<StorageNetwork.StoredItem> all = visible();
		int pages = Math.max(1, (all.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
		maxRow = Math.max(0, (all.size() + COLUMNS - 1) / COLUMNS - ITEM_SLOTS / COLUMNS);
		row = Math.clamp(row, 0, maxRow);
		shown = all.subList(row * COLUMNS, Math.min(all.size(), row * COLUMNS + ITEM_SLOTS));

		for (int i = 0; i < 54; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}

		for (int i = 0; i < shown.size(); i++) {
			StorageNetwork.StoredItem entry = shown.get(i);
			display.setItem(i, iconFor(entry));
		}

		this.pages = pages;
		kinds = all.size();
		total = all.stream().mapToLong(this::shownCount).sum();

		// the client mod draws real buttons in the bottom row instead
		if (clientUi) {
			return;
		}

		ItemStack filler = button(Items.GRAY_STAINED_GLASS_PANE, " ");

		for (int i = ITEM_SLOTS; i < 54; i++) {
			display.setItem(i, filler.copy());
		}

		if (row > 0) {
			display.setItem(SLOT_PREV, button(Items.ARROW, "Vorherige Seite"));
		}

		if (row < maxRow) {
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
		display.setItem(SLOT_INFO, button(Items.ENDER_EYE, "Seite " + (page() + 1) + "/" + pages + " · "
				+ all.size() + " Sorten · " + format(total) + " Items"));

		if (project != null) {
			ItemStack settings = button(Items.COMPARATOR, "⚙ Projekt einstellen");
			settings.set(DataComponents.LORE, new ItemLore(List.of(
					Component.literal("Items, Mengen, Name und Blocken").withStyle(ChatFormatting.GRAY))));
			display.setItem(SLOT_SETTINGS, settings);
		}
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
				Component.literal("Linksklick: 1 Stapel nehmen").withStyle(ChatFormatting.GRAY),
				Component.literal("Rechtsklick: 1 Item nehmen").withStyle(ChatFormatting.GRAY),
				Component.literal("Shift-Linksklick: Inventar füllen (ohne Hotbar)").withStyle(ChatFormatting.GRAY),
				Component.literal("Shift-Rechtsklick: halbes Inventar füllen").withStyle(ChatFormatting.GRAY))));
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
				row = 0;
			} else {
				SearchMenu.open(serverPlayer, search, text -> open(serverPlayer, network, filter, title, onClose, text, project, freeOnly));
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
		if (slot == SLOT_PREV && row > 0) {
			row = Math.max(0, row - PAGE_ROWS);
			return;
		}

		if (slot == SLOT_NEXT) {
			row = Math.min(maxRow, row + PAGE_ROWS);
			return;
		}

		if (slot == SLOT_SORT && !clientUi) {
			SORTS.put(player.getUUID(), next(sort()));
			row = 0;
			return;
		}

		if (slot == SLOT_SETTINGS && project != null && player instanceof ServerPlayer serverPlayer) {
			Projects.openSettings(serverPlayer, project);
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

		ItemStack taken = ItemStack.EMPTY;

		if (input == ContainerInput.QUICK_MOVE) {
			// shift-click fills the inventory without the hotbar, shift-right-click half of it
			int got = InventoryFill.fill(player.getInventory(), prototype, button == 1, amount -> network.extract(prototype, amount, project));
			taken = got > 0 ? prototype : ItemStack.EMPTY;
		} else if (input == ContainerInput.PICKUP && carried.isEmpty()) {
			// click: one stack, right-click: one item
			taken = network.extract(prototype, button == 1 ? 1 : max, project);
			setCarried(taken);
		}

		if (taken.isEmpty() && (input == ContainerInput.QUICK_MOVE || input == ContainerInput.PICKUP) && player instanceof ServerPlayer serverPlayer) {
			List<Component> blocked = Projects.blockedLines(network, prototype, project);

			if (!blocked.isEmpty()) {
				serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(blocked.get(0)));
			}
		}
	}

	private void sync() {
		if (viewer instanceof ServerPlayer player) {
			sendAllDataToRemote();

			if (clientUi) {
				sendPage(player);
			}
		}
	}

	/** The page the shown rows belong to; after scrolling to the end it is the last one. */
	private int page() {
		return row >= maxRow ? pages - 1 : row / PAGE_ROWS;
	}

	private void sendPage(ServerPlayer player) {
		TerminalNetworking.sendPage(player, containerId, page(), pages, kinds, total, row > 0, row < maxRow);
	}

	/** The client mod's mouse wheel: scrolls the shown items by whole rows, positive is down. */
	public void scroll(int rows) {
		int before = row;
		row = Math.clamp((long) row + rows, 0, maxRow);

		if (row != before) {
			refresh();
			sync();
		}
	}

	/** A button of the client mod's bottom row: previous page, next page, deposit the inventory. */
	public void pressButton(int button) {
		int slot = switch (button) {
			case TerminalNetworking.BUTTON_PREV -> SLOT_PREV;
			case TerminalNetworking.BUTTON_NEXT -> SLOT_NEXT;
			case TerminalNetworking.BUTTON_DEPOSIT -> SLOT_DEPOSIT;
			case TerminalNetworking.BUTTON_SETTINGS -> SLOT_SETTINGS;
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

		if (onClose != null) {
			onClose.run();
		}
		// The display container must never drop its icons.
		for (int i = 0; i < 54; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}
	}
}
