package de.nomisimo.storagedim.client;

import java.util.List;

import de.nomisimo.storagedim.robot.Areas;
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
 * The remote's screen: the auto mode switch on top, below it every antenna area around with an on/off switch.
 * A switched-off area sends its robots home to wait.
 */
@Environment(EnvType.CLIENT)
public class RemoteScreen extends Screen {
	private static final int WIDTH = 236;
	private static final int ROW = 22;
	private static final int ROWS = 7;
	private static final int TOP = 44;
	private static final int TEXT = 0xFF404040;

	private Areas.OpenPayload data;
	private int scroll;

	private RemoteScreen(Areas.OpenPayload data) {
		super(Component.literal("Fernbedienung"));
		this.data = data;
	}

	/** The server answered a right-click with the remote, or a switch: opens the screen or shows the new state. */
	public static void receive(Areas.OpenPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft.screen instanceof RemoteScreen screen) {
			screen.data = payload;
			screen.init(screen.width, screen.height);
		} else {
			minecraft.setScreen(new RemoteScreen(payload));
		}
	}

	private int left() {
		return (width - WIDTH) / 2;
	}

	private int top() {
		return (height - height()) / 2;
	}

	private int height() {
		return TOP + ROWS * ROW + 10;
	}

	@Override
	protected void init() {
		int l = left();
		int t = top();
		boolean auto = data.auto();
		addRenderableWidget(Button.builder(Component.literal("Auto-Modus: ").append(Component.literal(auto ? "an" : "aus")
						.withStyle(auto ? ChatFormatting.GREEN : ChatFormatting.WHITE)),
						b -> send(Areas.ACTION_AUTO, 0, !auto))
				.bounds(l + 8, t + 18, WIDTH - 16, 16)
				.tooltip(Tooltip.create(Component.literal("Ist der Auto-Modus an und hast du die Fernbedienung im Inventar, "
						+ "pausieren die Areas, in denen du gerade stehst. Ihre Roboter warten dann an der Station, bis du wieder draußen bist.")))
				.build());

		List<Areas.Info> areas = data.areas();
		scroll = Math.clamp(scroll, 0, Math.max(0, areas.size() - ROWS));

		for (int i = 0; i < ROWS && scroll + i < areas.size(); i++) {
			Areas.Info area = areas.get(scroll + i);
			int y = t + TOP + i * ROW;
			addRenderableWidget(Button.builder(Component.literal(area.enabled() ? "An" : "Aus").withStyle(area.enabled() ? ChatFormatting.GREEN : ChatFormatting.RED),
							b -> send(Areas.ACTION_TOGGLE, area.station(), !area.enabled()))
					.bounds(l + WIDTH - 46, y + 1, 38, 18)
					.tooltip(Tooltip.create(Component.literal(area.enabled() ? "Klick: Area ausschalten, die Roboter warten an der Station"
							: "Klick: Area einschalten, die Roboter arbeiten wieder")))
					.build());
		}
	}

	private void send(int action, long station, boolean value) {
		ClientPlayNetworking.send(new Areas.ActionPayload(action, data.hand(), station, value));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		int l = left();
		int t = top();
		StationScreen.panel(graphics, l, t, WIDTH, height());
		graphics.fill(l + 7, t + TOP - 2, l + WIDTH - 7, t + TOP + ROWS * ROW + 1, 0xFF8B8B8B);
		graphics.fill(l + 8, t + TOP - 1, l + WIDTH - 8, t + TOP + ROWS * ROW, 0xFF2B2B2B);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int l = left();
		int t = top();
		graphics.text(font, title, l + 8, t + 6, TEXT, false);
		List<Areas.Info> areas = data.areas();

		if (areas.isEmpty()) {
			graphics.textWithWordWrap(font, Component.literal("Keine Antennen-Area in der Nähe. Setz eine gepaarte Antenne auf eine Roboter-Station "
					+ "und eine zweite als Gegenecke.").withStyle(ChatFormatting.GRAY), l + 12, t + TOP + 4, WIDTH - 24, 0xFFAAAAAA, false);
			return;
		}

		if (areas.size() > ROWS) {
			graphics.text(font, (scroll + 1) + "–" + Math.min(areas.size(), scroll + ROWS) + " von " + areas.size() + " ↕",
					l + WIDTH - 8 - font.width((scroll + 1) + "–" + Math.min(areas.size(), scroll + ROWS) + " von " + areas.size() + " ↕"), t + 6, TEXT, false);
		}

		for (int i = 0; i < ROWS && scroll + i < areas.size(); i++) {
			Areas.Info area = areas.get(scroll + i);
			int y = t + TOP + i * ROW;
			graphics.fill(l + 12, y + 5, l + 22, y + 15, 0xFF000000);
			graphics.fill(l + 13, y + 6, l + 21, y + 14, 0xFF000000 | area.color());
			String name = font.plainSubstrByWidth(area.name(), WIDTH - 82);
			graphics.text(font, name.equals(area.name()) ? name : font.plainSubstrByWidth(area.name(), WIDTH - 88) + "…", l + 26, y + 2, 0xFFFFFFFF, true);
			Component state = !area.enabled() ? Component.literal("aus · Roboter warten").withStyle(ChatFormatting.RED)
					: area.paused() ? Component.literal("Pause (Auto-Modus)").withStyle(ChatFormatting.GOLD)
					: Component.literal("arbeitet").withStyle(ChatFormatting.GREEN);
			graphics.text(font, Component.literal(area.size() + " · ").withStyle(ChatFormatting.GRAY).append(state), l + 26, y + 12, 0xFFCCCCCC, false);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int old = scroll;
		scroll = Math.clamp(scroll - (int) Math.signum(scrollY), 0, Math.max(0, data.areas().size() - ROWS));

		if (old != scroll) {
			init(width, height);
		}

		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
