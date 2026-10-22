package de.nomisimo.storagedim.storage;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * What a storage chest shows when nobody is inside: an ordinary looking, empty chest.
 * Anything put in is handed back when the screen closes, so it cannot be used for storage.
 */
public class DecoyChestMenu extends ChestMenu {
	private final SimpleContainer container;

	private DecoyChestMenu(int containerId, Inventory inventory, SimpleContainer container) {
		super(MenuType.GENERIC_9x3, containerId, inventory, container, 3);
		this.container = container;
	}

	public static void open(ServerPlayer player) {
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new DecoyChestMenu(id, inventory, new SimpleContainer(27)),
				Component.translatable("container.chest")));
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		clearContainer(player, container);
	}
}
