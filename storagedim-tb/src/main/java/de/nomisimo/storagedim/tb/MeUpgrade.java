package de.nomisimo.storagedim.tb;

import java.util.List;

import com.mojang.datafixers.util.Pair;
import com.tiviacz.travelersbackpack.client.screens.BackpackScreen;
import com.tiviacz.travelersbackpack.client.screens.widgets.WidgetBase;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import com.tiviacz.travelersbackpack.inventory.UpgradeManager;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.inventory.upgrades.IEnable;
import com.tiviacz.travelersbackpack.inventory.upgrades.Point;
import com.tiviacz.travelersbackpack.inventory.upgrades.UpgradeBase;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.inventory.Slot;

/**
 * Its widget opens the shared ME storage. The upgrade's on/off switch is the "ME als Quelle" setting for the
 * crafting upgrade (see {@link MeCrafting}); TB keeps it on the upgrade item.
 */
public class MeUpgrade extends UpgradeBase<MeUpgrade> implements IEnable {
	public MeUpgrade(UpgradeManager manager, int dataHolderSlot) {
		super(manager, dataHolderSlot, new Point(24, 24));
	}

	@Override
	@Environment(EnvType.CLIENT)
	public WidgetBase<?> createWidget(BackpackScreen screen, int x, int y) {
		return new MeWidget(screen, this, new Point(screen.getGuiLeft() + x, screen.getGuiTop() + y));
	}

	@Override
	public boolean hasTab() {
		return false;
	}

	@Override
	public List<Pair<Integer, Integer>> getUpgradeSlotsPosition(int x, int y) {
		return List.of();
	}

	@Override
	public List<? extends Slot> getUpgradeSlots(BackpackBaseMenu menu, BackpackWrapper wrapper, int x, int y) {
		return List.of();
	}
}
