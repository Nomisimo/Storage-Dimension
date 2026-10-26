package de.nomisimo.storagedim.robot;

import com.mojang.serialization.MapCodec;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Home of the collector robots. Right-click opens its settings (range, filter, colour, name); robots laid in its
 * input slot come out configured for this station. Their range counts from here, and here they wait when idle.
 */
public class RobotStationBlock extends Block {
	public static final MapCodec<RobotStationBlock> CODEC = simpleCodec(RobotStationBlock::new);
	public static final ResourceKey<Block> KEY = ResourceKey.create(Registries.BLOCK, StorageDimensionMod.id("robot_station"));
	public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, StorageDimensionMod.id("robot_station"));

	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, KEY, new RobotStationBlock(BlockBehaviour.Properties.of()
			.setId(KEY)
			.mapColor(MapColor.COLOR_ORANGE)
			.strength(1.5F, 6F)
			.sound(SoundType.COPPER)
			.lightLevel(state -> 6)));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new BlockItem(BLOCK, new Item.Properties().setId(ITEM_KEY)));

	public RobotStationBlock(Properties properties) {
		super(properties);
	}

	/** Loads the class, which registers the block and its item. */
	public static void register() {
	}

	@Override
	protected MapCodec<? extends RobotStationBlock> codec() {
		return CODEC;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer) {
			StationMenu.open(serverPlayer, pos);
		}

		return InteractionResult.SUCCESS;
	}
}
