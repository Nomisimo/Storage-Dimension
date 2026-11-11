package de.nomisimo.storagedim.robot;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Keeps the work area of a robot station loaded, switched on at the station (off by default): every chunk of its
 * range (or its antenna box) is force-loaded like with {@code /forceload}, so the robots go on working and their
 * items and blocks tick while no player is near. Switched off, or once the station is gone, the chunks are released.
 * <p>
 * Several stations may share chunks, and a chunk a player force-loaded himself is left alone: the mod remembers
 * which chunks it forced and only ever releases those, when no station needs them any more.
 */
public final class StationChunks {
	/** How often the loaded chunks are matched to the stations (range, antennas or the station itself may change). */
	private static final int TICKS = 100;
	/** Blocks around the area that are loaded too, since robots step a little over its edge. */
	private static final int MARGIN = 4;

	private StationChunks() {
	}

	/** A chunk position packed like Minecraft does (x in the low, z in the high half), as in its force-loaded list. */
	static long pack(int x, int z) {
		return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL) << 32;
	}

	static int chunkX(long chunk) {
		return (int) chunk;
	}

	static int chunkZ(long chunk) {
		return (int) (chunk >>> 32);
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % TICKS == 0) {
				update(server);
			}
		});
	}

	public static boolean enabled(String dim, BlockPos station) {
		StorageData data = StorageData.get();
		return data != null && data.loadedStations.getOrDefault(dim, Set.of()).contains(station.asLong());
	}

	/** The station's button: keeps its work area loaded, or no longer. */
	public static Component toggle(ServerLevel level, String dim, BlockPos station) {
		StorageData data = StorageData.get();
		Set<Long> stations = data.loadedStations.computeIfAbsent(dim, d -> new HashSet<>());
		boolean on = !stations.remove(station.asLong());

		if (on) {
			stations.add(station.asLong());
		}

		StorageData.markDirty();
		update(level.getServer());

		if (!on) {
			return Component.literal("Der Arbeitsbereich wird nicht mehr geladen gehalten.").withStyle(ChatFormatting.YELLOW);
		}

		int count = chunks(data, dim, station).size();
		return Component.literal("Der Arbeitsbereich bleibt geladen (" + count + (count == 1 ? " Chunk" : " Chunks")
				+ "), auch wenn kein Spieler in der Nähe ist. Die Roboter arbeiten weiter.").withStyle(ChatFormatting.GREEN);
	}

	/** How many chunks the station keeps loaded, 0 when it is switched off. */
	public static int loaded(String dim, BlockPos station) {
		return enabled(dim, station) ? chunks(StorageData.get(), dim, station).size() : 0;
	}

	/**
	 * The chunks of the station's work area: its antenna box, else the square (mining) or circle (collecting) of its
	 * range around it, plus a few blocks around.
	 */
	static Set<Long> chunks(StorageData data, String dim, BlockPos station) {
		StationArea area = data.stationArea(dim, station);
		RobotConfig config = data.station(dim, station);
		Set<Long> chunks = new HashSet<>();
		chunks.add(pack(station.getX() >> 4, station.getZ() >> 4));

		if (area.connected()) {
			AABB box = area.box(station).inflate(MARGIN, 0, MARGIN);
			int minX = (int) Math.floor(box.minX) >> 4;
			int maxX = (int) Math.floor(box.maxX) >> 4;
			int minZ = (int) Math.floor(box.minZ) >> 4;
			int maxZ = (int) Math.floor(box.maxZ) >> 4;

			for (int cx = minX; cx <= maxX; cx++) {
				for (int cz = minZ; cz <= maxZ; cz++) {
					chunks.add(pack(cx, cz));
				}
			}

			return chunks;
		}

		int range = Math.clamp(config.range(), 16, RobotEntity.RANGE) + MARGIN;
		int sx = station.getX();
		int sz = station.getZ();

		for (int cx = (sx - range) >> 4; cx <= (sx + range) >> 4; cx++) {
			for (int cz = (sz - range) >> 4; cz <= (sz + range) >> 4; cz++) {
				// the point of the chunk closest to the station
				long dx = Math.max(0, Math.max((cx << 4) - sx, sx - ((cx << 4) + 15)));
				long dz = Math.max(0, Math.max((cz << 4) - sz, sz - ((cz << 4) + 15)));

				if (config.mining() || dx * dx + dz * dz <= (long) range * range) {
					chunks.add(pack(cx, cz));
				}
			}
		}

		return chunks;
	}

	/** Forces the chunks the stations need and releases the ones none needs any more; drops stations that are gone. */
	public static void update(MinecraftServer server) {
		StorageData data = StorageData.get();

		if (data == null) {
			return;
		}

		Set<String> dims = new HashSet<>(data.loadedStations.keySet());
		dims.addAll(data.forcedChunks.keySet());

		for (String dim : dims) {
			ServerLevel level = Rooms.levelById(server, dim);

			if (level == null) {
				continue;
			}

			Set<Long> wanted = new HashSet<>();
			Set<Long> stations = data.loadedStations.getOrDefault(dim, Set.of());

			for (Iterator<Long> it = stations.iterator(); it.hasNext(); ) {
				BlockPos station = BlockPos.of(it.next());

				// broken, blown up or moved: its chunks are released (the station chunk is forced, so it is loaded)
				if (level.isLoaded(station) && !level.getBlockState(station).is(RobotStationBlock.BLOCK)) {
					it.remove();
					StorageData.markDirty();
					continue;
				}

				wanted.addAll(chunks(data, dim, station));
			}

			Set<Long> forced = data.forcedChunks.computeIfAbsent(dim, d -> new HashSet<>());
			Set<Long> already = new HashSet<>(level.getForceLoadedChunks());

			for (Iterator<Long> it = forced.iterator(); it.hasNext(); ) {
				long chunk = it.next();

				if (!wanted.contains(chunk)) {
					level.setChunkForced(chunkX(chunk), chunkZ(chunk), false);
					it.remove();
					StorageData.markDirty();
				}
			}

			for (long chunk : wanted) {
				if (forced.contains(chunk)) {
					// released by hand (/forceload remove): force it again
					if (!already.contains(chunk)) {
						level.setChunkForced(chunkX(chunk), chunkZ(chunk), true);
					}
				} else if (!already.contains(chunk)) {
					// a chunk somebody else forced stays theirs
					level.setChunkForced(chunkX(chunk), chunkZ(chunk), true);
					forced.add(chunk);
					StorageData.markDirty();
				}
			}

			if (stations.isEmpty()) {
				data.loadedStations.remove(dim);
			}

			if (forced.isEmpty()) {
				data.forcedChunks.remove(dim);
			}
		}
	}

	/** Server smoke test: switching on forces the area, a chunk forced by hand is kept, breaking the station releases the rest. */
	public static void smokeTest(ServerLevel level, List<String> errors) {
		String dim = Rooms.id(level);
		BlockPos station = new BlockPos(-4000, 100, -4000);
		StorageData data = StorageData.get();
		level.setChunkForced(station.getX() >> 4, station.getZ() >> 4, true);
		level.setBlockAndUpdate(station, RobotStationBlock.BLOCK.defaultBlockState());
		data.setStation(dim, station, data.station(dim, station).withRange(16));
		// a chunk inside the range that a player force-loaded himself
		long own = pack((station.getX() >> 4) + 1, station.getZ() >> 4);
		level.setChunkForced(chunkX(own), chunkZ(own), true);
		toggle(level, dim, station);
		Set<Long> chunks = chunks(data, dim, station);
		Set<Long> forcedNow = new HashSet<>(level.getForceLoadedChunks());

		if (chunks.size() < 9 || !forcedNow.containsAll(chunks)) {
			errors.add("Station hält ihren Arbeitsbereich nicht geladen (" + chunks.size() + " Chunks)");
		}

		if (data.forcedChunks.getOrDefault(dim, Set.of()).contains(own)) {
			errors.add("Station hat einen von Hand geladenen Chunk übernommen");
		}

		level.setBlockAndUpdate(station, Blocks.AIR.defaultBlockState());
		update(level.getServer());
		Set<Long> after = new HashSet<>(level.getForceLoadedChunks());

		if (enabled(dim, station) || !after.contains(own)) {
			errors.add("Abgebaute Station gibt ihre Chunks nicht richtig frei");
		}

		for (long chunk : chunks) {
			if (chunk != own && chunk != pack(station.getX() >> 4, station.getZ() >> 4) && after.contains(chunk)) {
				errors.add("Chunk " + chunkX(chunk) + " " + chunkZ(chunk) + " bleibt nach dem Abbau der Station geladen");
				break;
			}
		}

		level.setChunkForced(chunkX(own), chunkZ(own), false);
		level.setChunkForced(station.getX() >> 4, station.getZ() >> 4, false);
		data.stations.getOrDefault(dim, Map.of()).remove(station.asLong());
		StorageData.markDirty();
	}
}
