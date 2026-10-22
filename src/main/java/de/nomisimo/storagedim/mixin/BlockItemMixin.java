package de.nomisimo.storagedim.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import de.nomisimo.storagedim.storage.StorageEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(BlockItem.class)
abstract class BlockItemMixin {
	/** Registers storage chests the moment they are placed, while the item stack is still known. */
	@WrapOperation(
			method = "place",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;setPlacedBy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V")
	)
	private void storagedim$afterPlaced(Block block, Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack, Operation<Void> original) {
		original.call(block, level, pos, state, placer, stack);
		StorageEvents.onPlaced(level, pos, state, placer, stack);
	}
}
