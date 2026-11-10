package de.nomisimo.storagedim.robot;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** What the mining robots of a station dig together: the whole area ({@link MiningJob}) or only the ores below it ({@link OreJob}). */
public interface DigJob {
	/** Blocks for a robot standing at {@code from}, best first; others' and skipped ones left out. */
	List<BlockPos> candidates(ServerLevel level, BlockPos from, UUID robot, int count, long now);

	/** More blocks right around the robot, for when none of the candidates can be got at. */
	List<BlockPos> nearby(ServerLevel level, BlockPos from, UUID robot, int count, long now);

	/** Still a block to dig (not gone, not protected). */
	boolean wanted(ServerLevel level, BlockPos pos);

	/** The block was dug (or turned out to be gone). */
	void dug(ServerLevel level, BlockPos pos);

	void claim(BlockPos pos, UUID robot);

	void release(BlockPos pos, UUID robot);

	/** No robot found a way there: left out for a while. */
	void skip(BlockPos pos, long now);

	/** Nothing is left to dig. */
	boolean finished();

	/** The robot keeps what the block drops (everything when mining; only ores in the ore search). */
	default boolean keepsDrops(BlockState state) {
		return true;
	}
}
