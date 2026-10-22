package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.item.ItemStack;

/**
 * An ME-style item store: every distinct item (item + components) is one entry with a long count.
 * A network belongs to one player and can be shared with members.
 */
public class StorageNetwork {
	public final UUID owner;
	public final Set<UUID> members = new LinkedHashSet<>();
	public int room;
	final List<StoredItem> items = new ArrayList<>();

	public StorageNetwork(UUID owner, int room) {
		this.owner = owner;
		this.room = room;
	}

	public static final class StoredItem {
		/** Prototype with count 1; never handed out directly. */
		public final ItemStack prototype;
		public long count;

		StoredItem(ItemStack prototype, long count) {
			this.prototype = prototype;
			this.count = count;
		}
	}

	/** Takes the whole stack into the network and empties it. */
	public void insert(ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}

		StoredItem entry = find(stack);

		if (entry == null) {
			items.add(new StoredItem(stack.copyWithCount(1), stack.getCount()));
		} else {
			entry.count += stack.getCount();
		}

		stack.setCount(0);
		StorageData.markDirty();
	}

	/** Removes up to {@code amount} items matching the prototype and returns them as a stack. */
	public ItemStack extract(ItemStack prototype, int amount) {
		StoredItem entry = find(prototype);

		if (entry == null || amount <= 0) {
			return ItemStack.EMPTY;
		}

		int taken = (int) Math.min(amount, entry.count);
		entry.count -= taken;

		if (entry.count <= 0) {
			items.remove(entry);
		}

		StorageData.markDirty();
		return entry.prototype.copyWithCount(taken);
	}

	public StoredItem find(ItemStack stack) {
		for (StoredItem entry : items) {
			if (ItemStack.isSameItemSameComponents(entry.prototype, stack)) {
				return entry;
			}
		}

		return null;
	}

	/** Entries sorted by amount, largest first. */
	public List<StoredItem> sorted() {
		List<StoredItem> list = new ArrayList<>(items);
		list.sort(Comparator.comparingLong((StoredItem e) -> e.count).reversed());
		return list;
	}

	public long totalCount() {
		long total = 0;

		for (StoredItem entry : items) {
			total += entry.count;
		}

		return total;
	}

	public int typeCount() {
		return items.size();
	}

	public boolean canAccess(UUID player) {
		return owner.equals(player) || members.contains(player);
	}
}
