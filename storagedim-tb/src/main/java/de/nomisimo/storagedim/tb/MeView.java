package de.nomisimo.storagedim.tb;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The ME storage shown inside the backpack window, in place of the backpack's own slots. Crafting, the other upgrades
 * and the settings stay as they are. The ME upgrade's icon switches between the two.
 *
 * <p>Both sides swap the backpack's storage slots for {@link MeSlot}s at the same positions, so the slot numbers stay
 * the same. The server fills them page by page with icons of the stored items and handles every click on them.
 */
final class MeView {
	static final int MAX_SEARCH = 50;
	private static final Map<AbstractContainerMenu, MeView> VIEWS = Collections.synchronizedMap(new WeakHashMap<>());
	/** Players who sort the ME view by name instead of amount, until the server restarts. */
	private static final Set<UUID> BY_NAME = ConcurrentHashMap.newKeySet();

	private final BackpackBaseMenu menu;
	private final boolean client;
	private SimpleContainer display = new SimpleContainer(0);
	/** The backpack's own slots while the ME slots are in their place, or null. */
	private List<Slot> originals;
	private List<ItemStack> shown = List.of();
	private int page;
	private boolean hasNext;
	private String search = "";
	/** The last row of ME slots is covered by the buttons and the page info, like the bottom row of the ME terminal. */
	private int barSlots;

	private MeView(BackpackBaseMenu menu, boolean client) {
		this.menu = menu;
		this.client = client;
	}

	static MeView of(AbstractContainerMenu menu) {
		return VIEWS.get(menu);
	}

	static boolean isOpen(AbstractContainerMenu menu) {
		return VIEWS.containsKey(menu);
	}

	/** Switches the ME view on or off for this menu. Returns whether it is on afterwards. */
	static boolean set(BackpackBaseMenu menu, boolean on, boolean client) {
		MeView view = VIEWS.get(menu);

		if (on && view == null) {
			view = new MeView(menu, client);
			VIEWS.put(menu, view);
			view.apply();
		} else if (!on && view != null) {
			view.restore();
			VIEWS.remove(menu);
		}

		return on;
	}

	/** The slot the backpack really has at this index, also while the ME view covers it. */
	static Slot realSlot(BackpackBaseMenu menu, int index) {
		MeView view = VIEWS.get(menu);

		if (view != null && view.originals != null) {
			int k = index - menu.BACKPACK_INV_START;

			if (k >= 0 && k < view.originals.size()) {
				return view.originals.get(k);
			}
		}

		return menu.getSlot(index);
	}

	/** Puts ME slots where the backpack's storage slots are; picks up the current backpack slots first. */
	void apply() {
		restore();
		int start = menu.BACKPACK_INV_START;
		int end = Math.min(menu.BACKPACK_INV_END, menu.slots.size());

		if (display.getContainerSize() != end - start) {
			display = new SimpleContainer(Math.max(0, end - start));
		}

		List<Slot> saved = new ArrayList<>();

		for (int i = start; i < end; i++) {
			Slot original = menu.slots.get(i);
			saved.add(original);
			MeSlot slot = new MeSlot(display, i - start, original.x, original.y, client);
			slot.index = i;
			menu.slots.set(i, slot);
		}

		originals = saved;
		barSlots = bottomRow(saved);

		if (barSlots > 0) {
			int bottom = saved.stream().mapToInt(slot -> slot.y).max().orElse(0);

			for (int i = start; i < end; i++) {
				((MeSlot) menu.slots.get(i)).covered = menu.slots.get(i).y == bottom;
			}
		}
	}

	/** How many of these slots lie in their bottom row; 0 when there is only one row (then nothing is covered). */
	static int bottomRow(List<Slot> slots) {
		int top = Integer.MAX_VALUE;
		int bottom = Integer.MIN_VALUE;

		for (Slot slot : slots) {
			top = Math.min(top, slot.y);
			bottom = Math.max(bottom, slot.y);
		}

		if (slots.isEmpty() || top == bottom) {
			return 0;
		}

		int count = 0;

		for (Slot slot : slots) {
			if (slot.y == bottom) {
				count++;
			}
		}

		return count;
	}

	/** Gives the backpack its own slots back. */
	void restore() {
		if (originals == null) {
			return;
		}

		for (int k = 0; k < originals.size(); k++) {
			int index = menu.BACKPACK_INV_START + k;

			if (index < menu.slots.size() && menu.slots.get(index) instanceof MeSlot) {
				menu.slots.set(index, originals.get(k));
			}
		}

		originals = null;
	}

	/** Redraws the current page from the ME storage. Server only. */
	void refresh(ServerPlayer player) {
		List<ItemStack> prototypes = new ArrayList<>();
		List<Long> counts = new ArrayList<>();
		MeBridge.stored(player, prototypes, counts);

		if (!search.isBlank()) {
			String query = search.trim().toLowerCase(Locale.ROOT);

			for (int i = prototypes.size() - 1; i >= 0; i--) {
				ItemStack stack = prototypes.get(i);

				if (!stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)
						&& !BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains(query)) {
					prototypes.remove(i);
					counts.remove(i);
				}
			}
		}

