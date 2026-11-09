package de.nomisimo.storagedim;

import de.nomisimo.storagedim.robot.AntennaBlock;
import de.nomisimo.storagedim.robot.RemoteItem;
import de.nomisimo.storagedim.robot.RobotBarrierBlock;
import de.nomisimo.storagedim.robot.RobotStationBlock;
import de.nomisimo.storagedim.robot.Robots;
import de.nomisimo.storagedim.storage.StorageItems;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

/**
 * Our own tab in the creative inventory, with every item of the mod. They are all real items now (see
 * {@link StorageItems}), so this is a plain item list; JEI takes its item list from here and from the item registry.
 * The Traveler's Backpack add-on puts its ME upgrade in here too.
 */
public final class StorageTab {
	public static final ResourceKey<CreativeModeTab> KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB, StorageDimensionMod.id("items"));

	private StorageTab() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, KEY, FabricCreativeModeTab.builder()
				.title(Component.translatable("itemGroup.storagedim.items"))
				.icon(() -> new ItemStack(StorageItems.DIMENSION_CHEST))
				.displayItems((parameters, output) -> {
					output.accept(StorageItems.DIMENSION_CHEST);
					output.accept(StorageItems.ME_TERMINAL);
					output.accept(StorageItems.FILTER_CHEST);
					output.accept(StorageItems.PROJECT_CHEST);

					// with the add-on, its own ME upgrade replaces ours (same as the recipes)
					if (!FabricLoader.getInstance().isModLoaded("storagedim_tb")) {
						output.accept(StorageItems.ME_UPGRADE);
					}

					output.accept(StorageItems.ME_BACKPACK);
					output.accept(Robots.ROBOT_ITEM);
					output.accept(RobotStationBlock.ITEM);
					output.accept(RobotBarrierBlock.ITEM);
					output.accept(AntennaBlock.ITEM);
					output.accept(RemoteItem.ITEM);
				})
				.build());

		// like any other mod: the chests and the terminal also among the vanilla functional blocks
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> {
			output.accept(StorageItems.DIMENSION_CHEST);
			output.accept(StorageItems.ME_TERMINAL);
			output.accept(StorageItems.FILTER_CHEST);
			output.accept(StorageItems.PROJECT_CHEST);
		});
	}
}
