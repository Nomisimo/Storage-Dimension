package de.nomisimo.storagedim.robot;

import com.mojang.serialization.MapCodec;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.Rooms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A thin antenna, placed like an end rod (pointing away from the block it was put on). One on top of a robot
 * station and a second one somewhere else span the box the station's robots work in. It has to be paired with a
 * station first, by laying it into the station's input slot.
 */
public class AntennaBlock extends Block {
	public static final MapCodec<AntennaBlock> CODEC = simpleCodec(AntennaBlock::new);
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final ResourceKey<Block> KEY = ResourceKey.create(Registries.BLOCK, StorageDimensionMod.id("antenna"));
	public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, StorageDimensionMod.id("antenna"));

	// before the block: registering it already asks for its shapes
	private static final VoxelShape VERTICAL = Block.box(6, 0, 6, 10, 16, 10);
	private static final VoxelShape NORTH_SOUTH = Block.box(6, 6, 0, 10, 10, 16);
	private static final VoxelShape EAST_WEST = Block.box(0, 6, 6, 16, 10, 10);

	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, KEY, new AntennaBlock(BlockBehaviour.Properties.of()
			.setId(KEY)
			.mapColor(MapColor.COLOR_ORANGE)
			.strength(0.5F)
			.sound(SoundType.COPPER)
			.lightLevel(state -> 7)
			.noOcclusion()
			.pushReaction(PushReaction.DESTROY)));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new AntennaItem(BLOCK, new Item.Properties().setId(ITEM_KEY)));

	public AntennaBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
	}

	/** Loads the class, which registers the block and its item. */
	public static void register() {
	}

	@Override
	protected MapCodec<? extends AntennaBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getClickedFace());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (state.getValue(FACING).getAxis()) {
			case X -> EAST_WEST;
			case Z -> NORTH_SOUTH;
			case Y -> VERTICAL;
		};
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (level instanceof ServerLevel server) {
			Areas.antennaRemoved(Rooms.id(server), pos);
		}

		return super.playerWillDestroy(level, pos, state, player);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		Areas.antennaRemoved(Rooms.id(level), pos);
	}

	/** Now and then a small spark at the tip. */
	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (random.nextInt(4) != 0) {
			return;
		}

		Direction facing = state.getValue(FACING);
		double x = pos.getX() + 0.5 + facing.getStepX() * 0.45;
		double y = pos.getY() + 0.5 + facing.getStepY() * 0.45;
		double z = pos.getZ() + 0.5 + facing.getStepZ() * 0.45;
		level.addParticle(new DustParticleOptions(0xE04A2A, 0.6F), x, y, z, 0, 0, 0);
	}
}
