package de.nomisimo.storagedim.tb;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The add-on's mod settings, opened from Mod Menu: one switch for opening the backpack in the ME view. */
@Environment(EnvType.CLIENT)
public class TbConfigScreen extends Screen {
	private static final int TEXT_WIDTH = 300;
	private final Screen parent;

	public TbConfigScreen(Screen parent) {
		super(Component.literal("Storage Dimension: Traveler's Backpack"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		boolean on = TbClientConfig.autoMe();
		addRenderableWidget(Button.builder(Component.literal("ME-Ansicht automatisch öffnen: ")
						.append(Component.literal(on ? "An" : "Aus").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED)), b -> {
					TbClientConfig.setAutoMe(!on);
					rebuildWidgets();
				})
				.bounds(width / 2 - 125, height / 2 - 10, 250, 20)
				.tooltip(Tooltip.create(Component.literal("An: Hast du den Rucksack in der ME-Ansicht geschlossen, öffnet er sich auch wieder darin. "
						+ "Aus: Der Rucksack öffnet sich immer mit seinem eigenen Inhalt.")))
				.build());
		addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> onClose())
				.bounds(width / 2 - 100, height - 30, 200, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(font, title, (width - font.width(title)) / 2, 20, 0xFFFFFFFF, true);
		Component hint = Component.literal("Gilt nur für dich. Wird auf dem Server beim Einloggen und bei jeder Änderung übernommen.");
		int textWidth = Math.min(TEXT_WIDTH, width - 32);
		graphics.textWithWordWrap(font, hint, (width - textWidth) / 2, height / 2 + 20, textWidth, 0xFFAAAAAA, false);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
