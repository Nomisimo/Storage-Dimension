package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A mailbox of the parcel post: its own address, and the address it sends to. What lies in the chest beside it is
 * fetched by the courier robots of a station (mode "Kurier") and carried in their beak to the mailbox with that
 * address, which puts it into the chest beside it. Addresses may use wildcards: {@code *} for any text, {@code ?}
 * for one character; of several matching mailboxes the closest one with room gets it. A slot number at the end of
 * the destination ({@code "Ofen:2"}) sends into that slot of the machine beside the receiving mailbox.
 */
public record Mailbox(String address, String destination) {
	public static final Codec<Mailbox> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.optionalFieldOf("address", "").forGetter(Mailbox::address),
			Codec.STRING.optionalFieldOf("destination", "").forGetter(Mailbox::destination)
	).apply(i, Mailbox::new));
	/** Stacks a courier takes along at a time: one in its beak, the rest in its bag (like a mining robot). */
	public static final int MAX_STACKS = RobotEntity.BAG_SLOTS;
	public static final int MAX_ADDRESS = 32;
	/** Machine slots the screen shows (and a destination may name). */
	public static final int MAX_MACHINE_SLOTS = 36;
	/** Mailbox (dimension@packed position) to the courier robot on its way there to fetch the post. */
	private static final Map<String, UUID> CLAIMED = new HashMap<>();


	/** Mailbox flags: once a second every loaded mailbox shows whether post is waiting in the chest beside it. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(Mailbox::tick);
	}

	private static void tick(MinecraftServer server) {
		StorageData data = StorageData.get();

		if (data == null || server.getTickCount() % 20 != 7) {
			return;
		}

		for (ServerLevel level : server.getAllLevels()) {
			for (Map.Entry<Long, Mailbox> entry : List.copyOf(data.mailboxes(Rooms.id(level)).entrySet())) {
				BlockPos pos = BlockPos.of(entry.getKey());

				if (!level.isLoaded(pos)) {
					continue;
				}

				BlockState state = level.getBlockState(pos);

				if (!state.is(MailboxBlock.BLOCK)) {
					continue;
				}

				boolean waiting = !entry.getValue().destination().isEmpty() && !waiting(level, pos, 1).isEmpty();

				if (state.getValue(MailboxBlock.MAIL) != waiting) {
					level.setBlock(pos, state.setValue(MailboxBlock.MAIL, waiting), Block.UPDATE_ALL);
				}
			}
		}
	}

	// ---- addresses ----

	/**
	 * The machine slot a destination names, counted from 0, or -1: {@code "Ofen:2"} sends into slot 2 (counted from
	 * 1 as the screen shows it) of the machine beside the mailbox "Ofen".
	 */
	public static int slot(String destination) {
		int colon = destination.lastIndexOf(':');

		if (colon < 0 || colon == destination.length() - 1) {
			return -1;
		}

		String number = destination.substring(colon + 1).strip();

		if (number.isEmpty() || number.length() > 3 || !number.chars().allMatch(Character::isDigit)) {
			return -1;
		}

		int slot = Integer.parseInt(number);
		return slot >= 1 && slot <= MAX_MACHINE_SLOTS ? slot - 1 : -1;
	}

	/** The address part of a destination, without a slot number at the end. */
	public static String pattern(String destination) {
		return slot(destination) < 0 ? destination : destination.substring(0, destination.lastIndexOf(':')).strip();
	}

	/** The address matches the pattern (case does not matter; {@code *} any text, {@code ?} one character). */
	public static boolean matches(String pattern, String address) {
		String p = pattern.strip().toLowerCase(Locale.ROOT);
		String a = address.strip().toLowerCase(Locale.ROOT);
		return !p.isEmpty() && !a.isEmpty() && glob(p, 0, a, 0);
	}

	private static boolean glob(String p, int pi, String a, int ai) {
		while (pi < p.length()) {
			char c = p.charAt(pi);

			if (c == '*') {
				for (int i = ai; i <= a.length(); i++) {
					if (glob(p, pi + 1, a, i)) {
						return true;
					}
				}

				return false;
			}

			if (ai >= a.length() || c != '?' && c != a.charAt(ai)) {
				return false;
			}

			pi++;
			ai++;
		}

		return ai == a.length();
	}

	/**
	 * The mailbox the post of {@code from} goes to: of all others whose address matches its destination and that
	 * pass {@code allowed} (in reach, has room), the closest one; null when there is none.
	 */
	public static BlockPos resolve(ServerLevel level, BlockPos from, Predicate<BlockPos> allowed) {
		StorageData data = StorageData.get();
		String dim = Rooms.id(level);
		Mailbox sender = data == null ? null : data.mailbox(dim, from);

		if (sender == null || sender.destination().isEmpty()) {
			return null;
		}

		BlockPos best = null;

		for (Map.Entry<Long, Mailbox> entry : data.mailboxes(dim).entrySet()) {
			BlockPos pos = BlockPos.of(entry.getKey());

			if (pos.equals(from) || !matches(pattern(sender.destination()), entry.getValue().address())) {
				continue;
			}

			if ((best == null || pos.distSqr(from) < best.distSqr(from)) && level.isLoaded(pos) && level.getBlockState(pos).is(MailboxBlock.BLOCK)
					&& allowed.test(pos)) {
				best = pos;
			}
		}

		return best;
	}

	/** Some mailbox has the address (or matches the pattern), wherever it is. */
	public static boolean known(String dim, String pattern) {
		StorageData data = StorageData.get();
		return data != null && data.mailboxes(dim).values().stream().anyMatch(mailbox -> matches(pattern(pattern), mailbox.address()));
	}

	/** The name shown for a mailbox: its address, or its position when it has none. */
	public static String label(ServerLevel level, BlockPos pos) {
		StorageData data = StorageData.get();
		Mailbox mailbox = data == null ? null : data.mailbox(Rooms.id(level), pos);
		return mailbox == null || mailbox.address().isEmpty() ? pos.toShortString() : mailbox.address();
	}

	// ---- the chests beside it ----

	/**
	 * Where the mailbox sends from or puts what arrives, machines first: it sends what comes out of the machines
	 * beside it (their output, taken from below like a hopper under a furnace); what arrives goes into their fuel
	 * slot when it is fuel (from the side, like a hopper there), else into their ingredients (from above). Then the
	 * storage blocks beside it (chests, barrels and other storage, by the same
	 * rules as at a station). Sending leaves out the mod's ME blocks and ender chests, so a mailbox never empties a
	 * whole ME storage.
	 */
	private static List<Storage<ItemVariant>> beside(ServerLevel level, BlockPos pos, boolean sending) {
		List<Storage<ItemVariant>> storages = new ArrayList<>();
		StorageData data = StorageData.get();
		String dim = Rooms.id(level);
		// the fuel side first: a furnace takes only fuel there, everything else goes on to the ingredients
		Direction[] machineFaces = sending ? new Direction[] {Direction.DOWN} : new Direction[] {Direction.NORTH, Direction.UP};

		for (Direction side : Direction.values()) {
			BlockPos other = pos.relative(side);

			if (!level.isLoaded(other) || !isMachine(level, other, data, dim)) {
				continue;
			}

			for (Direction face : machineFaces) {
				Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, other, face);

				if (storage != null) {
					storages.add(storage);
				}
			}
		}

		for (Direction side : Direction.values()) {
			BlockPos other = pos.relative(side);

			if (!level.isLoaded(other)) {
				continue;
			}

			BlockState state = level.getBlockState(other);

			if (state.is(MailboxBlock.BLOCK) || state.is(RobotStationBlock.BLOCK) || state.is(RobotEntity.NOT_STORAGE) || !RobotEntity.looksLikeStorage(state)) {
				continue;
			}

			if (sending && (state.is(Blocks.ENDER_CHEST) || data != null && (data.chestOwner(dim, other) != null || data.filterChest(dim, other) != null
					|| data.isTerminal(dim, other) || data.projectChest(dim, other) != null))) {
				continue;
			}

			Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, other, side.getOpposite());

			if (storage != null) {
				storages.add(storage);
			}
		}

		return storages;
	}

	/**
	 * A machine for the mailbox: any block that takes or gives items but is no storage block (furnaces, smokers,
	 * brewing stands, hoppers, other mods' machines); never the mod's own blocks or ME blocks.
	 */
	private static boolean isMachine(ServerLevel level, BlockPos pos, StorageData data, String dim) {
		BlockState state = level.getBlockState(pos);

		if (state.is(MailboxBlock.BLOCK) || state.is(RobotStationBlock.BLOCK) || state.is(Blocks.ENDER_CHEST)
				|| RobotEntity.looksLikeStorage(state) && !state.is(RobotEntity.NOT_STORAGE)) {
			return false;
		}

		return data == null || data.chestOwner(dim, pos) == null && data.filterChest(dim, pos) == null && !data.isTerminal(dim, pos)
				&& data.projectChest(dim, pos) == null;
	}

	/** All slots of a machine, one by one, whatever side they are reachable from; null when it has none. */
	private static SlottedStorage<ItemVariant> slotted(ServerLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof Container container) {
			return InventoryStorage.of(container, null);
		}

		return ItemStorage.SIDED.find(level, pos, null) instanceof SlottedStorage<ItemVariant> slotted ? slotted : null;
	}

	/** The machines beside the mailbox with their slots one by one. */
	private static List<BlockPos> machines(ServerLevel level, BlockPos pos) {
		List<BlockPos> machines = new ArrayList<>();
		StorageData data = StorageData.get();
		String dim = Rooms.id(level);

		for (Direction side : Direction.values()) {
			BlockPos other = pos.relative(side);

			if (level.isLoaded(other) && isMachine(level, other, data, dim) && slotted(level, other) != null) {
				machines.add(other);
			}
		}

		return machines;
	}

	/** The machine beside the mailbox as its screen shows it: name and what lies in its slots; null without one. */
	public static MachineView machine(ServerLevel level, BlockPos pos) {
		for (BlockPos other : machines(level, pos)) {
			SlottedStorage<ItemVariant> slotted = slotted(level, other);
			List<ItemStack> contents = new ArrayList<>();

			for (int i = 0; i < Math.min(slotted.getSlotCount(), MAX_MACHINE_SLOTS); i++) {
				StorageView<ItemVariant> view = slotted.getSlot(i);
				contents.add(view.isResourceBlank() || view.getAmount() <= 0 ? ItemStack.EMPTY
						: view.getResource().toStack((int) Math.min(view.getAmount(), 99)));
			}

			return new MachineView(level.getBlockState(other).getBlock().getName().getString(), contents);
		}

		return null;
	}

	/** A machine as the mailbox screen shows it. */
	public record MachineView(String name, List<ItemStack> slots) {
	}

	/** Some storage block or machine stands beside the mailbox for what arrives. */
	public static boolean hasChest(ServerLevel level, BlockPos pos) {
		return !beside(level, pos, false).isEmpty();
	}

	/** Some machine stands beside the mailbox. */
	public static boolean hasMachine(ServerLevel level, BlockPos pos) {
		StorageData data = StorageData.get();
		String dim = Rooms.id(level);

		for (Direction side : Direction.values()) {
			BlockPos other = pos.relative(side);

			if (level.isLoaded(other) && isMachine(level, other, data, dim) && ItemStorage.SIDED.find(level, other, Direction.UP) != null) {
				return true;
			}
		}

		return false;
	}

	/** What would be sent: up to {@code max} stacks from the chests beside it (nothing is taken out). */
	public static List<ItemStack> waiting(ServerLevel level, BlockPos pos, int max) {
		List<ItemStack> stacks = new ArrayList<>();

		for (Storage<ItemVariant> storage : beside(level, pos, true)) {
			for (StorageView<ItemVariant> view : storage) {
				if (view.isResourceBlank() || view.getAmount() <= 0) {
					continue;
				}

				ItemStack stack = view.getResource().toStack();
				stacks.add(view.getResource().toStack((int) Math.min(view.getAmount(), stack.getMaxStackSize())));

				if (stacks.size() >= max) {
					return stacks;
				}
			}
		}

		return stacks;
	}

	/** Takes up to {@code max} stacks out of the chests beside the mailbox. */
	public static List<ItemStack> take(ServerLevel level, BlockPos pos, int max) {
		List<ItemStack> taken = new ArrayList<>();

		try (Transaction transaction = Transaction.openOuter()) {
			outer:
			for (Storage<ItemVariant> storage : beside(level, pos, true)) {
				for (StorageView<ItemVariant> view : storage) {
					if (view.isResourceBlank() || view.getAmount() <= 0) {
						continue;
					}

					ItemVariant variant = view.getResource();
					long want = Math.min(view.getAmount(), variant.toStack().getMaxStackSize());
					long got = view.extract(variant, want, transaction);

					if (got > 0) {
						taken.add(variant.toStack((int) got));
					}

					if (taken.size() >= max) {
						break outer;
					}
				}
			}

			transaction.commit();
		}

		return taken;
	}

	/** Puts as much of the stack as fits into the chests beside the mailbox (or only works it out) and says how many. */
	public static int insert(ServerLevel level, BlockPos pos, ItemStack stack, boolean simulate) {
		return insert(level, pos, stack, simulate, -1);
	}

	/**
	 * Like {@link #insert(ServerLevel, BlockPos, ItemStack, boolean)}, but post addressed to a machine slot
	 * ({@code slot} from 0, -1 for none) goes only into that slot of the machine beside the mailbox. Without a machine
	 * that has the slot it goes in as usual.
	 */
	public static int insert(ServerLevel level, BlockPos pos, ItemStack stack, boolean simulate, int slot) {
		if (stack.isEmpty()) {
			return 0;
		}

		List<Storage<ItemVariant>> targets = new ArrayList<>();

		if (slot >= 0) {
			for (BlockPos machine : machines(level, pos)) {
				SlottedStorage<ItemVariant> slotted = slotted(level, machine);

				if (slotted != null && slot < slotted.getSlotCount()) {
					targets.add(slotted.getSlot(slot));
				}
			}
		}

		if (targets.isEmpty()) {
			targets = beside(level, pos, false);
		}

		try (Transaction transaction = Transaction.openOuter()) {
			long stored = 0;

			for (Storage<ItemVariant> storage : targets) {
				stored += storage.insert(ItemVariant.of(stack), stack.getCount() - stored, transaction);

				if (stored >= stack.getCount()) {
					break;
				}
			}

			if (!simulate) {
				transaction.commit();
			}

			return (int) stored;
		}
	}

	// ---- couriers ----

	private static String key(String dim, BlockPos pos) {
		return dim + "@" + pos.asLong();
	}

	/** Another courier is already on its way to fetch the post there. */
	public static boolean claimedByOther(String dim, BlockPos pos, UUID robot) {
		UUID by = CLAIMED.get(key(dim, pos));
		return by != null && !by.equals(robot);
	}

	public static void claim(String dim, BlockPos pos, UUID robot) {
		CLAIMED.put(key(dim, pos), robot);
	}

	public static void release(String dim, BlockPos pos, UUID robot) {
		CLAIMED.remove(key(dim, pos), robot);
	}

	/** The mailbox was broken: nobody fetches anything there any more. */
	public static void removed(String dim, BlockPos pos) {
		CLAIMED.remove(key(dim, pos));
		StorageData data = StorageData.get();

		if (data != null) {
			data.removeMailbox(dim, pos);
		}
	}
}
