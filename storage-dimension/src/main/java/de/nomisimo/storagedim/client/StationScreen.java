package de.nomisimo.storagedim.client;

import java.util.List;

import de.nomisimo.storagedim.robot.RobotConfig;
import de.nomisimo.storagedim.robot.RobotEntity;
import de.nomisimo.storagedim.robot.StationMenu;
import de.nomisimo.storagedim.robot.StationNetworking;
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
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/**
 * The robot station's own screen: name fields, buttons for filter mode, range, colour and the recall, the filter and
 * robot slots, and next to it the list of the station's robots with what each one is doing.
 */
@Environment(EnvType.CLIENT)
public class StationScreen extends AbstractContainerScreen<StationMenu> {
	private static final int LIST_GAP = 4;
	private static final int LIST_WIDTH = 150;
	private static final int ENTRY_HEIGHT = 21;
	private static final int LIST_TOP = 20;
	private static final int TEXT = 0xFF404040;

	private StationNetworking.SyncPayload data;
	private EditBox stationName;
	private EditBox robotName;
	private Button mode;
	private Button color;
	private int scroll;

	public StationScreen(StationMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, StationMenu.WIDTH, StationMenu.HEIGHT);
		inventoryLabelY = StationMenu.INVENTORY_Y - 10;
	}

	/** Settings and robot list from the server. */
	public static void receive(StationNetworking.SyncPayload payload) {
		if (Minecraft.getInstance().screen instanceof StationScreen screen && screen.menu.containerId == payload.containerId()) {
			screen.data = payload;
			screen.update();
		}
	}

	@Override
	protected void init() {
		super.init();
		// the robot list sits to the right of the window; both together are centred
		leftPos -= (LIST_WIDTH + LIST_GAP) / 2;
		int l = leftPos;
		int t = topPos;

		stationName = addRenderableWidget(new NameBox(font, l + 7, t + 5, 162, 12, "Name der Station …"));
		stationName.setResponder(text -> send(StationMenu.ACTION_STATION_NAME, 0, text));
		mode = addRenderableWidget(Button.builder(Component.empty(), b -> send(StationMenu.ACTION_MODE, 0, ""))
				.bounds(l + 8, t + StationMenu.MODE_Y, 160, 14)
				.tooltip(Tooltip.create(Component.literal("Umschalten: die Items im Filter sammeln oder liegen lassen")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("−"), b -> send(StationMenu.ACTION_RANGE, -1, ""))
				.bounds(l + 8, t + StationMenu.RANGE_Y, 20, 14).tooltip(Tooltip.create(Component.literal("Reichweite kleiner"))).build());
		addRenderableWidget(Button.builder(Component.literal("+"), b -> send(StationMenu.ACTION_RANGE, 1, ""))
				.bounds(l + 148, t + StationMenu.RANGE_Y, 20, 14).tooltip(Tooltip.create(Component.literal("Reichweite größer"))).build());
		color = addRenderableWidget(Button.builder(Component.empty(), b -> send(StationMenu.ACTION_COLOR, 1, ""))
				.bounds(l + 8, t + StationMenu.BUTTONS_Y, 78, 14).tooltip(Tooltip.create(Component.literal("Klick: nächste Farbe"))).build());
		addRenderableWidget(Button.builder(Component.literal("Zurückrufen"), b -> send(StationMenu.ACTION_RECALL, 0, ""))
				.bounds(l + 90, t + StationMenu.BUTTONS_Y, 78, 14)
				.tooltip(Tooltip.create(Component.literal("Alle Roboter kommen zur Station und warten dort eine Minute. "
						+ "Wer keinen Weg zur Station findet, teleportiert sich her.")))
				.build());
		robotName = addRenderableWidget(new NameBox(font, l + 8, t + StationMenu.IO_Y, 84, 16, "Robotername …"));
		robotName.setResponder(text -> send(StationMenu.ACTION_ROBOT_NAME, 0, text));
		update();
	}

	private void send(int action, int value, String text) {
		ClientPlayNetworking.send(new StationNetworking.ActionPayload(menu.containerId, action, value, text));
	}

	/** Shows the latest settings; a name field being typed in is left alone. */
	private void update() {
		if (data == null || mode == null) {
			return;
		}

		if (!stationName.isFocused() && !stationName.getValue().equals(data.stationName())) {
			stationName.setValue(data.stationName());
		}

		if (!robotName.isFocused() && !robotName.getValue().equals(data.robotName())) {
			robotName.setValue(data.robotName());
		}

		mode.setMessage(Component.literal(data.exclude() ? "Sammelt alles AUSSER Filter" : "Sammelt nur den Filter"));
		color.setMessage(Component.literal("■ ").withColor(data.color()).append(Component.literal(RobotConfig.colorName(data.color()))
				.withStyle(ChatFormatting.WHITE)));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		int l = leftPos;
		int t = topPos;
		panel(graphics, l, t, imageWidth, imageHeight);
		panel(graphics, l + imageWidth + LIST_GAP, t, LIST_WIDTH, imageHeight);

		for (int i = 0; i < StationMenu.FILTER_SLOTS; i++) {
			slot(graphics, l + StationMenu.FILTER_X + i * 18, t + StationMenu.FILTER_Y);
		}

		slot(graphics, l + StationMenu.INPUT_X, t + StationMenu.IO_Y);
		slot(graphics, l + StationMenu.OUTPUT_X, t + StationMenu.IO_Y);

		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 9; col++) {
				slot(graphics, l + 8 + col * 18, t + StationMenu.INVENTORY_Y + row * 18);
			}
		}

		for (int col = 0; col < 9; col++) {
			slot(graphics, l + 8 + col * 18, t + StationMenu.INVENTORY_Y + 58);
		}

		// the robot list: a darker inset with one entry per robot
		int x = l + imageWidth + LIST_GAP + 6;
		int y = t + LIST_TOP;
		graphics.fill(x - 1, y - 1, x + LIST_WIDTH - 11, t + imageHeight - 6, 0xFF8B8B8B);
		graphics.fill(x, y, x + LIST_WIDTH - 12, t + imageHeight - 7, 0xFF2B2B2B);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
		graphics.text(font, "Filter (leer = alles)", 8, StationMenu.FILTER_Y - 10, TEXT, false);
		graphics.text(font, "Roboter hineinlegen:", 8, StationMenu.IO_Y - 10, TEXT, false);
		graphics.text(font, "→", StationMenu.INPUT_X + 23, StationMenu.IO_Y + 4, TEXT, false);

		if (data == null) {
			return;
		}

		graphics.centeredText(font, Component.literal(RobotConfig.rangeText(data.range())), 88, StationMenu.RANGE_Y + 3, 0xFFFFFFFF);

		int x = imageWidth + LIST_GAP;
		List<String> names = data.names();
		graphics.text(font, "Roboter (" + names.size() + "/" + StationMenu.MAX_ROBOTS + ")", x + 6, 6, TEXT, false);

		if (names.isEmpty()) {
			graphics.textWithWordWrap(font, Component.literal("Noch keine Roboter. Leg einen Roboter in den Eingang und stell ihn hin.")
					.withStyle(ChatFormatting.GRAY), x + 9, LIST_TOP + 4, LIST_WIDTH - 18, 0xFFAAAAAA, false);
			return;
		}

		int visible = visibleEntries();
		scroll = Math.clamp(scroll, 0, Math.max(0, names.size() - visible));

		for (int i = 0; i < visible && scroll + i < names.size(); i++) {
			int index = scroll + i;
			int y = LIST_TOP + 2 + i * ENTRY_HEIGHT;
			String name = names.get(index).isEmpty() ? "Roboter " + (index + 1) : names.get(index);
			graphics.text(font, font.plainSubstrByWidth(name, LIST_WIDTH - 20), x + 9, y, 0xFFFFFFFF, true);
			Component state = data.states().get(index);
			graphics.text(font, font.substrByWidth(state, LIST_WIDTH - 20).getString().equals(state.getString())
					? state : Component.literal(font.plainSubstrByWidth(state.getString(), LIST_WIDTH - 26) + "…").withStyle(state.getStyle()),
					x + 9, y + 10, 0xFFCCCCCC, false);
		}

		if (names.size() > visible) {
			graphics.text(font, "↕", x + LIST_WIDTH - 14, 6, TEXT, false);
		}
	}

	/** A robot's list entry with its error icons in red. */
	private static Component errorIconsRed(String name) {
		MutableComponent text = Component.empty();
		String[] parts = name.split(RobotEntity.ERROR_ICON, -1);

		for (int i = 0; i < parts.length; i++) {
			if (i > 0) {
				text.append(Component.literal(RobotEntity.ERROR_ICON).withStyle(ChatFormatting.RED));
			}

			text.append(parts[i]);
		}

		return text;
	}

	/** Where the robot list is on screen, for JEI to keep its item list away from it. */
	public Rect2i listArea() {
		return new Rect2i(leftPos + imageWidth + LIST_GAP, topPos, LIST_WIDTH, imageHeight);
	}

	private int visibleEntries() {
		return (imageHeight - LIST_TOP - 10) / ENTRY_HEIGHT;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= leftPos + imageWidth) {
			scroll -= (int) Math.signum(scrollY);
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
		// the robot list is part of the window, a click there must not drop the carried item
		boolean list = mouseX >= left + imageWidth && mouseX < left + imageWidth + LIST_GAP + LIST_WIDTH && mouseY >= top && mouseY < top + imageHeight;
		return !list && super.hasClickedOutside(mouseX, mouseY, left, top);
	}

	/** A window panel in the look of vanilla screens. */
	static void panel(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		graphics.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
		graphics.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
		graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF);
		graphics.fill(x + 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
		graphics.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
	}

	/** A slot background for an item at (x, y). */
	static void slot(GuiGraphicsExtractor graphics, int x, int y) {
		graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
		graphics.fill(x, y, x + 17, y + 17, 0xFFFFFFFF);
		graphics.fill(x, y, x + 16, y + 16, 0xFF8B8B8B);
	}

	/** A name field that keeps every key while focused, so typing "e" or a number does not close the screen or swap items. */
	private static final class NameBox extends EditBox {
		NameBox(Font font, int x, int y, int width, int height, String hint) {
			super(font, x, y, width, height, Component.literal(hint));
			setMaxLength(StationMenu.MAX_NAME);
			setHint(Component.literal(hint));
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
