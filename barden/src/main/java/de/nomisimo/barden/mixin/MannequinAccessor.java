package de.nomisimo.barden.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;

@Mixin(Mannequin.class)
public interface MannequinAccessor {
	@Invoker("setProfile")
	void barden$setProfile(ResolvableProfile profile);

	@Invoker("setDescription")
	void barden$setDescription(Component description);

	@Invoker("setHideDescription")
	void barden$setHideDescription(boolean hide);
}
