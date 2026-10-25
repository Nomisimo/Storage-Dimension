package de.nomisimo.storagedim.storage;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optional client UI for the ME terminal. Players who also have the mod on their client get a real search field
 * and sort button in the terminal window; everybody else keeps the sign and comparator buttons.
 */
public final class TerminalNetworking {
	private TerminalNetworking() {
	}

	/** Server to client: the terminal with this container id just opened. */
	public record OpenedPayload(int containerId, String sort) implements CustomPacketPayload {
		public static final Type<OpenedPayload> TYPE = new Type<>(StorageDimensionMod.id("terminal_opened"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenedPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, OpenedPayload::containerId,
				ByteBufCodecs.STRING_UTF8, OpenedPayload::sort,
				OpenedPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client to server: the text in the search field changed. */
	public record SearchPayload(String text) implements CustomPacketPayload {
		public static final Type<SearchPayload> TYPE = new Type<>(StorageDimensionMod.id("terminal_search"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SearchPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), SearchPayload::text,
				SearchPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client to server: the sort button was clicked. The server answers with the new order. */
	public record SortPayload() implements CustomPacketPayload {
		public static final SortPayload INSTANCE = new SortPayload();
		public static final Type<SortPayload> TYPE = new Type<>(StorageDimensionMod.id("terminal_sort"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SortPayload> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server to client: the sort order is now this. */
	public record SortedPayload(String sort) implements CustomPacketPayload {
		public static final Type<SortedPayload> TYPE = new Type<>(StorageDimensionMod.id("terminal_sorted"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SortedPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, SortedPayload::sort,
				SortedPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(OpenedPayload.TYPE, OpenedPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SortedPayload.TYPE, SortedPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SearchPayload.TYPE, SearchPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SortPayload.TYPE, SortPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SearchPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof StorageMenu menu) {
				menu.setSearch(payload.text());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(SortPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof StorageMenu menu) {
				StorageMenu.Sort sort = menu.toggleSort();
				ServerPlayNetworking.send(context.player(), new SortedPayload(sort.name()));
			}
		});
	}

	/** True when the player's client has this mod and can draw the search field. */
	public static boolean hasClientUi(ServerPlayer player) {
		return ServerPlayNetworking.canSend(player, OpenedPayload.TYPE);
	}

	static void sendOpened(ServerPlayer player, int containerId, StorageMenu.Sort sort) {
		ServerPlayNetworking.send(player, new OpenedPayload(containerId, sort.name()));
	}
}
