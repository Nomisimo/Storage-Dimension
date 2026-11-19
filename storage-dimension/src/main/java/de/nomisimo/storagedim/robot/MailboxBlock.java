package de.nomisimo.storagedim.robot;

import com.mojang.serialization.MapCodec;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Mailbox of the parcel post ({@link Mailbox}): stands beside a chest. Right-click sets its own address and the
 * address it sends to; the courier robots fetch what lies in the chest and bring it to the mailbox with that address.
 * Its flag ({@link #MAIL}) is up while post waits to be fetched.
 */
public class MailboxBlock extends Block {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	/** Post waits in the chest beside it to be fetched. */
	public static final BooleanProperty MAIL = BooleanProperty.create("mail");
	public static final MapCodec<MailboxBlock> CODEC = simpleCodec(MailboxBlock::new);
	public static final ResourceKey<Block> KEY = ResourceKey.create(Registries.BLOCK, StorageDimensionMod.id("mailbox"));
	public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, StorageDimensionMod.id("mailbox"));

	public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, KEY, new MailboxBlock(BlockBehaviour.Properties.of()
			.setId(KEY)
			.mapColor(MapColor.WOOD)
			.strength(1.5F, 3F)
			.sound(SoundType.WOOD)));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ITEM_KEY,
			new BlockItem(BLOCK, new Item.Properties().setId(ITEM_KEY)));

	public MailboxBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MAIL, false));
	}

	/** Loads the class, which registers the block and its item. */
	public static void register() {
		Mailbox.register();
		MailboxNetworking.register();
	}

	@Override
	protected MapCodec<? extends MailboxBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, MAIL);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// the slot faces the player who sets it
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
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
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer) {
			MailboxNetworking.open(serverPlayer, pos);
		}

		return InteractionResult.SUCCESS;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		StorageData data = StorageData.get();

		if (!(level instanceof ServerLevel server) || data == null) {
			return;
		}

		String dim = Rooms.id(server);
		// a mailbox renamed in an anvil takes that name as its address, else it is numbered
		Component custom = stack.get(DataComponents.CUSTOM_NAME);
		String address = custom != null ? custom.getString().strip() : "Briefkasten " + (data.mailboxes(dim).size() + 1);

		if (address.length() > Mailbox.MAX_ADDRESS) {
			address = address.substring(0, Mailbox.MAX_ADDRESS);
		}

		data.setMailbox(dim, pos, new Mailbox(address, ""));

		if (placer instanceof ServerPlayer player) {
			player.sendSystemMessage(Component.literal("Briefkasten „" + address + "“: Stell eine Kiste daneben. Rechtsklick stellt Adresse und Ziel ein.")
					.withStyle(net.minecraft.ChatFormatting.GREEN), true);
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		Mailbox.removed(Rooms.id(level), pos);
	}
}
