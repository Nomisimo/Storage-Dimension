package de.nomisimo.storagedim.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import de.nomisimo.storagedim.StorageConfig;
import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.robot.RobotConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * All persistent state of the mod, stored as storagedim.json in the world folder.
 */
public final class StorageData {
	/** Owner id of the one network everybody uses when sharedStorage is on. */
	public static final UUID SHARED = new UUID(0L, 0L);
	private static final Gson GSON = new GsonBuilder().create();
	private static StorageData instance;
	private static boolean dirty;

	private final MinecraftServer server;
	private final Path file;

	public final Map<UUID, StorageNetwork> networks = new HashMap<>();
	/** Player to the network owner whose network they joined. */
	public final Map<UUID, UUID> memberOf = new HashMap<>();
	/** Dimension id to (packed block pos to chest owner). */
	public final Map<String, Map<Long, UUID>> chests = new HashMap<>();
	/** Dimension id to (packed block pos to filter chest). */
	public final Map<String, Map<Long, FilterChests.FilterChest>> filterChests = new HashMap<>();
	/** Dimension id to packed positions of placed ME terminals. */
	public final Map<String, Set<Long>> terminals = new HashMap<>();
	/** Settings of each robot station per dimension, applied to robots configured there. */
	public final Map<String, Map<Long, RobotConfig>> stations = new HashMap<>();
	/** Robots placed for each station per dimension: robot id to its name, in the order they were placed. */
	public final Map<String, Map<Long, Map<UUID, String>>> stationRobots = new HashMap<>();
	/** Where to send a player back to when they leave the storage room. */
	public final Map<UUID, SavedPos> returns = new HashMap<>();
	/**
	 * Players who were watching through a chest in an older version; they get their position and
	 * game mode back on their next join.
	 */
	public final Map<UUID, Viewer> viewers = new HashMap<>();
	public int nextRoom;

	public record SavedPos(String dimension, double x, double y, double z, float yRot, float xRot) {
	}

	public record Viewer(SavedPos back, String gameMode, UUID network) {
	}

	private StorageData(MinecraftServer server) {
		this.server = server;
		this.file = server.getWorldPath(LevelResource.ROOT).resolve("storagedim.json");
	}

	public static StorageData get() {
		return instance;
	}

	public static void markDirty() {
		dirty = true;
	}

	public static void load(MinecraftServer server) {
		instance = new StorageData(server);
		dirty = false;

		if (Files.exists(instance.file)) {
			try (Reader reader = Files.newBufferedReader(instance.file, StandardCharsets.UTF_8)) {
				instance.read(GSON.fromJson(reader, JsonObject.class));
			} catch (Exception e) {
				StorageDimensionMod.LOGGER.error("storagedim.json konnte nicht gelesen werden", e);
			}
		}
	}

	public static void unload() {
		save(true);
		instance = null;
	}

	public static void save(boolean force) {
		if (instance == null || (!dirty && !force)) {
			return;
		}

		Path tmp = instance.file.resolveSibling("storagedim.json.tmp");

		try {
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(instance.write(), writer);
			}

			Files.move(tmp, instance.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			StorageDimensionMod.LOGGER.error("storagedim.json konnte nicht gespeichert werden", e);
		}
	}

	// ---- networks ----

	/** The network a player currently uses (a joined one, or their own). */
	public StorageNetwork networkFor(UUID player) {
		if (StorageConfig.get().sharedStorage) {
			return ownNetwork(SHARED);
		}

		UUID joined = memberOf.get(player);

		if (joined != null) {
			StorageNetwork network = networks.get(joined);

			if (network != null && network.members.contains(player)) {
				return network;
			}

			memberOf.remove(player);
			markDirty();
		}

		return ownNetwork(player);
	}

	public StorageNetwork ownNetwork(UUID player) {
		return networks.computeIfAbsent(player, id -> {
			markDirty();
			return new StorageNetwork(id, nextRoom++);
		});
	}

	public StorageNetwork networkByRoom(int room) {
		for (StorageNetwork network : networks.values()) {
			if (network.room == room) {
				return network;
			}
		}

		return null;
	}

	// ---- chests ----

