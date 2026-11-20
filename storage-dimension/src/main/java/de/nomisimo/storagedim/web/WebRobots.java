package de.nomisimo.storagedim.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.nomisimo.storagedim.robot.Mailbox;
import de.nomisimo.storagedim.robot.MailboxBlock;
import de.nomisimo.storagedim.robot.MailboxNetworking;
import de.nomisimo.storagedim.robot.RobotConfig;
import de.nomisimo.storagedim.robot.RobotEntity;
import de.nomisimo.storagedim.robot.RobotStationBlock;
import de.nomisimo.storagedim.robot.StationArea;
import de.nomisimo.storagedim.robot.StationChunks;
import de.nomisimo.storagedim.robot.StationCluster;
import de.nomisimo.storagedim.robot.StationProgress;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * The robot stations and mailboxes for the web view, like in their screens in the game. Stations and mailboxes
 * belong to nobody in the game (anyone may open them), so every player of the page sees all of them. Every method
 * runs on the server thread. Nothing here loads a chunk: what is not loaded is listed as "nicht geladen".
 */
final class WebRobots {
	private static final Gson GSON = new Gson();

	private WebRobots() {
	}

	// ---- stations ----

	static String stations(MinecraftServer server) {
		StorageData data = StorageData.get();

		if (data == null) {
			return error("Der Speicher ist noch nicht geladen.");
		}

		JsonArray stations = new JsonArray();

		for (Map.Entry<String, Map<Long, RobotConfig>> dim : data.stations.entrySet()) {
			ServerLevel level = Rooms.levelById(server, dim.getKey());

			if (level == null) {
				continue;
			}

			for (Long packed : List.copyOf(dim.getValue().keySet())) {
				BlockPos pos = BlockPos.of(packed);
				boolean loaded = level.isLoaded(pos);

				if (loaded && (!level.getBlockState(pos).is(RobotStationBlock.BLOCK) || !StationCluster.main(level, pos).equals(pos))) {
					// gone, or a station of a group whose main station speaks for it
					continue;
				}

				stations.add(station(data, level, dim.getKey(), pos, loaded));
			}
		}

		JsonObject root = new JsonObject();
		root.add("stations", stations);
		return GSON.toJson(root);
	}

	private static JsonObject station(StorageData data, ServerLevel level, String dim, BlockPos pos, boolean loaded) {
		RobotConfig config = data.station(dim, pos);
		JsonObject station = new JsonObject();
		station.addProperty("dim", dim);
		station.addProperty("world", worldName(dim));
		station.addProperty("x", pos.getX());
		station.addProperty("y", pos.getY());
		station.addProperty("z", pos.getZ());
		station.addProperty("name", config.stationName());
		station.addProperty("mode", config.courier() ? "Kurier" : config.mining() ? config.oreSearch() ? "Erzsuche" : "Abbauen" : "Sammeln");
		station.addProperty("progress", StationProgress.shown(dim, pos, config));
		station.addProperty("loaded", loaded);
		station.addProperty("keptLoaded", StationChunks.loaded(dim, pos));
		station.addProperty("range", config.range());

		int stations = loaded ? StationCluster.members(level, pos).size() : 1;
		station.addProperty("stations", Math.max(1, stations));
		station.addProperty("maxRobots", StationCluster.maxRobots(loaded ? StationCluster.size(level, pos) : 1));

		StationArea area = data.stationArea(dim, pos);

		if (area.connected()) {
			station.addProperty("area", area.name().isEmpty() ? "Area" : area.name());
			station.addProperty("areaOn", area.enabled());
		}

		JsonArray robots = new JsonArray();
		int number = 0;

		for (Map.Entry<UUID, String> entry : data.stationRobots(dim, pos).entrySet()) {
			number++;
			JsonObject robot = new JsonObject();
			robot.addProperty("name", entry.getValue().isEmpty() ? "Roboter " + number : entry.getValue());
			Entity entity = level.getEntity(entry.getKey());

			if (entity instanceof RobotEntity r) {
				Component status = r.status();
				robot.addProperty("loaded", true);
				robot.addProperty("status", status.getString());
				robot.addProperty("tone", tone(status));
				robot.addProperty("error", r.hasError());
				robot.addProperty("color", String.format("#%06x", RobotEntity.nameColor(r.getColor()) & 0xFFFFFF));
				robot.addProperty("distance", (int) Math.round(Math.sqrt(r.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))));
				robot.addProperty("health", Math.round(r.getHealth()));
				robot.addProperty("maxHealth", Math.round(r.getMaxHealth()));
				robot.addProperty("mood", r.getMood());
			} else {
				robot.addProperty("loaded", false);
				robot.addProperty("status", "nicht geladen");
				robot.addProperty("tone", "off");
				robot.addProperty("error", false);
			}

			robots.add(robot);
		}

