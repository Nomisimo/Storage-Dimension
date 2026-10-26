package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.List;

import de.nomisimo.storagedim.storage.FilterChests;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.SearchMenu;
import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Settings of a robot station in a 3-row chest screen.
 * Top row: nine filter slots (click with an item sets it, with an empty hand clears it).
 * Middle row: range, colour and name buttons, then input slot → output slot: a robot laid in comes out configured.
 */
public class StationMenu extends ChestMenu {
	private static final int SLOT_RANGE = 9;
	private static final int SLOT_COLOR = 10;
	private static final int SLOT_NAME = 11;
	private static final int SLOT_INPUT = 13;
	private static final int SLOT_ARROW = 14;
	private static final int SLOT_OUTPUT = 15;
	private static final int SLOT_HELP = 22;
	private static final int SIZE = 27;
	private static final int[] RANGES = {16, 32, 64, 96, 128, RobotEntity.RANGE};
	private static final int MAX_NAME = 32;

	private final SimpleContainer container;
	private final ServerPlayer viewer;
	private final BlockPos pos;
	private final String dim;
	private RobotConfig config;

	private StationMenu(int containerId, Inventory inventory, SimpleContainer container, ServerPlayer viewer, BlockPos pos) {
		super(MenuType.GENERIC_9x3, containerId, inventory, container, 3);
		this.container = container;
		this.viewer = viewer;
		this.pos = pos;
		this.dim = Rooms.id(viewer.level());
		this.config = StorageData.get().station(dim, pos);
		refresh();
	}

