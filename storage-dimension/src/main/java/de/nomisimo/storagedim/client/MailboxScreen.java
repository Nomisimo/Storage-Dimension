package de.nomisimo.storagedim.client;

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

/**
 * The mailbox's screen: its own address, the address it sends to, and a line on what is going on (chest beside it,
 * post waiting, destination found). Saved with the button or when the screen closes.
 */
@Environment(EnvType.CLIENT)
public class MailboxScreen extends Screen {
	private static final int WIDTH = 236;
	private static final int HEIGHT = 196;
	private static final int TEXT = 0xFF404040;

	private MailboxNetworking.OpenPayload data;
	private EditBox address;
	private EditBox destination;
	private String typedAddress;
	private String typedDestination;
	private int machineIn;
	private boolean machineOut;
	private Button inButton;
	private Button outButton;

	private MailboxScreen(MailboxNetworking.OpenPayload data) {
		super(Component.literal("Briefkasten"));
		this.data = data;
		this.typedAddress = data.address();
		this.typedDestination = data.destination();
		this.machineIn = data.machineIn();
		this.machineOut = data.machineOut();
	}

	/** The server opened the mailbox, or answered a save with the new state. */
	public static void receive(MailboxNetworking.OpenPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft.screen instanceof MailboxScreen screen && screen.data.pos() == payload.pos()) {
			// only the state line changes; what is being typed stays
			screen.data = payload;
			screen.updateButtons();
		} else {
			minecraft.setScreen(new MailboxScreen(payload));
		}
	}

	private int left() {
		return (width - WIDTH) / 2;
	}

	private int top() {
		return (height - HEIGHT) / 2;
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
				+ "* steht für beliebigen Text, ? für ein Zeichen: „Ofen*“ geht an den nächsten Briefkasten, dessen Adresse mit Ofen anfängt.")));
		inButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			machineIn = (machineIn + 1) % 3;
			updateButtons();
			save();
		}).bounds(l + 10, t + 88, WIDTH - 20, 16)
				.tooltip(Tooltip.create(Component.literal("Steht eine Maschine (Ofen, Räucherofen, Braustand, Trichter, Maschinen anderer Mods) direkt am Briefkasten: "
						+ "Was ankommt, kommt in ihre Zutaten (wie ein Trichter von oben) oder in den Brennstoff (wie ein Trichter von der Seite). "
						+ "Was nicht passt, kommt in die Kiste daneben.")))
				.build());
		outButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			machineOut = !machineOut;
			updateButtons();
			save();
		}).bounds(l + 10, t + 108, WIDTH - 20, 16)
				.tooltip(Tooltip.create(Component.literal("An: Der Briefkasten schickt auch, was fertig aus der Maschine kommt (ihren Ausgang, wie ein Trichter darunter), "
						+ "an das Ziel oben. Zutaten und Brennstoff bleiben drin.")))
				.build());
		updateButtons();
		addRenderableWidget(Button.builder(Component.literal("Speichern"), b -> save())
				.bounds(l + WIDTH - 90, t + HEIGHT - 26, 80, 16).build());
		setInitialFocus(address);
	}

	private void updateButtons() {
		String in = machineIn == Mailbox.IN_INGREDIENTS ? "Zutaten" : machineIn == Mailbox.IN_FUEL ? "Brennstoff" : "aus";
		inButton.setMessage(Component.literal("Ankommendes in Maschine: ").append(Component.literal(in)
				.withStyle(machineIn == Mailbox.IN_NONE ? ChatFormatting.WHITE : ChatFormatting.GREEN)));
		outButton.setMessage(Component.literal("Fertiges aus Maschine schicken: ").append(Component.literal(machineOut ? "an" : "aus")
				.withStyle(machineOut ? ChatFormatting.GREEN : ChatFormatting.WHITE)));
		inButton.active = data.machine() || machineIn != Mailbox.IN_NONE;
		outButton.active = data.machine() || machineOut;
	}

	private void save() {
		ClientPlayNetworking.send(new MailboxNetworking.SavePayload(data.pos(), typedAddress, typedDestination, machineIn, machineOut));
	}

	@Override
	public void onClose() {
		save();
		super.onClose();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		StationScreen.panel(graphics, left(), top(), WIDTH, HEIGHT);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int l = left();
		int t = top();
		graphics.text(font, title, l + 10, t + 7, TEXT, false);
		graphics.text(font, "Adresse", l + 10, t + 20, TEXT, false);
		graphics.text(font, "Schickt an", l + 10, t + 54, TEXT, false);
		graphics.text(font, data.machine() ? "Maschine daneben" : "Keine Maschine daneben", l + 10, t + 79, TEXT, false);
		graphics.textWithWordWrap(font, Component.literal(data.state()).withStyle(ChatFormatting.DARK_GRAY), l + 10, t + 130, WIDTH - 20, TEXT, false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
