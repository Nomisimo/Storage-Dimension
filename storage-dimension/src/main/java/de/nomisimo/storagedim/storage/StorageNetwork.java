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
	/** The chests of this network's library in the storage dimension, see {@link Library}. */
	public final List<Library.Shelf> library = new ArrayList<>();
	/** The aisles of the library, west to east. */
	public final List<Library.Aisle> aisles = new ArrayList<>();
	/** Counts the rebuilds, so the labels of an older build can be told apart; 0 while nothing is built. */
	public int libraryBuild;
	/** Radius of the chest island of versions up to 0.7, still to be taken down; 0 when there is none. */
	public int oldIslandRadius;

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

	/**
	 * Removes up to {@code amount} items matching the prototype and returns them as a stack. What blocking projects
	 * keep stays in, see {@link Projects}.
	 */
	public ItemStack extract(ItemStack prototype, int amount) {
		return extract(prototype, amount, null);
	}

	/** Like {@link #extract(ItemStack, int)}, for a project chest: the project's own blocked items may go too. */
	public ItemStack extract(ItemStack prototype, int amount, Projects.Project project) {
		StoredItem entry = find(prototype);

		if (entry == null || amount <= 0) {
			return ItemStack.EMPTY;
		}

		int taken = (int) Math.min(amount, Projects.available(this, entry, project));

		if (taken <= 0) {
			return ItemStack.EMPTY;
		}

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

	/** Read-only view of the entries in storage order. */
	public List<StoredItem> entries() {
		return java.util.Collections.unmodifiableList(items);
	}

	/** How many of this item can be taken out, after what blocking projects keep. */
	public long available(ItemStack prototype) {
		StoredItem entry = find(prototype);
		return entry == null ? 0 : Projects.available(this, entry, null);
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
		return owner.equals(StorageData.SHARED) || owner.equals(player) || members.contains(player);
	}
}