	public UUID chestOwner(String dimension, BlockPos pos) {
		Map<Long, UUID> map = chests.get(dimension);
		return map == null ? null : map.get(pos.asLong());
	}

	public void setChest(String dimension, BlockPos pos, UUID owner) {
		chests.computeIfAbsent(dimension, d -> new HashMap<>()).put(pos.asLong(), owner);
		ChestMarks.changed(dimension);
		markDirty();
	}

	public void removeChest(String dimension, BlockPos pos) {
		Map<Long, UUID> map = chests.get(dimension);

		if (map != null && map.remove(pos.asLong()) != null) {
			ChestMarks.changed(dimension);
			markDirty();
		}
	}

	// ---- filter chests ----

	public FilterChests.FilterChest filterChest(String dimension, BlockPos pos) {
		Map<Long, FilterChests.FilterChest> map = filterChests.get(dimension);
		return map == null ? null : map.get(pos.asLong());
	}

	public void setFilterChest(String dimension, BlockPos pos, FilterChests.FilterChest chest) {
		filterChests.computeIfAbsent(dimension, d -> new HashMap<>()).put(pos.asLong(), chest);
		ChestMarks.changed(dimension);
		markDirty();
	}

	public void removeFilterChest(String dimension, BlockPos pos) {
		Map<Long, FilterChests.FilterChest> map = filterChests.get(dimension);

		if (map != null && map.remove(pos.asLong()) != null) {
			ChestMarks.changed(dimension);
			markDirty();
		}
	}

	// ---- terminals ----

	/** The settings of the station at {@code pos}, with defaults for a new one. */
	public RobotConfig station(String dimension, BlockPos pos) {
		return stations.getOrDefault(dimension, Map.of()).getOrDefault(pos.asLong(), RobotConfig.DEFAULT).withStation(dimension, pos);
	}

	public void setStation(String dimension, BlockPos pos, RobotConfig config) {
		stations.computeIfAbsent(dimension, d -> new HashMap<>()).put(pos.asLong(), config);
		markDirty();
	}

	/** The robots placed for the station at {@code pos} (id to name); changes must be followed by {@link #markDirty()}. */
	public Map<UUID, String> stationRobots(String dimension, BlockPos pos) {
		return stationRobots.computeIfAbsent(dimension, d -> new HashMap<>()).computeIfAbsent(pos.asLong(), p -> new LinkedHashMap<>());
	}

	public boolean isTerminal(String dimension, BlockPos pos) {
		Set<Long> set = terminals.get(dimension);
		return set != null && set.contains(pos.asLong());
	}

	public void setTerminal(String dimension, BlockPos pos, boolean present) {
		Set<Long> set = terminals.computeIfAbsent(dimension, d -> new HashSet<>());

		if (present ? set.add(pos.asLong()) : set.remove(pos.asLong())) {
			ChestMarks.changed(dimension);
			markDirty();
		}
	}

	// ---- json ----

	private DynamicOps<JsonElement> ops() {
		return server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
	}

