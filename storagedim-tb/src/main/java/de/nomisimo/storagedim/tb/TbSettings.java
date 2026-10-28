package de.nomisimo.storagedim.tb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * What each player last chose in the backpack window, kept in {@code storagedim-tb.json} next to the world: whether
 * the backpack opens in the ME view, and whether the ME view sorts by name.
 */
final class TbSettings {
	/** Players whose backpack opens in the ME view, because they left it that way. */
	static final Set<UUID> ME_VIEW = ConcurrentHashMap.newKeySet();
	/** Players who sort the ME view by name instead of amount. */
	static final Set<UUID> BY_NAME = ConcurrentHashMap.newKeySet();
	private static Path file;

	private TbSettings() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(TbSettings::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ME_VIEW.clear();
			BY_NAME.clear();
			file = null;
		});
	}

	private static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("storagedim-tb.json");
		ME_VIEW.clear();
		BY_NAME.clear();

		if (!Files.exists(file)) {
			return;
		}

		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			read(root, "meView", ME_VIEW);
			read(root, "sortByName", BY_NAME);
		} catch (IOException | RuntimeException e) {
			StorageTbMod.LOGGER.warn("storagedim-tb.json konnte nicht gelesen werden", e);
		}
	}

	private static void read(JsonObject root, String key, Set<UUID> into) {
		if (root.has(key)) {
			for (JsonElement id : root.getAsJsonArray(key)) {
				into.add(UUID.fromString(id.getAsString()));
			}
		}
	}

	/** Adds or removes the player and saves right away; the choices change rarely. */
	static void set(Set<UUID> set, UUID player, boolean on) {
		if (on ? set.add(player) : set.remove(player)) {
			save();
		}
	}

	private static void save() {
		if (file == null) {
			return;
		}

		JsonObject root = new JsonObject();
		root.add("meView", write(ME_VIEW));
		root.add("sortByName", write(BY_NAME));

		try {
			Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			StorageTbMod.LOGGER.warn("storagedim-tb.json konnte nicht gespeichert werden", e);
		}
	}

	private static JsonArray write(Set<UUID> set) {
		JsonArray array = new JsonArray();
		set.forEach(id -> array.add(id.toString()));
		return array;
	}
}
