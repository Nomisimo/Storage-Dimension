package de.nomisimo.storagedim.tb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's own settings for the add-on, in {@code config/storagedim-tb-client.json}: whether the backpack may open
 * in the ME view by itself. Changed in the mod settings (Mod Menu) or in the file; the server is told on joining and
 * on every change.
 */
@Environment(EnvType.CLIENT)
public final class TbClientConfig {
	private static boolean autoMe = true;
	private static boolean loaded;

	private TbClientConfig() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("storagedim-tb-client.json");
	}

	/** Whether a backpack left in the ME view opens in it again. */
	public static boolean autoMe() {
		load();
		return autoMe;
	}

	public static void setAutoMe(boolean on) {
		load();
		autoMe = on;
		save();
		sendToServer();
	}

	static void sendToServer() {
		if (ClientPlayNetworking.canSend(MeNetworking.AutoPayload.TYPE)) {
			ClientPlayNetworking.send(new MeNetworking.AutoPayload(autoMe()));
		}
	}

	private static void load() {
		if (loaded) {
			return;
		}

		loaded = true;

		try {
			if (Files.exists(file())) {
				JsonObject root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();

				if (root.has("autoOpenMeView")) {
					autoMe = root.get("autoOpenMeView").getAsBoolean();
				}
			}
		} catch (IOException | RuntimeException e) {
			StorageTbMod.LOGGER.warn("storagedim-tb-client.json konnte nicht gelesen werden", e);
		}
	}

	private static void save() {
		JsonObject root = new JsonObject();
		root.addProperty("autoOpenMeView", autoMe);

		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), root.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			StorageTbMod.LOGGER.warn("storagedim-tb-client.json konnte nicht gespeichert werden", e);
		}
	}
}
