package de.nomisimo.storagedim.client;

import java.util.ArrayList;
import java.util.List;

import de.nomisimo.storagedim.robot.Mailbox;
import de.nomisimo.storagedim.robot.MailboxNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The mailbox's screen: its own address, the address it sends to, and a line on what is going on (chest beside it,
 * post waiting, destination found). Saved with the button or when the screen closes.
 */
@Environment(EnvType.CLIENT)
public class MailboxScreen extends Screen {
	private static final int WIDTH = 236;
	private static final int BASE_HEIGHT = 160;
	/** Machine slots per row, and where the slot grid starts. */
	private static final int COLUMNS = 12;
	private static final int SLOTS_Y = 146;
	private static final int TEXT = 0xFF404040;

	private MailboxNetworking.OpenPayload data;
	private EditBox address;
	private EditBox destination;
	private String typedAddress;
	private String typedDestination;

	private MailboxScreen(MailboxNetworking.OpenPayload data) {
		super(Component.literal("Briefkasten"));
		this.data = data;
		this.typedAddress = data.address();
		this.typedDestination = data.destination();
	}

	/** The server opened the mailbox, or answered a save with the new state. */
	public static void receive(MailboxNetworking.OpenPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft.screen instanceof MailboxScreen screen && screen.data.pos() == payload.pos()) {
			// only the state line and the slots change; what is being typed stays
			boolean resize = screen.rows() != rows(payload);
			screen.data = payload;

			if (resize) {
				screen.rebuildWidgets();
			}
		} else {
			minecraft.setScreen(new MailboxScreen(payload));
		}
	}

	private static int rows(MailboxNetworking.OpenPayload payload) {
		return (payload.slots().size() + COLUMNS - 1) / COLUMNS;
	}

	private int rows() {
		return rows(data);
	}

	/** Taller with a machine beside it: its slots are shown with their numbers for the destination. */
	private int panelHeight() {
		return data.machine().isEmpty() ? BASE_HEIGHT : SLOTS_Y + rows() * 18 + 30;
	}

	private int left() {
		return (width - WIDTH) / 2;
	}

	private int top() {
		return (height - panelHeight()) / 2;
	}

	/** The machine slot under the mouse (from 0), or -1. */
	private int hoveredSlot(double mouseX, double mouseY) {
		int x = (int) Math.floor(mouseX) - left() - slotsLeft();
		int y = (int) Math.floor(mouseY) - top() - SLOTS_Y;

		if (x < 0 || y < 0 || x >= COLUMNS * 18 || y >= rows() * 18) {
			return -1;
		}

		int index = y / 18 * COLUMNS + x / 18;
		return index < data.slots().size() ? index : -1;
	}

	private int slotsLeft() {
		return (WIDTH - COLUMNS * 18) / 2 + 1;
	}

	@Override
	protected void init() {
		int l = left();
		int t = top();
		address = addRenderableWidget(new EditBox(font, l + 10, t + 30, WIDTH - 20, 14, Component.literal("Adresse")));
		address.setMaxLength(Mailbox.MAX_ADDRESS);
		address.setHint(Component.literal("Adresse dieses Briefkastens …"));
		address.setValue(typedAddress);
		address.setResponder(text -> typedAddress = text);
		address.setTooltip(Tooltip.create(Component.literal("Unter dieser Adresse bekommt der Briefkasten Post. Mehrere dürfen gleich heißen.")));
		destination = addRenderableWidget(new EditBox(font, l + 10, t + 64, WIDTH - 20, 14, Component.literal("Ziel")));
		destination.setMaxLength(Mailbox.MAX_ADDRESS);
		destination.setHint(Component.literal("Ziel (leer = nur empfangen) …"));
		destination.setValue(typedDestination);
		destination.setResponder(text -> typedDestination = text);
		destination.setTooltip(Tooltip.create(Component.literal("Was in der Kiste daneben liegt, bringen die Kurier-Roboter zum Briefkasten mit dieser Adresse. "
				+ "* steht für beliebigen Text, ? für ein Zeichen: „Ofen*“ geht an den nächsten Briefkasten, dessen Adresse mit Ofen anfängt. "
				+ "Mit Doppelpunkt und Nummer am Ende geht es in diesen Slot der Maschine dort: „Ofen:2“ schickt in Slot 2.")));
		addRenderableWidget(Button.builder(Component.literal("Speichern"), b -> save())
				.bounds(l + WIDTH - 90, t + panelHeight() - 26, 80, 16).build());
		setInitialFocus(address);
	}

	private void save() {
		ClientPlayNetworking.send(new MailboxNetworking.SavePayload(data.pos(), typedAddress, typedDestination));
	}

	@Override
	public void onClose() {
		save();
		super.onClose();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		StationScreen.panel(graphics, left(), top(), WIDTH, panelHeight());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int l = left();
		int t = top();
		graphics.text(font, title, l + 10, t + 7, TEXT, false);
		graphics.text(font, "Adresse", l + 10, t + 20, TEXT, false);
		graphics.text(font, "Schickt an", l + 10, t + 54, TEXT, false);
		graphics.textWithWordWrap(font, Component.literal(data.state()).withStyle(ChatFormatting.DARK_GRAY), l + 10, t + 86, WIDTH - 20, TEXT, false);

		if (data.machine().isEmpty()) {
			return;
		}

		// the machine's slots with their numbers: "Adresse:Nummer" sends into that slot
		graphics.text(font, data.machine() + ": Slots (Ziel „Adresse:Nr.“)", l + 10, t + SLOTS_Y - 11, TEXT, false);

		for (int i = 0; i < data.slots().size(); i++) {
			int x = l + slotsLeft() + i % COLUMNS * 18;
			int y = t + SLOTS_Y + i / COLUMNS * 18;
			StationScreen.slot(graphics, x, y);
			ItemStack stack = data.slots().get(i);

			if (!stack.isEmpty()) {
				graphics.item(stack, x, y);
			}

			String number = String.valueOf(i + 1);
			graphics.text(font, number, x + 17 - font.width(number), y + 9, 0xFFFFFF55, true);
		}

		int hovered = hoveredSlot(mouseX, mouseY);

		if (hovered >= 0) {
			ItemStack stack = data.slots().get(hovered);
			List<Component> lines = new ArrayList<>();
			lines.add(Component.literal("Slot " + (hovered + 1)));
			lines.add(stack.isEmpty() ? Component.literal("leer").withStyle(ChatFormatting.GRAY)
					: Component.literal(stack.getCount() + "× ").append(stack.getHoverName()).withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal("Ziel „" + (data.address().isEmpty() ? "Adresse" : data.address()) + ":" + (hovered + 1)
					+ "“ an einem anderen Briefkasten schickt hierher").withStyle(ChatFormatting.DARK_GRAY));
			graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
