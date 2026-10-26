package de.nomisimo.storagedim.robot;

import java.util.Map;
import java.util.UUID;

import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.core.BlockPos;

/**
 * The robots of one station, listed in its screen. A name given at the station is numbered once two robots
 * would share it: Quak, then Quak1 and Quak2.
 */
public final class StationRobots {
	private StationRobots() {
	}

	/** Adds a newly placed robot and returns its name. */
	public static String register(String dim, BlockPos station, UUID id, String base) {
		Map<UUID, String> robots = StorageData.get().stationRobots(dim, station);
		robots.remove(id);
		String name = base.isEmpty() ? "" : freeName(robots, base);
		robots.put(id, name);
		StorageData.markDirty();
		return name;
	}

	public static void unregister(String dim, BlockPos station, UUID id) {
		StorageData data = StorageData.get();

		if (data != null && data.stationRobots(dim, station).remove(id) != null) {
			StorageData.markDirty();
		}
	}

	/** The station's name for its robots changed: all of them take it, numbered when there are several. */
	public static void rename(String dim, BlockPos station, String base) {
		Map<UUID, String> robots = StorageData.get().stationRobots(dim, station);
		int number = 1;

		for (Map.Entry<UUID, String> entry : robots.entrySet()) {
			entry.setValue(base.isEmpty() ? "" : robots.size() == 1 ? base : base + number++);
		}

		StorageData.markDirty();
	}

	private static String freeName(Map<UUID, String> robots, String base) {
		boolean taken = robots.values().stream().anyMatch(name -> name.equals(base) || isNumbered(name, base));

		if (!taken) {
			return base;
		}

		// the robot that had the plain name becomes number 1
		robots.replaceAll((id, name) -> name.equals(base) ? base + 1 : name);

		for (int number = 1; ; number++) {
			String name = base + number;

			if (!robots.containsValue(name)) {
				return name;
			}
		}
	}

	private static boolean isNumbered(String name, String base) {
		return name.length() > base.length() && name.startsWith(base) && name.substring(base.length()).chars().allMatch(Character::isDigit);
	}
}
