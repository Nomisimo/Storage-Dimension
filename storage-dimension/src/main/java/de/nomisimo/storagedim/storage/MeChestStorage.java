package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Lets machines from other mods (Tech Reborn, pipes, ...) talk to dimension and filter chests through the Fabric
 * transfer API: whatever they insert goes straight into ME storage, and a filter chest also hands out its filter
 * items. Vanilla hoppers keep using the chest's own slots.
 */
final class MeChestStorage extends SnapshotParticipant<List<MeChestStorage.Change>> implements Storage<ItemVariant> {
	/** Positive amounts are insertions, negative ones extractions, applied when the outer transaction commits. */
	record Change(ItemVariant variant, long amount) {
	}

	private final StorageNetwork network;
	/** Only these item types are accepted and offered; null accepts everything and offers nothing. */
	private final List<ItemStack> filters;
	private List<Change> pending = new ArrayList<>();

	private MeChestStorage(StorageNetwork network, List<ItemStack> filters) {
		this.network = network;
		this.filters = filters;
	}

	static void register() {
		ItemStorage.SIDED.registerForBlocks((level, pos, state, blockEntity, side) -> find(level, pos), Blocks.CHEST);
	}

	private static MeChestStorage find(Level level, BlockPos pos) {
		StorageData data = StorageData.get();

		if (level.isClientSide() || data == null) {
			return null;
		}

		FilterChests.FilterChest filterChest = data.filterChest(Rooms.id(level), pos);

		if (filterChest != null) {
			FilterChests.FilterChest chest = StorageEvents.resolveFilterChest(level, pos, data, filterChest);
			return new MeChestStorage(data.networkFor(chest.owner()), chest.active());
		}

		var owner = data.chestOwner(Rooms.id(level), pos);
		return owner == null ? null : new MeChestStorage(data.networkFor(owner), null);
	}

	private boolean accepts(ItemVariant variant) {
		return !variant.isBlank() && (filters == null || FilterChests.matches(filters, variant.toStack()));
	}

	private long available(ItemVariant variant) {
		StorageNetwork.StoredItem entry = network.find(variant.toStack());
		long amount = entry == null ? 0 : entry.count;

		for (Change change : pending) {
			if (change.variant().equals(variant)) {
				amount += change.amount();
			}
		}

		return amount;
	}

	@Override
	public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		if (maxAmount <= 0 || !accepts(resource)) {
			return 0;
		}

		updateSnapshots(transaction);
		pending.add(new Change(resource, maxAmount));
		return maxAmount;
	}

	@Override
	public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
		if (filters == null || maxAmount <= 0 || !accepts(resource)) {
			return 0;
		}

		long amount = Math.min(maxAmount, available(resource));

		if (amount > 0) {
			updateSnapshots(transaction);
			pending.add(new Change(resource, -amount));
		}

		return Math.max(amount, 0);
	}

	@Override
	public boolean supportsExtraction() {
		return filters != null;
	}

	@Override
	public Iterator<StorageView<ItemVariant>> iterator() {
		List<StorageView<ItemVariant>> views = new ArrayList<>();

		if (filters != null) {
			for (StorageNetwork.StoredItem entry : network.sorted()) {
				ItemVariant variant = ItemVariant.of(entry.prototype);

				if (accepts(variant)) {
					views.add(new View(variant));
				}
			}
		}

		return views.iterator();
	}

	@Override
	protected List<Change> createSnapshot() {
		return new ArrayList<>(pending);
	}

	@Override
	protected void readSnapshot(List<Change> snapshot) {
		pending = snapshot;
	}

	@Override
	protected void onFinalCommit() {
		for (Change change : pending) {
			ItemStack prototype = change.variant().toStack();

			if (change.amount() > 0) {
				network.insert(prototype.copyWithCount((int) Math.min(change.amount(), Integer.MAX_VALUE)));
			} else {
				network.extract(prototype, (int) Math.min(-change.amount(), Integer.MAX_VALUE));
			}
		}

		pending = new ArrayList<>();
		StorageMenu.refreshAll(network);
	}

	private final class View implements StorageView<ItemVariant> {
		private final ItemVariant variant;

		private View(ItemVariant variant) {
			this.variant = variant;
		}

		@Override
		public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
			return resource.equals(variant) ? MeChestStorage.this.extract(resource, maxAmount, transaction) : 0;
		}

		@Override
		public boolean isResourceBlank() {
			return false;
		}

		@Override
		public ItemVariant getResource() {
			return variant;
		}

		@Override
		public long getAmount() {
			return Math.max(available(variant), 0);
		}

		@Override
		public long getCapacity() {
			return getAmount();
		}
	}
}
