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
	private static final Set<StorageMenu> OPEN = java.util.Collections.newSetFromMap(new WeakHashMap<>());
	/** Search text and sort order per player, kept until the server restarts. */
	private static final Map<UUID, View> VIEWS = new HashMap<>();

	private final StorageNetwork network;
	private final SimpleContainer display;
	private final Player viewer;
	/** Filter items of a filter chest, or null for the full terminal. */
	private final List<ItemStack> filter;
	private final View view;
	private List<StorageNetwork.StoredItem> shown = new ArrayList<>();
	private int page;

	public enum Sort {
		AMOUNT("Menge"), NAME("Alphabet");

		final String label;

		Sort(String label) {
			this.label = label;
		}
	}

	private static final class View {
		Sort sort = Sort.AMOUNT;
		String search = "";
	}

	private StorageMenu(int containerId, Inventory inventory, SimpleContainer display, StorageNetwork network, List<ItemStack> filter) {
		super(MenuType.GENERIC_9x6, containerId, inventory, display, 6);
		this.network = network;
		this.display = display;
		this.viewer = inventory.player;
		this.filter = filter;
		this.view = VIEWS.computeIfAbsent(inventory.player.getUUID(), id -> new View());
		refresh();
		OPEN.add(this);
	}

	public static void open(ServerPlayer player, StorageNetwork network) {
		open(player, network, null);
	}

	/** Opens the terminal; with a filter list only those items are shown and accepted. */
	public static void open(ServerPlayer player, StorageNetwork network, List<ItemStack> filter) {
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new StorageMenu(id, inventory, new SimpleContainer(54), network, filter),
				Component.literal(filter == null ? "ME-Speicher" : "Filterkiste")));
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
		if (view.search.isEmpty()) {
			return true;
		}

		String query = view.search.toLowerCase(Locale.ROOT);
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

		if (view.sort == Sort.NAME) {
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
			ItemStack icon = displayIcon(entry);
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

		ItemStack search = button(Items.OAK_SIGN, view.search.isEmpty() ? "Suchen" : "Suche: " + view.search);
		search.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Linksklick: Suchbegriff eingeben").withStyle(ChatFormatting.GRAY),
				Component.literal("Rechtsklick: Suche löschen").withStyle(ChatFormatting.GRAY))));
		display.setItem(SLOT_SEARCH, search);

		ItemStack sort = button(Items.COMPARATOR, "Sortierung: " + view.sort.label);
		sort.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Klick: nach " + next(view.sort).label + " sortieren").withStyle(ChatFormatting.GRAY))));
		display.setItem(SLOT_SORT, sort);

		display.setItem(SLOT_DEPOSIT, button(Items.HOPPER, filter == null
				? "Inventar einlagern (ohne Hotbar)" : "Passende Items einlagern (ohne Hotbar)"));
		display.setItem(SLOT_INFO, button(Items.ENDER_EYE, "Seite " + (page + 1) + "/" + pages + " · "
				+ all.size() + " Sorten · " + format(all.stream().mapToLong(e -> e.count).sum()) + " Items"));
	}

	/**
	 * The icon of a stored item. A slot can only show up to 99, so stackable items go up to 99 on the icon
	 * and the exact amount is always part of the name.
	 */
	private static ItemStack displayIcon(StorageNetwork.StoredItem entry) {
		ItemStack icon = entry.prototype.copyWithCount(1);
		int max = icon.getMaxStackSize();

		if (max > 1) {
			icon.set(DataComponents.MAX_STACK_SIZE, ICON_MAX);
			icon.setCount((int) Math.min(entry.count, ICON_MAX));
		}

		icon.set(DataComponents.CUSTOM_NAME, Component.empty()
				.append(entry.prototype.getHoverName())
				.append(Component.literal(" × " + format(entry.count)).withStyle(ChatFormatting.GOLD))
				.withStyle(s -> s.withItalic(false)));
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
		if (slotIndex == SLOT_SEARCH && player instanceof ServerPlayer serverPlayer) {
			if (buttonNum == 1) {
				view.search = "";
				page = 0;
			} else {
				SearchMenu.open(serverPlayer, view.search, text -> {
					view.search = text;
					open(serverPlayer, network, filter);
				});
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

		if (slot == SLOT_SORT) {
			view.sort = next(view.sort);
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
