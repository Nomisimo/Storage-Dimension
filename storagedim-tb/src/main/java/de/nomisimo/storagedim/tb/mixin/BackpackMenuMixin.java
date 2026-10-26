package de.nomisimo.storagedim.tb.mixin;

import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;

import de.nomisimo.storagedim.tb.MeViewHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the ME view in the backpack window handle clicks on its slots, and keeps its slots in place when
 * Traveler's Backpack rebuilds or moves the backpack slots (e.g. after an upgrade was added).
 */
@Mixin(value = BackpackBaseMenu.class, remap = false)
public class BackpackMenuMixin {
	@Inject(method = "doClick", at = @At("HEAD"), cancellable = true, remap = false)
	private void storagedimTb$meClick(int slotIndex, int button, ContainerInput input, Player player, CallbackInfo ci) {
		if (MeViewHooks.click((BackpackBaseMenu) (Object) this, slotIndex, button, input, player)) {
			ci.cancel();
		}
	}

	@Inject(method = {"rebuildSlots", "updateSlotsPosition"}, at = @At("HEAD"), remap = false)
	private void storagedimTb$restoreSlots(CallbackInfo ci) {
		MeViewHooks.beforeSlotChange((BackpackBaseMenu) (Object) this);
	}

	@Inject(method = {"rebuildSlots", "updateSlotsPosition"}, at = @At("RETURN"), remap = false)
	private void storagedimTb$reapplySlots(CallbackInfo ci) {
		MeViewHooks.afterSlotChange((BackpackBaseMenu) (Object) this);
	}
}
