package de.nomisimo.storagedim.robot;

import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Switching a station between collecting, mining (everything in the area) and the ore search (only the ores below it).
 * Mining needs pickaxes: from a filter chest the player put right next to the station (it hands out the pickaxes of
 * its ME storage), or else from a tool chest that is set up next to the station when mining is switched on (or the
 * one from before is used again). Axes and shovels are taken along when there are some, but not needed.
 */
public final class StationMining {
	private StationMining() {
	}

	/** What happened, for the player who pressed the button. */
	public record Result(boolean ok, Component message) {
	}

	/** The work mode button: collecting, then mining (with a tool chest next to the station), then the ore search, then collecting again. */
	public static Result toggle(ServerLevel level, String dim, BlockPos station) {
		StorageData data = StorageData.get();
		RobotConfig config = data.station(dim, station);

		if (config.mining() && config.oreSearch()) {
			data.setStation(dim, station, config.withMining(false).withOreSearch(false));
			MiningJob.reset(dim, station);
			OreJob.reset(dim, station);
			return new Result(true, Component.literal("Die Roboter sammeln wieder. Ihr Werkzeug bringen sie in die Werkzeugkiste zurück.")
					.withStyle(ChatFormatting.YELLOW));
		}

		if (config.mining()) {
			data.setStation(dim, station, config.withOreSearch(true));
			OreJob.reset(dim, station);
			return new Result(true, Component.literal("Erzsuche: Die Roboter spüren die Erze unter der Station auf, graben eine Wendeltreppe "
					+ "nach unten und Gänge zu den Erzen. Nur die Erze kommen in die Kisten.").withStyle(ChatFormatting.AQUA));
		}

		BlockPos filter = filterChestBeside(level, dim, station);

		if (filter != null) {
			data.setStation(dim, station, config.withMining(true).withOreSearch(false).withToolChest(filter));
			MiningJob.reset(dim, station);
			return new Result(true, Component.literal("Die Roboter holen ihre Spitzhacken aus der Filterkiste " + sideName(station, filter)
					+ " der Station, also aus dem ME-Speicher. Stell dort eine Spitzhacke als Filter ein. Äxte und Schaufeln nehmen sie mit, wenn welche da sind.")
					.withStyle(ChatFormatting.GREEN));
		}

		BlockPos chest = config.toolChest().filter(pos -> isToolChest(level, pos, station)).orElse(null);
		boolean placed = false;

		if (chest == null) {
			chest = freeSide(level, station);

			if (chest == null) {
				return new Result(false, Component.literal("Kein Platz für die Werkzeugkiste: neben der Station muss eine Seite frei sein.")
						.withStyle(ChatFormatting.RED));
			}

			BlockState state = Blocks.CHEST.defaultBlockState();

			// its front faces away from the station
			for (Direction side : Direction.Plane.HORIZONTAL) {
				if (station.relative(side).equals(chest)) {
					state = state.setValue(ChestBlock.FACING, side);
				}
			}

			level.setBlockAndUpdate(chest, state);
			placed = true;
		}

		data.setStation(dim, station, config.withMining(true).withOreSearch(false).withToolChest(chest));
		MiningJob.reset(dim, station);
		String side = sideName(station, chest);
		return new Result(true, Component.literal((placed ? "Werkzeugkiste " + side + " der Station aufgestellt. " : "Werkzeugkiste " + side + " der Station. ")
				+ "Leg für jeden Roboter eine Spitzhacke hinein (Axt und Schaufel gehen schneller, sind aber nicht nötig). "
				+ "Oder stell eine Filterkiste mit Spitzhacken-Filter neben die Station.").withStyle(ChatFormatting.GREEN));
	}

	/** The tool chest is still there, right next to the station. */
	public static boolean isToolChest(ServerLevel level, BlockPos pos, BlockPos station) {
		return pos.distManhattan(station) == 1 && level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof ChestBlock;
	}

	/**
	 * A filter chest right next to the station (on one of its six sides), placed there by a player: the robots take
	 * their tools from it, out of its ME storage. Null when there is none.
	 */
	public static BlockPos filterChestBeside(ServerLevel level, String dim, BlockPos station) {
		StorageData data = StorageData.get();

		if (data == null) {
			return null;
		}

		for (Direction side : Direction.values()) {
			BlockPos pos = station.relative(side);

			if (data.filterChest(dim, pos) != null && level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof ChestBlock) {
				return pos;
			}
		}

		return null;
	}

	/** A side of the station (north, east, south, west) with room for a chest: nothing there but air, grass or the like. */
	private static BlockPos freeSide(ServerLevel level, BlockPos station) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos pos = station.relative(side);
			BlockState state = level.getBlockState(pos);

			if (state.canBeReplaced() && state.getFluidState().isEmpty() && !hasChestBeside(level, pos)) {
				return pos;
			}
		}

		return null;
	}

	/** A chest right beside would join with the new one into a double chest. */
	private static boolean hasChestBeside(ServerLevel level, BlockPos pos) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (level.getBlockState(pos.relative(side)).is(Blocks.CHEST)) {
				return true;
			}
		}

		return false;
	}

	static String sideName(BlockPos station, BlockPos pos) {
		if (pos.getY() != station.getY()) {
			return pos.getY() > station.getY() ? "über" : "unter";
		}

		if (pos.getX() > station.getX()) {
			return "östlich";
		}

		if (pos.getX() < station.getX()) {
			return "westlich";
		}

		return pos.getZ() > station.getZ() ? "südlich" : "nördlich";
	}
}