	private JsonObject write() {
		DynamicOps<JsonElement> ops = ops();
		JsonObject root = new JsonObject();
		root.addProperty("nextRoom", nextRoom);

		JsonArray nets = new JsonArray();

		for (StorageNetwork network : networks.values()) {
			JsonObject n = new JsonObject();
			n.addProperty("owner", network.owner.toString());
			n.addProperty("room", network.room);
			JsonArray members = new JsonArray();
			network.members.forEach(m -> members.add(m.toString()));
			n.add("members", members);
			JsonArray items = new JsonArray();

			for (StorageNetwork.StoredItem entry : network.items) {
				ItemStack.CODEC.encodeStart(ops, entry.prototype).result().ifPresent(json -> {
					JsonObject item = new JsonObject();
					item.add("stack", json);
					item.addProperty("count", entry.count);
					items.add(item);
				});
			}

			n.add("items", items);
			nets.add(n);
		}

		root.add("networks", nets);

		JsonObject memberObj = new JsonObject();
		memberOf.forEach((player, owner) -> memberObj.addProperty(player.toString(), owner.toString()));
		root.add("memberOf", memberObj);

		JsonObject chestObj = new JsonObject();
		chests.forEach((dim, map) -> {
			JsonObject d = new JsonObject();
			map.forEach((pos, owner) -> d.addProperty(Long.toString(pos), owner.toString()));
			chestObj.add(dim, d);
		});
		root.add("chests", chestObj);

		JsonObject filterObj = new JsonObject();
		filterChests.forEach((dim, map) -> {
			JsonObject d = new JsonObject();
			map.forEach((pos, chest) -> {
				JsonObject c = new JsonObject();
				c.addProperty("owner", chest.owner().toString());
				JsonArray slots = new JsonArray();

				for (int i = 0; i < chest.filters().size(); i++) {
					ItemStack filter = chest.filters().get(i);
					int slot = i;

					if (!filter.isEmpty()) {
						ItemStack.CODEC.encodeStart(ops, filter).result().ifPresent(json -> {
							JsonObject entry = new JsonObject();
							entry.addProperty("slot", slot);
							entry.add("stack", json);
							slots.add(entry);
						});
					}
				}

				c.add("filters", slots);
				d.add(Long.toString(pos), c);
			});
			filterObj.add(dim, d);
		});
		root.add("filterChests", filterObj);

		JsonObject terminalObj = new JsonObject();
		terminals.forEach((dim, set) -> {
			JsonArray arr = new JsonArray();
			set.forEach(arr::add);
			terminalObj.add(dim, arr);
		});
		root.add("terminals", terminalObj);

		JsonObject stationObj = new JsonObject();
		stations.forEach((dim, map) -> {
			JsonObject d = new JsonObject();
			map.forEach((pos, config) -> RobotConfig.CODEC.encodeStart(ops, config).result().ifPresent(json -> d.add(Long.toString(pos), json)));
			stationObj.add(dim, d);
		});
		root.add("stations", stationObj);

		JsonObject stationRobotObj = new JsonObject();
		stationRobots.forEach((dim, map) -> {
			JsonObject d = new JsonObject();
			map.forEach((pos, robots) -> {
				JsonObject r = new JsonObject();
				robots.forEach((id, name) -> r.addProperty(id.toString(), name));
				d.add(Long.toString(pos), r);
			});
			stationRobotObj.add(dim, d);
		});
		root.add("stationRobots", stationRobotObj);

		JsonObject returnObj = new JsonObject();
		returns.forEach((player, pos) -> returnObj.add(player.toString(), writePos(pos)));
		root.add("returns", returnObj);

		JsonObject viewerObj = new JsonObject();
		viewers.forEach((player, viewer) -> {
			JsonObject v = new JsonObject();
			v.add("back", writePos(viewer.back()));
			v.addProperty("gameMode", viewer.gameMode());
			v.addProperty("network", viewer.network().toString());
			viewerObj.add(player.toString(), v);
		});
		root.add("viewers", viewerObj);
		return root;
	}

