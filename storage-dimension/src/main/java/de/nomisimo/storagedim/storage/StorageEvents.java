package de.nomisimo.storagedim.storage;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import de.nomisimo.storagedim.StorageConfig;
import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public final class StorageEvents {
	/** Player to the new owner they want to give the next storage chest they click. */
	static final Map<UUID, UUID> PENDING_OWNER = new HashMap<>();
	private static int tickCounter;

	private StorageEvents() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(StorageData::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> StorageData.unload());
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> StorageData.save(false));
		ServerTickEvents.END_SERVER_TICK.register(StorageEvents::tick);
		MeChestStorage.register();
		ChestMarks.register();
		LibraryExtras.register();
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}

			return onUseBlock(serverPlayer, (ServerLevel) level, hit.getBlockPos());
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || StorageData.get() == null) {
				return InteractionResult.PASS;
			}

			if (Rooms.isStorage(level) && player.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BucketItem) {
				return InteractionResult.FAIL;
			}

			return Backpacks.onUseItem(serverPlayer, hand);
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> onBreak(level, player, pos, state));
		ServerPlayerEvents.JOIN.register(player -> {
			StorageItems.upgradeInventory(player);
			StorageData data = StorageData.get();

			if (data != null && data.viewers.containsKey(player.getUUID())) {
				// Was watching through a chest in an older version: put them back.
				stopViewing(player);
			}
		});
	}

	// ---- placing & breaking ----

	/** Called from the BlockItem mixin right after a block was placed from an item. */
	public static void onPlaced(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		if (!level.isClientSide() && StorageItems.isTerminal(stack) && state.is(Blocks.ENDER_CHEST)) {
			StorageData.get().setTerminal(Rooms.id(level), pos, true, placer == null ? null : placer.getUUID());
			return;
		}

		boolean storageChest = StorageItems.isStorageChest(stack);
		boolean filterChest = StorageItems.isFilterChest(stack);
		boolean projectChest = StorageItems.isProjectChest(stack);

		if (level.isClientSide() || !state.is(Blocks.CHEST)) {
			return;
		}

		StorageData data = StorageData.get();
		String dim = Rooms.id(level);
		BlockPos other = state.getValue(ChestBlock.TYPE) == ChestType.SINGLE ? null : pos.relative(ChestBlock.getConnectedDirection(state));

		if (projectChest) {
			if (other != null) {
				splitChest(level, pos, state, other);
			}

			if (!(placer instanceof ServerPlayer player)) {
				return;
			}

			// a broken project chest keeps its project
			Projects.Project project = Projects.get(Projects.idOf(stack));

			if (project == null) {
				project = Projects.create(player.getUUID());
			}

			data.setProjectChest(dim, pos, project);
			player.sendSystemMessage(Component.literal("Projektkiste „" + project.name + "“ gesetzt. Schleichen + Rechtsklick mit leerer Hand: "
					+ "Items, Mengen und Name einstellen. Rechtsklick: die Projekt-Items aus dem ME-Speicher.").withStyle(ChatFormatting.DARK_PURPLE));
			return;
		}

		if (!storageChest && !filterChest) {
			// A plain chest never joins a special one.
			if (other != null && (data.filterChest(dim, other) != null || data.chestOwner(dim, other) != null
					|| data.projectChest(dim, other) != null)) {
				splitChest(level, pos, state, other);
			}

			return;
		}

		if (!(placer instanceof ServerPlayer player)) {
			return;
		}

		FilterChests.FilterChest partner = other == null ? null : data.filterChest(dim, other);

		if (filterChest && partner != null) {
			// Two filter chests make a double chest; its filters live on the half with the smaller position.
			FilterChests.FilterChest placed = FilterChests.FilterChest.empty(player.getUUID());

			if (pos.asLong() < other.asLong()) {
				placed.filters().clear();
				placed.filters().addAll(partner.filters());
			}

			placed.resize(FilterChests.DOUBLE_SLOTS);
			partner.resize(FilterChests.DOUBLE_SLOTS);
			data.setFilterChest(dim, pos, placed);
			player.sendSystemMessage(Component.literal("Doppelte Filterkiste: jetzt " + FilterChests.DOUBLE_SLOTS + " Filter-Plätze.")
					.withStyle(ChatFormatting.DARK_PURPLE));
			return;
		}

		// Otherwise never merge into a double chest; each special chest stands alone.
		if (other != null) {
			splitChest(level, pos, state, other);
		}

		if (filterChest) {
			data.setFilterChest(dim, pos, FilterChests.FilterChest.empty(player.getUUID()));
			player.sendSystemMessage(Component.literal("Filterkiste gesetzt. Schleichen + Rechtsklick mit leerer Hand stellt die Filter ein. "
					+ "Zwei Filterkisten nebeneinander werden eine Doppelkiste mit " + FilterChests.DOUBLE_SLOTS + " Filtern.")
					.withStyle(ChatFormatting.DARK_PURPLE));
			return;
		}

		data.setChest(dim, pos, player.getUUID());
		player.sendSystemMessage(Component.literal("Dimensionskiste gesetzt. Wer Zugriff auf den Speicher hat, kommt beim Öffnen hinein.")
				.withStyle(ChatFormatting.DARK_PURPLE));
	}

	private static void splitChest(Level level, BlockPos pos, BlockState state, BlockPos other) {
		BlockState otherState = level.getBlockState(other);

		if (otherState.is(Blocks.CHEST)) {
			level.setBlock(other, otherState.setValue(ChestBlock.TYPE, ChestType.SINGLE), Block.UPDATE_ALL);
		}

		level.setBlock(pos, state.setValue(ChestBlock.TYPE, ChestType.SINGLE), Block.UPDATE_ALL);
	}

	/** The other half of a double filter chest, or null. */
	private static BlockPos filterPartner(Level level, BlockPos pos, StorageData data) {
		BlockState state = level.getBlockState(pos);

		if (!state.is(Blocks.CHEST) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
			return null;
		}

		BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
		return level.getBlockState(other).is(Blocks.CHEST) && data.filterChest(Rooms.id(level), other) != null ? other : null;
	}

	/**
	 * The filters that apply at {@code pos}: a single chest has {@link FilterChests#SLOTS}, a double chest shares the
	 * {@link FilterChests#DOUBLE_SLOTS} of the half with the smaller position.
	 */
	static FilterChests.FilterChest resolveFilterChest(Level level, BlockPos pos, StorageData data, FilterChests.FilterChest own) {
		BlockPos partner = filterPartner(level, pos, data);
		FilterChests.FilterChest chest = partner != null && partner.asLong() < pos.asLong() ? data.filterChest(Rooms.id(level), partner) : own;
		int size = partner == null ? FilterChests.SLOTS : FilterChests.DOUBLE_SLOTS;

		if (chest.filters().size() != size) {
			chest.resize(size);
			StorageData.markDirty();
		}

		return chest;
	}

	private static boolean onBreak(Level level, net.minecraft.world.entity.player.Player player, BlockPos pos, BlockState state) {
		if (level.isClientSide()) {
			return true;
		}

		StorageData data = StorageData.get();

		if (Rooms.isStorage(level)) {
			// The library cannot be taken apart.
			return false;
		}

		if (data.isTerminal(Rooms.id(level), pos)) {
			data.setTerminal(Rooms.id(level), pos, false);

			if (state.is(Blocks.ENDER_CHEST)) {
				level.removeBlock(pos, false);

				if (!player.isCreative()) {
					Block.popResource(level, pos, StorageItems.terminal());
				}

				return false;
			}

			return true;
		}

		Projects.Project projectChest = data.projectChest(Rooms.id(level), pos);

		if (projectChest != null) {
			data.removeProjectChest(Rooms.id(level), pos);

			if (!state.is(Blocks.CHEST)) {
				return true;
			}

			drain((ServerLevel) level, pos, projectChest.owner);
			level.removeBlock(pos, false);

			if (!player.isCreative()) {
				Block.popResource(level, pos, StorageItems.projectChest(projectChest));
			}

			return false;
		}

		FilterChests.FilterChest filterChest = data.filterChest(Rooms.id(level), pos);

		if (filterChest != null) {
			BlockPos partner = filterPartner(level, pos, data);

			if (partner != null && pos.asLong() < partner.asLong()) {
				// The half holding the shared filters goes: the other half keeps the first seven.
				FilterChests.FilterChest survivor = data.filterChest(Rooms.id(level), partner);
				survivor.filters().clear();
				survivor.filters().addAll(filterChest.filters());
				survivor.resize(FilterChests.SLOTS);
			}

			data.removeFilterChest(Rooms.id(level), pos);

			if (!state.is(Blocks.CHEST)) {
				return true;
			}

			drain((ServerLevel) level, pos, filterChest.owner());
			level.removeBlock(pos, false);

			if (!player.isCreative()) {
				Block.popResource(level, pos, StorageItems.filterChest());
			}

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

		if (data.isTerminal(Rooms.id(level), pos)) {
			if (!level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
				data.setTerminal(Rooms.id(level), pos, false);
				return InteractionResult.PASS;
			}

			if (player.isSecondaryUseActive() && !player.getMainHandItem().isEmpty()) {
				return InteractionResult.PASS;
			}

			StorageMenu.open(player, data.networkFor(player.getUUID()));
			return InteractionResult.SUCCESS;
		}

		if (Rooms.isStorage(level)) {
			// Chests and the exit work; nothing can be placed and the barrels stay shut.
			return Library.use(player, level, pos);
		}

		Projects.Project projectChest = data.projectChest(Rooms.id(level), pos);

		if (projectChest != null) {
			if (!level.getBlockState(pos).is(Blocks.CHEST)) {
				data.removeProjectChest(Rooms.id(level), pos);
				return InteractionResult.PASS;
			}

			return useProject(player, projectChest);
		}

		FilterChests.FilterChest filterChest = data.filterChest(Rooms.id(level), pos);

		if (filterChest != null) {
			return useFilterChest(player, level, pos, data, resolveFilterChest(level, pos, data, filterChest));
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

		// Everybody with access to the chest's storage comes in, any number at once; others see an empty chest.
		StorageNetwork network = data.networkFor(owner);

		if (network.canAccess(player.getUUID())) {
			enterRoom(player, network);
		} else {
			DecoyChestMenu.open(player);
		}

		return InteractionResult.SUCCESS;
	}

	/** Right click on a project chest, in the world or in the library. */
	static InteractionResult useProject(ServerPlayer player, Projects.Project project) {
		if (player.isSecondaryUseActive() && !player.getMainHandItem().isEmpty()) {
			// Sneaking with an item in hand places it (e.g. a hopper) instead.
			return InteractionResult.PASS;
		}

		if (!project.network().canAccess(player.getUUID())) {
			DecoyChestMenu.open(player);
			return InteractionResult.SUCCESS;
		}

		if (player.isSecondaryUseActive() || project.entries.isEmpty()) {
			if (project.entries.isEmpty()) {
				player.connection.send(new ClientboundSetActionBarTextPacket(
						Component.literal("Noch keine Items im Projekt: Item auf einen Platz legen oder suchen").withStyle(ChatFormatting.YELLOW)));
			}

			Projects.openSettings(player, project);
			return InteractionResult.SUCCESS;
		}

		StorageMenu.openProject(player, project);
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult useFilterChest(ServerPlayer player, ServerLevel level, BlockPos pos, StorageData data,
			FilterChests.FilterChest chest) {
		if (!level.getBlockState(pos).is(Blocks.CHEST)) {
			data.removeFilterChest(Rooms.id(level), pos);
			return InteractionResult.PASS;
		}

		if (player.isSecondaryUseActive()) {
			// Sneaking with an item in hand places it (e.g. a hopper) instead.
			if (!player.getMainHandItem().isEmpty()) {
				return InteractionResult.PASS;
			}

			FilterMenu.open(player, chest);
			return InteractionResult.SUCCESS;
		}

		if (!chest.hasFilters()) {
			player.connection.send(new ClientboundSetActionBarTextPacket(
					Component.literal("Noch keine Filter: Schleichen + Rechtsklick mit leerer Hand").withStyle(ChatFormatting.YELLOW)));
			FilterMenu.open(player, chest);
			return InteractionResult.SUCCESS;
		}

		StorageMenu.open(player, data.networkFor(player.getUUID()), chest.active());
		return InteractionResult.SUCCESS;
	}

	// ---- entering & leaving ----

	public static void enterRoom(ServerPlayer player, StorageNetwork network) {
		StorageData data = StorageData.get();

		if (!Rooms.isStorage(player.level())) {
			data.returns.put(player.getUUID(), here(player));
			StorageData.markDirty();
		}

		Library.teleportToSpawn(player, network);
		player.sendSystemMessage(Component.literal("Willkommen in deiner Bibliothek! Jeder Gang ist eine Abteilung, das Lesepult ist der Katalog, der Leuchtstein der Ausgang.")
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

	// ---- old chest watchers ----

	/** Watching through a chest was removed; this only restores players saved while watching in an older version. */
	private static void stopViewing(ServerPlayer player) {
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
		ChestMarks.tick(server);
		StorageData data = StorageData.get();

		if (data == null) {
			return;
		}

		if (++tickCounter % 10 == 0) {
			drainAll(server, data);
		}

		if (tickCounter % 20 == 0) {
			keepInLibraries(server, data);
		}
	}

	/**
	 * Players in the storage dimension who are not in a library they may use go back to their own.
	 * Libraries with players in them grow with newly stored kinds.
	 */
	private static void keepInLibraries(MinecraftServer server, StorageData data) {
		ServerLevel level = Rooms.level(server);

		if (level == null) {
			return;
		}

		Set<StorageNetwork> visited = new java.util.HashSet<>();

		for (ServerPlayer player : level.players()) {
			StorageNetwork here = data.networkByRoom(Library.roomAt(player.blockPosition()));

			if (here != null && here.canAccess(player.getUUID()) && Library.isInside(player, here)) {
				visited.add(here);
				continue;
			}

			Library.teleportToSpawn(player, data.networkFor(player.getUUID()));
		}

		for (StorageNetwork network : visited) {
			Library.tick(level, network);
		}
	}

	/** Moves everything hoppers dropped into storage and filter chests into the owner's network. */
	private static void drainAll(MinecraftServer server, StorageData data) {
		for (Map.Entry<String, Map<Long, FilterChests.FilterChest>> dim : data.filterChests.entrySet()) {
			ServerLevel level = Rooms.levelById(server, dim.getKey());

			if (level == null) {
				continue;
			}

			Iterator<Map.Entry<Long, FilterChests.FilterChest>> it = dim.getValue().entrySet().iterator();

			while (it.hasNext()) {
				Map.Entry<Long, FilterChests.FilterChest> chest = it.next();
				BlockPos pos = BlockPos.of(chest.getKey());

				if (!level.hasChunkAt(pos)) {
					continue;
				}

				if (!level.getBlockState(pos).is(Blocks.CHEST)) {
					it.remove();
					StorageData.markDirty();
					continue;
				}

				drain(level, pos, chest.getValue().owner());
				FilterChests.FilterChest resolved = resolveFilterChest(level, pos, data, chest.getValue());
				pullHoppers(level, pos, data.networkFor(resolved.owner()), resolved.active());

				if (level.getBlockState(pos.below()).is(Blocks.HOPPER)) {
					restock(level, pos, resolveFilterChest(level, pos, data, chest.getValue()));
				}
			}
		}

		for (Map.Entry<String, Map<Long, UUID>> dim : data.projectChests.entrySet()) {
			ServerLevel level = Rooms.levelById(server, dim.getKey());

			if (level == null) {
				continue;
			}

			Iterator<Map.Entry<Long, UUID>> it = dim.getValue().entrySet().iterator();

			while (it.hasNext()) {
				Map.Entry<Long, UUID> chest = it.next();
				BlockPos pos = BlockPos.of(chest.getKey());
				Projects.Project project = Projects.get(chest.getValue());

				if (!level.hasChunkAt(pos)) {
					continue;
				}

				if (project == null || !level.getBlockState(pos).is(Blocks.CHEST)) {
					it.remove();
					ChestMarks.changed(dim.getKey());
					StorageData.markDirty();
					continue;
				}

				drain(level, pos, project.owner);
				pullHoppers(level, pos, project.network(), null);
			}
		}

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
				pullHoppers(level, pos, data.networkFor(chest.getValue()), null);
			}
		}

		for (Map.Entry<String, Set<Long>> dim : data.terminals.entrySet()) {
			ServerLevel level = Rooms.levelById(server, dim.getKey());

			if (level == null) {
				continue;
			}

			for (long packed : List.copyOf(dim.getValue())) {
				BlockPos pos = BlockPos.of(packed);

				if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
					continue;
				}

				UUID owner = data.terminalOwner(dim.getKey(), pos);

				if (owner != null || StorageConfig.get().sharedStorage) {
					pullHoppers(level, pos, data.networkFor(owner == null ? new UUID(0, 0) : owner), null);
				}
			}
		}
	}

	/**
	 * Hoppers pointing into a storage block hand over everything they hold at once, not one item every few ticks
	 * like into a normal chest. A locked hopper (redstone) keeps its items. With {@code filters}, only those items.
	 */
	private static void pullHoppers(ServerLevel level, BlockPos pos, StorageNetwork network, List<ItemStack> filters) {
		boolean changed = false;

		for (Direction side : Direction.values()) {
			BlockPos hopperPos = pos.relative(side);
			BlockState state = level.getBlockState(hopperPos);

			if (!state.is(Blocks.HOPPER) || state.getValue(HopperBlock.FACING) != side.getOpposite() || !state.getValue(HopperBlock.ENABLED)
					|| !(level.getBlockEntity(hopperPos) instanceof Container hopper)) {
				continue;
			}

			for (int i = 0; i < hopper.getContainerSize(); i++) {
				ItemStack stack = hopper.getItem(i);

				if (!stack.isEmpty() && (filters == null || FilterChests.matches(filters, stack))) {
					network.insert(hopper.removeItemNoUpdate(i));
					changed = true;
				}
			}

			if (changed) {
				hopper.setChanged();
			}
		}

		if (changed) {
			StorageMenu.refreshAll(network);
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

	/**
	 * Lets a hopper under a filter chest pull the filter items out of ME storage: right after draining, every
	 * filter slot of the chest gets up to one stack of its item again. Whatever the hopper does not take goes
	 * back into storage on the next drain.
	 */
	private static void restock(ServerLevel level, BlockPos pos, FilterChests.FilterChest chest) {
		if (!(level.getBlockEntity(pos) instanceof Container container)) {
			return;
		}

		StorageNetwork network = StorageData.get().networkFor(chest.owner());
		boolean changed = false;

		for (int i = 0; i < chest.filters().size() && i < container.getContainerSize(); i++) {
			ItemStack filter = chest.filters().get(i);

			if (filter.isEmpty()) {
				continue;
			}

			for (StorageNetwork.StoredItem entry : network.sorted()) {
				if (FilterChests.matches(List.of(filter), entry.prototype)) {
					container.setItem(i, network.extract(entry.prototype, entry.prototype.getMaxStackSize()));
					changed = true;
					break;
				}
			}
		}

		if (changed) {
			container.setChanged();
			StorageMenu.refreshAll(network);
		}
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