		station.add("robots", robots);
		return station;
	}

	/** How the page colours a status line: the colour it has in the game. */
	private static String tone(Component status) {
		TextColor color = status.getStyle().getColor();
		int value = color == null ? -1 : color.getValue();

		if (value == color(ChatFormatting.RED)) {
			return "error";
		}

		if (value == color(ChatFormatting.GREEN)) {
			return "busy";
		}

		if (value == color(ChatFormatting.AQUA)) {
			return "pause";
		}

		return "idle";
	}

	private static int color(ChatFormatting formatting) {
		Integer value = formatting.getColor();
		return value == null ? -2 : value;
	}

	// ---- mailboxes ----

	static String mailboxes(MinecraftServer server) {
		StorageData data = StorageData.get();

		if (data == null) {
			return error("Der Speicher ist noch nicht geladen.");
		}

		JsonArray list = new JsonArray();

		for (Map.Entry<String, Map<Long, Mailbox>> dim : data.mailboxes.entrySet()) {
			ServerLevel level = Rooms.levelById(server, dim.getKey());

			if (level == null) {
				continue;
			}

			for (Map.Entry<Long, Mailbox> entry : List.copyOf(dim.getValue().entrySet())) {
				BlockPos pos = BlockPos.of(entry.getKey());
				boolean loaded = level.isLoaded(pos);

				if (loaded && !level.getBlockState(pos).is(MailboxBlock.BLOCK)) {
					continue;
				}

				Mailbox mailbox = entry.getValue();
				JsonObject box = new JsonObject();
				box.addProperty("dim", dim.getKey());
				box.addProperty("world", worldName(dim.getKey()));
				box.addProperty("x", pos.getX());
				box.addProperty("y", pos.getY());
				box.addProperty("z", pos.getZ());
				box.addProperty("address", mailbox.address());
				box.addProperty("destination", mailbox.destination());
				box.addProperty("loaded", loaded);

				if (loaded) {
					String state = MailboxNetworking.state(level, pos, mailbox);
					box.addProperty("state", state.replaceAll("§.", ""));
					box.addProperty("tone", state.contains("§c") ? "error" : state.contains("§6") ? "warn" : "ok");
					box.addProperty("mail", level.getBlockState(pos).getValue(MailboxBlock.MAIL));
				} else {
					box.addProperty("state", "Nicht geladen: der Briefkasten steht in einem Bereich, in dem gerade niemand ist.");
					box.addProperty("tone", "off");
					box.addProperty("mail", false);
				}

				list.add(box);
			}
		}

		JsonObject root = new JsonObject();
		root.add("mailboxes", list);
		root.addProperty("maxAddress", Mailbox.MAX_ADDRESS);
		return GSON.toJson(root);
	}

	/** {@code {dim, x, y, z, address, destination}}: new addresses for a mailbox, like in its screen. */
	static String changeMailbox(MinecraftServer server, JsonObject body) {
		StorageData data = StorageData.get();

		if (data == null || body == null || !body.has("dim") || !body.has("x") || !body.has("y") || !body.has("z")) {
			return error("Ungültige Anfrage.");
		}

		String dim = body.get("dim").getAsString();
		ServerLevel level = Rooms.levelById(server, dim);
		BlockPos pos = new BlockPos(body.get("x").getAsInt(), body.get("y").getAsInt(), body.get("z").getAsInt());

		if (level == null || data.mailbox(dim, pos) == null || level.isLoaded(pos) && !level.getBlockState(pos).is(MailboxBlock.BLOCK)) {
			return error("Diesen Briefkasten gibt es nicht mehr.");
		}

		String address = MailboxNetworking.clean(body.has("address") ? body.get("address").getAsString() : "");
		String destination = MailboxNetworking.clean(body.has("destination") ? body.get("destination").getAsString() : "");
		data.setMailbox(dim, pos, new Mailbox(address, destination));
		return mailboxes(server);
	}

	// ---- helpers ----

	private static String worldName(String dim) {
		return switch (dim) {
			case "minecraft:overworld" -> "Oberwelt";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "Ende";
			case "storagedim:storage" -> "Storage Dimension";
			default -> dim;
		};
	}

	private static String error(String message) {
		JsonObject root = new JsonObject();
		root.addProperty("error", message);
		return GSON.toJson(root);
	}
}