		if (BY_NAME.contains(player.getUUID())) {
			List<Integer> order = new ArrayList<>();

			for (int i = 0; i < prototypes.size(); i++) {
				order.add(i);
			}

			Collator collator = Collator.getInstance(Locale.GERMAN);
			order.sort(Comparator.comparing(i -> prototypes.get(i).getHoverName().getString(), collator));
			List<ItemStack> sortedItems = new ArrayList<>();
			List<Long> sortedCounts = new ArrayList<>();

			for (int i : order) {
				sortedItems.add(prototypes.get(i));
				sortedCounts.add(counts.get(i));
			}

			prototypes.clear();
			prototypes.addAll(sortedItems);
			counts.clear();
			counts.addAll(sortedCounts);
		}

		int size = display.getContainerSize();
		// the bottom row stays free for the buttons drawn over it
		int perPage = Math.max(1, size - barSlots);
		int pages = Math.max(1, (prototypes.size() + perPage - 1) / perPage);
		page = Math.min(page, pages - 1);
		hasNext = page < pages - 1;
		int from = page * perPage;
		int to = Math.min(prototypes.size(), from + perPage);
		shown = prototypes.subList(from, to);

		for (int i = 0; i < size; i++) {
			int k = from + i;
			display.setItem(i, i < perPage && k < to ? MeBridge.icon(prototypes.get(k), counts.get(k)) : ItemStack.EMPTY);
		}

		long total = 0;

		for (long count : counts) {
			total += count;
		}

		if (ServerPlayNetworking.canSend(player, MeNetworking.PagePayload.TYPE)) {
			ServerPlayNetworking.send(player, new MeNetworking.PagePayload(menu.containerId, page, pages, prototypes.size(), total,
					BY_NAME.contains(player.getUUID())));
		}
	}

	/** A button of the ME view: previous or next page, deposit the inventory (without hotbar), sort order. Server only. */
	void press(ServerPlayer player, int button) {
		switch (button) {
			case MeNetworking.BUTTON_PREV -> page = Math.max(0, page - 1);
			case MeNetworking.BUTTON_NEXT -> {
				if (hasNext) {
					page++;
				}
			}
			case MeNetworking.BUTTON_DEPOSIT -> {
				Inventory inventory = player.getInventory();

				// slots 9 to 35: the inventory above the hotbar
				for (int i = 9; i < 36; i++) {
					ItemStack stack = inventory.getItem(i);

					if (!stack.isEmpty()) {
						MeBridge.insert(player, stack);
						inventory.setChanged();
					}
				}
			}
			case MeNetworking.BUTTON_SORT -> {
				if (!BY_NAME.remove(player.getUUID())) {
					BY_NAME.add(player.getUUID());
				}
			}
			default -> {
				return;
			}
		}

		refresh(player);
		menu.sendAllDataToRemote();
	}

	/** Text typed into the search field above the window. Server only. */
	void setSearch(ServerPlayer player, String text) {
		String clean = text.length() > MAX_SEARCH ? text.substring(0, MAX_SEARCH) : text;

		if (!clean.equals(search)) {
			search = clean;
			page = 0;
			refresh(player);
			menu.sendAllDataToRemote();
		}
	}

	/**
	 * Handles a click on an ME slot, or a shift-click in the player inventory (which stores the stack in ME).
	 * Returns false for every other click, which the backpack handles as usual. The client only skips its own guess;
	 * the server answers with the real state.
	 */
	boolean click(int index, int button, ContainerInput input, Player player) {
		int k = index - menu.BACKPACK_INV_START;
		boolean onMe = originals != null && k >= 0 && k < originals.size();
		boolean storeFromInventory = input == ContainerInput.QUICK_MOVE && index >= menu.PLAYER_INV_START && index < menu.PLAYER_HOT_END;

		if (!onMe && !storeFromInventory) {
			return false;
		}

		if (client || !(player instanceof ServerPlayer serverPlayer)) {
			return true;
		}

		if (storeFromInventory) {
			Slot slot = menu.getSlot(index);
			MeBridge.insert(serverPlayer, slot.getItem());
			slot.setChanged();
		} else {
			clickMe(k, button, input, serverPlayer);
		}

		refresh(serverPlayer);
		menu.sendAllDataToRemote();
		return true;
	}

	private void clickMe(int k, int button, ContainerInput input, ServerPlayer player) {
		// the covered bottom row does nothing
		if (k >= display.getContainerSize() - barSlots) {
			return;
		}

		ItemStack carried = menu.getCarried();

		if (input == ContainerInput.PICKUP && !carried.isEmpty()) {
			MeBridge.insert(player, button == 1 ? carried.split(1) : carried);

			if (carried.isEmpty()) {
				menu.setCarried(ItemStack.EMPTY);
			}

			return;
		}

		if (k >= shown.size()) {
			return;
		}

		ItemStack prototype = shown.get(k);
		int max = prototype.getMaxStackSize();

		if (input == ContainerInput.QUICK_MOVE) {
			ItemStack taken = MeBridge.extract(player, prototype, max);
			player.getInventory().add(taken);

			if (!taken.isEmpty()) {
				MeBridge.insert(player, taken);
			}
		} else if (input == ContainerInput.PICKUP && carried.isEmpty()) {
			menu.setCarried(MeBridge.extract(player, prototype, button == 1 ? Math.max(1, max / 2) : max));
		}
	}
}
