package de.nomisimo.storagedim.robot;

import com.mojang.serialization.MapCodec;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A see-through plasma shield that only stops collector robots, e.g. in a doorway.
 * Players, mobs and items pass through it; robots bump into it and never plan a way through.
 */
public class RobotBarrierBlock extends Block {
	public static final MapCodec<RobotBarrierBlock> CODEC = simpleCodec(RobotBarrierBlock::new);
	public static final ResourceKey<Block> KEY = ResourceKey.create(Registries.BLOCK, StorageDimensionMod.id("robot_barrier"));
	public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, StorageDimensionMod.id("robot_barrier"));

	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, KEY, new RobotBarrierBlock(BlockBehaviour.Properties.of()
			.setId(KEY)
			.mapColor(MapColor.COLOR_CYAN)
			.strength(0.3F)
			.sound(SoundType.AMETHYST)
			.lightLevel(state -> 4)
			.noOcclusion()
			.dynamicShape()
			.pushReaction(PushReaction.DESTROY)
			.isRedstoneConductor((state, level, pos) -> false)
			.isSuffocating((state, level, pos) -> false)
			.isViewBlocking((state, level, pos) -> false)));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new BlockItem(BLOCK, new Item.Properties().setId(ITEM_KEY)));

	/** Taller than a block, like a fence, so a robot cannot step over a single row. */
	private static final VoxelShape ROBOT_SHAPE = Shapes.box(0, 0, 0, 1, 1.5, 1);

	public RobotBarrierBlock(Properties properties) {
		super(properties);
	}

	/** Loads the class, which registers the block and its item. */
	public static void register() {
	}

	@Override
	protected MapCodec<? extends RobotBarrierBlock> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return context instanceof EntityCollisionContext entityContext && entityContext.getEntity() instanceof RobotEntity
				? ROBOT_SHAPE : Shapes.empty();
	}

	@Override
	protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return true;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0F;
	}

	/** A wall of barriers looks like one shield, without inner faces. */
	@Override
	protected boolean skipRendering(BlockState state, BlockState neighbor, Direction direction) {
		return neighbor.is(this) || super.skipRendering(state, neighbor, direction);
	}
}
