package de.nomisimo.storagedim.tb;

import com.tiviacz.travelersbackpack.client.screens.BackpackScreen;
import com.tiviacz.travelersbackpack.client.screens.widgets.UpgradeWidgetBase;
import com.tiviacz.travelersbackpack.inventory.upgrades.Point;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Left-click on the icon opens the ME storage, right-click removes the upgrade as usual. */
@Environment(EnvType.CLIENT)
public class MeWidget extends UpgradeWidgetBase<MeUpgrade> {
	public MeWidget(BackpackScreen screen, MeUpgrade upgrade, Point pos) {
		super(screen, upgrade, pos, new Point(137, 0), "screen.storagedim_tb.me_upgrade");
	}

	@Override
	public void getAdditionalTooltips(Consumer<Component> consumer) {
		consumer.accept(Component.translatable("screen.storagedim_tb.me_upgrade_click"));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && isMouseOverIcon(event)) {
			ClientPlayNetworking.send(OpenStoragePayload.INSTANCE);
			screen.playUIClickSound();
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}
}
