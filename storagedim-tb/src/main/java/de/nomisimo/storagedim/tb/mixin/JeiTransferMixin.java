package de.nomisimo.storagedim.tb.mixin;

import de.nomisimo.storagedim.tb.JeiTransfer;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * JEI only looks at the inventory and backpack slots. When the backpack has the ME upgrade and something is
 * missing there, the transfer is handed to the server, which also takes from ME storage.
 */
@Pseudo
@Mixin(targets = "mezz.jei.library.transfer.BasicRecipeTransferHandler", remap = false)
public class JeiTransferMixin {
	@Inject(method = "transferRecipeInternal", at = @At("RETURN"), cancellable = true, remap = false)
	private void storagedimTb$useMeStorage(AbstractContainerMenu container, Object recipe, IRecipeSlotsView recipeSlotsView, Player player,
			boolean maxTransfer, boolean doTransfer, IRecipeTransferContext<?, ?> context, CallbackInfoReturnable<IRecipeTransferError> cir) {
		IRecipeTransferError error = cir.getReturnValue();

		if (error != null && error.getType() != IRecipeTransferError.Type.COSMETIC
				&& JeiTransfer.handle(container, recipeSlotsView, maxTransfer, doTransfer)) {
			cir.setReturnValue(null);
		}
	}
}
