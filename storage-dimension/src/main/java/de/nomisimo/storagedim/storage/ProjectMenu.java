package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Settings of a project chest: the list of items with the amounts the project needs, its name and the switch
 * "im ME blocken". Items get in by laying them on a slot, shift-clicking them in the own inventory, or through the
 * search over every item of the game. The selected entry's amount changes with the − and + buttons or is typed in.
 * No real items ever move here.
 */
public class ProjectMenu extends ChestMenu {
	private static final int ENTRY_SLOTS = 36;
	private static final int SLOT_SELECTED = 36;
	private static final int SLOT_MINUS_64 = 37;
	private static final int SLOT_MINUS_1 = 38;
	private static final int SLOT_AMOUNT = 39;
	private static final int SLOT_PLUS_1 = 40;
	private static final int SLOT_PLUS_64 = 41;
	private static final int SLOT_REMOVE = 44;
	private static final int SLOT_PREV = 45;
	private static final int SLOT_NAME = 46;
	private static final int SLOT_BLOCK = 47;
	private static final int SLOT_SEARCH = 48;
	private static final int SLOT_INFO = 49;
	private static final int SLOT_CLEAR = 51;
	private static final int SLOT_ITEMS = 52;
	private static final int SLOT_NEXT = 53;
	private static final int PICKER_SLOTS = 45;
	private static final int SLOT_BACK = 49;
	private static final Set<ProjectMenu> OPEN = Collections.newSetFromMap(new WeakHashMap<>());

	private final Projects.Project project;
	private final SimpleContainer display;
	private final Player viewer;
	private int page;
	private Projects.Entry selected;
	/** The search results while picking an item, or null for the settings. */
	private List<ItemStack> picker;
	private String search = "";
	private int pickerPage;

	private ProjectMenu(int containerId, Inventory inventory, SimpleContainer display, Projects.Project project, Projects.Entry selected,
			List<ItemStack> picker, String search) {
		super(MenuType.GENERIC_9x6, containerId, inventory, display, 6);
		this.project = project;
		this.display = display;
		this.viewer = inventory.player;
		this.selected = selected;
		this.picker = picker;
		this.search = search;

		if (selected != null && project.entries.contains(selected)) {
			page = project.entries.indexOf(selected) / ENTRY_SLOTS;
		}

		refresh();
		OPEN.add(this);
	}

	public static void open(ServerPlayer player, Projects.Project project) {
		open(player, project, null, null, "");
	}