	private void read(JsonObject root) {
		if (root == null) {
			return;
		}

		DynamicOps<JsonElement> ops = ops();
		nextRoom = root.has("nextRoom") ? root.get("nextRoom").getAsInt() : 0;

		if (root.has("networks")) {
			for (JsonElement el : root.getAsJsonArray("networks")) {
				JsonObject n = el.getAsJsonObject();
				StorageNetwork network = new StorageNetwork(UUID.fromString(n.get("owner").getAsString()), n.get("room").getAsInt());

				for (JsonElement m : n.getAsJsonArray("members")) {
					network.members.add(UUID.fromString(m.getAsString()));
				}

				for (JsonElement i : n.getAsJsonArray("items")) {
					JsonObject item = i.getAsJsonObject();
					long count = item.get("count").getAsLong();
					ItemStack.CODEC.parse(ops, item.get("stack")).resultOrPartial(StorageDimensionMod.LOGGER::warn)
							.ifPresent(stack -> network.items.add(new StorageNetwork.StoredItem(stack.copyWithCount(1), count)));
				}

				networks.put(network.owner, network);
				nextRoom = Math.max(nextRoom, network.room + 1);
			}
		}

		if (root.has("memberOf")) {
			root.getAsJsonObject("memberOf").entrySet().forEach(e ->
					memberOf.put(UUID.fromString(e.getKey()), UUID.fromString(e.getValue().getAsString())));
		}

		if (root.has("chests")) {
			root.getAsJsonObject("chests").entrySet().forEach(dim -> {
				Map<Long, UUID> map = new HashMap<>();
				dim.getValue().getAsJsonObject().entrySet().forEach(e ->
						map.put(Long.parseLong(e.getKey()), UUID.fromString(e.getValue().getAsString())));
				chests.put(dim.getKey(), map);
			});
		}

		if (root.has("filterChests")) {
			root.getAsJsonObject("filterChests").entrySet().forEach(dim -> {
				Map<Long, FilterChests.FilterChest> map = new HashMap<>();
				dim.getValue().getAsJsonObject().entrySet().forEach(e -> {
					JsonObject c = e.getValue().getAsJsonObject();
					FilterChests.FilterChest chest = FilterChests.FilterChest.empty(UUID.fromString(c.get("owner").getAsString()));

					for (JsonElement el : c.getAsJsonArray("filters")) {
						JsonObject entry = el.getAsJsonObject();
						int slot = entry.get("slot").getAsInt();

						if (slot >= 0 && slot < FilterChests.DOUBLE_SLOTS) {
							if (slot >= chest.filters().size()) {
								chest.resize(FilterChests.DOUBLE_SLOTS);
							}

							ItemStack.CODEC.parse(ops, entry.get("stack")).resultOrPartial(StorageDimensionMod.LOGGER::warn)
									.ifPresent(stack -> chest.filters().set(slot, stack.copyWithCount(1)));
						}
					}

					map.put(Long.parseLong(e.getKey()), chest);
				});
				filterChests.put(dim.getKey(), map);
			});
		}

		if (root.has("terminals")) {
			root.getAsJsonObject("terminals").entrySet().forEach(dim -> {
				Set<Long> set = new HashSet<>();
				dim.getValue().getAsJsonArray().forEach(e -> set.add(e.getAsLong()));
				terminals.put(dim.getKey(), set);
			});
		}

		if (root.has("stations")) {
			root.getAsJsonObject("stations").entrySet().forEach(dim -> {
				Map<Long, RobotConfig> map = new HashMap<>();
				dim.getValue().getAsJsonObject().entrySet().forEach(e -> RobotConfig.CODEC.parse(ops, e.getValue())
						.resultOrPartial(StorageDimensionMod.LOGGER::warn).ifPresent(config -> map.put(Long.parseLong(e.getKey()), config)));
				stations.put(dim.getKey(), map);
			});
		}

		if (root.has("stationRobots")) {
			root.getAsJsonObject("stationRobots").entrySet().forEach(dim -> {
				Map<Long, Map<UUID, String>> map = new HashMap<>();
				dim.getValue().getAsJsonObject().entrySet().forEach(e -> {
					Map<UUID, String> robots = new LinkedHashMap<>();
					e.getValue().getAsJsonObject().entrySet().forEach(r -> robots.put(UUID.fromString(r.getKey()), r.getValue().getAsString()));
					map.put(Long.parseLong(e.getKey()), robots);
				});
				stationRobots.put(dim.getKey(), map);
			});
		}

		if (root.has("returns")) {
			root.getAsJsonObject("returns").entrySet().forEach(e ->
					returns.put(UUID.fromString(e.getKey()), readPos(e.getValue().getAsJsonObject())));
		}

		if (root.has("viewers")) {
			root.getAsJsonObject("viewers").entrySet().forEach(e -> {
				JsonObject v = e.getValue().getAsJsonObject();
				viewers.put(UUID.fromString(e.getKey()), new Viewer(readPos(v.getAsJsonObject("back")),
						v.get("gameMode").getAsString(), UUID.fromString(v.get("network").getAsString())));
			});
		}
	}

	private static JsonObject writePos(SavedPos pos) {
		JsonObject o = new JsonObject();
		o.addProperty("dim", pos.dimension());
		o.addProperty("x", pos.x());
		o.addProperty("y", pos.y());
		o.addProperty("z", pos.z());
		o.addProperty("yRot", pos.yRot());
		o.addProperty("xRot", pos.xRot());
		return o;
	}

	private static SavedPos readPos(JsonObject o) {
		return new SavedPos(o.get("dim").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(),
				o.get("z").getAsDouble(), o.get("yRot").getAsFloat(), o.get("xRot").getAsFloat());
	}
}
