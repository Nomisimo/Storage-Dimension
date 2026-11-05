package de.nomisimo.storagedim.client;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.recipe.v1.sync.ClientRecipeSynchronizedEvent;
import net.minecraft.world.item.crafting.RecipeType;

/** Remembers whether the server sent our recipes (see {@link de.nomisimo.storagedim.RecipeSync}). */
final class SyncedRecipes {
	private static volatile int own = -1;

	private SyncedRecipes() {
	}

	static void register() {
		ClientRecipeSynchronizedEvent.EVENT.register((client, recipes) -> {
			own = (int) recipes.getAllOfType(RecipeType.CRAFTING).stream()
					.filter(holder -> holder.id().identifier().getNamespace().startsWith(StorageDimensionMod.MOD_ID)).count();
			StorageDimensionMod.LOGGER.info("Rezepte vom Server erhalten, davon {} von Storage Dimension", own);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> own = -1);
	}

	/** Number of our crafting recipes the server sent on this connection, or -1 if it sent none. */
	static int own() {
		return own;
	}
}
