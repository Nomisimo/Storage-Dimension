package de.nomisimo.storagedim.storage;

import java.util.function.Consumer;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Text input for the terminal search, using the anvil rename field: type the term, then click the result slot.
 * The paper in the anvil is only a prop and never ends up in the player's inventory.
 */
public class SearchMenu extends AnvilMenu {
	private static final int RESULT_SLOT = 2;
	private static final String PLACEHOLDER = "Suchbegriff";

	private final Consumer<String> onDone;
	private final String placeholder;
	private String text;

	private SearchMenu(int containerId, Inventory inventory, String current, String placeholder, Consumer<String> onDone) {
		super(containerId, inventory, ContainerLevelAccess.NULL);
		this.onDone = onDone;
		this.placeholder = placeholder;
		this.text = current == null ? "" : current;
		ItemStack paper = new ItemStack(Items.PAPER);
		paper.set(DataComponents.CUSTOM_NAME, Component.literal(text.isEmpty() ? placeholder : text));
		inputSlots.setItem(0, paper);
	}

	public static void open(ServerPlayer player, String current, Consumer<String> onDone) {
		open(player, current, "Suche: Text eingeben, dann rechts klicken", PLACEHOLDER, onDone);
	}

	/** The same text field for other things, e.g. a robot's name. */
	public static void open(ServerPlayer player, String current, String title, String placeholder, Consumer<String> onDone) {
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new SearchMenu(id, inventory, current, placeholder, onDone),
				Component.literal(title)));
	}

	/**
	 * Replaces the anvil logic: the result is just the paper with the typed name, and the level cost stays 0,
	 * so searching never costs experience.
	 */
	@Override
	public void createResult() {
		ItemStack input = inputSlots.getItem(0);

		if (input.isEmpty()) {
			resultSlots.setItem(0, ItemStack.EMPTY);
			return;
		}

		ItemStack result = input.copy();
		result.set(DataComponents.CUSTOM_NAME, Component.literal(text == null || text.isEmpty() ? placeholder : text));
		resultSlots.setItem(0, result);
	}

	@Override
	public boolean setItemName(String name) {
		text = name == null ? "" : name.trim();
		return super.setItemName(name);
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (slotIndex == RESULT_SLOT) {
			String term = placeholder.equals(text) ? "" : text;
			inputSlots.setItem(0, ItemStack.EMPTY);
			onDone.accept(term);
			return;
		}

		// the anvil's own input slots are display only
		if (slotIndex >= 0 && slotIndex < RESULT_SLOT) {
			return;
		}

		super.clicked(slotIndex, buttonNum, input, player);
	}

	@Override
	public void removed(Player player) {
		inputSlots.setItem(0, ItemStack.EMPTY);
		inputSlots.setItem(1, ItemStack.EMPTY);
		resultSlots.setItem(0, ItemStack.EMPTY);
		super.removed(player);
	}
}
