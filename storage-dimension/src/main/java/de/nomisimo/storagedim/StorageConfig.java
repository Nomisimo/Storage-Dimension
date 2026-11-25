package de.nomisimo.storagedim;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * config/storagedim.json, created with defaults on first start.
 */
public final class StorageConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static StorageConfig instance;

	/** true: every player uses the same storage and the same library. false: everyone has their own, shareable via /storage teilen. */
	public boolean sharedStorage = true;

	/** Client only. true: the search field of the ME terminal and JEI's search bar always hold the same text. */
	public boolean jeiSearchSync = false;

	/** Server. true: a chest a robot puts something into also makes its opening and closing sound (the lid moves either way). */
	public boolean robotChestSound = false;

	/**
	 * Server. true: a dedicated server does not pause without players ({@code pause-when-empty-seconds}) while a robot
	 * station keeps its work area loaded, so the robots go on working. Changed in the game in the mod settings.
	 */
	public boolean keepServerAwake = true;

	/**
	 * Server. false: robot stations keep no chunks loaded at all, whatever they are set to; the chunks the mod forced
	 * are released. Changed with {@code /storage chunkloading an|aus}.
	 */
	public boolean chunkLoading = true;

	/** Server. Port of the ME web view ({@code /storage web}); 0 switches it off. */
	public int webPort = 8765;

	/**
	 * Server. Address players open the web view at, e.g. "mc.example.de" or "123.45.67.89" (without port).
	 * With "http://" or "https://" it is the whole start of the link and used as it is (with port, if needed),
	 * e.g. "https://mc.example.de:10000" behind a reverse proxy or tunnel with HTTPS.
	 * Empty: the server-ip from server.properties, or "localhost".
	 */
	public String webAddress = "";

	public static StorageConfig get() {
		if (instance == null) {
			load();
		}

		return instance;
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("storagedim.json");

		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					instance = GSON.fromJson(reader, StorageConfig.class);
				}

				if (instance != null) {
					// Adds settings that are new in this version to the file, so they can be found.
					save();
				}
			}
		} catch (Exception e) {
			StorageDimensionMod.LOGGER.error("storagedim.json fehlerhaft, nutze Standardwerte", e);
		}

		if (instance == null) {
			instance = new StorageConfig();

			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, writer);
			} catch (Exception e) {
				StorageDimensionMod.LOGGER.error("storagedim.json konnte nicht geschrieben werden", e);
			}
		}
	}

	/** Writes the current settings, e.g. after a switch in the game changed one. */
	public static void save() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("storagedim.json");

		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(get(), writer);
		} catch (Exception e) {
			StorageDimensionMod.LOGGER.error("storagedim.json konnte nicht geschrieben werden", e);
		}
	}
}
