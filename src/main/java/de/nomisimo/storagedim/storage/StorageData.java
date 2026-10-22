package de.nomisimo.storagedim.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/**
 * All persistent state of the mod, stored as storagedim.json in the world folder.
 */
public final class StorageData {
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
	/** Where to send a player back to when they leave the storage room. */
	public final Map<UUID, SavedPos> returns = new HashMap<>();
	/** Players currently watching through a chest; restored on join if the server stopped meanwhile. */
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
		markDirty();
	}

	public void removeChest(String dimension, BlockPos pos) {
		Map<Long, UUID> map = chests.get(dimension);

		if (map != null && map.remove(pos.asLong()) != null) {
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
