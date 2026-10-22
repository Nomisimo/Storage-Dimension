package de.nomisimo.storagedim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;

@Mixin(Mannequin.class)
public interface MannequinAccessor {
	@Invoker("setProfile")
	void storagedim$setProfile(ResolvableProfile profile);

	@Invoker("setDescription")
	void storagedim$setDescription(Component description);

	@Invoker("setHideDescription")
	void storagedim$setHideDescription(boolean hide);
}
