package de.nomisimo.storagedim;

import net.fabricmc.fabric.api.recipe.v1.sync.RecipeSynchronization;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Since 1.21.2 the server no longer sends recipes to clients, so recipe viewers like JEI see none on a dedicated server.
 * With Fabric API's recipe sync the server sends every vanilla-type recipe (crafting, smelting, ...) to clients that
 * have this mod, our own recipes included. JEI picks them up on the client, without JEI on the server.
 * Has to run on both sides: the client tells the server which recipe types it wants.
 */
public final class RecipeSync {
	private RecipeSync() {
	}

	public static void register() {
		int count = 0;

		// our recipes only use vanilla types, so the vanilla serializers are enough (JEI does the same when it is on the server)
		for (var entry : BuiltInRegistries.RECIPE_SERIALIZER.entrySet()) {
			if (!entry.getKey().identifier().getNamespace().equals("minecraft")) {
				continue;
			}

			try {
				RecipeSynchronization.synchronizeRecipeSerializer(entry.getValue());
				count++;
			} catch (RuntimeException e) {
				StorageDimensionMod.LOGGER.debug("Rezepttyp {} wird nicht synchronisiert", entry.getKey(), e);
			}
		}

		StorageDimensionMod.LOGGER.info("Rezepte für JEI: {} Rezepttypen werden an die Clients gesendet", count);
	}
}
