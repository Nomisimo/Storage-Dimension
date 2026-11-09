package de.nomisimo.storagedim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.decoration.ItemFrame;

@Mixin(ItemFrame.class)
public interface ItemFrameAccessor {
	/** A fixed frame cannot be turned, emptied or knocked off and stays even without a block behind it. */
	@Accessor("fixed")
	void storagedim$setFixed(boolean fixed);
}
