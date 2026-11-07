package de.nomisimo.storagedim;

import de.nomisimo.storagedim.robot.AntennaBlock;
import de.nomisimo.storagedim.robot.RemoteItem;
import de.nomisimo.storagedim.robot.RobotBarrierBlock;
import de.nomisimo.storagedim.robot.RobotStationBlock;
import de.nomisimo.storagedim.robot.Robots;
import de.nomisimo.storagedim.storage.StorageItems;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;

/**
 * Our own tab in the creative inventory. JEI builds its item list from the creative tabs, so without it
 * none of our items show up in JEI either, and their recipes cannot be looked up there.
 * The Traveler's Backpack add-on puts its ME upgrade in here too.
 */
public final class StorageTab {
	public static final ResourceKey<CreativeModeTab> KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB, StorageDimensionMod.id("items"));

	private StorageTab() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, KEY, FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.storagedim.items"))
				.icon(StorageItems::storageChest)
				.displayItems((parameters, output) -> {
					output.accept(StorageItems.storageChest());
					output.accept(StorageItems.terminal());
					output.accept(StorageItems.filterChest());
					output.accept(StorageItems.projectChest(null));

					// with the add-on, its own ME upgrade replaces the paper one (same as the recipes)
					if (!FabricLoader.getInstance().isModLoaded("storagedim_tb")) {
						output.accept(StorageItems.upgrade());
					}

					output.accept(StorageItems.backpack());
					output.accept(Robots.ROBOT_ITEM);
					output.accept(RobotStationBlock.ITEM);
					output.accept(RobotBarrierBlock.ITEM);
					output.accept(AntennaBlock.ITEM);
					output.accept(RemoteItem.ITEM);
				})
				.build());
	}
}
