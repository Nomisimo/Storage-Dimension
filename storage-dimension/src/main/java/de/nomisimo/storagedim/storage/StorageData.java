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
import net.minecraft.core.Direction;
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
	/** Every project by id, see {@link Projects}. */
	public final Map<UUID, Projects.Project> projects = new LinkedHashMap<>();
	/** Dimension id to (packed block pos to the project of the placed project chest). */
	public final Map<String, Map<Long, UUID>> projectChests = new HashMap<>();
	/** Dimension id to packed positions of placed ME terminals. */
	public final Map<String, Set<Long>> terminals = new HashMap<>();
	/** Dimension id to packed position to the player who placed the terminal; whose storage hoppers fill. */
	public final Map<String, Map<Long, UUID>> terminalOwners = new HashMap<>();
	/** Settings of each robot station per dimension, applied to robots configured there. */
	public final Map<String, Map<Long, RobotConfig>> stations = new HashMap<>();
	/** Robots placed for each station per dimension: robot id to its name, in the order they were placed. */
	public final Map<String, Map<Long, Map<UUID, String>>> stationRobots = new HashMap<>();
	/** The antenna area of each robot station per dimension (antennas, area name, switched on or off). */
	public final Map<String, Map<Long, de.nomisimo.storagedim.robot.StationArea>> stationAreas = new HashMap<>();
	/** Robot stations per dimension that keep their work area loaded (switched on at the station). */
	public final Map<String, Set<Long>> loadedStations = new HashMap<>();
	/** Chunks per dimension this mod force-loads for those stations, so only these are released again. */
	public final Map<String, Set<Long>> forcedChunks = new HashMap<>();
	/** Where to send a player back to when they leave the library. */
	public final Map<UUID, SavedPos> returns = new HashMap<>();
	/**
	 * Players who were watching through a chest in an older version; they get their position and
	 * game mode back on their next join.
	 */
	public final Map<UUID, Viewer> viewers = new HashMap<>();
	public int nextRoom;
	/** The rooms of versions before the chest island were taken down. */
	public boolean oldRoomsCleared;

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

	// ---- project chests ----

	public Projects.Project projectChest(String dimension, BlockPos pos) {
		Map<Long, UUID> map = projectChests.get(dimension);
		return map == null ? null : projects.get(map.get(pos.asLong()));
	}

	public void setProjectChest(String dimension, BlockPos pos, Projects.Project project) {
		projectChests.computeIfAbsent(dimension, d -> new HashMap<>()).put(pos.asLong(), project.id);
		ChestMarks.changed(dimension);
		markDirty();
	}

	public void removeProjectChest(String dimension, BlockPos pos) {
		Map<Long, UUID> map = projectChests.get(dimension);

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

	/** The antenna area of the station at {@code pos}, empty for a station without antennas. */
	public de.nomisimo.storagedim.robot.StationArea stationArea(String dimension, BlockPos pos) {
		return stationAreas.getOrDefault(dimension, Map.of()).getOrDefault(pos.asLong(), de.nomisimo.storagedim.robot.StationArea.EMPTY);
	}

	public void setStationArea(String dimension, BlockPos pos, de.nomisimo.storagedim.robot.StationArea area) {
		stationAreas.computeIfAbsent(dimension, d -> new HashMap<>()).put(pos.asLong(), area);
		markDirty();
	}

	private static JsonObject writeLongSets(Map<String, Set<Long>> sets) {
		JsonObject obj = new JsonObject();
		sets.forEach((dim, set) -> {
			JsonArray arr = new JsonArray();
			set.forEach(arr::add);
			obj.add(dim, arr);
		});
		return obj;
	}

	private static void readLongSets(JsonObject root, String key, Map<String, Set<Long>> sets) {
		if (root.has(key)) {
			root.getAsJsonObject(key).entrySet().forEach(dim -> {
				Set<Long> set = new HashSet<>();
				dim.getValue().getAsJsonArray().forEach(e -> set.add(e.getAsLong()));
				sets.put(dim.getKey(), set);
			});
		}
	}

	/** The robots placed for the station at {@code pos} (id to name); changes must be followed by {@link #markDirty()}. */
	public Map<UUID, String> stationRobots(String dimension, BlockPos pos) {
		return stationRobots.computeIfAbsent(dimension, d -> new HashMap<>()).computeIfAbsent(pos.asLong(), p -> new LinkedHashMap<>());
	}

	public boolean isTerminal(String dimension, BlockPos pos) {
		Set<Long> set = terminals.get(dimension);
		return set != null && set.contains(pos.asLong());
	}

	/** The player who placed this terminal, or null (placed before 0.8.0). */
	public UUID terminalOwner(String dimension, BlockPos pos) {
		return terminalOwners.getOrDefault(dimension, Map.of()).get(pos.asLong());
	}

	public void setTerminal(String dimension, BlockPos pos, boolean present, UUID owner) {
		setTerminal(dimension, pos, present);

		if (present && owner != null) {
			terminalOwners.computeIfAbsent(dimension, d -> new HashMap<>()).put(pos.asLong(), owner);
			markDirty();
		}
	}

	public void setTerminal(String dimension, BlockPos pos, boolean present) {
		if (!present && terminalOwners.containsKey(dimension) && terminalOwners.get(dimension).remove(pos.asLong()) != null) {
			markDirty();
		}

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
		root.addProperty("oldRoomsCleared", oldRoomsCleared);

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
			JsonArray library = new JsonArray();

			for (Library.Shelf shelf : network.library) {
				ItemStack.CODEC.encodeStart(ops, shelf.key()).result().ifPresent(json -> {
					JsonObject c = new JsonObject();
					c.addProperty("x", shelf.dx());
					c.addProperty("y", shelf.dy());
					c.addProperty("z", shelf.dz());
					c.addProperty("part", shelf.part());
					c.addProperty("facing", shelf.facing().name());
					c.add("key", json);
					library.add(c);
				});
			}

			n.add("library", library);
			JsonArray aisles = new JsonArray();

			for (Library.Aisle aisle : network.aisles) {
				JsonObject a = new JsonObject();
				a.addProperty("category", aisle.category.name());
				a.addProperty("x", aisle.x);
				a.addProperty("used", aisle.used);
				aisles.add(a);
			}

			n.add("aisles", aisles);
			n.addProperty("libraryBuild", network.libraryBuild);
			n.addProperty("islandRadius", network.oldIslandRadius);
			n.addProperty("tradeHallRows", network.tradeHallRows);
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

		JsonArray projectArr = new JsonArray();
		projects.values().forEach(project -> {
			JsonObject p = new JsonObject();
			p.addProperty("id", project.id.toString());
			p.addProperty("owner", project.owner.toString());
			p.addProperty("name", project.name);
			p.addProperty("block", project.block);
			JsonArray entries = new JsonArray();

			for (Projects.Entry entry : project.entries) {
				ItemStack.CODEC.encodeStart(ops, entry.item).result().ifPresent(json -> {
					JsonObject e = new JsonObject();
					e.add("stack", json);
					e.addProperty("target", entry.target);

					if (entry.taken > 0) {
						e.addProperty("taken", entry.taken);
					}
					entries.add(e);
				});
			}

			p.add("entries", entries);
			projectArr.add(p);
		});
		root.add("projects", projectArr);

		JsonObject projectChestObj = new JsonObject();
		projectChests.forEach((dim, map) -> {
			JsonObject d = new JsonObject();
			map.forEach((pos, id) -> d.addProperty(Long.toString(pos), id.toString()));
			projectChestObj.add(dim, d);
		});
		root.add("projectChests", projectChestObj);

		JsonObject terminalObj = new JsonObject();
		terminals.forEach((dim, set) -> {
			JsonArray arr = new JsonArray();
			set.forEach(arr::add);
			terminalObj.add(dim, arr);
		});
		root.add("terminals", terminalObj);

		JsonObject terminalOwnerObj = new JsonObject();
		terminalOwners.forEach((dim, map) -> {
			JsonObject d = new JsonObject();
			map.forEach((pos, id) -> d.addProperty(Long.toString(pos), id.toString()));
			terminalOwnerObj.add(dim, d);
		});
		root.add("terminalOwners", terminalOwnerObj);

		JsonObject stationObj = new JsonObject();
		stations.forEach((dim, map) -> {
			JsonObject d = new JsonObject();
			map.forEach((pos, config) -> RobotConfig.CODEC.encodeStart(ops, config).result().ifPresent(json -> d.add(Long.toString(pos), json)));
			stationObj.add(dim, d);
		});
		root.add("stations", stationObj);

		JsonObject areaObj = new JsonObject();
		stationAreas.forEach((dim, map) -> {
			JsonObject d = new JsonObject();
			map.forEach((pos, area) -> de.nomisimo.storagedim.robot.StationArea.CODEC.encodeStart(ops, area).result()
					.ifPresent(json -> d.add(Long.toString(pos), json)));
			areaObj.add(dim, d);
		});
		root.add("stationAreas", areaObj);
		root.add("loadedStations", writeLongSets(loadedStations));
		root.add("forcedChunks", writeLongSets(forcedChunks));

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
		oldRoomsCleared = root.has("oldRoomsCleared") && root.get("oldRoomsCleared").getAsBoolean();

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

				if (n.has("library") && n.has("aisles")) {
					for (JsonElement c : n.getAsJsonArray("library")) {
						JsonObject shelf = c.getAsJsonObject();
						int dx = shelf.get("x").getAsInt();
						int dy = shelf.get("y").getAsInt();
						int dz = shelf.get("z").getAsInt();
						int part = shelf.get("part").getAsInt();
						Direction facing = java.util.Arrays.stream(Direction.values())
								.filter(d -> d.name().equals(shelf.get("facing").getAsString())).findFirst().orElse(Direction.NORTH);
						ItemStack.CODEC.parse(ops, shelf.get("key")).resultOrPartial(StorageDimensionMod.LOGGER::warn).ifPresent(stack -> network.library
								.add(new Library.Shelf(dx, dy, dz, stack.copyWithCount(1), part, facing)));
					}

					for (JsonElement a : n.getAsJsonArray("aisles")) {
						JsonObject aisle = a.getAsJsonObject();

						try {
							network.aisles.add(new Library.Aisle(Library.Category.valueOf(aisle.get("category").getAsString()), aisle.get("x").getAsInt(),
									aisle.get("used").getAsInt()));
						} catch (IllegalArgumentException e) {
							StorageDimensionMod.LOGGER.warn("Unbekannte Abteilung {}", aisle.get("category"));
						}
					}

					network.libraryBuild = n.has("libraryBuild") ? n.get("libraryBuild").getAsInt() : 0;
				}

				// versions up to 0.7: the chest island, which is taken down when the library is built
				network.oldIslandRadius = n.has("islandRadius") ? n.get("islandRadius").getAsInt() : 0;
				network.tradeHallRows = n.has("tradeHallRows") ? n.get("tradeHallRows").getAsInt() : 0;
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

		if (root.has("projects")) {
			for (JsonElement el : root.getAsJsonArray("projects")) {
				JsonObject p = el.getAsJsonObject();
				Projects.Project project = new Projects.Project(UUID.fromString(p.get("id").getAsString()), UUID.fromString(p.get("owner").getAsString()),
						p.get("name").getAsString(), p.get("block").getAsBoolean());

				for (JsonElement e : p.getAsJsonArray("entries")) {
					JsonObject entry = e.getAsJsonObject();
					long target = entry.get("target").getAsLong();
					long taken = entry.has("taken") ? entry.get("taken").getAsLong() : 0;
					ItemStack.CODEC.parse(ops, entry.get("stack")).resultOrPartial(StorageDimensionMod.LOGGER::warn)
							.ifPresent(stack -> project.entries.add(new Projects.Entry(stack.copyWithCount(1), target, taken)));
				}

				projects.put(project.id, project);
			}
		}

		if (root.has("projectChests")) {
			root.getAsJsonObject("projectChests").entrySet().forEach(dim -> {
				Map<Long, UUID> map = new HashMap<>();
				dim.getValue().getAsJsonObject().entrySet().forEach(e ->
						map.put(Long.parseLong(e.getKey()), UUID.fromString(e.getValue().getAsString())));
				projectChests.put(dim.getKey(), map);
			});
		}

		if (root.has("terminals")) {
			root.getAsJsonObject("terminals").entrySet().forEach(dim -> {
				Set<Long> set = new HashSet<>();
				dim.getValue().getAsJsonArray().forEach(e -> set.add(e.getAsLong()));
				terminals.put(dim.getKey(), set);
			});
		}

		if (root.has("terminalOwners")) {
			root.getAsJsonObject("terminalOwners").entrySet().forEach(dim -> {
				Map<Long, UUID> map = new HashMap<>();
				dim.getValue().getAsJsonObject().entrySet().forEach(e ->
						map.put(Long.parseLong(e.getKey()), UUID.fromString(e.getValue().getAsString())));
				terminalOwners.put(dim.getKey(), map);
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

		if (root.has("stationAreas")) {
			root.getAsJsonObject("stationAreas").entrySet().forEach(dim -> {
				Map<Long, de.nomisimo.storagedim.robot.StationArea> map = new HashMap<>();
				dim.getValue().getAsJsonObject().entrySet().forEach(e -> de.nomisimo.storagedim.robot.StationArea.CODEC.parse(ops, e.getValue())
						.resultOrPartial(StorageDimensionMod.LOGGER::warn).ifPresent(area -> map.put(Long.parseLong(e.getKey()), area)));
				stationAreas.put(dim.getKey(), map);
			});
		}

		readLongSets(root, "loadedStations", loadedStations);
		readLongSets(root, "forcedChunks", forcedChunks);

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
