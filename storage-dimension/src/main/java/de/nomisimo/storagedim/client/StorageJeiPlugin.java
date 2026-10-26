package de.nomisimo.storagedim.client;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import de.nomisimo.storagedim.StorageDimensionMod;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Since 1.21.2 servers no longer send recipes to clients, so JEI cannot see ours on a server.
 * This plugin reads them from our own jar and hands them to JEI.
 * It also gives the ME terminal access to JEI's search bar, for the search sync.
 */
public class StorageJeiPlugin implements IModPlugin {
	private static IJeiRuntime runtime;

	@Override
	public Identifier getPluginUid() {
		return Identifier.fromNamespaceAndPath("storagedim", "jei");
	}

	@Override
	public void registerRecipes(IRecipeRegistration registration) {
		Minecraft minecraft = Minecraft.getInstance();

		// In singleplayer JEI already gets every recipe from the built-in server.
		if (minecraft.hasSingleplayerServer() || minecraft.getConnection() == null) {
			return;
		}

		RegistryOps<JsonElement> ops = minecraft.getConnection().registryAccess().createSerializationContext(JsonOps.INSTANCE);
		List<RecipeHolder<CraftingRecipe>> recipes = new ArrayList<>();
		// our own recipes and, if installed, those of the Traveler's Backpack add-on
		for (String mod : List.of("storagedim", "storagedim_tb")) {
			Path dir = FabricLoader.getInstance().getModContainer(mod).flatMap(container -> container.findPath("data/" + mod + "/recipe")).orElse(null);

			if (dir == null) {
				continue;
			}

			try (Stream<Path> files = Files.list(dir)) {
				for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
					String name = file.getFileName().toString().replace(".json", "");

					try (Reader reader = Files.newBufferedReader(file)) {
						JsonElement json = JsonParser.parseReader(reader);

						// the only condition we use: "not when the add-on is installed"
						if (json.isJsonObject() && json.getAsJsonObject().has("fabric:load_conditions")
								&& FabricLoader.getInstance().isModLoaded("storagedim_tb")) {
							continue;
						}

						Recipe.CODEC.parse(ops, json).resultOrPartial(StorageDimensionMod.LOGGER::warn).ifPresent(recipe -> {
							if (recipe instanceof CraftingRecipe crafting) {
								recipes.add(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE,
										Identifier.fromNamespaceAndPath(mod, name)), crafting));
							}
						});
					}
				}
			} catch (Exception e) {
				StorageDimensionMod.LOGGER.warn("Rezepte für JEI nicht lesbar", e);
			}
		}

		registration.addRecipes(RecipeTypes.CRAFTING, recipes);
	}

	@Override
	public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
		runtime = jeiRuntime;
	}

	@Override
	public void onRuntimeUnavailable() {
		runtime = null;
	}

	/** The text in JEI's search bar, or null while JEI is not ready. */
	static String searchText() {
		return runtime == null ? null : runtime.getIngredientFilter().getFilterText();
	}

	static void setSearchText(String text) {
		if (runtime != null && !text.equals(runtime.getIngredientFilter().getFilterText())) {
			runtime.getIngredientFilter().setFilterText(text);
		}
	}
}
