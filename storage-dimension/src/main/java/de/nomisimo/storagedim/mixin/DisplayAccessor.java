package de.nomisimo.storagedim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.math.Transformation;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;

/** The display entity setters are private; the chest island sets them when it spawns its labels and sky. */
@Mixin(Display.class)
public interface DisplayAccessor {
	@Invoker("setTransformation")
	void storagedim$setTransformation(Transformation transformation);

	@Invoker("setBillboardConstraints")
	void storagedim$setBillboardConstraints(Display.BillboardConstraints constraints);

	@Invoker("setBrightnessOverride")
	void storagedim$setBrightnessOverride(Brightness brightness);

	@Invoker("setViewRange")
	void storagedim$setViewRange(float range);
}
