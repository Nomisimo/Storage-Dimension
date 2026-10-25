package de.nomisimo.storagedim.tb;

import com.tiviacz.travelersbackpack.inventory.UpgradeManager;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingContainerImproved;
import com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingUpgrade;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * "ME als Quelle": while the switch on the ME upgrade is on, every craft in the backpack crafting grid is topped
 * up again from ME storage, so the grid only needs one set and shift-click crafts as long as the storage has items.
 * Switched off, the grid works as usual and only uses what is in it.
 */
public final class MeCrafting {
	private MeCrafting() {
	}

	/** The grid's stacks (same objects, TB shrinks them in place) with their counts and types before the craft. */
	public record Snapshot(CraftingContainerImproved grid, ItemStack[] stacks, int[] counts, ItemStack[] types) {
	}

	public static Snapshot snapshot(Player player) {
		CraftingContainerImproved grid = activeGrid(player);

		if (grid == null) {
			return null;
		}

		int size = grid.getContainerSize();
		ItemStack[] stacks = new ItemStack[size];
		int[] counts = new int[size];
		ItemStack[] types = new ItemStack[size];

		for (int i = 0; i < size; i++) {
			stacks[i] = grid.getItem(i);
			counts[i] = stacks[i].getCount();
			types[i] = stacks[i].isEmpty() ? ItemStack.EMPTY : stacks[i].copyWithCount(1);
		}

		return new Snapshot(grid, stacks, counts, types);
	}

	public static void refill(Player player, Snapshot before) {
		if (before == null || !(player instanceof ServerPlayer serverPlayer) || activeGrid(player) != before.grid()) {
			return;
		}

		CraftingContainerImproved grid = before.grid();

		for (int i = 0; i < before.stacks().length; i++) {
			ItemStack stack = before.stacks()[i];
			ItemStack type = before.types()[i];
			int missing = before.counts()[i] - stack.getCount();
			ItemStack current = grid.getItem(i);

			// a container item (e.g. an empty bucket) took the slot: leave it alone
			if (type.isEmpty() || missing <= 0 || (current != stack && !current.isEmpty())) {
				continue;
			}

			ItemStack taken = MeBridge.extract(serverPlayer, type, missing);

			if (taken.isEmpty()) {
				continue;
			}

			// grow the same stack object so TB's shift-click loop, which holds on to it, keeps crafting
			stack.grow(taken.getCount());

			if (!ItemStack.isSameItemSameComponents(stack, type)) {
				stack = type.copyWithCount(taken.getCount());
			}

			grid.setItem(i, stack);
		}
	}

	/** The crafting grid of the open backpack, if it has the crafting upgrade and the ME upgrade is switched on. */
	private static CraftingContainerImproved activeGrid(Player player) {
		if (player.level().isClientSide() || !(player.containerMenu instanceof BackpackBaseMenu menu)) {
			return null;
		}

		UpgradeManager upgrades = menu.getWrapper().getUpgradeManager();
		MeUpgrade me = upgrades.getUpgrade(MeUpgrade.class).orElse(null);
		CraftingUpgrade crafting = upgrades.getUpgrade(CraftingUpgrade.class).orElse(null);

		if (me == null || !me.isEnabled(me) || crafting == null) {
			return null;
		}

		return crafting.craftSlots;
	}
}
