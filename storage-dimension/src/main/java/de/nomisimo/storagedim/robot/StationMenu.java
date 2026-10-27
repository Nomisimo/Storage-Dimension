package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.FilterChests;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Settings of a robot station, drawn by {@code StationScreen} with real buttons and text fields.
 * Slots: nine filter slots (click with an item sets it, with an empty hand clears it), the input slot where robots
 * are laid in and the output slot where they come out set up for this station, then the player inventory.
 * Everything else travels as {@link StationNetworking} packets: the screen sends button presses and typed names,
 * the menu sends the settings and the list of the station's robots.
 */
public class StationMenu extends AbstractContainerMenu {
	public static final MenuType<StationMenu> TYPE = Registry.register(BuiltInRegistries.MENU, StorageDimensionMod.id("robot_station"),
			new MenuType<>(StationMenu::new, FeatureFlags.VANILLA_SET));

	public static final int FILTER_SLOTS = 9;
	public static final int SLOT_INPUT = 9;
	public static final int SLOT_OUTPUT = 10;
	private static final int PLAYER_START = 11;
	public static final int MAX_ROBOTS = 32;
	public static final int MAX_NAME = 32;
	private static final int[] RANGES = {16, 32, 64, 96, 128, RobotEntity.RANGE};
	private static final int SYNC_TICKS = 10;

	// layout, shared with the screen (item positions inside the window)
	public static final int WIDTH = 176;
	public static final int HEIGHT = 216;
	public static final int FILTER_X = 8;
	public static final int FILTER_Y = 30;
	public static final int MODE_Y = 50;
	public static final int RANGE_Y = 66;
	public static final int BUTTONS_Y = 82;
	public static final int INPUT_X = 100;
	public static final int OUTPUT_X = 140;
	public static final int IO_Y = 108;
	public static final int INVENTORY_Y = 134;

	// actions the screen sends
	public static final int ACTION_RANGE = 0;
	public static final int ACTION_COLOR = 1;
	public static final int ACTION_MODE = 2;
	public static final int ACTION_RECALL = 3;
	public static final int ACTION_STATION_NAME = 4;
	public static final int ACTION_ROBOT_NAME = 5;

	private final Container filterSlots;
	private final Container io;
	/** Server side only: the player looking at the station, and where it is. */
	private final ServerPlayer viewer;
	private final BlockPos pos;
	private final String dim;
	private RobotConfig config;
	private int ticks;
	private StationNetworking.SyncPayload lastSync;

	/** Client side: the slots are filled by the server. */
	private StationMenu(int containerId, Inventory inventory) {
		this(containerId, inventory, null, null);
	}