	private static void open(ServerPlayer player, Projects.Project project, Projects.Entry selected, List<ItemStack> picker, String search) {
		String title = picker == null ? "Projekt „" + project.name + "“ einstellen" : "Item wählen: " + (search.isEmpty() ? "alle" : search);
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new ProjectMenu(id, inventory, new SimpleContainer(54), project, selected, picker, search),
				Component.literal(title)));
	}

	/** Redraws open settings of the projects of this network, e.g. after items came in. */
	static void refreshAll(StorageNetwork network) {
		for (ProjectMenu menu : new ArrayList<>(OPEN)) {
			if (menu.project.network() == network) {
				menu.refresh();
				menu.sync();
			}
		}
	}

	// ---- drawing ----

	private void refresh() {
		for (int i = 0; i < 54; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}

		if (picker != null) {
			refreshPicker();
			return;
		}

		if (selected != null && !project.entries.contains(selected)) {
			selected = null;
		}

		int pages = Math.max(1, (project.entries.size() + ENTRY_SLOTS - 1) / ENTRY_SLOTS);
		page = Math.min(page, pages - 1);
		StorageNetwork network = project.network();

		for (int i = 0; i < ENTRY_SLOTS; i++) {
			int index = page * ENTRY_SLOTS + i;

			if (index < project.entries.size()) {
				display.setItem(i, entryIcon(network, project.entries.get(index)));
			}
		}

		ItemStack filler = button(Items.GRAY_STAINED_GLASS_PANE, " ");

		for (int i = ENTRY_SLOTS; i < 54; i++) {
			display.setItem(i, filler.copy());
		}

		if (selected == null) {
			display.setItem(SLOT_SELECTED, button(Items.LIGHT_GRAY_STAINED_GLASS_PANE, "Kein Item gewählt",
					"Klick auf ein Item oben wählt es aus"));
		} else {
			ItemStack icon = entryIcon(network, selected);
			icon.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
			display.setItem(SLOT_SELECTED, icon);
			display.setItem(SLOT_MINUS_64, button(Items.RED_CONCRETE, "− 64"));
			display.setItem(SLOT_MINUS_1, button(Items.RED_STAINED_GLASS_PANE, "− 1"));
			display.setItem(SLOT_AMOUNT, button(Items.NAME_TAG, "Menge: " + Projects.format(selected.target), "Klick: Zahl eingeben"));
			display.setItem(SLOT_PLUS_1, button(Items.LIME_STAINED_GLASS_PANE, "+ 1"));
			display.setItem(SLOT_PLUS_64, button(Items.LIME_CONCRETE, "+ 64"));
			display.setItem(SLOT_REMOVE, button(Items.BARRIER, "Aus dem Projekt entfernen"));
		}

		if (page > 0) {
			display.setItem(SLOT_PREV, button(Items.ARROW, "Vorherige Seite"));
		}

		if (page < pages - 1) {
			display.setItem(SLOT_NEXT, button(Items.ARROW, "Nächste Seite"));
		}

		display.setItem(SLOT_NAME, button(Items.WRITABLE_BOOK, "Name: " + project.name, "Klick: Namen ändern"));
		display.setItem(SLOT_BLOCK, button(project.block ? Items.LIME_DYE : Items.GRAY_DYE, (project.block ? "☑" : "☐") + " Im ME blocken",
				project.block ? "Die Mengen bleiben für das Projekt im Speicher" : "Aus: alle können die Items nehmen",
				"Klick: umschalten"));
		display.setItem(SLOT_SEARCH, button(Items.COMPASS, "Item suchen", "Alle Items des Spiels durchsuchen"));
		long have = 0;
		long need = 0;

		for (Projects.Entry entry : project.entries) {
			need += entry.target;
			have += Math.min(entry.target, stored(network, entry));
		}

		display.setItem(SLOT_INFO, button(Items.ENDER_EYE, project.entries.size() + " Items · " + Projects.format(have) + " / " + Projects.format(need),
				"Seite " + (page + 1) + "/" + pages,
				"Item auf einen Platz legen oder Shift-Klick im Inventar fügt es hinzu"));
		display.setItem(SLOT_CLEAR, button(Items.LAVA_BUCKET, "Alle Items entfernen", "Shift-Klick: Liste leeren"));
		display.setItem(SLOT_ITEMS, button(Items.CHEST, "Zu den Items", "Zeigt die Projekt-Items aus dem ME-Speicher"));
	}

	private void refreshPicker() {
		int pages = Math.max(1, (picker.size() + PICKER_SLOTS - 1) / PICKER_SLOTS);
		pickerPage = Math.min(pickerPage, pages - 1);

		for (int i = 0; i < PICKER_SLOTS; i++) {
			int index = pickerPage * PICKER_SLOTS + i;

			if (index < picker.size()) {
				ItemStack icon = picker.get(index).copy();
				boolean listed = project.wants(icon);
				icon.set(DataComponents.LORE, new ItemLore(List.of(
						Component.literal(listed ? "Schon im Projekt: Klick wählt es aus" : "Klick: 1 zum Projekt").withStyle(ChatFormatting.GRAY),
						Component.literal("Shift-Klick: 64 zum Projekt").withStyle(ChatFormatting.GRAY))));
				display.setItem(i, icon);
			}
		}

		ItemStack filler = button(Items.GRAY_STAINED_GLASS_PANE, " ");

		for (int i = PICKER_SLOTS; i < 54; i++) {
			display.setItem(i, filler.copy());
		}

		if (pickerPage > 0) {
			display.setItem(SLOT_PREV, button(Items.ARROW, "Vorherige Seite"));
		}

		if (pickerPage < pages - 1) {
			display.setItem(SLOT_NEXT, button(Items.ARROW, "Nächste Seite"));
		}

		display.setItem(SLOT_SEARCH, button(Items.COMPASS, "Neue Suche"));
		display.setItem(SLOT_BACK, button(Items.WRITABLE_BOOK, "Zurück zum Projekt",
				picker.size() + " Treffer · Seite " + (pickerPage + 1) + "/" + pages));
	}

	/** How many of the entry's item the network holds. */
	private static long stored(StorageNetwork network, Projects.Entry entry) {
		long count = 0;

		for (StorageNetwork.StoredItem stored : network.entries()) {
			if (entry.matches(stored.prototype)) {
				count += stored.count;
			}
		}

		return count;
	}

	private ItemStack entryIcon(StorageNetwork network, Projects.Entry entry) {
		long have = stored(network, entry);
		ItemStack icon = StorageMenu.icon(entry.item, entry.target);
		icon.set(DataComponents.CUSTOM_NAME, Component.empty().append(entry.item.getHoverName())
				.append(Component.literal(" × " + Projects.format(entry.target)).withStyle(ChatFormatting.GOLD))
				.withStyle(s -> s.withItalic(false)));
		List<Component> lore = new ArrayList<>();
		lore.add(Component.literal("Benötigt: " + Projects.format(entry.target)).withStyle(ChatFormatting.GOLD));
		lore.add(Component.literal("Im ME: " + Projects.format(have)).withStyle(ChatFormatting.GRAY));
		lore.add(have >= entry.target
				? Component.literal("✔ alles da").withStyle(ChatFormatting.GREEN)
				: Component.literal("Fehlt: " + Projects.format(entry.target - have)).withStyle(ChatFormatting.RED));
		lore.add(Component.literal("Klick: auswählen").withStyle(ChatFormatting.GRAY));
		icon.set(DataComponents.LORE, new ItemLore(lore));

		if (entry == selected) {
			icon.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}

		return icon;
	}

	private static ItemStack button(Item item, String name, String... lore) {
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withItalic(false)));

		if (lore.length > 0) {
			List<Component> lines = new ArrayList<>();

			for (String line : lore) {
				lines.add(Component.literal(line).withStyle(ChatFormatting.GRAY));
			}

			stack.set(DataComponents.LORE, new ItemLore(lines));
		}

		return stack;
	}

	// ---- clicks ----

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (input == ContainerInput.PICKUP_ALL || input == ContainerInput.QUICK_CRAFT) {
			super.clicked(slotIndex, buttonNum, input, player);
		} else if (slotIndex >= 0 && slotIndex < 54) {
			if (player instanceof ServerPlayer serverPlayer && (picker == null ? clickSettings(slotIndex, input, serverPlayer)
					: clickPicker(slotIndex, input, serverPlayer))) {
				// another screen opened instead
				return;
			}
		} else if (slotIndex >= 54 && input == ContainerInput.QUICK_MOVE) {
			ItemStack stack = slots.get(slotIndex).getItem();

			if (!stack.isEmpty() && picker == null) {
				Projects.Entry existing = project.entryFor(stack);
				selected = existing != null ? existing : project.add(stack, stack.getCount());
			}
		} else if (slotIndex >= 54) {
			super.clicked(slotIndex, buttonNum, input, player);
		}

		changed();
	}

	/** Returns true when another screen took over. */
	private boolean clickSettings(int slot, ContainerInput input, ServerPlayer player) {
		ItemStack carried = getCarried();

		if (slot < ENTRY_SLOTS) {
			if (!carried.isEmpty()) {
				Projects.Entry existing = project.entryFor(carried);
				selected = existing != null ? existing : project.add(carried, carried.getCount());
				return false;
			}

			int index = page * ENTRY_SLOTS + slot;

			if (index < project.entries.size()) {
				selected = project.entries.get(index);
			}

			return false;
		}

		switch (slot) {
			case SLOT_PREV -> page = Math.max(0, page - 1);
			case SLOT_NEXT -> page++;
			case SLOT_MINUS_64 -> adjust(-64);
			case SLOT_MINUS_1 -> adjust(-1);
			case SLOT_PLUS_1 -> adjust(1);
			case SLOT_PLUS_64 -> adjust(64);
			case SLOT_REMOVE -> {
				if (selected != null) {
					project.entries.remove(selected);
					selected = null;
				}
			}
			case SLOT_BLOCK -> project.block = !project.block;
			case SLOT_CLEAR -> {
				if (input == ContainerInput.QUICK_MOVE) {
					project.entries.clear();
					selected = null;
				}
			}
			case SLOT_AMOUNT -> {
				if (selected != null) {
					Projects.Entry entry = selected;
					SearchMenu.open(player, Long.toString(entry.target), "Menge eingeben, dann rechts klicken", "Menge", text -> {
						try {
							entry.target = Math.max(1, Math.min(Long.parseLong(text.trim().replace(".", "")), 1_000_000_000L));
							StorageData.markDirty();
							StorageMenu.refreshAll(project.network());
						} catch (NumberFormatException e) {
							// keeps the old amount
						}

						open(player, project, entry, null, "");
					});
					return true;
				}
			}
			case SLOT_NAME -> {
				Projects.Entry keep = selected;
				SearchMenu.open(player, project.name, "Projektname eingeben, dann rechts klicken", "Name", text -> {
					String name = text.trim();

					if (!name.isEmpty()) {
						project.name = name.length() > Projects.MAX_NAME ? name.substring(0, Projects.MAX_NAME) : name;
						StorageData.markDirty();
					}

					open(player, project, keep, null, "");
				});
				return true;
			}
			case SLOT_SEARCH -> {
				openSearch(player, "");
				return true;
			}
			case SLOT_ITEMS -> {
				StorageMenu.openProject(player, project);
				return true;
			}
			default -> {
			}
		}

		return false;
	}

	private boolean clickPicker(int slot, ContainerInput input, ServerPlayer player) {
		if (slot < PICKER_SLOTS) {
			int index = pickerPage * PICKER_SLOTS + slot;

			if (index < picker.size()) {
				ItemStack item = picker.get(index);
				Projects.Entry existing = project.entryFor(item);
				Projects.Entry entry = existing != null ? existing : project.add(item, input == ContainerInput.QUICK_MOVE ? 64 : 1);
				open(player, project, entry, null, "");
				return true;
			}

			return false;
		}

		switch (slot) {
			case SLOT_PREV -> pickerPage = Math.max(0, pickerPage - 1);
			case SLOT_NEXT -> pickerPage++;
			case SLOT_BACK -> {
				open(player, project, selected, null, "");
				return true;
			}
			case SLOT_SEARCH -> {
				openSearch(player, search);
				return true;
			}
			default -> {
			}
		}

		return false;
	}

	private void openSearch(ServerPlayer player, String current) {
		Projects.Entry keep = selected;
		SearchMenu.open(player, current, "Item suchen: Text eingeben, dann rechts klicken", "Suchbegriff",
				text -> open(player, project, keep, Projects.search(player.level().enabledFeatures(), player.level().registryAccess(), text), text));
	}

	private void adjust(long delta) {
		if (selected != null) {
			selected.target = Math.max(1, Math.min(selected.target + delta, 1_000_000_000L));
		}
	}

	private void changed() {
		StorageData.markDirty();
		StorageMenu.refreshAll(project.network());
		refresh();
		sync();
	}

	private void sync() {
		if (viewer instanceof ServerPlayer) {
			sendAllDataToRemote();
		}
	}

	/** The picker and the settings are separate screens, so a test can look at what this one shows. */
	public boolean isPicker() {
		return picker != null;
	}

	public Projects.Project project() {
		return project;
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
		OPEN.remove(this);

		for (int i = 0; i < 54; i++) {
			display.setItem(i, ItemStack.EMPTY);
		}
	}
}
