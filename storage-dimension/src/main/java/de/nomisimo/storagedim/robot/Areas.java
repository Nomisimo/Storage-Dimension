package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Antenna areas on the server: which are switched off by hand or paused by a player in auto mode, the packets
 * for the remote (its screen, its switches) and the outlines drawn for players holding one.
 * Anyone with a remote may switch any area.
 */
public final class Areas {
	/** Players are checked for auto mode this often, and remote holders get the outlines this often. */
	private static final int TICKS = 10;
	/** Areas farther away than this are not listed in the remote nor drawn. */
	public static final int VIEW_DISTANCE = 256;
	public static final int ACTION_TOGGLE = 0;
	public static final int ACTION_AUTO = 1;

	/** Areas paused right now because a player with a remote in auto mode stands in them ("dimension@station"). */
	private static final Set<String> AUTO_PAUSED = new HashSet<>();
	/** The outlines last sent to each player, so unchanged ones are not sent again. */
	private static final Map<UUID, List<Info>> SENT = new HashMap<>();

	private Areas() {
	}

	/** One area as the client sees it: in the remote's list and as an outline. */
	public record Info(long station, String name, int color, boolean enabled, boolean paused, double minX, double minY, double minZ,
			double maxX, double maxY, double maxZ, String size) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Info> CODEC = StreamCodec.of((buf, info) -> {
			buf.writeLong(info.station);
			buf.writeUtf(info.name);
			buf.writeInt(info.color);
			buf.writeBoolean(info.enabled);
			buf.writeBoolean(info.paused);
			buf.writeDouble(info.minX);
			buf.writeDouble(info.minY);
			buf.writeDouble(info.minZ);
			buf.writeDouble(info.maxX);
			buf.writeDouble(info.maxY);
			buf.writeDouble(info.maxZ);
			buf.writeUtf(info.size);
		}, buf -> new Info(buf.readLong(), buf.readUtf(), buf.readInt(), buf.readBoolean(), buf.readBoolean(), buf.readDouble(),
				buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readUtf()));

		public AABB box() {
			return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
		}

