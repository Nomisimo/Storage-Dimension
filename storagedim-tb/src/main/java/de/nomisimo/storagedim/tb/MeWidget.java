package de.nomisimo.storagedim.tb;

import com.tiviacz.travelersbackpack.client.screens.BackpackScreen;
import com.tiviacz.travelersbackpack.client.screens.widgets.UpgradeWidgetBase;
import com.tiviacz.travelersbackpack.inventory.upgrades.Point;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Left-click on the icon shows the ME storage in place of the backpack slots (and the backpack again), right-click
 * removes the upgrade as usual.
 * The small switch next to it toggles between "ME als Quelle" and "nur Inventar" for crafting.
 */
@Environment(EnvType.CLIENT)
public class MeWidget extends UpgradeWidgetBase<MeUpgrade> {
	public MeWidget(BackpackScreen screen, MeUpgrade upgrade, Point pos) {
		super(screen, upgrade, pos, new Point(137, 0), "screen.storagedim_tb.me_upgrade");
	}

	@Override
	public void getAdditionalTooltips(Consumer<Component> consumer) {
		consumer.accept(Component.translatable(MeView.isOpen(screen.getMenu()) ? "screen.storagedim_tb.me_upgrade_click_back" : "screen.storagedim_tb.me_upgrade_click"));
		consumer.accept(Component.translatable(upgrade.isEnabled(upgrade) ? "screen.storagedim_tb.source_me" : "screen.storagedim_tb.source_inventory")
				.withStyle(ChatFormatting.GRAY));
	}

	@Override
	public void renderEnableButtonTooltip(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
		if (isMouseOverEnableButton(mouseX, mouseY)) {
			boolean me = upgrade.isEnabled(upgrade);
			guiGraphics.setTooltipForNextFrame(screen.getFont(), List.of(
					Component.translatable(me ? "screen.storagedim_tb.source_me" : "screen.storagedim_tb.source_inventory"),
					Component.translatable(me ? "screen.storagedim_tb.switch_to_inventory" : "screen.storagedim_tb.switch_to_me").withStyle(ChatFormatting.GRAY)),
					Optional.empty(), mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (enableButtonMouseClicked(event.x(), event.y())) {
			return true;
		}

		if (event.button() == 0 && isMouseOverIcon(event)) {
			ClientPlayNetworking.send(MeNetworking.TogglePayload.INSTANCE);
			screen.playUIClickSound();
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}
}
