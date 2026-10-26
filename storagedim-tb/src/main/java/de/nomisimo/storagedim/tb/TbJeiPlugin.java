package de.nomisimo.storagedim.tb;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.Identifier;

/** Only keeps JEI's transfer helper, to mark the missing ingredients when ME storage cannot fill a recipe either. */
public class TbJeiPlugin implements IModPlugin {
	static IRecipeTransferHandlerHelper transferHelper;

	@Override
	public Identifier getPluginUid() {
		return StorageTbMod.id("jei");
	}

	@Override
	public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
		transferHelper = registration.getTransferHelper();
	}
}
