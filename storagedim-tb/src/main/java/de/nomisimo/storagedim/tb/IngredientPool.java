package de.nomisimo.storagedim.tb;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

/**
 * Items that can go into the crafting grid, counted by type. Hands out one item per grid slot, so a recipe only counts
 * as possible when there is enough of everything at once. Used by the server to fill the grid and by the client to tell
 * JEI whether "+" can work.
 */
final class IngredientPool {
	private final List<ItemStack> types = new ArrayList<>();
	private final List<Long> amounts = new ArrayList<>();

	void add(ItemStack stack, long amount) {
		if (stack.isEmpty() || amount <= 0) {
			return;
		}

		for (int i = 0; i < types.size(); i++) {
			if (ItemStack.isSameItemSameComponents(types.get(i), stack)) {
				amounts.set(i, amounts.get(i) + amount);
				return;
			}
		}

		types.add(stack.copyWithCount(1));
		amounts.add(amount);
	}

	void add(ItemStack stack) {
		add(stack, stack.getCount());
	}

	/** Takes one item that fits any of the wanted stacks, exact matches first. Empty when nothing fits any more. */
	ItemStack take(List<ItemStack> wanted) {
		for (boolean exact : new boolean[] {true, false}) {
			for (int i = 0; i < types.size(); i++) {
				ItemStack type = types.get(i);

				if (amounts.get(i) > 0 && wanted.stream().anyMatch(w -> matches(type, w, exact))) {
					amounts.set(i, amounts.get(i) - 1);
					return type;
				}
			}
		}

		return ItemStack.EMPTY;
	}

	/**
	 * JEI lists plain items for tag ingredients, so a loose match only compares the item.
	 * Candidates that carry components (potions, enchanted books, ...) always need an exact match.
	 */
	static boolean matches(ItemStack stack, ItemStack wanted, boolean exact) {
		if (ItemStack.isSameItemSameComponents(stack, wanted)) {
			return true;
		}

		return !exact && stack.is(wanted.getItem()) && wanted.getComponentsPatch().isEmpty();
	}
}
