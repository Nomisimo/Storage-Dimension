package de.nomisimo.storagedim.robot;

import java.util.HashMap;
import java.util.Map;

import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.core.BlockPos;

/**
 * How far the mining of a station is, for the station screen. The jobs work it out while the robots dig and keep the
 * last value here, saved with the world, so it is still shown after a restart or while no robot is digging.
 */
public final class StationProgress {
	private StationProgress() {
	}

	/**
	 * What was worked out last: for which job ({@code key}: the mode and the area), the number of blocks there were
	 * to dig at the start (quarry only), and how far it is in tenths of a percent.
	 */
	public record Entry(String key, long total, int permille) {
	}

	public static Entry get(String dim, BlockPos station) {
		StorageData data = StorageData.get();
		return data == null ? null : data.stationProgress.getOrDefault(dim, Map.of()).get(station.asLong());
	}

	static void put(String dim, BlockPos station, Entry entry) {
		StorageData data = StorageData.get();

		if (data == null || entry.equals(get(dim, station))) {
			return;
		}

		data.stationProgress.computeIfAbsent(dim, d -> new HashMap<>()).put(station.asLong(), entry);
		StorageData.markDirty();
	}

	/** The work mode was switched: the new job starts at zero. */
	static void clear(String dim, BlockPos station) {
		StorageData data = StorageData.get();
		Map<Long, Entry> map = data == null ? null : data.stationProgress.get(dim);

		if (map != null && map.remove(station.asLong()) != null) {
			StorageData.markDirty();
		}
	}

	/** For the station screen: tenths of a percent of the mode the station is in, -1 while it is not known yet (or not mining). */
	public static int shown(String dim, BlockPos station, RobotConfig config) {
		if (!config.mining()) {
			return -1;
		}

		if (config.oreSearch()) {
			OreJob job = OreJob.peek(dim, station);

			if (job != null) {
				return job.progress();
			}
		} else {
			MiningJob job = MiningJob.peek(dim, station);

			if (job != null && job.progress() >= 0) {
				return job.progress();
			}
		}

		Entry entry = get(dim, station);
		return entry != null && entry.key().startsWith(config.oreSearch() ? "ore:" : "mine:") ? entry.permille() : -1;
	}
}
