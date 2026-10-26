package de.nomisimo.storagedim.tb.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the ME view rename the backpack window while it shows ME storage. */
@Mixin(Screen.class)
public interface ScreenTitleAccessor {
	@Mutable
	@Accessor("title")
	void storagedimTb$setTitle(Component title);
}
