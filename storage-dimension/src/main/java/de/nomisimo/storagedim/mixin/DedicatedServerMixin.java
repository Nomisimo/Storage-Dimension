package de.nomisimo.storagedim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import de.nomisimo.storagedim.ServerSettings;
import net.minecraft.server.dedicated.DedicatedServer;

@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
	/**
	 * A dedicated server pauses after {@code pause-when-empty-seconds} without players, and with it the robots of
	 * stations that keep their area loaded. While such a station is there (and the setting is on), it does not pause.
	 */
	@Inject(method = "pauseWhenEmptySeconds", at = @At("HEAD"), cancellable = true)
	private void storagedim$keepAwake(CallbackInfoReturnable<Integer> cir) {
		if (ServerSettings.keepsAwake()) {
			cir.setReturnValue(0);
		}
	}
}
