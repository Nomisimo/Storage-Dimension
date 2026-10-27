package de.nomisimo.storagedim.tb.mixin;

import com.tiviacz.travelersbackpack.client.screens.BackpackScreen;

import de.nomisimo.storagedim.tb.StorageTbClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The backpack draws its title row buttons and slots after the window background, so the ME view draws its bars right
 * before the tooltips are queued: late enough to cover the backpack's buttons, early enough that tooltips come on top.
 */
@Mixin(value = BackpackScreen.class, remap = false)
public class BackpackScreenMixin {
	@Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void storagedimTb$drawMeView(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		// the covered buttons of the backpack keep their tooltips to themselves
		if (StorageTbClient.drawOverBackpack((BackpackScreen) (Object) this, graphics, mouseX, mouseY)) {
			ci.cancel();
		}
	}
}
