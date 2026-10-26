package de.nomisimo.storagedim.api;

import java.util.List;
import java.util.function.Predicate;

import de.nomisimo.storagedim.storage.StorageData;
import de.nomisimo.storagedim.storage.StorageMenu;
import de.nomisimo.storagedim.storage.StorageNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Entry points for add-ons such as the Traveler's Backpack ME upgrade.
 * Add-ons call these by reflection, so the signatures only use vanilla types.
 */
public final class StorageApi {
	private StorageApi() {
	}

	/** Opens the ME terminal screen for the network the player uses. Returns false before the server has started. */
	public static boolean openStorage(ServerPlayer player) {
		StorageNetwork network = network(player);

		if (network == null) {
			return false;
		}

		StorageMenu.open(player, network);
		return true;
	}

	/** A count-1 copy of the first stored item (largest amount first) the filter accepts, or empty. */
	public static ItemStack findMatching(ServerPlayer player, Predicate<ItemStack> filter) {
		StorageNetwork network = network(player);

		if (network != null) {
			for (StorageNetwork.StoredItem entry : network.sorted()) {
				if (filter.test(entry.prototype)) {
					return entry.prototype.copy();
				}
			}
		}

		return ItemStack.EMPTY;
	}

	/**
	 * Fills the two lists with every stored item (count-1 copies) and its amount, largest amount first.
	 * Does nothing before the server has started.
	 */
	public static void stored(ServerPlayer player, List<ItemStack> prototypes, List<Long> counts) {
		StorageNetwork network = network(player);

		if (network != null) {
			for (StorageNetwork.StoredItem entry : network.sorted()) {
				prototypes.add(entry.prototype.copyWithCount(1));
				counts.add(entry.count);
			}
		}
	}

	/** The terminal icon for a stored item: amount in the name, small digits on the client and click help as lore. */
	public static ItemStack icon(ItemStack prototype, long count) {
		return StorageMenu.icon(prototype, count);
	}

	/** How many items exactly like the prototype (item + components) are stored. */
	public static long count(ServerPlayer player, ItemStack prototype) {
		StorageNetwork network = network(player);
		StorageNetwork.StoredItem entry = network == null ? null : network.find(prototype);
		return entry == null ? 0 : entry.count;
	}

	/** Takes up to {@code amount} items exactly like the prototype out of storage. */
	public static ItemStack extract(ServerPlayer player, ItemStack prototype, int amount) {
		StorageNetwork network = network(player);

		if (network == null) {
			return ItemStack.EMPTY;
		}

		ItemStack taken = network.extract(prototype, amount);
		StorageMenu.refreshAll(network);
		return taken;
	}

	/** Moves the whole stack into storage and empties it. Does nothing before the server has started. */
	public static void insert(ServerPlayer player, ItemStack stack) {
		StorageNetwork network = network(player);

		if (network != null) {
			network.insert(stack);
			StorageMenu.refreshAll(network);
		}
	}

	private static StorageNetwork network(ServerPlayer player) {
		StorageData data = StorageData.get();
		return data == null ? null : data.networkFor(player.getUUID());
	}
}
