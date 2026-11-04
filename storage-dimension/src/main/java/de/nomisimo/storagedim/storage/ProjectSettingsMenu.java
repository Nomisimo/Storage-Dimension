package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;

/**
 * Settings of a project chest, drawn by {@code ProjectScreen}: name, the switch "im ME blocken" and the list with one
 * row per item (amount field with − and + buttons), plus slots to write the project into a book and quill. The list
 * is no real slots; the screen sends what is clicked and typed as {@link ProjectNetworking} packets, and the menu sends
 * the list back.
 */
public class ProjectSettingsMenu extends AbstractContainerMenu {
	public static final MenuType<ProjectSettingsMenu> TYPE = Registry.register(BuiltInRegistries.MENU, StorageDimensionMod.id("project_settings"),
			new MenuType<>(ProjectSettingsMenu::new, FeatureFlags.VANILLA_SET));

	public static final int SLOT_BOOK_IN = 0;
	public static final int SLOT_BOOK_OUT = 1;
	private static final int PLAYER_START = 2;
	private static final int SYNC_TICKS = 20;
	private static final int LINES_PER_PAGE = 12;

	// layout, shared with the screen
	public static final int WIDTH = 176;
	public static final int HEIGHT = 256;
	public static final int NAME_Y = 5;
	public static final int SEARCH_Y = 20;
	public static final int LIST_Y = 36;
	public static final int ROW_HEIGHT = 20;
	public static final int ROWS = 5;
	public static final int BOOK_Y = 146;
	public static final int BOOK_IN_X = 44;
	public static final int BOOK_OUT_X = 80;
	public static final int INVENTORY_Y = 174;

	// actions the screen sends
	public static final int ACTION_SET_ITEM = 0;
	public static final int ACTION_ADD_STACK = 1;
	public static final int ACTION_ADJUST = 2;
	public static final int ACTION_SET_AMOUNT = 3;
	public static final int ACTION_REMOVE = 4;
	public static final int ACTION_NAME = 5;
	public static final int ACTION_BLOCK = 6;
	public static final int ACTION_ITEMS = 7;

	private final Container book = new SimpleContainer(2);
	/** Server side only. */
	private final ServerPlayer viewer;
	private final Projects.Project project;
	private int ticks;
	private ProjectNetworking.SyncPayload lastSync;

	private ProjectSettingsMenu(int containerId, Inventory inventory) {
		this(containerId, inventory, null, null);
	}

	private ProjectSettingsMenu(int containerId, Inventory inventory, ServerPlayer viewer, Projects.Project project) {
		super(TYPE, containerId);
		this.viewer = viewer;
		this.project = project;

		addSlot(new Slot(book, 0, BOOK_IN_X, BOOK_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return stack.is(Items.WRITABLE_BOOK);
			}
		});
		addSlot(new Slot(book, 1, BOOK_OUT_X, BOOK_Y) {
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
	}

	public static void open(ServerPlayer player, Projects.Project project) {
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new ProjectSettingsMenu(id, inventory, player, project),
				Component.literal("Projekt einstellen")));

