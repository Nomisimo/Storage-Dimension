package de.nomisimo.storagedim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import de.nomisimo.storagedim.client.TerminalCounts;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/** ME terminal icons: the exact stored amount in small digits instead of the slot's 99. */
@Mixin(AbstractContainerScreen.class)
public class ContainerScreenMixin {
	@WrapOperation(require = 0, method = "extractSlot",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"))
	private void storagedim$smallCount(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y, String text, Operation<Void> original) {
		long count = TerminalCounts.count(stack);

		if (count < 0) {
			original.call(graphics, font, stack, x, y, text);
			return;
		}

		original.call(graphics, font, stack, x, y, "");
		TerminalCounts.draw(graphics, font, count, x, y);
	}
}
