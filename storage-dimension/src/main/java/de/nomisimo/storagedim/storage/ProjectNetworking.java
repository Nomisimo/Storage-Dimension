package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.List;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** Packets between the project settings screen and {@link ProjectSettingsMenu}. */
public final class ProjectNetworking {
	private ProjectNetworking() {
	}

	/** Client to server: a button, a typed amount or name, or an item to add (from the search or JEI). */
	public record ActionPayload(int containerId, int action, int index, long value, String text, ItemStack stack) implements CustomPacketPayload {
		public static final Type<ActionPayload> TYPE = new Type<>(StorageDimensionMod.id("project_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.of((buf, payload) -> {
			buf.writeVarInt(payload.containerId);
			buf.writeVarInt(payload.action);
			buf.writeVarInt(payload.index);
			buf.writeVarLong(payload.value);
			buf.writeUtf(payload.text, 64);
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack);
		}, buf -> new ActionPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(), buf.readUtf(64),
				ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server to client: the project's name, switch and list, with how many of each item the ME storage holds and how
	 * many were taken out of the project chest already.
	 */
	public record SyncPayload(int containerId, String name, boolean block, List<ItemStack> items, List<Long> targets, List<Long> have,
			List<Long> taken)
			implements CustomPacketPayload {
		public static final Type<SyncPayload> TYPE = new Type<>(StorageDimensionMod.id("project_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of((buf, payload) -> {
			buf.writeVarInt(payload.containerId);
			buf.writeUtf(payload.name);
			buf.writeBoolean(payload.block);
			buf.writeVarInt(payload.items.size());

			for (int i = 0; i < payload.items.size(); i++) {
				ItemStack.STREAM_CODEC.encode(buf, payload.items.get(i));
				buf.writeVarLong(payload.targets.get(i));
				buf.writeVarLong(payload.have.get(i));
				buf.writeVarLong(payload.taken.get(i));
			}
		}, buf -> {
			int containerId = buf.readVarInt();
			String name = buf.readUtf();
			boolean block = buf.readBoolean();
			int size = buf.readVarInt();
			List<ItemStack> items = new ArrayList<>();
			List<Long> targets = new ArrayList<>();
			List<Long> have = new ArrayList<>();
			List<Long> taken = new ArrayList<>();

			for (int i = 0; i < size; i++) {
				items.add(ItemStack.STREAM_CODEC.decode(buf));
				targets.add(buf.readVarLong());
				have.add(buf.readVarLong());
				taken.add(buf.readVarLong());
			}

			return new SyncPayload(containerId, name, block, items, targets, have, taken);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		ProjectSettingsMenu.register();
		PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof ProjectSettingsMenu menu && menu.containerId == payload.containerId()) {
				menu.action(payload.action(), payload.index(), payload.value(), payload.text(), payload.stack());
			}
		});
	}
}