		if (player.containerMenu instanceof ProjectSettingsMenu menu) {
			menu.sync(true);
		}
	}

	public Projects.Project project() {
		return project;
	}

	/** A button press, typed text or an item to add from the screen. */
	public void action(int action, int index, long value, String text, ItemStack stack) {
		if (viewer == null) {
			return;
		}

		List<Projects.Entry> entries = project.entries;
		Projects.Entry entry = index >= 0 && index < entries.size() ? entries.get(index) : null;

		switch (action) {
			case ACTION_SET_ITEM -> setItem(index, getCarried());
			case ACTION_ADD_STACK -> {
				if (!stack.isEmpty() && !project.wants(stack)) {
					project.add(stack, 1);
				}
			}
			case ACTION_ADJUST -> {
				if (entry != null) {
					entry.target = Math.max(1, Math.min(entry.target + value, 1_000_000_000L));
				}
			}
			case ACTION_SET_AMOUNT -> {
				if (entry != null && value > 0) {
					entry.target = Math.min(value, 1_000_000_000L);
				}
			}
			case ACTION_REMOVE -> {
				if (entry != null) {
					entries.remove(entry);
				}
			}
			case ACTION_NAME -> {
				String name = text.strip();

				if (!name.isEmpty()) {
					project.name = name.length() > Projects.MAX_NAME ? name.substring(0, Projects.MAX_NAME) : name;
				}
			}
			case ACTION_BLOCK -> project.block = !project.block;
			case ACTION_ITEMS -> {
				StorageMenu.openProject(viewer, project);
				return;
			}
			default -> {
				return;
			}
		}

		changed();
	}

	/** The item cell of a row was clicked: with an item it is set (the empty last row adds it), with an empty hand nothing happens. */
	private void setItem(int index, ItemStack carried) {
		if (carried.isEmpty()) {
			return;
		}

		Projects.Entry existing = project.entryFor(carried);

		if (index >= project.entries.size()) {
			if (existing == null) {
				project.add(carried, carried.getCount());
			}
		} else if (existing == null) {
			Projects.Entry old = project.entries.get(index);
			project.entries.set(index, new Projects.Entry(FilterChests.matchKey(carried), old.target));
		}
	}

	private void changed() {
		StorageData.markDirty();
		StorageMenu.refreshAll(project.network());
		writeBook();
		sync(true);
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		boolean server = viewer != null;

		if (slotIndex >= PLAYER_START && input == ContainerInput.QUICK_MOVE) {
			if (server) {
				Slot slot = slots.get(slotIndex);
				ItemStack stack = slot.getItem();

				if (stack.is(Items.WRITABLE_BOOK) && book.getItem(0).isEmpty()) {
					moveItemStackTo(stack, SLOT_BOOK_IN, SLOT_BOOK_IN + 1, false);
					slot.setChanged();
				} else if (!stack.isEmpty() && !project.wants(stack)) {
					// shift-click adds the item to the list
					project.add(stack, stack.getCount());
					changed();
				}
			}
		} else if ((slotIndex == SLOT_BOOK_IN || slotIndex == SLOT_BOOK_OUT) && input == ContainerInput.QUICK_MOVE) {
			if (server) {
				Slot slot = slots.get(slotIndex);
				moveItemStackTo(slot.getItem(), PLAYER_START, slots.size(), true);
				slot.setChanged();
			}
		} else {
			super.clicked(slotIndex, buttonNum, input, player);
		}

		if (server) {
			writeBook();
		}
	}

	/**
	 * A book and quill in the input comes out written with the project: name, then one line per item with the amount.
	 * One book at a time; a written book still in the output follows changes of the list.
	 */
	private void writeBook() {
		ItemStack in = book.getItem(0);
		ItemStack out = book.getItem(1);

		if (!out.isEmpty()) {
			book.setItem(1, projectBook(out.getCount()));
		} else if (in.is(Items.WRITABLE_BOOK)) {
			book.setItem(1, projectBook(1));
			in.shrink(1);
			book.setChanged();
		}
	}

	private ItemStack projectBook(int count) {
		List<String> lines = new ArrayList<>();

		for (Projects.Entry entry : project.entries) {
			lines.add(Projects.format(entry.target) + "× " + entry.item.getHoverName().getString());
		}

		List<Filterable<String>> pages = new ArrayList<>();
		StringBuilder page = new StringBuilder("Projekt: " + project.name + "\n\n");
		int onPage = 2;

		for (String line : lines) {
			if (onPage >= LINES_PER_PAGE) {
				pages.add(Filterable.passThrough(page.toString().stripTrailing()));
				page = new StringBuilder();
				onPage = 0;
			}

			page.append(line).append('\n');
			onPage++;
		}

		pages.add(Filterable.passThrough(page.toString().stripTrailing()));
		ItemStack stack = new ItemStack(Items.WRITABLE_BOOK, count);
		stack.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(pages));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Rezept: " + project.name).withStyle(s -> s.withItalic(false)));
		return stack;
	}

	@Override
	public void broadcastChanges() {
		super.broadcastChanges();

		if (viewer != null && ++ticks % SYNC_TICKS == 0) {
			sync(false);
		}
	}

	/** Sends name, switch and list with the stored amounts to the screen, when something changed (or always when forced). */
	void sync(boolean force) {
		if (viewer == null) {
			return;
		}

		StorageNetwork network = project.network();
		List<ItemStack> items = new ArrayList<>();
		List<Long> targets = new ArrayList<>();
		List<Long> have = new ArrayList<>();

		for (Projects.Entry entry : project.entries) {
			items.add(entry.item.copy());
			targets.add(entry.target);
			long count = 0;

			for (StorageNetwork.StoredItem stored : network.entries()) {
				if (entry.matches(stored.prototype)) {
					count += stored.count;
				}
			}

			have.add(count);
		}

		ProjectNetworking.SyncPayload payload = new ProjectNetworking.SyncPayload(containerId, project.name, project.block, items, targets, have);

		if (force || lastSync == null || !same(payload, lastSync)) {
			lastSync = payload;
			ServerPlayNetworking.send(viewer, payload);
		}
	}

	private static boolean same(ProjectNetworking.SyncPayload a, ProjectNetworking.SyncPayload b) {
		if (!a.name().equals(b.name()) || a.block() != b.block() || !a.targets().equals(b.targets()) || !a.have().equals(b.have())
				|| a.items().size() != b.items().size()) {
			return false;
		}

		for (int i = 0; i < a.items().size(); i++) {
			if (!ItemStack.isSameItemSameComponents(a.items().get(i), b.items().get(i))) {
				return false;
			}
		}

		return Objects.equals(a.containerId(), b.containerId());
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return slot.container != book;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);

		// books left in the slots go back to the player
		if (viewer != null) {
			for (int i = 0; i < 2; i++) {
				ItemStack stack = book.removeItemNoUpdate(i);

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
