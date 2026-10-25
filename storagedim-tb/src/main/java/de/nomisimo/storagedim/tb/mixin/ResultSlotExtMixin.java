package de.nomisimo.storagedim.tb.mixin;

import com.tiviacz.travelersbackpack.inventory.menu.slot.ResultSlotExt;

import de.nomisimo.storagedim.tb.MeCrafting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers the backpack crafting grid before a craft so {@link MeCrafting} can top it up from ME storage afterwards. */
@Mixin(value = ResultSlotExt.class, remap = false)
public class ResultSlotExtMixin {
	@Unique
	private MeCrafting.Snapshot storagedimTb$before;

	@Inject(method = "onTake", at = @At("HEAD"))
	private void storagedimTb$remember(Player player, ItemStack stack, CallbackInfo ci) {
		storagedimTb$before = MeCrafting.snapshot(player);
	}

	@Inject(method = "onTake", at = @At("RETURN"))
	private void storagedimTb$refill(Player player, ItemStack stack, CallbackInfo ci) {
		MeCrafting.refill(player, storagedimTb$before);
		storagedimTb$before = null;
	}
}
