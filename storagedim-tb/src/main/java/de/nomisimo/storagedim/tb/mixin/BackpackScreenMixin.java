package de.nomisimo.storagedim.tb.mixin;

import com.tiviacz.travelersbackpack.client.screens.BackpackScreen;

import de.nomisimo.storagedim.tb.StorageTbClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The backpack draws its title row buttons after the window background, so the ME view covers them right before the
 * tooltips are queued: late enough to hide the buttons, early enough that tooltips still come on top.
 */
@Mixin(value = BackpackScreen.class, remap = false)
public class BackpackScreenMixin {
	@Inject(method = "extractTooltip", at = @At("HEAD"), remap = false, require = 0)
	private void storagedimTb$coverCorner(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		StorageTbClient.coverCorner((BackpackScreen) (Object) this, graphics);
	}
}
