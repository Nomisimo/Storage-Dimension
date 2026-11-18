package de.nomisimo.storagedim.client;

import de.nomisimo.storagedim.ServerSettings;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The mod's settings, opened from Mod Menu: whether the server pauses without players while a robot station keeps
 * its area loaded. The setting is the server's (its {@code config/storagedim.json}); operators change it here while
 * they are on the server, in a single player world everyone.
 */
@Environment(EnvType.CLIENT)
public class StorageConfigScreen extends Screen {
	private static final int TEXT_WIDTH = 300;
	/** What the server last told: null while not in a world (or on a server without the mod). */
	private static ServerSettings.StatePayload state;
	private final Screen parent;

	public StorageConfigScreen(Screen parent) {
		super(Component.literal("Storage Dimension"));
		this.parent = parent;
	}

	/** The server's settings arrived (on joining, and whenever someone changes them). */
	static void receive(ServerSettings.StatePayload payload) {
		state = payload;

		if (Minecraft.getInstance().screen instanceof StorageConfigScreen screen) {
			screen.rebuildWidgets();
		}
	}

	private static boolean connected() {
		return Minecraft.getInstance().getConnection() != null && state != null && ClientPlayNetworking.canSend(ServerSettings.KeepAwakePayload.TYPE);
	}

	@Override
	protected void init() {
		boolean connected = connected();
		boolean on = connected && state.keepAwake();
		Component value = !connected ? Component.literal("nur in einer Welt").withStyle(ChatFormatting.GRAY)
				: Component.literal(on ? "Läuft weiter" : "Pausiert").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
		Button button = Button.builder(Component.literal("Server ohne Spieler: ").append(value), b -> {
					ClientPlayNetworking.send(new ServerSettings.KeepAwakePayload(!on));
				})
				.bounds(width / 2 - 125, height / 2 - 10, 250, 20)
				.tooltip(Tooltip.create(Component.literal("Läuft weiter: Solange eine Roboter-Station ihren Bereich geladen hält, pausiert der Server nicht, "
						+ "wenn kein Spieler online ist (pause-when-empty-seconds), und die Roboter arbeiten weiter. "
						+ "Pausiert: Der Server pausiert wie eingestellt, die Roboter stehen dann still.")))
				.build();
		button.active = connected && state.canEdit();
		addRenderableWidget(button);
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose())
				.bounds(width / 2 - 100, height - 30, 200, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(font, title, (width - font.width(title)) / 2, 20, 0xFFFFFFFF, true);
		Component hint = Component.literal(!connected() ? "Diese Einstellung gehört zum Server. Öffne sie, während du auf dem Server oder in deiner Welt bist."
				: state.canEdit() ? "Gilt für den ganzen Server und wird dort in config/storagedim.json gespeichert."
				: "Gilt für den ganzen Server. Nur Operatoren können sie ändern.");
		int textWidth = Math.min(TEXT_WIDTH, width - 32);
		graphics.textWithWordWrap(font, hint, (width - textWidth) / 2, height / 2 + 20, textWidth, 0xFFAAAAAA, false);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
