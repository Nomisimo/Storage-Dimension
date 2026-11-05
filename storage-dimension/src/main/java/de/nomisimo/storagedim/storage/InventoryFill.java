package de.nomisimo.storagedim.storage;

import java.util.function.IntFunction;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Shift-clicks on a stored item: fill the main inventory (slots 9 to 35, never the hotbar) with it, the whole free
 * space or half of it. Stacks of the item are topped up first, then empty slots are used. When the storage holds
 * less, everything it has is taken.
 */
public final class InventoryFill {
	private InventoryFill() {
	}

	/** How many of the item still fit into the main inventory. */
	public static int space(Inventory inventory, ItemStack prototype) {
		int max = prototype.getMaxStackSize();
		int space = 0;

		for (int i = 9; i < 36; i++) {
			ItemStack stack = inventory.getItem(i);

			if (stack.isEmpty()) {
				space += max;
			} else if (ItemStack.isSameItemSameComponents(stack, prototype)) {
				space += Math.max(0, max - stack.getCount());
			}
		}

		return space;
	}

	/**
	 * Fills the main inventory with the item, taken through {@code extract} (amount in, stack out, empty when nothing is
	 * left). With {@code half} only half of the free space is filled. Returns how many items were taken.
	 */
	public static int fill(Inventory inventory, ItemStack prototype, boolean half, IntFunction<ItemStack> extract) {
		int max = prototype.getMaxStackSize();
		int space = space(inventory, prototype);
		int left = half ? (space + 1) / 2 : space;
		int taken = 0;

		for (boolean emptySlots : new boolean[] {false, true}) {
			for (int i = 9; i < 36 && left > 0; i++) {
				ItemStack stack = inventory.getItem(i);
				boolean fits = emptySlots ? stack.isEmpty() : !stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, prototype);

				if (!fits || stack.getCount() >= max) {
					continue;
				}

				ItemStack got = extract.apply(Math.min(max - stack.getCount(), left));

				if (got.isEmpty()) {
					inventory.setChanged();
					return taken;
				}

				if (stack.isEmpty()) {
					inventory.setItem(i, got);
				} else {
					stack.grow(got.getCount());
				}

				left -= got.getCount();
				taken += got.getCount();
			}
		}

		inventory.setChanged();
		return taken;
	}
}
