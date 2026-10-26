package de.nomisimo.storagedim;

import de.nomisimo.storagedim.robot.Robots;
import de.nomisimo.storagedim.storage.StorageCommands;
import de.nomisimo.storagedim.storage.StorageEvents;
import de.nomisimo.storagedim.storage.TerminalNetworking;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StorageDimensionMod implements ModInitializer {
	public static final String MOD_ID = "storagedim";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** The void dimension that holds every storage room. Defined in data/storagedim/dimension/storage.json. */
	public static final ResourceKey<Level> STORAGE_LEVEL = ResourceKey.create(Registries.DIMENSION, id("storage"));

	@Override
	public void onInitialize() {
		StorageConfig.load();
		Robots.register();
		de.nomisimo.storagedim.robot.RobotBarrierBlock.register();
		de.nomisimo.storagedim.robot.RobotStationBlock.register();
		StorageEvents.register();
		StorageCommands.register();
		TerminalNetworking.register();
		SmokeTest.registerIfEnabled();
		LOGGER.info("Storage Dimension geladen");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
