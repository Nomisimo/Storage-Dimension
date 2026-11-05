package de.nomisimo.storagedim.robot;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The work area of a robot station, marked by two antennas: one on top of the station and one at the opposite
 * corner. The area is the box from the station block to the corner antenna, both included, in all three directions.
 * Its name is set at the station (its colour is the station's colour), and the remote switches it on and off.
 */
public record StationArea(boolean base, Optional<BlockPos> corner, String name, boolean enabled) {
	/** Longest side of an area in blocks, like the largest range without antennas (10 chunks). */
	public static final int MAX_SIZE = RobotEntity.RANGE;
	public static final StationArea EMPTY = new StationArea(false, Optional.empty(), "", true);
	public static final Codec<StationArea> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.BOOL.optionalFieldOf("base", false).forGetter(StationArea::base),
			BlockPos.CODEC.optionalFieldOf("corner").forGetter(StationArea::corner),
			Codec.STRING.optionalFieldOf("name", "").forGetter(StationArea::name),
			Codec.BOOL.optionalFieldOf("enabled", true).forGetter(StationArea::enabled)
	).apply(i, StationArea::new));

	public StationArea withBase(boolean base) {
		return new StationArea(base, corner, name, enabled);
	}

	public StationArea withCorner(BlockPos corner) {
		return new StationArea(base, Optional.ofNullable(corner), name, enabled);
	}

	public StationArea withName(String name) {
		return new StationArea(base, corner, name, enabled);
	}

	public StationArea withEnabled(boolean enabled) {
		return new StationArea(base, corner, name, enabled);
	}

	/** Both antennas stand: the robots work only inside the box. */
	public boolean connected() {
		return base && corner.isPresent();
	}

	/** Some antenna stands, so the station no longer shows its range. */
	public boolean hasAntenna() {
		return base || corner.isPresent();
	}

	/** The box from the station block to the corner antenna, both included. */
	public AABB box(BlockPos station) {
		BlockPos c = corner.orElse(station);
		return new AABB(Math.min(station.getX(), c.getX()), Math.min(station.getY(), c.getY()), Math.min(station.getZ(), c.getZ()),
				Math.max(station.getX(), c.getX()) + 1, Math.max(station.getY(), c.getY()) + 1, Math.max(station.getZ(), c.getZ()) + 1);
	}

	public boolean contains(BlockPos station, Vec3 pos) {
		AABB box = box(station);
		// an item lying on the floor of the box sits a little above its lowest block
		return pos.x >= box.minX && pos.x <= box.maxX && pos.z >= box.minZ && pos.z <= box.maxZ && pos.y >= box.minY - 0.01 && pos.y <= box.maxY;
	}

	/** "5×3×5" (width × height × depth in blocks). */
	public String sizeText(BlockPos station) {
		AABB box = box(station);
		return (int) box.getXsize() + "×" + (int) box.getYsize() + "×" + (int) box.getZsize();
	}

	/** Longest horizontal distance from the station to a point of the box, for how far the robots plan their ways. */
	public int reach(BlockPos station) {
		AABB box = box(station);
		double dx = Math.max(Math.abs(box.minX - station.getX()), Math.abs(box.maxX - station.getX()));
		double dz = Math.max(Math.abs(box.minZ - station.getZ()), Math.abs(box.maxZ - station.getZ()));
		return (int) Math.ceil(Math.sqrt(dx * dx + dz * dz));
	}

	/** A corner antenna here would make the area too big (more than {@link #MAX_SIZE} blocks on a side). */
	public static boolean tooFar(BlockPos station, BlockPos corner) {
		return Math.abs(station.getX() - corner.getX()) >= MAX_SIZE || Math.abs(station.getY() - corner.getY()) >= MAX_SIZE
				|| Math.abs(station.getZ() - corner.getZ()) >= MAX_SIZE;
	}
}
