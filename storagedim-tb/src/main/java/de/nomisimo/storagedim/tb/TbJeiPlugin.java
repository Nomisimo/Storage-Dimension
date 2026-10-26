package de.nomisimo.storagedim.tb;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;

/**
 * Keeps JEI's transfer helper, to mark the missing ingredients when ME storage cannot fill a recipe either,
 * and JEI's search bar for the search sync of the ME view.
 */
public class TbJeiPlugin implements IModPlugin {
	static IRecipeTransferHandlerHelper transferHelper;
	private static IJeiRuntime runtime;

	@Override
	public Identifier getPluginUid() {
		return StorageTbMod.id("jei");
	}

	@Override
	public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
		transferHelper = registration.getTransferHelper();
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
