package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.world.item.ItemStack;

/**
 * Filter chests: placed chests that open the ME terminal limited to up to {@link #SLOTS} chosen items,
 * one per item frame of the recipe. Two filter chests side by side form a double chest with
 * {@link #DOUBLE_SLOTS} filters, kept on the half with the smaller position.
 */
public final class FilterChests {
	public static final int SLOTS = 7;
	public static final int DOUBLE_SLOTS = 14;

	private FilterChests() {
	}

	/** One placed filter chest. {@code filters} has {@link #SLOTS} or {@link #DOUBLE_SLOTS} entries; unused ones are empty. */
	public record FilterChest(UUID owner, List<ItemStack> filters) {
		public static FilterChest empty(UUID owner) {
			List<ItemStack> filters = new ArrayList<>();

			for (int i = 0; i < SLOTS; i++) {
				filters.add(ItemStack.EMPTY);
			}

			return new FilterChest(owner, filters);
		}

		public boolean hasFilters() {
			return filters.stream().anyMatch(stack -> !stack.isEmpty());
		}

		/** Pads with empty slots, or, when shrinking, keeps the set filters first and drops the rest. */
		public void resize(int size) {
			if (filters.size() > size) {
				List<ItemStack> kept = new ArrayList<>(active());
				filters.clear();
				filters.addAll(kept.subList(0, Math.min(size, kept.size())));
			}

			while (filters.size() < size) {
				filters.add(ItemStack.EMPTY);
			}
		}

		/** The set filter items without the empty slots. */
		public List<ItemStack> active() {
			return filters.stream().filter(stack -> !stack.isEmpty()).toList();
		}
	}

	/** Matches by item type, so e.g. damaged tools or enchanted books of one kind all count. */
	public static boolean matches(List<ItemStack> filters, ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		for (ItemStack filter : filters) {
			if (!filter.isEmpty() && ItemStack.isSameItem(filter, stack)) {
				return true;
			}
		}

		return false;
	}
}
