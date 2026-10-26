package de.nomisimo.storagedim.tb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Packets for the ME view in the backpack window and for the list of stored items the client needs to tell JEI
 * which recipes can be filled from ME storage.
 */
final class MeNetworking {
	private static final int SYNC_TICKS = 10;
	/** Per player: container id and hash of the stock list last sent, to send only changes. */
	private static final Map<UUID, long[]> SENT = new HashMap<>();

	private MeNetworking() {
	}

	/** Client to server: the ME upgrade's icon was clicked, switch between backpack and ME storage. */
	record TogglePayload() implements CustomPacketPayload {
		static final TogglePayload INSTANCE = new TogglePayload();
		static final Type<TogglePayload> TYPE = new Type<>(StorageTbMod.id("toggle_me_view"));
		static final StreamCodec<RegistryFriendlyByteBuf, TogglePayload> CODEC = StreamCodec.unit(INSTANCE);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client to server: the text in the search field of the ME view changed. */
	record SearchPayload(String text) implements CustomPacketPayload {
		static final Type<SearchPayload> TYPE = new Type<>(StorageTbMod.id("me_view_search"));
		static final StreamCodec<RegistryFriendlyByteBuf, SearchPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(64), SearchPayload::text,
				SearchPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server to client: the backpack window with this id now shows ME storage (or the backpack again). */
	record ViewPayload(int containerId, boolean on) implements CustomPacketPayload {
		static final Type<ViewPayload> TYPE = new Type<>(StorageTbMod.id("me_view"));
		static final StreamCodec<RegistryFriendlyByteBuf, ViewPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ViewPayload::containerId,
				ByteBufCodecs.BOOL, ViewPayload::on,
				ViewPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server to client: everything in ME storage (count-1 stacks and amounts), while a backpack with the ME upgrade is open. */
	record StockPayload(List<ItemStack> items, List<Long> counts) implements CustomPacketPayload {
		static final Type<StockPayload> TYPE = new Type<>(StorageTbMod.id("me_stock"));
		static final StreamCodec<RegistryFriendlyByteBuf, StockPayload> CODEC = StreamCodec.composite(
				ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()), StockPayload::items,
				ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), StockPayload::counts,
				StockPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	static void register() {
		PayloadTypeRegistry.serverboundPlay().register(TogglePayload.TYPE, TogglePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ViewPayload.TYPE, ViewPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StockPayload.TYPE, StockPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SearchPayload.TYPE, SearchPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TogglePayload.TYPE, (payload, context) -> toggle(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(SearchPayload.TYPE, (payload, context) -> {
			MeView view = MeView.of(context.player().containerMenu);

			if (view != null) {
				view.setSearch(context.player(), payload.text());
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(MeNetworking::tick);
	}

	private static boolean hasMe(BackpackBaseMenu menu) {
		return menu.getWrapper().getUpgradeManager().getUpgrade(MeUpgrade.class).isPresent();
	}

	private static void toggle(ServerPlayer player) {
		if (player.containerMenu instanceof BackpackBaseMenu menu && hasMe(menu)) {
			setView(player, menu, !MeView.isOpen(menu));
		}
	}

	private static void setView(ServerPlayer player, BackpackBaseMenu menu, boolean on) {
		// the client swaps its slots first, then gets the new contents
		if (ServerPlayNetworking.canSend(player, ViewPayload.TYPE)) {
			ServerPlayNetworking.send(player, new ViewPayload(menu.containerId, on));
		} else if (on) {
			return;
		}

		MeView.set(menu, on, false);

		if (on) {
			MeView.of(menu).refresh(player);
		}

		menu.sendAllDataToRemote();
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % SYNC_TICKS != 0) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player.containerMenu instanceof BackpackBaseMenu menu) || !hasMe(menu)) {
				if (MeView.isOpen(player.containerMenu) && player.containerMenu instanceof BackpackBaseMenu menu) {
					setView(player, menu, false);
				}

				SENT.remove(player.getUUID());
				continue;
			}

			MeView view = MeView.of(menu);

			if (view != null) {
				view.refresh(player);
			}

			sendStock(player, menu);
		}

		SENT.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	private static void sendStock(ServerPlayer player, BackpackBaseMenu menu) {
		if (!ServerPlayNetworking.canSend(player, StockPayload.TYPE)) {
			return;
		}

		List<ItemStack> items = new ArrayList<>();
		List<Long> counts = new ArrayList<>();
		MeBridge.stored(player, items, counts);
		long hash = 1;

		for (int i = 0; i < items.size(); i++) {
			hash = hash * 31 + ItemStack.hashItemAndComponents(items.get(i));
			hash = hash * 31 + counts.get(i);
		}

		long[] last = SENT.get(player.getUUID());

		if (last != null && last[0] == menu.containerId && last[1] == hash) {
			return;
		}

		SENT.put(player.getUUID(), new long[] {menu.containerId, hash});
		ServerPlayNetworking.send(player, new StockPayload(items, counts));
	}
}