		/** Working right now: switched on and not paused by a player in auto mode. */
		public boolean active() {
			return enabled && !paused;
		}
	}

	/** Server to client: the areas around a player holding a remote, for the outlines. */
	public record OutlinePayload(List<Info> areas) implements CustomPacketPayload {
		public static final Type<OutlinePayload> TYPE = new Type<>(StorageDimensionMod.id("area_outlines"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OutlinePayload> CODEC = Info.CODEC
				.apply(net.minecraft.network.codec.ByteBufCodecs.list()).map(OutlinePayload::new, OutlinePayload::areas);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server to client: opens the remote's screen (right-click with it) with the list of areas. */
	public record OpenPayload(int hand, boolean auto, List<Info> areas) implements CustomPacketPayload {
		public static final Type<OpenPayload> TYPE = new Type<>(StorageDimensionMod.id("remote_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenPayload> CODEC = StreamCodec.of((buf, payload) -> {
			buf.writeVarInt(payload.hand);
			buf.writeBoolean(payload.auto);
			buf.writeVarInt(payload.areas.size());
			payload.areas.forEach(info -> Info.CODEC.encode(buf, info));
		}, buf -> {
			int hand = buf.readVarInt();
			boolean auto = buf.readBoolean();
			int size = buf.readVarInt();
			List<Info> areas = new ArrayList<>();

			for (int i = 0; i < size; i++) {
				areas.add(Info.CODEC.decode(buf));
			}

			return new OpenPayload(hand, auto, areas);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client to server: a switch in the remote's screen (an area on or off, or the auto mode of the remote in that hand). */
	public record ActionPayload(int action, int hand, long station, boolean value) implements CustomPacketPayload {
		public static final Type<ActionPayload> TYPE = new Type<>(StorageDimensionMod.id("remote_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.of((buf, payload) -> {
			buf.writeVarInt(payload.action);
			buf.writeVarInt(payload.hand);
			buf.writeLong(payload.station);
			buf.writeBoolean(payload.value);
		}, buf -> new ActionPayload(buf.readVarInt(), buf.readVarInt(), buf.readLong(), buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(OutlinePayload.TYPE, OutlinePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(OpenPayload.TYPE, OpenPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> action(context.player(), payload));
		ServerTickEvents.END_SERVER_TICK.register(Areas::tick);
	}

	private static String key(String dim, BlockPos station) {
		return dim + "@" + station.asLong();
	}

	/** The robots of this station may work: no area, or one that is switched on and not paused. */
	public static boolean working(String dim, BlockPos station) {
		StationArea area = StorageData.get().stationArea(dim, station);
		return !area.connected() || area.enabled() && !AUTO_PAUSED.contains(key(dim, station));
	}

	public static boolean autoPaused(String dim, BlockPos station) {
		return AUTO_PAUSED.contains(key(dim, station));
	}

	/** The station whose antenna stands at {@code pos}, or null. */
	public static BlockPos stationOf(String dim, BlockPos pos) {
		Map<Long, StationArea> areas = StorageData.get().stationAreas.get(dim);

		if (areas == null) {
			return null;
		}

		for (Map.Entry<Long, StationArea> entry : areas.entrySet()) {
			BlockPos station = BlockPos.of(entry.getKey());

			if (entry.getValue().base() && station.above().equals(pos) || entry.getValue().corner().map(pos::equals).orElse(false)) {
				return station;
			}
		}

		return null;
	}

	/** Antennas just taken off their station ("dimension@position" to station), so the dropped item stays paired. */
	private static final Map<String, BlockPos> REMOVED = new HashMap<>();

	/** The station a broken antenna belonged to, while its drops are worked out; null when unknown. */
	public static BlockPos removedStation(String dim, BlockPos pos) {
		BlockPos station = stationOf(dim, pos);
		return station != null ? station : REMOVED.remove(dim + "@" + pos.asLong());
	}

	/** An antenna was broken (or vanished): its station loses it. */
	public static void antennaRemoved(String dim, BlockPos pos) {
		BlockPos station = stationOf(dim, pos);

		if (station == null) {
			return;
		}

		if (REMOVED.size() > 64) {
			REMOVED.clear();
		}

		REMOVED.put(dim + "@" + pos.asLong(), station);

		StorageData data = StorageData.get();
		StationArea area = data.stationArea(dim, station);
		data.setStationArea(dim, station, station.above().equals(pos) ? area.withBase(false) : area.withCorner(null));
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % TICKS != 0 || StorageData.get() == null) {
			return;
		}

		checkAntennas(server);
		AUTO_PAUSED.clear();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isSpectator() || !hasAutoRemote(player)) {
				continue;
			}

			String dim = Rooms.id(player.level());
			Map<Long, StationArea> areas = StorageData.get().stationAreas.getOrDefault(dim, Map.of());
			areas.forEach((pos, area) -> {
				BlockPos station = BlockPos.of(pos);

				if (area.connected() && area.contains(station, player.position())) {
					AUTO_PAUSED.add(key(dim, station));
				}
			});
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean holding = RemoteItem.isRemote(player.getMainHandItem()) || RemoteItem.isRemote(player.getOffhandItem());
			List<Info> areas = holding ? list(player) : List.of();

			if (!Objects.equals(SENT.get(player.getUUID()), areas)) {
				SENT.put(player.getUUID(), areas);
				ServerPlayNetworking.send(player, new OutlinePayload(areas));
			}
		}

		SENT.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	/** Antennas that are gone (broken by an explosion, moved by a piston) are taken off their station where the chunk is loaded. */
	private static void checkAntennas(MinecraftServer server) {
		if (server.getTickCount() % (TICKS * 4) != 0) {
			return;
		}

		StorageData data = StorageData.get();

		for (Map.Entry<String, Map<Long, StationArea>> dim : data.stationAreas.entrySet()) {
			ServerLevel level = Rooms.levelById(server, dim.getKey());

			if (level == null) {
				continue;
			}

			for (Map.Entry<Long, StationArea> entry : new ArrayList<>(dim.getValue().entrySet())) {
				BlockPos station = BlockPos.of(entry.getKey());
				StationArea area = entry.getValue();
				StationArea checked = area;

				if (area.base() && level.isLoaded(station.above()) && !level.getBlockState(station.above()).is(AntennaBlock.BLOCK)) {
					checked = checked.withBase(false);
				}

				if (area.corner().isPresent() && level.isLoaded(area.corner().get()) && !level.getBlockState(area.corner().get()).is(AntennaBlock.BLOCK)) {
					checked = checked.withCorner(null);
				}

				if (!checked.equals(area)) {
					data.setStationArea(dim.getKey(), station, checked);
				}
			}
		}
	}

	private static boolean hasAutoRemote(ServerPlayer player) {
		if (RemoteItem.isAuto(player.getOffhandItem())) {
			return true;
		}

		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (RemoteItem.isAuto(player.getInventory().getItem(i))) {
				return true;
			}
		}

		return false;
	}

	/** The connected areas around the player, closest first. */
	public static List<Info> list(ServerPlayer player) {
		String dim = Rooms.id(player.level());
		StorageData data = StorageData.get();
		List<Info> areas = new ArrayList<>();

		data.stationAreas.getOrDefault(dim, Map.of()).forEach((pos, area) -> {
			BlockPos station = BlockPos.of(pos);

			if (!area.connected() || station.distToCenterSqr(player.position()) > (double) VIEW_DISTANCE * VIEW_DISTANCE) {
				return;
			}

			RobotConfig config = data.station(dim, station);
			String name = !area.name().isEmpty() ? area.name() : !config.stationName().isEmpty() ? config.stationName()
					: "Area " + station.getX() + " " + station.getY() + " " + station.getZ();
			AABB box = area.box(station);
			areas.add(new Info(pos, name, config.color(), area.enabled(), AUTO_PAUSED.contains(key(dim, station)),
					box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, area.sizeText(station)));
		});

		areas.sort(Comparator.comparingDouble(info -> BlockPos.of(info.station()).distToCenterSqr(player.position())));
		return areas;
	}

	/** Opens the remote's screen for the remote in this hand. */
	public static void open(ServerPlayer player, InteractionHand hand) {
		ItemStack remote = player.getItemInHand(hand);
		ServerPlayNetworking.send(player, new OpenPayload(hand.ordinal(), RemoteItem.isAuto(remote), list(player)));
	}

	private static void action(ServerPlayer player, ActionPayload payload) {
		InteractionHand hand = payload.hand() == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		ItemStack remote = player.getItemInHand(hand);

		if (!RemoteItem.isRemote(remote)) {
			return;
		}

		if (payload.action() == ACTION_AUTO) {
			RemoteItem.setAuto(remote, payload.value());
		} else if (payload.action() == ACTION_TOGGLE) {
			String dim = Rooms.id(player.level());
			BlockPos station = BlockPos.of(payload.station());
			StationArea area = StorageData.get().stationArea(dim, station);

			if (!area.connected()) {
				return;
			}

			StorageData.get().setStationArea(dim, station, area.withEnabled(payload.value()));
		}

		// the screen shows the new state at once; the outlines follow
		SENT.remove(player.getUUID());
		open(player, hand);
	}
}
