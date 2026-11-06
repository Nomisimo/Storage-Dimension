package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
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

	/**
	 * The components that make one kind of an item: a filter item that has one of them set (other than the item's default)
	 * only matches items with exactly the same value, e.g. a potion of healing only potions of healing, an enchanted book
	 * only books with the same enchantments.
	 */
	private static final List<DataComponentType<?>> VARIANTS = List.of(
			DataComponents.ENCHANTMENTS,
			DataComponents.STORED_ENCHANTMENTS,
			DataComponents.POTION_CONTENTS,
			DataComponents.OMINOUS_BOTTLE_AMPLIFIER,
			DataComponents.INSTRUMENT);

	/**
	 * Matches by item type, so e.g. damaged tools of one kind all count. A filter item of a certain kind (potion effect,
	 * enchantments, see {@link #VARIANTS}) only matches items of exactly that kind.
	 */
	public static boolean matches(List<ItemStack> filters, ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}

		for (ItemStack filter : filters) {
			if (!filter.isEmpty() && ItemStack.isSameItem(filter, stack) && sameVariant(filter, stack)) {
				return true;
			}
		}

		return false;
	}

	/** The item to remember for matching: a damaged tool counts as the plain tool, so every one of its kind matches. */
	public static ItemStack matchKey(ItemStack stack) {
		return stack.isDamageableItem() ? new ItemStack(stack.getItem()) : stack.copyWithCount(1);
	}

	private static boolean sameVariant(ItemStack filter, ItemStack stack) {
		ItemStack plain = filter.getItem().getDefaultInstance();

		for (DataComponentType<?> type : VARIANTS) {
			Object wanted = filter.get(type);

			if (!Objects.equals(wanted, plain.get(type)) && !Objects.equals(wanted, stack.get(type))) {
				return false;
			}
		}

		return true;
	}
}
