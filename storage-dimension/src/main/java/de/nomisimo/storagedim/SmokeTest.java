package de.nomisimo.storagedim;

import java.util.ArrayList;
import java.util.List;

import de.nomisimo.storagedim.storage.Islands;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Started by CI with -Dstoragedim.smoketest=true: boots a dev server, checks that the
 * dimension, recipe and island building work, then shuts the server down.
 */
final class SmokeTest {
	private SmokeTest() {
	}

	static void registerIfEnabled() {
		if (!Boolean.getBoolean("storagedim.smoketest")) {
			return;
		}

		ServerLifecycleEvents.SERVER_STARTED.register(SmokeTest::run);
	}

	private static void run(MinecraftServer server) {
		List<String> errors = new ArrayList<>();

		try {
			ServerLevel level = Rooms.level(server);

			if (level == null) {
				errors.add("Dimension storagedim:storage fehlt");
			} else {
				// A network that is not saved, on an island far from the real ones.
				StorageNetwork network = new StorageNetwork(java.util.UUID.randomUUID(), 250);
				network.insert(new ItemStack(Items.COBBLESTONE, 500));
				network.insert(new ItemStack(Items.DIAMOND_SWORD));
				Islands.prepare(level, network);
				BlockPos centre = Islands.centre(network.room);

				if (!level.getBlockState(centre).is(Blocks.BARREL)) {
					errors.add("Insel wurde nicht gebaut");
				}

				if (network.island.size() != 2) {
					errors.add("Insel hat " + network.island.size() + " statt 2 Kisten");
				} else {
					Islands.IslandChest first = network.island.get(0);

					if (!level.getBlockState(centre.offset(first.dx(), 1, first.dz())).is(Blocks.CHEST)) {
						errors.add("Kiste fehlt auf der Insel");
					}
				}
			}

			for (String recipe : List.of("storage_chest", "me_terminal", "me_upgrade", "me_backpack", "filter_chest", "robot")) {
				if (server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, StorageDimensionMod.id(recipe))).isEmpty()) {
					errors.add("Rezept fehlt: " + recipe);
				}
			}
		} catch (Throwable t) {
			StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme", t);
			errors.add(t.toString());
		}

		if (errors.isEmpty()) {
			StorageDimensionMod.LOGGER.info("SMOKETEST OK");
			server.halt(false);
		} else {
			errors.forEach(e -> StorageDimensionMod.LOGGER.error("SMOKETEST FEHLER: {}", e));
			Runtime.getRuntime().halt(1);
		}
	}
}
