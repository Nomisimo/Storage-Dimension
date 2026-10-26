package de.nomisimo.storagedim.tb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

	private final BackpackBaseMenu menu;
	private final boolean client;
	private SimpleContainer display = new SimpleContainer(0);
	/** The backpack's own slots while the ME slots are in their place, or null. */
	private List<Slot> originals;
	private List<ItemStack> shown = List.of();
	private int page;
	private boolean hasNext;
	private String search = "";

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

		int size = display.getContainerSize();
		// with more items than slots the last two slots turn the pages
		int perPage = prototypes.size() <= size ? size : Math.max(1, size - 2);
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

		if (pages > 1 && size >= 2) {
			String where = " (Seite " + (page + 1) + "/" + pages + ")";
			display.setItem(size - 2, page > 0 ? button("Vorherige Seite" + where) : ItemStack.EMPTY);
			display.setItem(size - 1, hasNext ? button("Nächste Seite" + where) : ItemStack.EMPTY);
		}
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

	private static ItemStack button(String name) {
		ItemStack stack = new ItemStack(Items.ARROW);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false)));
		return stack;
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
		int size = display.getContainerSize();
		boolean paged = shown.size() < size && (page > 0 || hasNext);

		if (paged && k == size - 2) {
			page = Math.max(0, page - 1);
			return;
		}

		if (paged && k == size - 1) {
			if (hasNext) {
				page++;
			}

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
