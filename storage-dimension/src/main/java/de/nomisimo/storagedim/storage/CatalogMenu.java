package de.nomisimo.storagedim.storage;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The library catalogue, opened at a lectern: one button per aisle that teleports to its start, a search that
 * teleports to the chests of an item, and buttons back to the hall and out of the dimension. No real items move.
 */
public class CatalogMenu extends ChestMenu {
	private static final int SLOT_SEARCH = 21;
	private static final int SLOT_HALL = 22;
	private static final int SLOT_EXIT = 23;

	private final SimpleContainer display;
	private final StorageNetwork network;

	private CatalogMenu(int containerId, Inventory inventory, SimpleContainer display, StorageNetwork network) {
		super(MenuType.GENERIC_9x3, containerId, inventory, display, 3);
		this.display = display;
		this.network = network;
		refresh();
	}

	public static void open(ServerPlayer player, StorageNetwork network) {
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new CatalogMenu(id, inventory, new SimpleContainer(27), network),
				Component.literal("Katalog der Bibliothek")));
	}

	private void refresh() {
		for (int i = 0; i < 27; i++) {
			ItemStack filler = new ItemStack(Items.BROWN_STAINED_GLASS_PANE);
			filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
			display.setItem(i, filler);
		}

		List<Library.Aisle> aisles = network.aisles;

		for (int i = 0; i < aisles.size() && i < 18; i++) {
			Library.Aisle aisle = aisles.get(i);
			long kinds = network.library.stream().filter(s -> s.part() == 0 && !Library.isProject(s.key()) && Math.abs(s.dx() - aisle.x) == 2).count();
			long chests = network.library.stream().filter(s -> !Library.isProject(s.key()) && Math.abs(s.dx() - aisle.x) == 2).count();
			display.setItem(i, button(new ItemStack(aisle.category.icon), aisle.category.title,
					kinds + (kinds == 1 ? " Sorte" : " Sorten") + " in " + chests + (chests == 1 ? " Kiste" : " Kisten"),
					"Klick: zum Gang teleportieren"));
		}

		display.setItem(SLOT_SEARCH, button(new ItemStack(Items.COMPASS), "Item suchen", "Name, Wirkung oder Verzauberung eingeben,",
				"dann geht es direkt zur Kiste"));
		display.setItem(SLOT_HALL, button(new ItemStack(Items.LECTERN), "Zur Eingangshalle"));
		display.setItem(SLOT_EXIT, button(new ItemStack(Items.LODESTONE), "Bibliothek verlassen"));
	}

	private static ItemStack button(ItemStack icon, String name, String... lore) {
		icon.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
		icon.set(DataComponents.LORE, new ItemLore(java.util.Arrays.stream(lore)
				.map(line -> (Component) Component.literal(line).withStyle(ChatFormatting.GRAY)).toList()));
		return icon;
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (input == ContainerInput.PICKUP_ALL || input == ContainerInput.QUICK_CRAFT) {
			// dragging and double-click collecting work in the own inventory only, see canDragTo and canTakeItemForPickAll
			super.clicked(slotIndex, buttonNum, input, player);
			return;
		}

		if (slotIndex < 0 || slotIndex >= 27) {
			if (input != ContainerInput.QUICK_MOVE) {
				super.clicked(slotIndex, buttonNum, input, player);
			}

			return;
		}

		if (!(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		if (slotIndex < network.aisles.size() && slotIndex < 18) {
			Library.Aisle aisle = network.aisles.get(slotIndex);
			serverPlayer.closeContainer();
			Library.teleportToAisle(serverPlayer, network, aisle);
		} else if (slotIndex == SLOT_SEARCH) {
			SearchMenu.open(serverPlayer, "", "Item suchen: Text eingeben, dann rechts klicken", "Suchbegriff", text -> find(serverPlayer, text));
		} else if (slotIndex == SLOT_HALL) {
			serverPlayer.closeContainer();
			Library.teleportToSpawn(serverPlayer, network);
		} else if (slotIndex == SLOT_EXIT) {
			serverPlayer.closeContainer();
			StorageEvents.leaveRoom(serverPlayer);
		} else {
			refresh();
			sendAllDataToRemote();
		}
	}

	/** Teleports to the first chest of the first kind the text finds. */
	private void find(ServerPlayer player, String text) {
		String query = ItemSearch.query(text);
		player.closeContainer();

		if (query.isEmpty()) {
			return;
		}

		for (Library.Shelf shelf : network.library) {
			if (!Library.isProject(shelf.key()) && shelf.part() == 0 && ItemSearch.matches(shelf.key(), query)) {
				Library.teleportToShelf(player, network, shelf);
				return;
			}
		}

		player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("Nichts gefunden: " + text).withStyle(ChatFormatting.RED)));
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return slot.container != display;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return slot.container != display;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);

		for (int i = 0; i < 27; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}
	}
}