	public static void open(ServerPlayer player, BlockPos pos) {
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new StationMenu(id, inventory, new SimpleContainer(SIZE), player, pos),
				Component.literal("Roboter-Station")));
	}

	private void save() {
		StorageData.get().setStation(dim, pos, config);
	}

	private void refresh() {
		for (int i = 0; i < 9; i++) {
			ItemStack filter = i < config.filter().size() ? config.filter().get(i) : ItemStack.EMPTY;

			if (filter.isEmpty()) {
				container.setItem(i, ItemStack.EMPTY);
			} else {
				ItemStack icon = filter.copyWithCount(1);
				icon.set(DataComponents.LORE, new ItemLore(List.of(
						Component.literal("Klick mit leerer Hand: entfernen").withStyle(ChatFormatting.GRAY))));
				container.setItem(i, icon);
			}
		}

		for (int i = 9; i < SIZE; i++) {
			if (i != SLOT_INPUT && i != SLOT_OUTPUT) {
				container.setItem(i, button(Items.GRAY_STAINED_GLASS_PANE, " "));
			}
		}

		container.setItem(SLOT_RANGE, button(Items.COMPASS, "Reichweite: " + RobotConfig.rangeText(config.range()),
				"Linksklick: größer · Rechtsklick: kleiner", "Gemessen ab dieser Station"));
		DyeColor dye = RobotConfig.dyeFor(config.color());
		container.setItem(SLOT_COLOR, button(dye == null ? Items.ORANGE_DYE
				: BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(dye.getName() + "_dye")), "Farbe: " + RobotConfig.colorName(config.color()),
				"Linksklick: nächste · Rechtsklick: vorige"));
		container.setItem(SLOT_NAME, button(Items.NAME_TAG, config.name().isEmpty() ? "Name: keiner" : "Name: " + config.name(),
				"Linksklick: Namen eingeben", "Rechtsklick: Namen löschen"));
		container.setItem(SLOT_ARROW, button(Items.ARROW, "Roboter links hineinlegen",
				"rechts kommt er mit diesen Einstellungen heraus"));
		container.setItem(SLOT_HELP, button(Items.BOOK, "Roboter-Station",
				"Oben: Filter, was die Roboter sammeln (leer = alles)",
				"Die Roboter arbeiten im Umkreis dieser Station",
				"und warten hier, wenn es nichts zu tun gibt"));
	}

	private static ItemStack button(Item item, String name, String... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false)));

		if (lore.length > 0) {
			List<Component> lines = new ArrayList<>();

			for (String line : lore) {
				lines.add(Component.literal(line).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)));
			}

			stack.set(DataComponents.LORE, new ItemLore(lines));
		}

		return stack;
	}

	private static boolean isRobot(ItemStack stack) {
		return stack.is(Robots.ROBOT_ITEM);
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (input == ContainerInput.PICKUP_ALL || input == ContainerInput.QUICK_CRAFT) {
			super.clicked(slotIndex, buttonNum, input, player);
		} else if (slotIndex >= 0 && slotIndex < 9) {
			setFilter(slotIndex, getCarried());
		} else if (slotIndex == SLOT_RANGE) {
			config = config.withRange(step(RANGES, config.range(), buttonNum == 1 ? -1 : 1));
			save();
		} else if (slotIndex == SLOT_COLOR) {
			config = config.withColor(step(RobotConfig.colors(), config.color(), buttonNum == 1 ? -1 : 1));
			save();
		} else if (slotIndex == SLOT_NAME) {
			if (buttonNum == 1) {
				config = config.withName("");
				save();
			} else {
				returnItems();
				SearchMenu.open(viewer, config.name(), "Name: eingeben, dann rechts klicken", "Name", text -> {
					config = config.withName(text.length() > MAX_NAME ? text.substring(0, MAX_NAME) : text);
					save();
					open(viewer, pos);
				});
				return;
			}
		} else if (slotIndex == SLOT_INPUT || slotIndex == SLOT_OUTPUT) {
			if (input == ContainerInput.QUICK_MOVE) {
				ItemStack stack = container.getItem(slotIndex);
				player.getInventory().add(stack);
				container.setItem(slotIndex, stack);
			} else if (slotIndex == SLOT_INPUT && !getCarried().isEmpty() && !isRobot(getCarried())) {
				// only robots go in
			} else {
				super.clicked(slotIndex, buttonNum, input, player);
			}
		} else if (slotIndex >= SIZE && input == ContainerInput.QUICK_MOVE) {
			ItemStack stack = slots.get(slotIndex).getItem();

			if (isRobot(stack) && container.getItem(SLOT_INPUT).isEmpty()) {
				container.setItem(SLOT_INPUT, stack.copy());
				stack.setCount(0);
				slots.get(slotIndex).setChanged();
			} else if (!stack.isEmpty() && !isRobot(stack)) {
				setFilter(config.filter().size(), stack);
			}
		} else if (slotIndex >= SIZE || slotIndex < 0) {
			super.clicked(slotIndex, buttonNum, input, player);
		}

		configureInput();
		refresh();
		sendAllDataToRemote();
	}

	/** Sets (or with an empty stack clears) one filter slot; a full or already listed item is ignored. */
	private void setFilter(int slot, ItemStack stack) {
		List<ItemStack> filter = new ArrayList<>(config.filter());

		if (stack.isEmpty()) {
			if (slot < filter.size()) {
				filter.remove(slot);
			}
		} else if (!FilterChests.matches(filter, stack)) {
			if (slot < filter.size()) {
				filter.set(slot, stack.copyWithCount(1));
			} else if (filter.size() < 9) {
				filter.add(stack.copyWithCount(1));
			}
		}

		config = config.withFilter(filter);
		save();
	}

	/** Moves robots from the input to the output slot, configured for this station. */
	private void configureInput() {
		ItemStack in = container.getItem(SLOT_INPUT);
		ItemStack out = container.getItem(SLOT_OUTPUT);

		if (!isRobot(in)) {
			return;
		}

		ItemStack configured = new ItemStack(Robots.ROBOT_ITEM, in.getCount());
		config.write(configured, viewer.level().registryAccess());

		if (out.isEmpty()) {
			container.setItem(SLOT_OUTPUT, configured);
			container.setItem(SLOT_INPUT, ItemStack.EMPTY);
		} else if (ItemStack.isSameItemSameComponents(out, configured)) {
			int moved = Math.min(in.getCount(), out.getMaxStackSize() - out.getCount());
			out.grow(moved);
			in.shrink(moved);
		}
	}

	private static int step(int[] values, int current, int direction) {
		int index = 0;

		for (int i = 0; i < values.length; i++) {
			if (values[i] == current) {
				index = i;
			}
		}

		return values[Math.floorMod(index + direction, values.length)];
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return slot.container != container;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return slot.container != container;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}

	/** Robots left in the input or output slot go back to the player. */
	private void returnItems() {
		for (int slot : new int[] {SLOT_INPUT, SLOT_OUTPUT}) {
			ItemStack stack = container.getItem(slot);

			if (!stack.isEmpty()) {
				viewer.getInventory().placeItemBackInInventory(stack);
				container.setItem(slot, ItemStack.EMPTY);
			}
		}
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		returnItems();

		for (int i = 0; i < SIZE; i++) {
			container.setItem(i, ItemStack.EMPTY);
		}
	}
}
