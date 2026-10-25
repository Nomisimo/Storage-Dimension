package de.nomisimo.storagedim.api;

import de.nomisimo.storagedim.storage.StorageData;
import de.nomisimo.storagedim.storage.StorageMenu;
import net.minecraft.server.level.ServerPlayer;

/**
 * Entry points for add-ons such as the Traveler's Backpack ME upgrade.
 */
public final class StorageApi {
	private StorageApi() {
	}

	/** Opens the ME terminal screen for the network the player uses. Returns false before the server has started. */
	public static boolean openStorage(ServerPlayer player) {
		StorageData data = StorageData.get();

		if (data == null) {
			return false;
		}

		StorageMenu.open(player, data.networkFor(player.getUUID()));
		return true;
	}
}
