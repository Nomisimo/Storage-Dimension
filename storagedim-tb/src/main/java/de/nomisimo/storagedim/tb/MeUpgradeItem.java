package de.nomisimo.storagedim.tb;

import java.util.Optional;

import org.apache.commons.lang3.function.TriFunction;

import com.tiviacz.travelersbackpack.inventory.UpgradeManager;
import com.tiviacz.travelersbackpack.inventory.upgrades.UpgradeBase;
import com.tiviacz.travelersbackpack.item.upgrade.UpgradeItem;

import net.minecraft.world.item.ItemStack;

public class MeUpgradeItem extends UpgradeItem {
	public MeUpgradeItem(Properties properties) {
		super(properties, null);
	}

	@Override
	public boolean requiresEquippedBackpack() {
		return false;
	}

	@Override
	public boolean hasBlockFunctionality() {
		return true;
	}

	@Override
	public Class<? extends UpgradeBase<?>> getUpgradeClass() {
		return MeUpgrade.class;
	}

	@Override
	public TriFunction<UpgradeManager, Integer, ItemStack, Optional<? extends UpgradeBase<?>>> getUpgrade() {
		return (manager, slot, stack) -> Optional.of(new MeUpgrade(manager, slot));
	}
}
