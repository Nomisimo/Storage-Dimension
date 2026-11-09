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
 * Switching a station between collecting and mining. Mining needs a tool chest: it is set up right next to the
 * station when mining is switched on (or the one from before is used again), and each robot takes a pickaxe, an axe
 * and a shovel from it.
 */
public final class StationMining {
	private StationMining() {
	}

	/** What happened, for the player who pressed the button. */
	public record Result(boolean ok, Component message) {
	}

	/** Switches the station's robots to mining (with a tool chest next to it) or back to collecting. */
	public static Result toggle(ServerLevel level, String dim, BlockPos station) {
		StorageData data = StorageData.get();
		RobotConfig config = data.station(dim, station);

		if (config.mining()) {
			data.setStation(dim, station, config.withMining(false));
			MiningJob.reset(dim, station);
			return new Result(true, Component.literal("Die Roboter sammeln wieder. Ihr Werkzeug bringen sie in die Werkzeugkiste zurück.")
					.withStyle(ChatFormatting.YELLOW));
		}

		BlockPos chest = config.toolChest().filter(pos -> isToolChest(level, pos, station)).orElse(null);
		boolean placed = false;

		if (chest == null) {
			chest = freeSide(level, station);

			if (chest == null) {
				return new Result(false, Component.literal("Kein Platz für die Werkzeugkiste: neben der Station muss eine Seite frei sein.")
						.withStyle(ChatFormatting.RED));
			}

			Direction facing = Direction.fromDelta(chest.getX() - station.getX(), 0, chest.getZ() - station.getZ());
			BlockState state = Blocks.CHEST.defaultBlockState();

			if (facing != null) {
				state = state.setValue(ChestBlock.FACING, facing);
			}

			level.setBlockAndUpdate(chest, state);
			placed = true;
		}

		data.setStation(dim, station, config.withMining(true).withToolChest(chest));
		MiningJob.reset(dim, station);
		String side = sideName(station, chest);
		return new Result(true, Component.literal((placed ? "Werkzeugkiste " + side + " der Station aufgestellt. " : "Werkzeugkiste " + side + " der Station. ")
				+ "Leg für jeden Roboter eine Spitzhacke, eine Axt und eine Schaufel hinein.").withStyle(ChatFormatting.GREEN));
	}

	/** The tool chest is still there, right next to the station. */
	public static boolean isToolChest(ServerLevel level, BlockPos pos, BlockPos station) {
		return pos.distManhattan(station) == 1 && level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof ChestBlock;
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
		if (pos.getX() > station.getX()) {
			return "östlich";
		}

		if (pos.getX() < station.getX()) {
			return "westlich";
		}

		return pos.getZ() > station.getZ() ? "südlich" : "nördlich";
	}
}
