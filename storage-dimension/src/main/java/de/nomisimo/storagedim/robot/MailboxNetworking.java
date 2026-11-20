package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.List;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Packets of the mailbox screen: the server opens it with the mailbox's addresses, the screen sends them back changed. */
public final class MailboxNetworking {
	private MailboxNetworking() {
	}

	/**
	 * Server to client: open the screen of the mailbox at {@code pos}; {@code state} says what is going on there,
	 * {@code machine} names the machine beside it (empty without one) and {@code slots} is what lies in its slots.
	 */
	public record OpenPayload(long pos, String address, String destination, String state, String machine, List<ItemStack> slots)
			implements CustomPacketPayload {
		public static final Type<OpenPayload> TYPE = new Type<>(StorageDimensionMod.id("mailbox_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> CODEC = StreamCodec.of((buf, payload) -> {
			buf.writeVarLong(payload.pos);
			buf.writeUtf(payload.address, 64);
			buf.writeUtf(payload.destination, 64);
			buf.writeUtf(payload.state, 512);
			buf.writeUtf(payload.machine, 128);
			buf.writeVarInt(payload.slots.size());

			for (ItemStack stack : payload.slots) {
				ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
			}
		}, buf -> {
			long pos = buf.readVarLong();
			String address = buf.readUtf(64);
			String destination = buf.readUtf(64);
			String state = buf.readUtf(512);
			String machine = buf.readUtf(128);
			int size = Math.min(buf.readVarInt(), Mailbox.MAX_MACHINE_SLOTS);
			List<ItemStack> slots = new ArrayList<>();

			for (int i = 0; i < size; i++) {
				slots.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
			}

			return new OpenPayload(pos, address, destination, state, machine, slots);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client to server: the addresses typed into the screen. */
	public record SavePayload(long pos, String address, String destination) implements CustomPacketPayload {
		public static final Type<SavePayload> TYPE = new Type<>(StorageDimensionMod.id("mailbox_save"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SavePayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_LONG, SavePayload::pos,
				ByteBufCodecs.stringUtf8(64), SavePayload::address,
				ByteBufCodecs.stringUtf8(64), SavePayload::destination,
				SavePayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(OpenPayload.TYPE, OpenPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SavePayload.TYPE, SavePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SavePayload.TYPE, (payload, context) -> save(context.player(), payload));
	}

	public static void open(ServerPlayer player, BlockPos pos) {
		StorageData data = StorageData.get();
		ServerLevel level = (ServerLevel) player.level();
		String dim = Rooms.id(level);

		if (data == null) {
			return;
		}

		Mailbox mailbox = data.mailbox(dim, pos);

		if (mailbox == null) {
			// set before the mailbox data existed (or by a command): it gets a number now
			mailbox = new Mailbox("Briefkasten " + (data.mailboxes(dim).size() + 1), "");
			data.setMailbox(dim, pos, mailbox);
		}

		send(player, level, pos, mailbox);
	}

	private static void send(ServerPlayer player, ServerLevel level, BlockPos pos, Mailbox mailbox) {
		Mailbox.MachineView machine = Mailbox.machine(level, pos);
		ServerPlayNetworking.send(player, new OpenPayload(pos.asLong(), mailbox.address(), mailbox.destination(), state(level, pos, mailbox),
				machine == null ? "" : machine.name(), machine == null ? List.of() : machine.slots()));
	}

	/** What the screen shows below the fields: chest beside it, post waiting, destination found or not. */
	public static String state(ServerLevel level, BlockPos pos, Mailbox mailbox) {
		if (!Mailbox.hasChest(level, pos)) {
			return "§cKeine Kiste und keine Maschine daneben: stell eine Kiste, ein Fass oder eine Maschine (z. B. einen Ofen) direkt an den Briefkasten.";
		}

		List<ItemStack> waiting = Mailbox.waiting(level, pos, Mailbox.MAX_STACKS);
		String post = waiting.isEmpty() ? "Keine Post da." : "Post wartet: " + waiting.size() + (waiting.size() >= Mailbox.MAX_STACKS ? "+" : "")
				+ (waiting.size() == 1 ? " Stapel." : " Stapel.");

		if (mailbox.destination().isEmpty()) {
			return "Empfängt nur (kein Ziel). Was ankommt, legt er in die Kiste daneben.";
		}

		BlockPos target = Mailbox.resolve(level, pos, other -> true);

		if (target == null) {
			String name = Mailbox.pattern(mailbox.destination());
			return (Mailbox.known(Rooms.id(level), name) ? "§6Ziel „" + name + "“ ist nicht geladen. "
					: "§cKein Briefkasten heißt „" + name + "“. ") + "§r" + post;
		}

		int distance = (int) Math.round(Math.sqrt(target.distSqr(pos)));
		int slot = Mailbox.slot(mailbox.destination());
		String into = slot < 0 ? "" : Mailbox.machine(level, target) == null ? ", §6Slot " + (slot + 1) + ": dort steht keine Maschine, kommt in die Kiste§r"
				: ", in Slot " + (slot + 1) + " der Maschine dort";
		return "Ziel: " + Mailbox.label(level, target) + " (" + distance + " m" + into + "). " + post
				+ " Abholen: Roboter einer Station im Modus Kurier, in deren Bereich beide Briefkästen stehen.";
	}

	private static void save(ServerPlayer player, SavePayload payload) {
		StorageData data = StorageData.get();
		BlockPos pos = BlockPos.of(payload.pos());
		ServerLevel level = (ServerLevel) player.level();

		if (data == null || !level.getBlockState(pos).is(MailboxBlock.BLOCK) || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64) {
			return;
		}

		String dim = Rooms.id(level);
		Mailbox mailbox = new Mailbox(clean(payload.address()), clean(payload.destination()));
		data.setMailbox(dim, pos, mailbox);
		// the screen shows the new state (destination found or not)
		send(player, level, pos, mailbox);
	}

	public static String clean(String text) {
		String trimmed = text.strip();
		return trimmed.length() > Mailbox.MAX_ADDRESS ? trimmed.substring(0, Mailbox.MAX_ADDRESS) : trimmed;
	}
}
