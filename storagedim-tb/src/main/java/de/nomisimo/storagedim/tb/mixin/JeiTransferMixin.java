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
 * JEI only looks at the inventory and backpack slots. When the backpack has the ME upgrade, {@link JeiTransfer} decides
 * instead, counting ME storage too, and the server fills the grid.
 */
@Pseudo
@Mixin(targets = "mezz.jei.library.transfer.BasicRecipeTransferHandler", remap = false)
public class JeiTransferMixin {
	/** Newer JEI builds route every transfer through this private method. */
	@Inject(method = "transferRecipeInternal", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private void storagedimTb$useMeStorage(AbstractContainerMenu container, Object recipe, IRecipeSlotsView recipeSlotsView, Player player,
			boolean maxTransfer, boolean doTransfer, IRecipeTransferContext<?, ?> context, CallbackInfoReturnable<IRecipeTransferError> cir) {
		apply(container, recipeSlotsView, player, maxTransfer, doTransfer, cir);
	}

	/**
	 * Older JEI builds only have this method. On newer ones it delegates to the method above; deciding twice gives the
	 * same answer, and only this outer call may send the transfer, so the inner one never does.
	 */
	@Inject(method = "transferRecipe(Lnet/minecraft/world/inventory/AbstractContainerMenu;Ljava/lang/Object;Lmezz/jei/api/gui/ingredient/IRecipeSlotsView;Lnet/minecraft/world/entity/player/Player;ZZ)Lmezz/jei/api/recipe/transfer/IRecipeTransferError;",
			at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private void storagedimTb$useMeStorageLegacy(AbstractContainerMenu container, Object recipe, IRecipeSlotsView recipeSlotsView, Player player,
			boolean maxTransfer, boolean doTransfer, CallbackInfoReturnable<IRecipeTransferError> cir) {
		apply(container, recipeSlotsView, player, maxTransfer, doTransfer, cir);
	}

	private static void apply(AbstractContainerMenu container, IRecipeSlotsView recipeSlotsView, Player player, boolean maxTransfer, boolean doTransfer,
			CallbackInfoReturnable<IRecipeTransferError> cir) {
		Object result = JeiTransfer.handle(container, recipeSlotsView, player, maxTransfer, doTransfer, cir.getReturnValue());

		if (result != JeiTransfer.JEI_DECIDES) {
			cir.setReturnValue((IRecipeTransferError) result);
		}
	}
}
