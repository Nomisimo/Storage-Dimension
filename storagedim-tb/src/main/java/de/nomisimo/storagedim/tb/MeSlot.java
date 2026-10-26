package de.nomisimo.storagedim.tb;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A display-only slot of the ME view in the backpack window. It shows a stored item as an icon; nothing can be put in
 * or taken out by vanilla code, every click is handled by {@link MeView}. Only the client accepts {@link #set}, so the
 * server's sync can draw the icons there.
 */
final class MeSlot extends Slot {
	private final boolean client;

	MeSlot(Container display, int slot, int x, int y, boolean client) {
		super(display, slot, x, y);
		this.client = client;
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return false;
	}

	@Override
	public boolean mayPickup(Player player) {
		return false;
	}

	@Override
	public boolean allowModification(Player player) {
		return false;
	}

	@Override
	public ItemStack remove(int amount) {
		return ItemStack.EMPTY;
	}

	@Override
	public void set(ItemStack stack) {
		if (client) {
			super.set(stack);
		}
	}

	@Override
	public void setByPlayer(ItemStack stack, ItemStack previous) {
	}
}
