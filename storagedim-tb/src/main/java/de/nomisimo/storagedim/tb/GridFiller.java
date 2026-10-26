package de.nomisimo.storagedim.tb;

import java.util.ArrayList;
import java.util.List;

import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.inventory.menu.slot.DisabledSlot;
import com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingContainerImproved;
import com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingUpgrade;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Fills the backpack crafting grid for a recipe, taking ingredients from the backpack, the player inventory
 * and then the ME storage (unless the ME upgrade is switched to "nur Inventar"), the way JEI does it with the inventory alone.
 */
final class GridFiller {
	private static final int GRID = 9;

	private GridFiller() {
	}

	static void fill(ServerPlayer player, FillGridPayload payload) {
		if (!(player.containerMenu instanceof BackpackBaseMenu menu) || payload.slots().size() != GRID) {
			return;
		}

		MeUpgrade me = menu.getWrapper().getUpgradeManager().getUpgrade(MeUpgrade.class).orElse(null);

		if (me == null) {
			return;
		}

		// switched to "nur Inventar": only backpack and inventory (this path is still used while the ME view covers the backpack slots)
		boolean useMe = me.isEnabled(me);

		CraftingUpgrade upgrade = menu.getWrapper().getUpgradeManager().getUpgrade(CraftingUpgrade.class).orElse(null);

		if (upgrade == null || upgrade.craftSlots == null) {
			return;
		}

		CraftingContainerImproved grid = upgrade.craftSlots;
		grid.checkChanges = false;

		try {
			clearGrid(player, grid);

			List<Slot> local = localSources(menu, player);
			IngredientPool pool = new IngredientPool();
			local.forEach(slot -> pool.add(slot.getItem()));

			if (useMe) {
				List<ItemStack> stored = new ArrayList<>();
				List<Long> counts = new ArrayList<>();
				MeBridge.stored(player, stored, counts);

				for (int i = 0; i < stored.size(); i++) {
					pool.add(stored.get(i), counts.get(i));
				}
			}

			ItemStack[] types = new ItemStack[GRID];

			for (int i = 0; i < GRID; i++) {
				List<ItemStack> wanted = payload.slots().get(i);

				if (!wanted.isEmpty()) {
					types[i] = pool.take(wanted);

					if (types[i].isEmpty()) {
						missing(player);
						return;
					}
				}
			}

			// group the slots by item type: how many of each type one craft needs
			List<ItemStack> groups = new ArrayList<>();
			List<Integer> perCraft = new ArrayList<>();

			for (ItemStack type : types) {
				if (type == null) {
					continue;
				}

				int index = indexOf(groups, type);

				if (index < 0) {
					groups.add(type);
					perCraft.add(1);
				} else {
					perCraft.set(index, perCraft.get(index) + 1);
				}
			}

			if (groups.isEmpty()) {
				return;
			}

			int crafts = payload.max() ? Integer.MAX_VALUE : 1;

			for (int g = 0; g < groups.size(); g++) {
				ItemStack type = groups.get(g);
				long available = countLocal(local, type) + (useMe ? MeBridge.count(player, type) : 0);
				long possible = available / perCraft.get(g);
				crafts = (int) Math.min(crafts, Math.min(possible, type.getMaxStackSize()));
			}

			if (crafts <= 0) {
				missing(player);
				return;
			}

			// take everything out first, then hand it out; a shortfall (should not happen) lowers the count
			long[] taken = new long[groups.size()];

			for (int g = 0; g < groups.size(); g++) {
				taken[g] = take(player, local, groups.get(g), (long) crafts * perCraft.get(g), useMe);
				crafts = (int) Math.min(crafts, taken[g] / perCraft.get(g));
			}

			for (int i = 0; i < GRID; i++) {
				if (types[i] != null && crafts > 0) {
					int g = indexOf(groups, types[i]);
					grid.setItem(i, types[i].copyWithCount(crafts));
					taken[g] -= crafts;
				}
			}

			for (int g = 0; g < groups.size(); g++) {
				if (taken[g] > 0) {
					giveBack(player, groups.get(g).copyWithCount((int) taken[g]));
				}
			}
		} finally {
			grid.checkChanges = true;
			menu.slotsChanged(grid);
			menu.broadcastChanges();
		}
	}

	private static void clearGrid(ServerPlayer player, CraftingContainerImproved grid) {
		for (int i = 0; i < GRID; i++) {
			ItemStack stack = grid.getItem(i).copy();

			if (!stack.isEmpty()) {
				grid.setItem(i, ItemStack.EMPTY);
				giveBack(player, stack);
			}
		}
	}

	/** Inventory first, then ME storage, and only if both are full it drops. */
	private static void giveBack(ServerPlayer player, ItemStack stack) {
		player.getInventory().add(stack);

		if (!stack.isEmpty()) {
			MeBridge.insert(player, stack);
		}

		if (!stack.isEmpty()) {
			player.drop(stack, false);
		}
	}

	private static List<Slot> localSources(BackpackBaseMenu menu, ServerPlayer player) {
		List<Slot> slots = new ArrayList<>();
		addRange(slots, menu, player, menu.BACKPACK_INV_START, menu.BACKPACK_INV_END);
		addRange(slots, menu, player, menu.PLAYER_INV_START, menu.PLAYER_HOT_END);
		return slots;
	}

	private static void addRange(List<Slot> slots, BackpackBaseMenu menu, ServerPlayer player, int from, int to) {
		for (int i = from; i < to && i < menu.slots.size(); i++) {
			// the backpack's own slot, also while the ME view covers it
			Slot slot = MeView.realSlot(menu, i);

			if (!(slot instanceof DisabledSlot) && slot.mayPickup(player)) {
				slots.add(slot);
			}
		}
	}

	private static long countLocal(List<Slot> local, ItemStack type) {
		long total = 0;

		for (Slot slot : local) {
			if (ItemStack.isSameItemSameComponents(slot.getItem(), type)) {
				total += slot.getItem().getCount();
			}
		}

		return total;
	}

	private static long take(ServerPlayer player, List<Slot> local, ItemStack type, long amount, boolean useMe) {
		long got = 0;

		for (Slot slot : local) {
			if (got >= amount) {
				break;
			}

			if (ItemStack.isSameItemSameComponents(slot.getItem(), type)) {
				got += slot.remove((int) Math.min(amount - got, slot.getItem().getCount())).getCount();
				slot.setChanged();
			}
		}

		while (useMe && got < amount) {
			ItemStack stored = MeBridge.extract(player, type, (int) Math.min(amount - got, Integer.MAX_VALUE));

			if (stored.isEmpty()) {
				break;
			}

			got += stored.getCount();
		}

		return got;
	}

	private static int indexOf(List<ItemStack> groups, ItemStack type) {
		for (int g = 0; g < groups.size(); g++) {
			if (ItemStack.isSameItemSameComponents(groups.get(g), type)) {
				return g;
			}
		}

		return -1;
	}

	private static void missing(ServerPlayer player) {
		player.connection.send(new ClientboundSetActionBarTextPacket(
				Component.literal("Zutaten fehlen in Inventar, Rucksack und ME-Speicher").withStyle(ChatFormatting.RED)));
	}
}
