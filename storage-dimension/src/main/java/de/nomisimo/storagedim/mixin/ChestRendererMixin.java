package de.nomisimo.storagedim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import de.nomisimo.storagedim.client.ClientChestMarks;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Draws dimension chests, filter chests and ME terminals with their own textures. */
@Mixin(ChestRenderer.class)
public class ChestRendererMixin {
	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/ChestRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Sheets;chooseSprite(Lnet/minecraft/client/renderer/blockentity/state/ChestRenderState$ChestMaterialType;Lnet/minecraft/world/level/block/state/properties/ChestType;)Lnet/minecraft/client/resources/model/sprite/SpriteId;"))
	private SpriteId storagedim$chooseSprite(ChestRenderState.ChestMaterialType material, ChestType type, Operation<SpriteId> original,
			@Local(argsOnly = true) ChestRenderState state) {
		SpriteId own = ClientChestMarks.spriteFor(state);
		return own != null ? own : original.call(material, type);
	}
}
