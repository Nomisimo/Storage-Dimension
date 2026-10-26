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
 */
public class StorageJeiPlugin implements IModPlugin {
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
		Path dir = FabricLoader.getInstance().getModContainer("storagedim").flatMap(mod -> mod.findPath("data/storagedim/recipe")).orElse(null);

		if (dir == null) {
			return;
		}

		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
				String name = file.getFileName().toString().replace(".json", "");

				try (Reader reader = Files.newBufferedReader(file)) {
					Recipe.CODEC.parse(ops, JsonParser.parseReader(reader)).resultOrPartial(StorageDimensionMod.LOGGER::warn).ifPresent(recipe -> {
						if (recipe instanceof CraftingRecipe crafting) {
							recipes.add(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE,
									Identifier.fromNamespaceAndPath("storagedim", name)), crafting));
						}
					});
				}
			}
		} catch (Exception e) {
			StorageDimensionMod.LOGGER.warn("Rezepte für JEI nicht lesbar", e);
		}

		registration.addRecipes(RecipeTypes.CRAFTING, recipes);
	}
}
