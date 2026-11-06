package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A chest of the library: a plain chest screen with 27 slots of real stacks (64 or whatever the item stacks to).
 * Chest n of a kind shows stacks 27·n to 27·n + 26 of everything the network holds of that kind, minus what projects
 * block. Taking a stack out takes it out of ME storage, putting one in stores it. The chests of a kind always look
 * filled from the first one on, so the last chest of a kind empties first.
 * <p>
 * Before every click the slots are filled from ME storage; after it, whatever changed in the slots is taken out of or
 * put into ME storage.
 */
public class ShelfMenu extends AbstractContainerMenu {
	private static final Set<ShelfMenu> OPEN = new HashSet<>();

	private final SimpleContainer container = new SimpleContainer(Library.CHEST_SLOTS);
	private final StorageNetwork network;
	private final ItemStack key;
	private final int part;
	private final Runnable onClose;
	private final Player viewer;

	private ShelfMenu(int containerId, Inventory inventory, StorageNetwork network, ItemStack key, int part, Runnable onClose) {
		super(MenuType.GENERIC_9x3, containerId);
		this.network = network;
		this.key = key;
		this.part = part;
		this.onClose = onClose;
		this.viewer = inventory.player;

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(container, col + row * 9, 8 + col * 18, 18 + row * 18) {
					@Override
					public boolean mayPlace(ItemStack stack) {
						return Library.inGroup(ShelfMenu.this.key, stack);
					}
				});
			}
		}

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 85 + row * 18));
			}
		}

		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(inventory, col, 8 + col * 18, 143));
		}

		fill();
		OPEN.add(this);
	}

	public static void open(ServerPlayer player, StorageNetwork network, Library.Shelf shelf, Runnable onClose) {
		long parts = network.library.stream().filter(s -> ItemStack.isSameItemSameComponents(s.key(), shelf.key())).count();
		Component title = Component.empty().append(shelf.key().getHoverName()).append(parts > 1 ? " (" + (shelf.part() + 1) + "/" + parts + ")" : "");
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new ShelfMenu(id, inventory, network, shelf.key(), shelf.part(), onClose), title));
	}

	/** Redraws every open library chest of this network, e.g. after a hopper delivered items. */
	public static void refreshAll(StorageNetwork network) {
		for (ShelfMenu menu : new ArrayList<>(OPEN)) {
			if (menu.network == network) {
				List<ItemStack> before = menu.snapshot();
				menu.fill();

				for (int i = 0; i < Library.CHEST_SLOTS; i++) {
					if (!ItemStack.matches(before.get(i), menu.container.getItem(i))) {
						menu.sync();
						break;
					}
				}
			}
		}
	}

	/** Puts this chest's share of the stored items into the slots. */
	private void fill() {
		List<ItemStack> stacks = new ArrayList<>();
		// stacks that belong to the earlier chests of this kind
		long skip = (long) part * Library.CHEST_SLOTS;

		for (StorageNetwork.StoredItem entry : network.entries()) {
			if (stacks.size() >= Library.CHEST_SLOTS) {
				break;
			}

			if (!Library.inGroup(key, entry.prototype)) {
				continue;
			}

			long left = Projects.available(network, entry, null);
			int max = Math.max(1, entry.prototype.getMaxStackSize());
			long entryStacks = (left + max - 1) / max;

			if (skip >= entryStacks) {
				skip -= entryStacks;
				continue;
			}

			left -= skip * max;
			skip = 0;

			while (left > 0 && stacks.size() < Library.CHEST_SLOTS) {
				int count = (int) Math.min(max, left);
				left -= count;
				stacks.add(entry.prototype.copyWithCount(count));
			}
		}

		for (int i = 0; i < Library.CHEST_SLOTS; i++) {
			container.setItem(i, i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY);
		}
	}

	private List<ItemStack> snapshot() {
		List<ItemStack> list = new ArrayList<>();

		for (int i = 0; i < Library.CHEST_SLOTS; i++) {
			list.add(container.getItem(i).copy());
		}

		return list;
	}

	/** Takes out of or puts into ME storage what changed in the slots since {@code before}. */
	private void commit(List<ItemStack> before) {
		List<ItemStack> kinds = new ArrayList<>();
		List<long[]> deltas = new ArrayList<>();

		for (int i = 0; i < Library.CHEST_SLOTS; i++) {
			add(kinds, deltas, before.get(i), -1);
			add(kinds, deltas, container.getItem(i), 1);
		}

		for (int i = 0; i < kinds.size(); i++) {
			long delta = deltas.get(i)[0];
			ItemStack prototype = kinds.get(i);

			if (delta < 0) {
				long missing = -delta;

				while (missing > 0) {
					ItemStack got = network.extract(prototype, (int) Math.min(Integer.MAX_VALUE, missing));

					if (got.isEmpty()) {
						break;
					}

					missing -= got.getCount();
				}
			} else if (delta > 0) {
				network.insert(prototype.copyWithCount((int) delta));
			}
		}
	}

	private static void add(List<ItemStack> kinds, List<long[]> deltas, ItemStack stack, int sign) {
		if (stack.isEmpty()) {
			return;
		}

		for (int i = 0; i < kinds.size(); i++) {
			if (ItemStack.isSameItemSameComponents(kinds.get(i), stack)) {
				deltas.get(i)[0] += (long) sign * stack.getCount();
				return;
			}
		}

		kinds.add(stack.copyWithCount(1));
		deltas.add(new long[] {(long) sign * stack.getCount()});
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		fill();
		List<ItemStack> before = snapshot();
		super.clicked(slotIndex, buttonNum, input, player);
		commit(before);
		StorageMenu.refreshAll(network);
		fill();
		sync();
	}

	/** Shift-click: from the chest into the inventory, or from the inventory straight into ME storage (the chest may be full). */
	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		Slot slot = slots.get(slotIndex);

		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}

		ItemStack stack = slot.getItem();

		// one move per click: the slots are filled anew after it
		if (slotIndex < Library.CHEST_SLOTS) {
			if (moveItemStackTo(stack, Library.CHEST_SLOTS, slots.size(), true)) {
				slot.setChanged();
			}

			return ItemStack.EMPTY;
		}

		if (Library.inGroup(key, stack)) {
			network.insert(stack);
			slot.setChanged();
		}

		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	private void sync() {
		if (viewer instanceof ServerPlayer) {
			sendAllDataToRemote();
		}
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		OPEN.remove(this);

		for (int i = 0; i < Library.CHEST_SLOTS; i++) {
			container.setItem(i, ItemStack.EMPTY);
		}

		if (onClose != null) {
			onClose.run();
		}
	}
}
