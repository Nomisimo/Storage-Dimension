package de.nomisimo.storagedim.tb.mixin;

import java.util.List;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the ME view rename the backpack window while it shows ME storage, and hide the backpack's sorting buttons. */
@Mixin(Screen.class)
public interface ScreenTitleAccessor {
	@Mutable
	@Accessor("title")
	void storagedimTb$setTitle(Component title);

	@Accessor("renderables")
	List<Renderable> storagedimTb$getRenderables();
}
