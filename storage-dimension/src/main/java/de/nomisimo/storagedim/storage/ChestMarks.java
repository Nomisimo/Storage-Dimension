package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells clients which chests in their dimension are dimension chests, filter chests or ME terminals,
 * so the client mod can draw them with their own textures.
 */
public final class ChestMarks {
	private static final Set<String> CHANGED = new HashSet<>();
	private static final Map<UUID, String> SENT = new HashMap<>();

	private ChestMarks() {
	}

	public record MarksPayload(String dimension, List<Long> storage, List<Long> filter, List<Long> terminal) implements CustomPacketPayload {
		public static final Type<MarksPayload> TYPE = new Type<>(StorageDimensionMod.id("chest_marks"));
		private static final StreamCodec<RegistryFriendlyByteBuf, List<Long>> LONGS = ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list());
		public static final StreamCodec<RegistryFriendlyByteBuf, MarksPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, MarksPayload::dimension,
				LONGS, MarksPayload::storage,
				LONGS, MarksPayload::filter,
				LONGS, MarksPayload::terminal,
				MarksPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(MarksPayload.TYPE, MarksPayload.CODEC);
	}

	/** Called whenever a special chest in this dimension is added or removed. */
	static void changed(String dimension) {
		CHANGED.add(dimension);
	}

	/** Sends the marks to players who changed dimension, joined, or whose dimension changed. */
	static void tick(MinecraftServer server) {
		StorageData data = StorageData.get();

		if (data == null) {
			return;
		}

		SENT.keySet().removeIf(uuid -> server.getPlayerList().getPlayer(uuid) == null);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			String dim = Rooms.id(player.level());

			if ((CHANGED.contains(dim) || !dim.equals(SENT.get(player.getUUID()))) && ServerPlayNetworking.canSend(player, MarksPayload.TYPE)) {
				ServerPlayNetworking.send(player, payload(data, dim));
				SENT.put(player.getUUID(), dim);
			}
		}

		CHANGED.clear();
	}

	private static MarksPayload payload(StorageData data, String dim) {
		Map<Long, UUID> chests = data.chests.get(dim);
		Map<Long, FilterChests.FilterChest> filters = data.filterChests.get(dim);
		Set<Long> terminals = data.terminals.get(dim);
		return new MarksPayload(dim,
				chests == null ? List.of() : new ArrayList<>(chests.keySet()),
				filters == null ? List.of() : new ArrayList<>(filters.keySet()),
				terminals == null ? List.of() : new ArrayList<>(terminals));
	}
}
