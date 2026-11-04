package de.nomisimo.storagedim.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.nomisimo.storagedim.storage.ProjectNetworking;
import de.nomisimo.storagedim.storage.ProjectSettingsMenu;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * The project chest's settings: name and "im ME blocken" on top, then a search over every item, the list with one row
 * per item (amount field between − and + buttons, ✕ to remove) and an empty row at the end to lay a new item in, the
 * book slots for writing the project into a book and quill, and the player inventory.
 */
@Environment(EnvType.CLIENT)
public class ProjectScreen extends AbstractContainerScreen<ProjectSettingsMenu> {
	private static final int TEXT = 0xFF404040;
	private static final int GRID_COLUMNS = 8;
	private static final int ITEM_X = 8;
	private static final int SCROLL_X = 162;

	private ProjectNetworking.SyncPayload data;
	private EditBox name;
	private EditBox search;
	private Button block;
	private final List<Row> rows = new ArrayList<>();
	private int scroll;
	/** Search hits over every item of the game, or empty while the search field is empty. */
	private List<ItemStack> hits = List.of();
	private boolean updating;

	public ProjectScreen(ProjectSettingsMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, ProjectSettingsMenu.WIDTH, ProjectSettingsMenu.HEIGHT);
		inventoryLabelY = ProjectSettingsMenu.INVENTORY_Y - 10;
	}

	public static void receive(ProjectNetworking.SyncPayload payload) {
		if (Minecraft.getInstance().screen instanceof ProjectScreen screen && screen.menu.containerId == payload.containerId()) {
			screen.data = payload;
			screen.update();
		}
	}

	/** One line of the list: the item cell is drawn, the buttons and the field are widgets. */
	private final class Row {
		final int y;
		final Button minus64;
		final Button minus1;
		final EditBox amount;
		final Button plus1;
		final Button plus64;
		final Button remove;
		int index = -1;

		Row(int y) {
			this.y = y;
			int l = leftPos;
			int t = topPos + y;
			minus64 = addRenderableWidget(Button.builder(Component.literal("-64"), b -> send(ProjectSettingsMenu.ACTION_ADJUST, index, -64))
					.bounds(l + 26, t + 2, 20, 16).build());
			minus1 = addRenderableWidget(Button.builder(Component.literal("-1"), b -> send(ProjectSettingsMenu.ACTION_ADJUST, index, -1))
					.bounds(l + 47, t + 2, 14, 16).build());
			amount = addRenderableWidget(new KeyBox(font, l + 63, t + 3, 36, 14, "Menge"));
			amount.setMaxLength(10);
			amount.setResponder(text -> {
				if (updating) {
					return;
				}

				// digits only
				String digits = text.replaceAll("\\D", "");

				if (!digits.equals(text)) {
					updating = true;
					amount.setValue(digits);
					updating = false;
				}

				if (!digits.isEmpty()) {
					send(ProjectSettingsMenu.ACTION_SET_AMOUNT, index, Long.parseLong(digits));
				}
			});
			plus1 = addRenderableWidget(Button.builder(Component.literal("+1"), b -> send(ProjectSettingsMenu.ACTION_ADJUST, index, 1))
					.bounds(l + 101, t + 2, 14, 16).build());
			plus64 = addRenderableWidget(Button.builder(Component.literal("+64"), b -> send(ProjectSettingsMenu.ACTION_ADJUST, index, 64))
					.bounds(l + 116, t + 2, 20, 16).build());
			remove = addRenderableWidget(Button.builder(Component.literal("✕").withStyle(ChatFormatting.RED), b -> send(ProjectSettingsMenu.ACTION_REMOVE, index, 0))
					.bounds(l + 138, t + 2, 14, 16).tooltip(Tooltip.create(Component.literal("Aus dem Projekt entfernen"))).build());
		}

		void show(int index, boolean entry) {
			this.index = index;
			boolean visible = entry && hits.isEmpty();
			minus64.visible = visible;
			minus1.visible = visible;
			amount.visible = visible;
			plus1.visible = visible;
			plus64.visible = visible;
			remove.visible = visible;

			if (visible && !amount.isFocused()) {
				String value = Long.toString(data.targets().get(index));

				if (!amount.getValue().equals(value)) {
					updating = true;
					amount.setValue(value);
					updating = false;
				}
			}
		}
	}

	@Override
	protected void init() {
		super.init();
		int l = leftPos;
		int t = topPos;
		rows.clear();
		name = addRenderableWidget(new KeyBox(font, l + 7, t + ProjectSettingsMenu.NAME_Y, 98, 12, "Projektname …"));
		name.setMaxLength(32);
		name.setResponder(text -> {
			if (!updating) {
				ClientPlayNetworking.send(new ProjectNetworking.ActionPayload(menu.containerId, ProjectSettingsMenu.ACTION_NAME, 0, 0, text, ItemStack.EMPTY));
			}
		});
		block = addRenderableWidget(Button.builder(Component.empty(), b -> send(ProjectSettingsMenu.ACTION_BLOCK, 0, 0))
				.bounds(l + 107, t + ProjectSettingsMenu.NAME_Y - 1, 62, 14)
				.tooltip(Tooltip.create(Component.literal("An: die Mengen bleiben für das Projekt im ME-Speicher, "
						+ "woanders kann man nur nehmen, was darüber hinausgeht")))
				.build());
		search = addRenderableWidget(new KeyBox(font, l + 7, t + ProjectSettingsMenu.SEARCH_Y, 162, 12, "Item suchen …"));
		search.setMaxLength(50);
		search.setResponder(this::searchChanged);

		for (int i = 0; i < ProjectSettingsMenu.ROWS; i++) {
			rows.add(new Row(ProjectSettingsMenu.LIST_Y + i * ProjectSettingsMenu.ROW_HEIGHT));
		}

		addRenderableWidget(Button.builder(Component.literal("Zu den Items"), b -> send(ProjectSettingsMenu.ACTION_ITEMS, 0, 0))
				.bounds(l + 104, t + ProjectSettingsMenu.BOOK_Y - 1, 65, 18)
				.tooltip(Tooltip.create(Component.literal("Die Projekt-Items aus dem ME-Speicher"))).build());
		update();
	}

	private void send(int action, int index, long value) {
		ClientPlayNetworking.send(new ProjectNetworking.ActionPayload(menu.containerId, action, index, value, "", ItemStack.EMPTY));
	}

	private void searchChanged(String text) {
		String query = text.strip().toLowerCase(Locale.ROOT);
		scroll = 0;

		if (query.isEmpty()) {
			hits = List.of();
		} else {
			List<ItemStack> found = new ArrayList<>();
			var features = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.enabledFeatures();

			for (Item item : BuiltInRegistries.ITEM) {
				if (item == Items.AIR || features != null && !item.isEnabled(features)) {
					continue;
				}

				ItemStack stack = new ItemStack(item);

				if (stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)
						|| BuiltInRegistries.ITEM.getKey(item).toString().contains(query)) {
					found.add(stack);
				}
			}

			hits = found;
		}

		update();
	}

	/** Shows the latest data; fields being typed in are left alone. */
	private void update() {
		if (block == null) {
			return;
		}

		int size = data == null ? 0 : data.items().size();
		scroll = Math.clamp(scroll, 0, maxScroll());

		for (int i = 0; i < rows.size(); i++) {
			int index = scroll + i;
			rows.get(i).show(index, data != null && index < size);
		}

		if (data == null) {
			return;
		}

		if (!name.isFocused() && !name.getValue().equals(data.name())) {
			updating = true;
			name.setValue(data.name());
			updating = false;
		}

		block.setMessage(Component.literal((data.block() ? "☑" : "☐") + " Blocken").withStyle(data.block() ? ChatFormatting.GREEN : ChatFormatting.WHITE));
	}

	/** Rows of the list: every item plus the empty row at the end; or rows of search hits. */
	private int lineCount() {
		if (!hits.isEmpty()) {
			return (hits.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
		}

		return (data == null ? 0 : data.items().size()) + 1;
	}

	private int maxScroll() {
		return Math.max(0, lineCount() - ProjectSettingsMenu.ROWS);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		int l = leftPos;
		int t = topPos;
		StationScreen.panel(graphics, l, t, imageWidth, imageHeight);
		int listTop = t + ProjectSettingsMenu.LIST_Y;
		int listBottom = listTop + ProjectSettingsMenu.ROWS * ProjectSettingsMenu.ROW_HEIGHT;
		graphics.fill(l + 6, listTop - 1, l + 170, listBottom + 1, 0xFF8B8B8B);
		graphics.fill(l + 7, listTop, l + 169, listBottom, 0xFFB0B0B0);

		// scroll bar
		int lines = lineCount();

		if (lines > ProjectSettingsMenu.ROWS) {
			int height = ProjectSettingsMenu.ROWS * ProjectSettingsMenu.ROW_HEIGHT;
			int thumb = Math.max(10, height * ProjectSettingsMenu.ROWS / lines);
			int y = listTop + (height - thumb) * scroll / maxScroll();
			graphics.fill(l + SCROLL_X, listTop, l + SCROLL_X + 6, listBottom, 0xFF555555);
			graphics.fill(l + SCROLL_X, y, l + SCROLL_X + 6, y + thumb, 0xFFE0E0E0);
		}

		if (!hits.isEmpty()) {
			for (int i = 0; i < ProjectSettingsMenu.ROWS * GRID_COLUMNS; i++) {
				int index = scroll * GRID_COLUMNS + i;

				if (index >= hits.size()) {
					break;
				}

				int x = l + ITEM_X + (i % GRID_COLUMNS) * 19;
				int y = listTop + 2 + (i / GRID_COLUMNS) * ProjectSettingsMenu.ROW_HEIGHT;
				StationScreen.slot(graphics, x, y);
				graphics.item(hits.get(index), x, y);
			}
		} else {
			for (Row row : rows) {
				int x = l + ITEM_X;
				int y = t + row.y + 2;
				boolean entry = data != null && row.index < data.items().size();

				if (data != null && row.index > data.items().size()) {
					continue;
				}

				if (entry && data.have().get(row.index) >= data.targets().get(row.index)) {
					// everything there: the row turns green
					graphics.fill(l + 7, y - 2, l + SCROLL_X - 1, y + 18, 0xFF8FCF8F);
				}

				StationScreen.slot(graphics, x, y);

				if (entry) {
					graphics.item(data.items().get(row.index), x, y);
				} else {
					graphics.text(font, "← Item hierher legen", x + 22, y + 4, 0xFF505050, false);
				}
			}
		}

		StationScreen.slot(graphics, l + ProjectSettingsMenu.BOOK_IN_X, t + ProjectSettingsMenu.BOOK_Y);
		StationScreen.slot(graphics, l + ProjectSettingsMenu.BOOK_OUT_X, t + ProjectSettingsMenu.BOOK_Y);

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				StationScreen.slot(graphics, l + 8 + col * 18, t + ProjectSettingsMenu.INVENTORY_Y + row * 18);
			}
		}

		for (int col = 0; col < 9; col++) {
			StationScreen.slot(graphics, l + 8 + col * 18, t + ProjectSettingsMenu.INVENTORY_Y + 58);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
		graphics.text(font, "Buch:", 8, ProjectSettingsMenu.BOOK_Y + 4, TEXT, false);
		graphics.text(font, "→", ProjectSettingsMenu.BOOK_IN_X + 22, ProjectSettingsMenu.BOOK_Y + 4, TEXT, false);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int hovered = hoveredCell(mouseX, mouseY);

		if (hovered < 0) {
			return;
		}

		if (!hits.isEmpty()) {
			List<Component> lines = new ArrayList<>();
			lines.add(hits.get(hovered).getHoverName());
			lines.add(Component.literal("Klick: zum Projekt").withStyle(ChatFormatting.GRAY));
			graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		} else if (data != null && hovered < data.items().size()) {
			long have = data.have().get(hovered);
			long target = data.targets().get(hovered);
			List<Component> lines = new ArrayList<>();
			lines.add(data.items().get(hovered).getHoverName());
			lines.add(Component.literal(TerminalCounts.format(have) + " von " + TerminalCounts.format(target) + " da")
					.withStyle(have >= target ? ChatFormatting.GREEN : ChatFormatting.AQUA));

			if (have < target) {
				lines.add(Component.literal("Fehlen: " + (target - have)).withStyle(ChatFormatting.RED));
			}

			lines.add(Component.literal("Klick mit einem Item: tauschen").withStyle(ChatFormatting.GRAY));
			graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		} else if (data != null) {
			graphics.setComponentTooltipForNextFrame(font, List.of(Component.literal("Item hierher legen: kommt ins Projekt"),
					Component.literal("Oder Shift-Klick im Inventar, oder oben suchen").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
		}
	}

	/** The list entry (or search hit) whose item cell is under the mouse, or -1. */
	private int hoveredCell(double mouseX, double mouseY) {
		int listTop = topPos + ProjectSettingsMenu.LIST_Y;
		int line = (int) Math.floor((mouseY - listTop) / ProjectSettingsMenu.ROW_HEIGHT);

		if (mouseY < listTop || line >= ProjectSettingsMenu.ROWS) {
			return -1;
		}

		double inRow = mouseY - listTop - line * ProjectSettingsMenu.ROW_HEIGHT;

		if (inRow < 2 || inRow >= 18) {
			return -1;
		}

		if (!hits.isEmpty()) {
			int column = (int) Math.floor((mouseX - leftPos - ITEM_X) / 19);

			if (mouseX < leftPos + ITEM_X || column >= GRID_COLUMNS || mouseX - leftPos - ITEM_X - column * 19 >= 16) {
				return -1;
			}

			int index = (scroll + line) * GRID_COLUMNS + column;
			return index < hits.size() ? index : -1;
		}

		if (mouseX < leftPos + ITEM_X || mouseX >= leftPos + ITEM_X + 16) {
			return -1;
		}

		int index = scroll + line;
		return data != null && index <= data.items().size() ? index : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int cell = hoveredCell(event.x(), event.y());

		if (cell >= 0) {
			if (!hits.isEmpty()) {
				ClientPlayNetworking.send(new ProjectNetworking.ActionPayload(menu.containerId, ProjectSettingsMenu.ACTION_ADD_STACK, 0, 0, "", hits.get(cell)));
				search.setValue("");
				// jump to the end, where the new item is
				scroll = Integer.MAX_VALUE;
			} else {
				send(ProjectSettingsMenu.ACTION_SET_ITEM, cell, 0);
			}

			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int listTop = topPos + ProjectSettingsMenu.LIST_Y;

		if (mouseY >= listTop && mouseY < listTop + ProjectSettingsMenu.ROWS * ProjectSettingsMenu.ROW_HEIGHT && mouseX >= leftPos && mouseX < leftPos + imageWidth) {
			scroll -= (int) Math.signum(scrollY);
			update();
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** Types into the search field, for the client test. */
	public void setSearch(String text) {
		search.setValue(text);
	}

	/** Where the list is on screen, for JEI to drop items onto. */
	public Rect2i listArea() {
		return new Rect2i(leftPos + 7, topPos + ProjectSettingsMenu.LIST_Y, 162, ProjectSettingsMenu.ROWS * ProjectSettingsMenu.ROW_HEIGHT);
	}

	/** An item dragged from JEI onto the list. */
	public void addFromJei(ItemStack stack) {
		ClientPlayNetworking.send(new ProjectNetworking.ActionPayload(menu.containerId, ProjectSettingsMenu.ACTION_ADD_STACK, 0, 0, "", stack.copyWithCount(1)));
		scroll = Integer.MAX_VALUE;
	}

	/** A text field that keeps every key while focused, so typing "e" or a number does not close the screen or swap items. */
	private static final class KeyBox extends EditBox {
		KeyBox(Font font, int x, int y, int width, int height, String hint) {
			super(font, x, y, width, height, Component.literal(hint));
			setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (super.keyPressed(event)) {
				return true;
			}

			return isFocused() && isVisible() && event.key() != GLFW.GLFW_KEY_ESCAPE;
		}
	}
}
