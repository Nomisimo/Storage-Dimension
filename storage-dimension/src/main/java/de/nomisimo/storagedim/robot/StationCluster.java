package de.nomisimo.storagedim.robot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A station group (Stations-Verbund): robot stations standing side by side at the same height (touching on north,
 * east, south or west) work as one. One of them is the main station: it keeps the settings, the robots, the
 * antenna area and the mining job of the whole group, and every station of the group opens its screen. Each station
 * of the group adds {@value #ROBOTS_PER_STATION} robots and one chunk of range (and 16 blocks of area side), up to
 * {@value #MAX_STATIONS} stations. The chests next to any of the stations are the group's chests: the robots unload
 * into all of them and take their tools from a tool chest or filter chest beside any of them.
 * <p>
 * Which station is the main one is kept in the block state ({@link RobotStationBlock#PART}), so it stays the same
 * when stations are added or taken away. Joining two groups, the one with more robots keeps its main station.
 */
public final class StationCluster {
	/** Robots per station of a group. */
	public static final int ROBOTS_PER_STATION = 32;
	/** Stations beyond this many still belong to the group but add nothing more. */
	public static final int MAX_STATIONS = 8;
	/** Largest range or area side of any group: one chunk more per station after the first. */
	public static final int MAX_RANGE = RobotEntity.RANGE + 16 * (MAX_STATIONS - 1);
	/** No group is looked at further than this many stations (safety net for huge rows of stations). */
	private static final int SEARCH_LIMIT = 64;
	private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private StationCluster() {
	}

	private static boolean isStation(Level level, BlockPos pos) {
		return level.isLoaded(pos) && level.getBlockState(pos).is(RobotStationBlock.BLOCK);
	}

	/** All stations of the group the station at {@code pos} belongs to, {@code pos} first; empty when none stands there. */
	public static List<BlockPos> members(Level level, BlockPos pos) {
		List<BlockPos> found = new ArrayList<>();

		if (!isStation(level, pos)) {
			return found;
		}

		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		BlockPos start = pos.immutable();
		seen.add(start);
		open.add(start);

		while (!open.isEmpty() && found.size() < SEARCH_LIMIT) {
			BlockPos next = open.poll();
			found.add(next);

			for (Direction side : SIDES) {
				BlockPos neighbour = next.relative(side);

				if (seen.add(neighbour) && isStation(level, neighbour)) {
					open.add(neighbour);
				}
			}
		}

		return found;
	}

	/** The main station of the group {@code pos} belongs to: {@code pos} itself for a single station. */
	public static BlockPos main(Level level, BlockPos pos) {
		if (!isStation(level, pos)) {
			return pos;
		}

		RobotStationBlock.Part part = level.getBlockState(pos).getValue(RobotStationBlock.PART);

		if (part != RobotStationBlock.Part.MEMBER) {
			return pos;
		}

		for (BlockPos member : members(level, pos)) {
			if (level.getBlockState(member).getValue(RobotStationBlock.PART) == RobotStationBlock.Part.MAIN) {
				return member;
			}
		}

		// a group without a main station (half loaded, or older blocks): sorted out now
		if (level instanceof ServerLevel server) {
			return update(server, pos);
		}

		return pos;
	}

	/** How many stations count for the size of the group (at most {@value #MAX_STATIONS}). */
	public static int size(Level level, BlockPos pos) {
		return Math.clamp(members(level, pos).size(), 1, MAX_STATIONS);
	}

	public static int maxRobots(int size) {
		return ROBOTS_PER_STATION * Math.clamp(size, 1, MAX_STATIONS);
	}

	/** Largest range (and area side) in blocks: 10 chunks, one more for each further station. */
	public static int maxRange(int size) {
		return RobotEntity.RANGE + 16 * (Math.clamp(size, 1, MAX_STATIONS) - 1);
	}

	/**
	 * Looks at the group around {@code pos} again after a station was set or taken away there: picks its main
	 * station and sets the look of every station (single, main or extension). Returns the main station.
	 */
	public static BlockPos update(ServerLevel level, BlockPos pos) {
		List<BlockPos> members = members(level, pos);

		if (members.isEmpty()) {
			return pos;
		}

		if (members.size() == 1) {
			setPart(level, members.get(0), RobotStationBlock.Part.SINGLE);
			clampRange(level, members.get(0), 1);
			return members.get(0);
		}

		String dim = Rooms.id(level);
		StorageData data = StorageData.get();
		Comparator<BlockPos> byRobots = Comparator.comparingInt(p -> data == null ? 0 : data.stationRobots(dim, p).size());
		// a main station stays the main one; of two groups joined, the one with more robots; else a station that
		// stood before (single) rather than the one just set; then the one with more robots, then a fixed order
		Comparator<BlockPos> order = Comparator.<BlockPos>comparingInt(p -> rank(level.getBlockState(p).getValue(RobotStationBlock.PART)))
				.thenComparing(byRobots.reversed())
				.thenComparingLong(BlockPos::asLong);
		BlockPos main = members.stream().min(order).orElseThrow();

		for (BlockPos member : members) {
			setPart(level, member, member.equals(main) ? RobotStationBlock.Part.MAIN : RobotStationBlock.Part.MEMBER);
		}

		clampRange(level, main, members.size());
		return main;
	}

	/** A group that got smaller: its range no longer goes further than it may. */
	private static void clampRange(ServerLevel level, BlockPos main, int stations) {
		StorageData data = StorageData.get();

		if (data == null) {
			return;
		}

		String dim = Rooms.id(level);
		RobotConfig config = data.station(dim, main);
		int max = maxRange(stations);

		if (config.range() > max) {
			data.setStation(dim, main, config.withRange(max));
		}
	}

	private static int rank(RobotStationBlock.Part part) {
		return switch (part) {
			case MAIN -> 0;
			case SINGLE -> 1;
			case MEMBER -> 2;
		};
	}

	static void setPart(ServerLevel level, BlockPos pos, RobotStationBlock.Part part) {
		BlockState state = level.getBlockState(pos);

		if (state.getValue(RobotStationBlock.PART) != part) {
			level.setBlock(pos, state.setValue(RobotStationBlock.PART, part), Block.UPDATE_CLIENTS);
		}
	}

	/**
	 * A station was taken away: the groups of its neighbours are sorted out again. When it was a main station, the
	 * new main station of the group next to it takes over its settings and its list of robots (the robots move
	 * there by themselves, see {@link #newHome}).
	 */
	public static void removed(ServerLevel level, BlockPos pos, RobotStationBlock.Part part) {
		// only the look changed (or it was set again): nothing was taken away
		if (part == RobotStationBlock.Part.SINGLE || level.getBlockState(pos).is(RobotStationBlock.BLOCK)) {
			return;
		}

		boolean takenOver = false;

		for (Direction side : SIDES) {
			BlockPos neighbour = pos.relative(side);

			if (!isStation(level, neighbour)) {
				continue;
			}

			BlockPos main = update(level, neighbour);

			if (part == RobotStationBlock.Part.MAIN && !takenOver) {
				takenOver = true;
				takeOver(level, pos, main);
			}
		}
	}

	/** The settings and robots of a broken main station go over to the new main station of its group. */
	private static void takeOver(ServerLevel level, BlockPos old, BlockPos main) {
		StorageData data = StorageData.get();

		if (data == null || old.equals(main)) {
			return;
		}

		String dim = Rooms.id(level);
		// the tool chest may still stand beside the group; if not, the robots find none and it is set up again
		data.setStation(dim, main, data.station(dim, old).withStation(dim, main));
		Map<UUID, String> robots = data.stationRobots(dim, old);
		Map<UUID, String> target = data.stationRobots(dim, main);
		Map<UUID, String> moved = new LinkedHashMap<>(robots);
		robots.clear();
		moved.forEach(target::putIfAbsent);
		StorageData.markDirty();
		MiningJob.reset(dim, old);
		OreJob.reset(dim, old);
	}

	/**
	 * Where a robot of {@code station} belongs now, or null when it stays: a station that became an extension of a
	 * group sends its robots to the main station; a broken station sends them to the main station of a group that
	 * stood right next to it.
	 */
	public static BlockPos newHome(Level level, BlockPos station) {
		if (!level.isLoaded(station)) {
			return null;
		}

		BlockState state = level.getBlockState(station);

		if (state.is(RobotStationBlock.BLOCK)) {
			if (state.getValue(RobotStationBlock.PART) != RobotStationBlock.Part.MEMBER) {
				return null;
			}

			BlockPos main = main(level, station);
			return main.equals(station) ? null : main;
		}

		for (Direction side : SIDES) {
			BlockPos neighbour = station.relative(side);

			if (isStation(level, neighbour)) {
				return main(level, neighbour);
			}
		}

		return null;
	}

	/** Whether {@code pos} touches (one of the six sides) a station of the group, given by its members. */
	public static boolean beside(List<BlockPos> members, BlockPos pos) {
		for (BlockPos member : members) {
			if (member.distManhattan(pos) == 1) {
				return true;
			}
		}

		return false;
	}

	/** Server smoke test: three stations in a row form a group, its main station leads it, and taking it away hands over. */
	public static void smokeTest(ServerLevel level, List<String> errors) {
		String dim = Rooms.id(level);
		StorageData data = StorageData.get();
		BlockPos first = new BlockPos(-4100, 100, -4100);
		BlockPos second = first.east();
		BlockPos third = second.east();
		level.setChunkForced(first.getX() >> 4, first.getZ() >> 4, true);
		level.setBlockAndUpdate(first, RobotStationBlock.BLOCK.defaultBlockState());
		update(level, first);
		data.setStation(dim, first, data.station(dim, first).withStationName("Verbund-Test"));
		UUID robot = UUID.randomUUID();
		data.stationRobots(dim, first).put(robot, "Testi");
		level.setBlockAndUpdate(second, RobotStationBlock.BLOCK.defaultBlockState());
		update(level, second);
		level.setBlockAndUpdate(third, RobotStationBlock.BLOCK.defaultBlockState());
		update(level, third);

		if (level.getBlockState(first).getValue(RobotStationBlock.PART) != RobotStationBlock.Part.MAIN
				|| level.getBlockState(third).getValue(RobotStationBlock.PART) != RobotStationBlock.Part.MEMBER) {
			errors.add("Stations-Verbund: die erste Station ist nicht die Hauptstation");
		}

		if (!main(level, third).equals(first) || size(level, third) != 3 || maxRobots(3) != 96 || maxRange(3) != RobotEntity.RANGE + 32) {
			errors.add("Stations-Verbund: Größe oder Hauptstation stimmt nicht (" + size(level, third) + " Stationen)");
		}

		if (!first.equals(newHome(level, second)) || newHome(level, first) != null) {
			errors.add("Stations-Verbund: Roboter einer Erweiterung ziehen nicht zur Hauptstation");
		}

		// the main station is taken away: the next one takes over its settings and robots
		level.setBlockAndUpdate(first, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		removed(level, first, RobotStationBlock.Part.MAIN);

		if (level.getBlockState(second).getValue(RobotStationBlock.PART) != RobotStationBlock.Part.MAIN
				|| !data.stationRobots(dim, second).containsKey(robot) || !data.station(dim, second).stationName().equals("Verbund-Test")
				|| !second.equals(newHome(level, first))) {
			errors.add("Stations-Verbund: nach dem Abbau der Hauptstation übernimmt die nächste nicht");
		}

		level.setBlockAndUpdate(third, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		update(level, second);

		if (level.getBlockState(second).getValue(RobotStationBlock.PART) != RobotStationBlock.Part.SINGLE) {
			errors.add("Stations-Verbund: eine allein übrige Station sieht nicht wieder einzeln aus");
		}

		level.setBlockAndUpdate(second, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
		level.setChunkForced(first.getX() >> 4, first.getZ() >> 4, false);

		for (BlockPos pos : List.of(first, second, third)) {
			data.stations.getOrDefault(dim, Map.of()).remove(pos.asLong());
			data.stationRobots.getOrDefault(dim, Map.of()).remove(pos.asLong());
		}

		StorageData.markDirty();
	}
}
