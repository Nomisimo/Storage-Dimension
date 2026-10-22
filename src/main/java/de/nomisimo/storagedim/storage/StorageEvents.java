package de.nomisimo.storagedim.storage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

public final class StorageEvents {
	private static final int VIEW_TICKS = 20 * 30;

	/** Watchers that are online right now, with remaining ticks. */
	private static final Map<UUID, Integer> ACTIVE_VIEWERS = new HashMap<>();
	/** Player to the new owner they want to give the next storage chest they click. */
	static final Map<UUID, UUID> PENDING_OWNER = new HashMap<>();
	private static int tickCounter;

	private StorageEvents() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(StorageData::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (UUID id : Set.copyOf(ACTIVE_VIEWERS.keySet())) {
				ServerPlayer player = server.getPlayerList().getPlayer(id);

				if (player != null) {
					stopViewing(player);
				}
			}

			StorageData.unload();
		});
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> StorageData.save(false));
		ServerTickEvents.END_SERVER_TICK.register(StorageEvents::tick);
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}

			return onUseBlock(serverPlayer, (ServerLevel) level, hit.getBlockPos());
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> onBreak(level, player, pos, state));
		ServerPlayerEvents.JOIN.register(player -> {
			StorageData data = StorageData.get();

			if (data != null && data.viewers.containsKey(player.getUUID())) {
				// Logged out or the server stopped while watching: put them back.
				stopViewing(player);
			}
		});
		ServerPlayerEvents.LEAVE.register(player -> ACTIVE_VIEWERS.remove(player.getUUID()));
	}

	// ---- placing & breaking ----

	/** Called from the BlockItem mixin right after a block was placed from an item. */
	public static void onPlaced(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		if (level.isClientSide() || !StorageItems.isStorageChest(stack) || !state.is(Blocks.CHEST) || !(placer instanceof ServerPlayer player)) {
			return;
		}

		// Never merge into a double chest; each storage chest stands alone.
		if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
			BlockState otherState = level.getBlockState(other);

			if (otherState.is(Blocks.CHEST)) {
				level.setBlock(other, otherState.setValue(ChestBlock.TYPE, ChestType.SINGLE), Block.UPDATE_ALL);
			}

			level.setBlock(pos, state.setValue(ChestBlock.TYPE, ChestType.SINGLE), Block.UPDATE_ALL);
		}

		StorageData.get().setChest(Rooms.id(level), pos, player.getUUID());
		player.sendSystemMessage(Component.literal("Dimensionskiste gesetzt. Nur du wirst beim Öffnen hineingezogen.").withStyle(ChatFormatting.DARK_PURPLE));
	}

	private static boolean onBreak(Level level, net.minecraft.world.entity.player.Player player, BlockPos pos, BlockState state) {
		if (level.isClientSide()) {
			return true;
		}

		StorageData data = StorageData.get();

		if (Rooms.isStorage(level) && Rooms.isShell(pos)) {
			return false;
		}

		UUID owner = data.chestOwner(Rooms.id(level), pos);

		if (owner == null || !state.is(Blocks.CHEST)) {
			return true;
		}

		drain((ServerLevel) level, pos, owner);
		data.removeChest(Rooms.id(level), pos);
		level.removeBlock(pos, false);

		if (!player.isCreative()) {
			Block.popResource(level, pos, StorageItems.storageChest());
		}

		return false;
	}

	// ---- interaction ----

	private static InteractionResult onUseBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
		StorageData data = StorageData.get();

		if (data == null) {
			return InteractionResult.PASS;
		}

		if (Rooms.isStorage(level)) {
			int room = Rooms.roomAt(pos);
			StorageNetwork network = data.networkByRoom(room);

			if (Rooms.isTerminal(pos)) {
				if (network != null && network.canAccess(player.getUUID())) {
					StorageMenu.open(player, network);
				}

				return InteractionResult.SUCCESS;
			}

			if (Rooms.isExit(pos)) {
				leaveRoom(player);
				return InteractionResult.SUCCESS;
			}

			return InteractionResult.PASS;
		}

		UUID owner = data.chestOwner(Rooms.id(level), pos);

		if (owner == null) {
			return InteractionResult.PASS;
		}

		if (!level.getBlockState(pos).is(Blocks.CHEST)) {
			data.removeChest(Rooms.id(level), pos);
			return InteractionResult.PASS;
		}

		UUID newOwner = PENDING_OWNER.remove(player.getUUID());

		if (newOwner != null) {
			if (owner.equals(player.getUUID()) || player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
				data.setChest(Rooms.id(level), pos, newOwner);
				player.sendSystemMessage(Component.literal("Die Kiste gehört jetzt jemand anderem.").withStyle(ChatFormatting.GREEN));
			} else {
				player.sendSystemMessage(Component.literal("Das ist nicht deine Kiste.").withStyle(ChatFormatting.RED));
			}

			return InteractionResult.SUCCESS;
		}

		// Sneaking with an item in hand places it (e.g. a hopper) instead of opening.
		if (player.isSecondaryUseActive() && !player.getMainHandItem().isEmpty()) {
			return InteractionResult.PASS;
		}

		if (owner.equals(player.getUUID())) {
			enterRoom(player);
			return InteractionResult.SUCCESS;
		}

		ServerPlayer ownerPlayer = level.getServer().getPlayerList().getPlayer(owner);
		StorageNetwork network = data.networkFor(owner);

		if (ownerPlayer != null && Rooms.isStorage(ownerPlayer.level()) && Rooms.roomAt(ownerPlayer.blockPosition()) == network.room) {
			startViewing(player, network);
		} else {
			DecoyChestMenu.open(player);
		}

		return InteractionResult.SUCCESS;
	}

	// ---- entering & leaving ----

	public static void enterRoom(ServerPlayer player) {
		StorageData data = StorageData.get();

		if (!Rooms.isStorage(player.level())) {
			data.returns.put(player.getUUID(), here(player));
			StorageData.markDirty();
		}

		StorageNetwork network = data.networkFor(player.getUUID());
		Rooms.teleportIntoRoom(player, network.room);
		player.sendSystemMessage(Component.literal("Willkommen in deiner Storage Dimension! Enderkiste = ME-Speicher, Leuchtstein = Ausgang.")
				.withStyle(ChatFormatting.DARK_PURPLE));
	}

	public static void leaveRoom(ServerPlayer player) {
		StorageData data = StorageData.get();
		StorageData.SavedPos back = data.returns.remove(player.getUUID());
		StorageData.markDirty();

		if (back == null || !teleport(player, back)) {
			ServerLevel overworld = player.level().getServer().overworld();
			BlockPos spawn = overworld.getLevelData().getRespawnData().pos();
			player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, Set.<Relative>of(), 0f, 0f, true);
		}
	}

	// ---- watching through a chest ----

	private static void startViewing(ServerPlayer player, StorageNetwork network) {
		StorageData data = StorageData.get();
		data.viewers.put(player.getUUID(), new StorageData.Viewer(here(player),
				Integer.toString(player.gameMode.getGameModeForPlayer().getId()), network.owner));
		StorageData.markDirty();
		ACTIVE_VIEWERS.put(player.getUUID(), VIEW_TICKS);
		player.setGameMode(GameType.SPECTATOR);
		Rooms.teleportToViewpoint(player, network.room);
		player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("Schleichen zum Zurückkehren").withStyle(ChatFormatting.GRAY)));
	}

	private static void stopViewing(ServerPlayer player) {
		ACTIVE_VIEWERS.remove(player.getUUID());
		StorageData.Viewer viewer = StorageData.get().viewers.remove(player.getUUID());
		StorageData.markDirty();

		if (viewer == null) {
			return;
		}

		player.setGameMode(GameType.byId(Integer.parseInt(viewer.gameMode())));
		teleport(player, viewer.back());
	}

	// ---- ticking ----

	private static void tick(MinecraftServer server) {
		StorageData data = StorageData.get();

		if (data == null) {
			return;
		}

		tickViewers(server, data);

		if (++tickCounter % 10 == 0) {
			drainAll(server, data);
		}

		if (tickCounter % 20 == 0) {
			keepInsideRooms(server, data);
		}
	}

	private static void tickViewers(MinecraftServer server, StorageData data) {
		Iterator<Map.Entry<UUID, Integer>> it = ACTIVE_VIEWERS.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<UUID, Integer> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			StorageData.Viewer viewer = data.viewers.get(entry.getKey());

			if (player == null || viewer == null) {
				it.remove();
				continue;
			}

			StorageNetwork network = data.networkFor(viewer.network());
			ServerPlayer owner = server.getPlayerList().getPlayer(viewer.network());
			boolean ownerInside = owner != null && Rooms.isStorage(owner.level()) && Rooms.roomAt(owner.blockPosition()) == network.room;
			int left = entry.getValue() - 1;

			if (left <= 0 || player.isShiftKeyDown() || !ownerInside) {
				it.remove();
				stopViewing(player);
				continue;
			}

			entry.setValue(left);
			AABB box = Rooms.interior(network.room);

			if (!Rooms.isStorage(player.level()) || !box.contains(player.position())) {
				Rooms.teleportToViewpoint(player, network.room);
			}
		}
	}

	/** Players in the storage dimension who somehow left their room go back in. */
	private static void keepInsideRooms(MinecraftServer server, StorageData data) {
		ServerLevel level = Rooms.level(server);

		if (level == null) {
			return;
		}

		for (ServerPlayer player : level.players()) {
			if (ACTIVE_VIEWERS.containsKey(player.getUUID())) {
				continue;
			}

			StorageNetwork network = data.networkFor(player.getUUID());

			if (!Rooms.interior(network.room).inflate(0.5).contains(player.position())) {
				Rooms.teleportIntoRoom(player, network.room);
			}
		}
	}

	/** Moves everything hoppers dropped into storage chests into the owner's network. */
	private static void drainAll(MinecraftServer server, StorageData data) {
		for (Map.Entry<String, Map<Long, UUID>> dim : data.chests.entrySet()) {
			ServerLevel level = Rooms.levelById(server, dim.getKey());

			if (level == null) {
				continue;
			}

			Iterator<Map.Entry<Long, UUID>> it = dim.getValue().entrySet().iterator();

			while (it.hasNext()) {
				Map.Entry<Long, UUID> chest = it.next();
				BlockPos pos = BlockPos.of(chest.getKey());

				if (!level.hasChunkAt(pos)) {
					continue;
				}

				if (!level.getBlockState(pos).is(Blocks.CHEST)) {
					it.remove();
					StorageData.markDirty();
					continue;
				}

				drain(level, pos, chest.getValue());
			}
		}
	}

	private static void drain(ServerLevel level, BlockPos pos, UUID owner) {
		BlockEntity blockEntity = level.getBlockEntity(pos);

		if (!(blockEntity instanceof Container container) || container.isEmpty()) {
			return;
		}

		StorageNetwork network = StorageData.get().networkFor(owner);

		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.removeItemNoUpdate(i);

			if (!stack.isEmpty()) {
				network.insert(stack);
			}
		}

		container.setChanged();
		StorageMenu.refreshAll(network);
	}

	// ---- helpers ----

	private static StorageData.SavedPos here(ServerPlayer player) {
		return new StorageData.SavedPos(Rooms.id(player.level()), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
	}

	private static boolean teleport(ServerPlayer player, StorageData.SavedPos pos) {
		ServerLevel level = Rooms.levelById(player.level().getServer(), pos.dimension());

		if (level == null) {
			return false;
		}

		player.teleportTo(level, pos.x(), pos.y(), pos.z(), Set.<Relative>of(), pos.yRot(), pos.xRot(), true);
		return true;
	}

	static void logMissingDimension() {
		StorageDimensionMod.LOGGER.error("Storage-Dimension nicht gefunden");
	}
}
