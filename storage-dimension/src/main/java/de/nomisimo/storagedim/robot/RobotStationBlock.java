package de.nomisimo.storagedim.robot;

import com.mojang.serialization.MapCodec;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Home of the collector robots. Right-click opens its settings (range, filter, colour, name); robots laid in its
 * input slot come out configured for this station. Their range counts from here, and here they wait when idle.
 * Stations set side by side form a group ({@link StationCluster}); {@link #PART} shows which one is the main station.
 */
public class RobotStationBlock extends Block {
	/** A station on its own, the main station of a group, or one of its extensions; each has its own look. */
	public enum Part implements StringRepresentable {
		SINGLE("single"),
		MAIN("main"),
		MEMBER("member");

		private final String name;

		Part(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
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
		registerDefaultState(stateDefinition.any().setValue(PART, Part.SINGLE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(PART);
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
			// every station of a group opens the screen of its main station
			StationMenu.open(serverPlayer, StationCluster.main(level, pos), pos);
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);

		if (level instanceof ServerLevel server) {
			// the station set last never takes the lead from stations that stood before
			StationCluster.setPart(server, pos, Part.MEMBER);
			BlockPos main = StationCluster.update(server, pos);
			int size = StationCluster.size(server, pos);

			if (size > 1 && placer instanceof ServerPlayer player) {
				player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Stations-Verbund aus " + StationCluster.members(server, pos).size()
						+ " Stationen: bis " + StationCluster.maxRobots(size) + " Roboter, Reichweite bis " + StationCluster.maxRange(size) / 16 + " Chunks."
						+ (main.equals(pos) ? "" : " Die Einstellungen liegen in der Hauptstation."))
						.withStyle(net.minecraft.ChatFormatting.GREEN), true);
			}
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		StationCluster.removed(level, pos, state.getValue(PART));
	}
}
