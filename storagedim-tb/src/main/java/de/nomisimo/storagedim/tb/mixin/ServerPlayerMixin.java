package de.nomisimo.storagedim.tb.mixin;

import java.util.OptionalInt;

import de.nomisimo.storagedim.tb.MeViewHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A backpack window that should open in the ME view switches right when it opens, not on a later tick. */
@Mixin(value = ServerPlayer.class, remap = false)
public class ServerPlayerMixin {
	@Inject(method = "openMenu", at = @At("RETURN"), remap = false)
	private void storagedimTb$opened(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
		if (cir.getReturnValue().isPresent()) {
			MeViewHooks.menuOpened((ServerPlayer) (Object) this);
		}
	}
}