	private StationMenu(int containerId, Inventory inventory, ServerPlayer viewer, BlockPos pos) {
		super(TYPE, containerId);
		this.filterSlots = new SimpleContainer(FILTER_SLOTS);
		this.io = new SimpleContainer(2);
		this.viewer = viewer;
		this.pos = pos;
		this.dim = viewer == null ? null : Rooms.id(viewer.level());
		this.config = viewer == null ? RobotConfig.DEFAULT : StorageData.get().station(dim, pos);

		for (int i = 0; i < FILTER_SLOTS; i++) {
			addSlot(new Slot(filterSlots, i, FILTER_X + i * 18, FILTER_Y) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return false;
				}

				@Override
				public boolean mayPickup(Player player) {
					return false;
				}
			});
		}

		addSlot(new Slot(io, 0, INPUT_X, IO_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return stack.is(Robots.ROBOT_ITEM);
			}
		});
		addSlot(new Slot(io, 1, OUTPUT_X, IO_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}
		});

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, INVENTORY_Y + row * 18));
			}
		}

		for (int col = 0; col < 9; col++) {
			addSlot(new Slot(inventory, col, 8 + col * 18, INVENTORY_Y + 58));
		}

		refreshFilter();
	}

	public static void open(ServerPlayer player, BlockPos pos) {
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new StationMenu(id, inventory, player, pos),
				Component.literal("Roboter-Station")));
	}

	private void save() {
		StorageData.get().setStation(dim, pos, config);
	}

	private void refreshFilter() {
		for (int i = 0; i < FILTER_SLOTS; i++) {
			filterSlots.setItem(i, i < config.filter().size() ? config.filter().get(i).copyWithCount(1) : ItemStack.EMPTY);
		}
	}

	/** A button press or typed name from the screen. */
	public void action(int action, int value, String text) {
		if (viewer == null) {
			return;
		}

		switch (action) {
			case ACTION_RANGE -> config = config.withRange(step(RANGES, config.range(), value));
			case ACTION_COLOR -> config = config.withColor(step(RobotConfig.colors(), config.color(), value));
			case ACTION_MODE -> config = config.withExclude(!config.exclude());
			case ACTION_RECALL -> RobotEntity.recall((ServerLevel) viewer.level(), dim, pos, viewer.getUUID());
			case ACTION_STATION_NAME -> config = config.withStationName(clean(text));
			case ACTION_ROBOT_NAME -> {
				// for the robots set up from now on; placed ones keep their own name
				config = config.withName(clean(text));
			}
			default -> {
				return;
			}
		}

		save();
		configureOutput();
		sync(true);
	}

	private static String clean(String text) {
		String trimmed = text.strip();
		return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		boolean server = viewer != null;

		if (slotIndex >= 0 && slotIndex < FILTER_SLOTS) {
			// filter slots are only a picture of the settings; the server changes them
			if (server && input != ContainerInput.QUICK_CRAFT && input != ContainerInput.PICKUP_ALL) {
				setFilter(slotIndex, getCarried());
			}
		} else if (slotIndex >= PLAYER_START && input == ContainerInput.QUICK_MOVE) {
			if (server) {
				Slot slot = slots.get(slotIndex);
				ItemStack stack = slot.getItem();

				if (stack.is(Robots.ROBOT_ITEM)) {
					moveItemStackTo(stack, SLOT_INPUT, SLOT_INPUT + 1, false);
					slot.setChanged();
				} else if (!stack.isEmpty()) {
					setFilter(config.filter().size(), stack);
				}
			}
		} else if (slotIndex == SLOT_INPUT || slotIndex == SLOT_OUTPUT) {
			if (input == ContainerInput.QUICK_MOVE) {
				if (server) {
					Slot slot = slots.get(slotIndex);
					moveItemStackTo(slot.getItem(), PLAYER_START, slots.size(), true);
					slot.setChanged();
				}
			} else {
				super.clicked(slotIndex, buttonNum, input, player);
			}
		} else {
			super.clicked(slotIndex, buttonNum, input, player);
		}

		if (server) {
			configureOutput();
			refreshFilter();
			sync(true);
		}
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
			} else if (filter.size() < FILTER_SLOTS) {
				filter.add(stack.copyWithCount(1));
			}
		}

		config = config.withFilter(filter);
		save();
	}

	/** Moves robots from the input to the output slot, set up for this station; robots already there get the new settings. */
	private void configureOutput() {
		ItemStack in = io.getItem(0);
		ItemStack out = io.getItem(1);

		if (!out.isEmpty()) {
			ItemStack updated = new ItemStack(Robots.ROBOT_ITEM, out.getCount());
			config.write(updated, viewer.level().registryAccess());
			io.setItem(1, updated);
			out = updated;
		}

		if (!in.is(Robots.ROBOT_ITEM)) {
			return;
		}

		ItemStack configured = new ItemStack(Robots.ROBOT_ITEM, in.getCount());
		config.write(configured, viewer.level().registryAccess());

		if (out.isEmpty()) {
			io.setItem(1, configured);
			io.setItem(0, ItemStack.EMPTY);
		} else {
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
	public void broadcastChanges() {
		super.broadcastChanges();

		if (viewer != null && ++ticks % SYNC_TICKS == 0) {
			sync(false);
		}
	}

	/** Sends the settings and the robot list to the screen, when something changed (or always when forced). */
	private void sync(boolean force) {
		config = StorageData.get().station(dim, pos);
		List<Component> names = new ArrayList<>();
		List<Component> states = new ArrayList<>();

		for (Map.Entry<UUID, String> robot : StorageData.get().stationRobots(dim, pos).entrySet()) {
			Entity entity = viewer.level().getEntity(robot.getKey());
			String name = robot.getValue().isEmpty() ? "Roboter " + (names.size() + 1) : robot.getValue();

			if (entity instanceof RobotEntity r) {
				int distance = (int) Math.round(Math.sqrt(r.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)));
				// the name in the robot's colour, like on its name tag
				Component shown = Component.literal(name).withColor(RobotEntity.nameColor(r.getColor()));
				names.add(Component.empty().append(r.hasError() ? RobotEntity.withErrorIcons(shown) : shown).append(" · " + distance + " m"));
				states.add(r.status());
			} else {
				names.add(Component.literal(name));
				states.add(Component.literal("nicht geladen").withStyle(ChatFormatting.GRAY));
			}
		}

		StationNetworking.SyncPayload payload = new StationNetworking.SyncPayload(containerId, config.stationName(), config.name(),
				config.range(), config.color(), config.exclude(), names, states);

		if (force || !Objects.equals(payload, lastSync)) {
			lastSync = payload;
			ServerPlayNetworking.send(viewer, payload);
		}
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return slot.container != filterSlots;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return slot.container != filterSlots && slot.container != io;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return viewer == null || player.level().getBlockState(pos).is(RobotStationBlock.BLOCK)
				&& player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);

		// robots left in the input or output slot go back to the player
		if (viewer != null) {
			for (int i = 0; i < 2; i++) {
				ItemStack stack = io.removeItemNoUpdate(i);

				if (!stack.isEmpty()) {
					viewer.getInventory().placeItemBackInInventory(stack);
				}
			}
		}
	}

	/** Loads the class, which registers the menu type. */
	public static void register() {
	}
}
