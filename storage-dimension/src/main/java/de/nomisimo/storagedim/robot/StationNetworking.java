package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.List;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Packets between the robot station screen and its menu. */
public final class StationNetworking {
	private StationNetworking() {
	}

	/** Client to server: a button was pressed (value: +1 / -1 for left / right click) or a name was typed. */
	public record ActionPayload(int containerId, int action, int value, String text) implements CustomPacketPayload {
		public static final Type<ActionPayload> TYPE = new Type<>(StorageDimensionMod.id("station_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ActionPayload::containerId,
				ByteBufCodecs.VAR_INT, ActionPayload::action,
				ByteBufCodecs.VAR_INT, ActionPayload::value,
				ByteBufCodecs.stringUtf8(64), ActionPayload::text,
				ActionPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server to client: the station's settings and its robots (names and what each is doing). */
	public record SyncPayload(int containerId, String stationName, String robotName, int range, int color, boolean exclude,
			List<Component> names, List<Component> states) implements CustomPacketPayload {
		public static final Type<SyncPayload> TYPE = new Type<>(StorageDimensionMod.id("station_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of((buf, payload) -> {
			buf.writeVarInt(payload.containerId);
			buf.writeUtf(payload.stationName);
			buf.writeUtf(payload.robotName);
			buf.writeVarInt(payload.range);
			buf.writeInt(payload.color);
			buf.writeBoolean(payload.exclude);
			buf.writeVarInt(payload.names.size());

			for (int i = 0; i < payload.names.size(); i++) {
				ComponentSerialization.STREAM_CODEC.encode(buf, payload.names.get(i));
				ComponentSerialization.STREAM_CODEC.encode(buf, payload.states.get(i));
			}
		}, buf -> {
			int containerId = buf.readVarInt();
			String stationName = buf.readUtf();
			String robotName = buf.readUtf();
			int range = buf.readVarInt();
			int color = buf.readInt();
			boolean exclude = buf.readBoolean();
			int size = buf.readVarInt();
			List<Component> names = new ArrayList<>();
			List<Component> states = new ArrayList<>();

			for (int i = 0; i < size; i++) {
				names.add(ComponentSerialization.STREAM_CODEC.decode(buf));
				states.add(ComponentSerialization.STREAM_CODEC.decode(buf));
			}

			return new SyncPayload(containerId, stationName, robotName, range, color, exclude, names, states);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		StationMenu.register();
		PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SyncPayload.TYPE, SyncPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof StationMenu menu && menu.containerId == payload.containerId()) {
				menu.action(payload.action(), payload.value(), payload.text());
			}
		});
	}
}
