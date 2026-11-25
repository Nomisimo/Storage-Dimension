package de.nomisimo.storagedim;

import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Settings of the server that players change in the mod settings (Mod Menu): kept in the server's
 * {@code config/storagedim.json}, sent to every player when they join and when one changes.
 */
public final class ServerSettings {
	private ServerSettings() {
	}

	/** Server to client: the settings as they are, and whether this player may change them. */
	public record StatePayload(boolean keepAwake, boolean canEdit) implements CustomPacketPayload {
		public static final Type<StatePayload> TYPE = new Type<>(StorageDimensionMod.id("server_settings"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StatePayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, StatePayload::keepAwake,
				ByteBufCodecs.BOOL, StatePayload::canEdit,
				StatePayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client to server: switch the server's pause without players off (true) or on again. */
	public record KeepAwakePayload(boolean on) implements CustomPacketPayload {
		public static final Type<KeepAwakePayload> TYPE = new Type<>(StorageDimensionMod.id("server_keep_awake"));
		public static final StreamCodec<RegistryFriendlyByteBuf, KeepAwakePayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, KeepAwakePayload::on,
				KeepAwakePayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(KeepAwakePayload.TYPE, KeepAwakePayload.CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> send(handler.player));
		ServerPlayNetworking.registerGlobalReceiver(KeepAwakePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();

			if (!mayEdit(player)) {
				player.sendSystemMessage(Component.literal("Nur Operatoren können die Server-Einstellungen von Storage Dimension ändern.").withStyle(ChatFormatting.RED));
				send(player);
				return;
			}

			StorageConfig.get().keepServerAwake = payload.on();
			StorageConfig.save();
			StorageDimensionMod.LOGGER.info("{} hat die Pause ohne Spieler für Stationen mit geladenem Bereich {}", player.getName().getString(),
					payload.on() ? "abgeschaltet" : "wieder erlaubt");
			MinecraftServer server = player.level().getServer();

			for (ServerPlayer other : server.getPlayerList().getPlayers()) {
				send(other);
			}
		});
	}

	/** Operators may change them; in a single player world (or a LAN world) its players too. */
	static boolean mayEdit(ServerPlayer player) {
		return !player.level().getServer().isDedicatedServer()
				|| player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
	}

	static void send(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, StatePayload.TYPE)) {
			ServerPlayNetworking.send(player, new StatePayload(StorageConfig.get().keepServerAwake, mayEdit(player)));
		}
	}

	/**
	 * The server does not pause without players: the setting is on and some robot station keeps its work area
	 * loaded, so its robots go on working.
	 */
	public static boolean keepsAwake() {
		if (!StorageConfig.get().keepServerAwake || !StorageConfig.get().chunkLoading) {
			return false;
		}

		StorageData data = StorageData.get();
		return data != null && data.loadedStations.values().stream().anyMatch(stations -> !stations.isEmpty());
	}
}
