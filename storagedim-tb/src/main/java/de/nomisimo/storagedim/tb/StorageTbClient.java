package de.nomisimo.storagedim.tb;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.tiviacz.travelersbackpack.client.screens.BackpackScreen;
import com.tiviacz.travelersbackpack.client.screens.buttons.IButton;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;

import de.nomisimo.storagedim.tb.mixin.ScreenTitleAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of the ME view in the backpack window, laid out like the ME terminal: a grey bar above the window with the
 * search field (coupled with JEI's search bar when the "J" switch is on) and the sort button, and over the bottom slot
 * row the page arrows, "Einlagern" and the page and stock info. Also the window title and the stored-item list JEI's "+"
 * checks against.
 *
 * <p>The backpack screen neither draws nor clicks widgets it does not know, so these are drawn after the screen and get
 * their clicks before it.
 */
@Environment(EnvType.CLIENT)
public class StorageTbClient implements ClientModInitializer {
	private static final boolean JEI = FabricLoader.getInstance().isModLoaded("jei");

	/** What ME storage holds, as last sent by the server while a backpack with the ME upgrade was open. */
	static volatile List<ItemStack> stockItems = List.of();
	static volatile List<Long> stockCounts = List.of();
	/** The backpack window's own title while it is renamed for the ME view. */
	private static Component backpackTitle;
	private static SearchBox searchBox;
	private static MeNetworking.PagePayload pageInfo;
	private static final Button SORT = Button.builder(Component.empty(), b -> press(MeNetworking.BUTTON_SORT)).size(51, 12).build();
	private static final Button SYNC = Button.builder(Component.empty(), b -> toggleJeiSync()).size(13, 12).build();
	private static final Button PREV = Button.builder(Component.literal("◀"), b -> press(MeNetworking.BUTTON_PREV)).size(16, 16)
			.tooltip(Tooltip.create(Component.literal("Vorherige Seite"))).build();
	private static final Button NEXT = Button.builder(Component.literal("▶"), b -> press(MeNetworking.BUTTON_NEXT)).size(16, 16)
			.tooltip(Tooltip.create(Component.literal("Nächste Seite"))).build();
	private static final Button DEPOSIT = Button.builder(Component.literal("Einlagern"), b -> press(MeNetworking.BUTTON_DEPOSIT)).size(50, 16)
			.tooltip(Tooltip.create(Component.literal("Das ganze Inventar ohne Hotbar einlagern"))).build();
	/** Where the bars are, worked out from the ME slots: the top bar and the covered bottom slot row. */
	private static int barX;
	private static int barY;
	private static int barWidth;
	private static int rowX;
	private static int rowY;
	private static int rowWidth;
	private static boolean hasRow;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(MeNetworking.ViewPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof BackpackBaseMenu menu && menu.containerId == payload.containerId()) {
				MeView.set(menu, payload.on(), true);

				if (context.client().screen instanceof BackpackScreen screen && screen.getMenu() == menu) {
					// the window is called "Storage Dimension" while it shows ME storage
					if (payload.on()) {
						backpackTitle = screen.getTitle();
						((ScreenTitleAccessor) screen).storagedimTb$setTitle(Component.literal("Storage Dimension"));
					} else if (backpackTitle != null) {
						((ScreenTitleAccessor) screen).storagedimTb$setTitle(backpackTitle);
					}

					// a fresh ME view starts without a search
					searchBox = null;
					updateSearch(screen, payload.on());
				}
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(MeNetworking.PagePayload.TYPE, (payload, context) -> pageInfo = payload);
		ClientPlayNetworking.registerGlobalReceiver(MeNetworking.StockPayload.TYPE, (payload, context) -> {
			stockItems = payload.items();
			stockCounts = payload.counts();
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof BackpackScreen backpack) {
				// Traveler's Backpack re-initialises its screen when upgrades change; the field must come back
				updateSearch(backpack, MeView.isOpen(backpack.getMenu()));

				// the backpack screen only draws its own widget types, so the field is drawn here
				ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> {
					if (searchBox != null && Screens.getWidgets(current).contains(searchBox)) {
						draw(backpack, graphics, mouseX, mouseY, partialTick);
					}
				});
				ScreenMouseEvents.allowMouseClick(screen).register((current, event) ->
						!(searchBox != null && Screens.getWidgets(current).contains(searchBox) && click(current, event)));

				// with the search sync on, text typed into JEI's search bar also searches ME storage
				ScreenEvents.afterTick(screen).register(current -> {
					updateSortingButtons(backpack);

					// Traveler's Backpack rebuilds its widgets now and then without telling anybody; the field comes back
					if (MeView.isOpen(backpack.getMenu()) && (searchBox == null || !Screens.getWidgets(current).contains(searchBox))) {
						updateSearch(backpack, true);
					}

					if (searchBox != null && Screens.getWidgets(current).contains(searchBox) && jeiSync()) {
						String jei = TbJeiPlugin.searchText();

						if (jei != null && !jei.equals(searchBox.getValue())) {
							searchBox.setValue(jei);
						}
					}
				});
			}
		});
	}

	/** Shows the search field above the window while the ME view is on, and removes it otherwise. */
	/** The backpack's own buttons while the ME view hides them. */
	private static List<IButton> hiddenButtons;

	/** The backpack's own five sorting buttons make no sense for ME storage: hidden while the ME view is on. */
	@SuppressWarnings("unchecked")
	private static void updateSortingButtons(BackpackScreen screen) {
		if (screen.sortingButtons == null) {
			return;
		}

		// drawn from the screen's renderables, clicked through its children
		List<GuiEventListener> children = (List<GuiEventListener>) screen.children();
		List<Renderable> renderables = ((ScreenTitleAccessor) screen).storagedimTb$getRenderables();
		boolean me = MeView.isOpen(screen.getMenu());

		if (me) {
			children.remove(screen.sortingButtons);
			renderables.remove(screen.sortingButtons);

			// the backpack's own buttons next to them (equip and the like) go too, until the backpack shows again
			if (!screen.buttons.isEmpty()) {
				hiddenButtons = new ArrayList<>(screen.buttons);
				screen.buttons.clear();
			}
		} else {
			if (hiddenButtons != null) {
				// a rebuilt screen made its own buttons meanwhile
				if (screen.buttons.isEmpty()) {
					screen.buttons.addAll(hiddenButtons);
				}

				hiddenButtons = null;
			}

			if (!children.contains(screen.sortingButtons)) {
				children.add(screen.sortingButtons);
			}

			if (!renderables.contains(screen.sortingButtons)) {
				renderables.add(screen.sortingButtons);
			}
		}
	}

	private static void updateSearch(BackpackScreen screen, boolean on) {
		updateSortingButtons(screen);
		Screens.getWidgets(screen).removeIf(widget -> widget instanceof SearchBox);

		if (!on) {
			searchBox = null;
			return;
		}

		String text = searchBox != null ? searchBox.getValue() : "";
		layout(screen);
		SearchBox search = new SearchBox(Minecraft.getInstance(), barX + 6, barY + 4, searchWidth(), 12);
		search.setMaxLength(MeView.MAX_SEARCH);
		search.setHint(Component.literal("Suchen …"));
		search.setValue(text);
		search.setResponder(value -> {
			ClientPlayNetworking.send(new MeNetworking.SearchPayload(value));

			if (jeiSync()) {
				TbJeiPlugin.setSearchText(value);
			}
		});
		searchBox = search;
		Screens.getWidgets(screen).add(search);

		String jei = jeiSync() ? TbJeiPlugin.searchText() : null;

		if (jei != null && !jei.isEmpty()) {
			search.setValue(jei);
		}
	}

	/** Places the bars by the ME slots, which sit where the backpack's storage slots are. */
	private static void layout(BackpackScreen screen) {
		BackpackBaseMenu menu = screen.getMenu();
		List<Slot> slots = new ArrayList<>();

		for (int i = menu.BACKPACK_INV_START; i < Math.min(menu.BACKPACK_INV_END, menu.slots.size()); i++) {
			slots.add(menu.slots.get(i));
		}

		int left = Integer.MAX_VALUE;
		int right = Integer.MIN_VALUE;
		int bottom = Integer.MIN_VALUE;

		for (Slot slot : slots) {
			left = Math.min(left, slot.x);
			right = Math.max(right, slot.x + 16);
			bottom = Math.max(bottom, slot.y);
		}

		if (slots.isEmpty()) {
			left = 8;
			right = 170;
			bottom = 18;
		}

		barWidth = right - left + 14;
		barX = screen.getGuiLeft() + left - 7;
		barY = screen.getGuiTop() - 21;
		hasRow = MeView.bottomRow(slots) > 0;
		rowX = screen.getGuiLeft() + left - 1;
		rowY = screen.getGuiTop() + bottom - 1;
		rowWidth = right - left + 2;

		int sortX = barX + barWidth - 6 - SORT.getWidth();
		SORT.setPosition(sortX, barY + 4);
		SYNC.setPosition(sortX - 2 - SYNC.getWidth(), barY + 4);
		PREV.setPosition(rowX, rowY + 1);
		DEPOSIT.setPosition(rowX + 18, rowY + 1);
		NEXT.setPosition(rowX + rowWidth - 16, rowY + 1);
		SYNC.visible = JEI;
		PREV.visible = hasRow;
		NEXT.visible = hasRow;
		DEPOSIT.visible = hasRow;

		if (searchBox != null) {
			searchBox.setPosition(barX + 6, barY + 4);
			searchBox.setWidth(searchWidth());
		}
	}

	private static int searchWidth() {
		return barWidth - 12 - SORT.getWidth() - 2 - (JEI ? SYNC.getWidth() + 2 : 0);
	}

	private static List<Button> buttons() {
		return List.of(SORT, SYNC, PREV, DEPOSIT, NEXT);
	}

	/** Draws the bars and their buttons over the backpack window, the way the ME terminal looks. */
	private static void draw(BackpackScreen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		layout(screen);
		MeNetworking.PagePayload info = pageInfo != null && pageInfo.containerId() == screen.getMenu().containerId ? pageInfo : null;
		boolean byName = info != null && info.byName();
		SORT.setMessage(Component.literal(byName ? "A–Z" : "Menge"));
		SORT.setTooltip(Tooltip.create(Component.literal(byName ? "Sortiert nach Name\nKlick: nach Menge" : "Sortiert nach Menge\nKlick: nach Name")));
		SYNC.setMessage(Component.literal("J").withStyle(jeiSync() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		SYNC.setTooltip(Tooltip.create(Component.literal(jeiSync()
				? "Suche mit JEI gekoppelt: an\nKlicken zum Trennen"
				: "Suche mit JEI gekoppelt: aus\nKlicken zum Koppeln")));
		PREV.active = info != null && info.page() > 0;
		NEXT.active = info != null && info.page() < info.pages() - 1;

		// the grey bar above the window, framed like the backpack window
		int x = barX;
		int y = barY;
		int w = barWidth;
		int h = 20;
		graphics.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
		graphics.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
		graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF);
		graphics.fill(x + 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
		graphics.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
		searchBox.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// the bottom slot row turns into the button row of the ME terminal
		if (hasRow) {
			graphics.fill(rowX, rowY, rowX + rowWidth, rowY + 18, 0xFFC6C6C6);

			if (info != null) {
				Font font = Minecraft.getInstance().font;
				graphics.text(font, "Seite " + (info.page() + 1) + "/" + info.pages(), rowX + 72, rowY + 1, 0xFF404040, false);
				graphics.text(font, info.kinds() + " Sorten · " + format(info.total()), rowX + 72, rowY + 10, 0xFF404040, false);
			}
		}

		for (Button button : buttons()) {
			if (button.visible) {
				button.extractRenderState(graphics, mouseX, mouseY, partialTick);
			}
		}
	}

	/** A click on the search field or one of the buttons; true when it was one of them. */
	private static boolean click(Screen screen, MouseButtonEvent event) {
		for (Button button : buttons()) {
			if (button.visible && button.isMouseOver(event.x(), event.y())) {
				if (button.active) {
					button.mouseClicked(event, false);
				}

				return true;
			}
		}

		if (searchBox.isMouseOver(event.x(), event.y())) {
			screen.setFocused(searchBox);
			searchBox.mouseClicked(event, false);
			return true;
		}

		if (searchBox.isFocused()) {
			searchBox.setFocused(false);
		}

		return false;
	}

	private static void press(int button) {
		ClientPlayNetworking.send(new MeNetworking.ButtonPayload(button));
	}

	/** Amounts as in the ME terminal: 999, then 1.2k, 45M and so on. */
	private static String format(long count) {
		if (count < 1000) {
			return Long.toString(count);
		}

		String[] units = {"k", "M", "G", "T"};
		double value = count;
		int unit = -1;

		while (value >= 1000 && unit < units.length - 1) {
			value /= 1000;
			unit++;
		}

		String number = value < 10 ? String.format(Locale.ROOT, "%.1f", Math.floor(value * 10) / 10) : Long.toString((long) value);
		return (number.endsWith(".0") ? number.substring(0, number.length() - 2) : number) + units[unit];
	}

	/** Switches the "J" search sync in Storage Dimension's config. */
	private static void toggleJeiSync() {
		try {
			Class<?> type = Class.forName("de.nomisimo.storagedim.StorageConfig");
			Object config = type.getMethod("get").invoke(null);
			Field field = config.getClass().getField("jeiSearchSync");
			boolean on = !field.getBoolean(config);
			field.setBoolean(config, on);
			type.getMethod("save").invoke(null);

			if (on && searchBox != null) {
				String jei = TbJeiPlugin.searchText();

				// JEI's search wins when it has one, otherwise the backpack's goes to JEI
				if (jei != null && !jei.isEmpty()) {
					searchBox.setValue(jei);
				} else {
					TbJeiPlugin.setSearchText(searchBox.getValue());
				}
			}
		} catch (ReflectiveOperationException e) {
			StorageTbMod.LOGGER.warn("JEI-Kopplung nicht umschaltbar", e);
		}
	}

	/** The "J" switch of the ME terminal (Storage Dimension's config), only with JEI. */
	private static boolean jeiSync() {
		if (!JEI) {
			return false;
		}

		try {
			Object config = Class.forName("de.nomisimo.storagedim.StorageConfig").getMethod("get").invoke(null);
			return config.getClass().getField("jeiSearchSync").getBoolean(config);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	/** A search field that keeps every key while focused, so typing "e" or a number does not close the screen or swap items. */
	private static final class SearchBox extends EditBox {
		SearchBox(Minecraft minecraft, int x, int y, int width, int height) {
			super(minecraft.font, x, y, width, height, Component.literal("Suchen"));
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
