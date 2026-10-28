package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import de.nomisimo.storagedim.StorageConfig;
import de.nomisimo.storagedim.storage.FilterChests;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import de.nomisimo.storagedim.storage.StorageMenu;
import de.nomisimo.storagedim.storage.StorageNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Walks around its home (up to {@link #RANGE} blocks, about 10 chunks, adjustable), picks up one stack of dropped items with
 * its beak and carries it to the nearest dimension chest, ME terminal or matching filter chest.
 * It is set up only at its robot station (range, filter, colour, name) and follows the station's settings while it works;
 * sneak + right-click with an empty hand picks it up again.
 */
public class RobotEntity extends PathfinderMob {
	public static final int RANGE = 160;
	private static final int MIN_RANGE = 16;
	/** Ticks between two looks at its station's settings. */
	private static final int SYNC_TICKS = 20;
	/** Push per tick while swimming, and its top speed in water. */
	/** Blocks next to the station the robots fill (chests, barrels, shulker boxes, ender chests); extendable by data packs. */
	public static final TagKey<Block> STORAGE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("storagedim", "robot_storage"));
	/** Blocks the robots never fill, even when their name sounds like storage (hoppers, droppers, machines). */
	public static final TagKey<Block> NOT_STORAGE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("storagedim", "robot_storage_excluded"));
	/** Other mods' storage blocks are recognised by their name, as long as they take items. */
	private static final List<String> STORAGE_WORDS = List.of("chest", "barrel", "crate", "drawer", "storage", "interface", "importer", "cabinet");

	/** Ticks without getting closer before it gives up on an item or chest for a while. */
	private static final int STUCK_TICKS = 100;
	private static final int IGNORE_TICKS = 1200;
	/** How many of the closest items or chests it checks for a way per search. */
	private static final int PATH_CHECKS = 4;
	/** Ticks between two searches for dropped items. */
	private static final int SEARCH_TICKS = 10;
	private static final int VERTICAL_RANGE = 48;
	public static final int DEFAULT_COLOR = 0xF06E1E;
	public static final int ACTION_TICKS = 14;
	/** Recall button presses per station ("dimension@packed position"), as game time. */
	private static final Map<String, Long> RECALLS = new HashMap<>();
	/** Who pressed the recall button of each station; the robots watch that player until they work again. */
	private static final Map<String, UUID> RECALLERS = new HashMap<>();
	/** Called back: teleports to the station after this many ticks without getting closer, then waits there this long. */
	private static final int RECALL_STUCK_TICKS = 200;
	/** Up to this many robots at a station, the recall gathers them within 3x3 blocks; more need 5x5. */
	private static final int RECALL_SMALL_AREA_ROBOTS = 20;
	private static final int RECALL_WAIT_TICKS = 1200;
	/** A robot loaded later than this after the button press does not follow the recall any more. */
	private static final int RECALL_VALID_TICKS = 2400;
	/** Wanting back to its station without getting any closer for 3 minutes, it teleports there. */
	private static final int HOME_STUCK_TICKS = 3600;
	private static final int RECALL_NONE = 0;
	private static final int RECALL_RETURNING = 1;
	private static final int RECALL_WAITING = 2;
	/** Item entities some robot is already walking to, so two robots do not chase the same one. */
	private static final Set<UUID> CLAIMED = new HashSet<>();

	private static final EntityDataAccessor<Integer> COLOR = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
	/** 0 = nothing, 1 = picking up, 2 = handing in; counts down from {@link #ACTION_TICKS}. */
	private static final EntityDataAccessor<Byte> ACTION = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Integer> ACTION_TIME = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Byte> SITTING = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BYTE);
	/** How it sits: {@link #MOOD_HAPPY} waiting at its station, {@link #MOOD_SAD} when it cannot work. */
	private static final EntityDataAccessor<Byte> MOOD = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BYTE);
	/** Lines for the look-at tooltip (Jade): station and range, what it collects, what it is doing. */
	private static final EntityDataAccessor<Component> INFO_STATION = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.COMPONENT);
	private static final EntityDataAccessor<Component> INFO_FILTER = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.COMPONENT);
	private static final EntityDataAccessor<Component> INFO_STATUS = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.COMPONENT);
	/** It cannot work (station gone, no chest, no way home): its name tag gets error icons. */
	private static final EntityDataAccessor<Boolean> ERROR = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BOOLEAN);
	/** The icon on both sides of the name while it has an error. */
	public static final String ERROR_ICON = "\ue5d0";
	public static final int MOOD_NONE = 0;
	public static final int MOOD_HAPPY = 1;
	public static final int MOOD_SAD = 2;
	/** Sitting after a recall, watching the player who called. */
	public static final int MOOD_WATCHING = 3;
	/** Close enough to its station to sit down and wait. */
	private static final double HOME_RADIUS = 3;
	/** How long it stays sad before trying to reach its station again. */
	private static final int RETRY_HOME_TICKS = 600;

	private UUID owner;
	private BlockPos home;
	private ItemEntity target;
	private BlockPos delivery;
	private int idleTicks;
	private int range = RANGE;
	/** Items it collects; empty = everything. */
	private final List<ItemStack> filter = new ArrayList<>();
	/** The filter lists what it leaves lying instead of the only things it collects. */
	private boolean excludeFilter;
	/** A robot barrier is right next to it (checked every tick). */
	private boolean nearBarrier;
	/** Items and chests it could not reach, with the game time until which it leaves them alone. */
	private final Map<UUID, Long> ignoredItems = new HashMap<>();
	private final Map<BlockPos, Long> ignoredChests = new HashMap<>();
	/** The way to the current item or chest, worked out once when it was chosen. */
	private Path plannedPath;
	private long nextSearch;
	/** The robot station it belongs to, or null for a robot without one (its home is where it was placed). */
	private BlockPos station;
	private long homeUnreachableUntil;
	/** No chest or other storage right next to its station. */
	private boolean noChest;
	/** Where it waits, out of the water (see {@link #restSpot()}). */
	private BlockPos restSpot;
	private long restSpotUntil;
	/** The last tick it was steering somewhere (for swimming at walking speed). */
	private long steeredAt = -10;
	/** Every chest next to its station is full (for what it carries or what lies around): it waits for room. */
	private boolean chestsFull;
	private String stationName = "";
	private long recallSeen;
	/** The player who called it back, watched while coming back and waiting. */
	private UUID recaller;
	private int recallPhase = RECALL_NONE;
	private long recallUntil;
	private double recallBest;
	private int recallStuck;
	private double homeBest = Double.MAX_VALUE;
	private int homeStuck;

	public RobotEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		setDropChance(EquipmentSlot.MAINHAND, 0);
		// dives: walks along the bottom and swims up to items floating at the top
		setPathfindingMalus(PathType.WATER, 0);
	}

	@Override
	public boolean canBreatheUnderwater() {
		return true;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 10)
				.add(Attributes.MOVEMENT_SPEED, 0.28)
				.add(Attributes.FOLLOW_RANGE, RANGE + 16)
				// hops up to two blocks, also onto fences and walls (see maxUpStep for robot barriers)
				.add(Attributes.STEP_HEIGHT, 2.05)
				.add(Attributes.SAFE_FALL_DISTANCE, 24);
	}

	public void setup(UUID owner, BlockPos home) {
		this.owner = owner;
		this.home = home;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(COLOR, DEFAULT_COLOR);
		builder.define(ACTION, (byte) 0);
		builder.define(ACTION_TIME, 0);
		builder.define(SITTING, (byte) 0);
		builder.define(MOOD, (byte) MOOD_NONE);
		builder.define(INFO_STATION, Component.empty());
		builder.define(INFO_FILTER, Component.empty());
		builder.define(INFO_STATUS, Component.empty());
		builder.define(ERROR, false);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(1, new WorkGoal());
		goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6));
		goalSelector.addGoal(7, new RandomLookAroundGoal(this));
	}

	// ---- state for the renderer ----

	public int getColor() {
		return entityData.get(COLOR);
	}

	public int getAction() {
		return entityData.get(ACTION);
	}

	/** 0 at the start of a pick-up or hand-in, 1 at its end. */
	public float getActionProgress() {
		return getAction() == 0 ? 0 : 1 - entityData.get(ACTION_TIME) / (float) ACTION_TICKS;
	}

	public boolean isSitting() {
		return entityData.get(SITTING) != 0;
	}

	private void setSitting(boolean sitting) {
		entityData.set(SITTING, (byte) (sitting ? 1 : 0));
	}

	public int getMood() {
		return entityData.get(MOOD);
	}

	private void setMood(int mood) {
		entityData.set(MOOD, (byte) mood);
	}

	/** The look-at tooltip lines (Jade): station and range, what it collects, what it is doing. */
	public List<Component> info() {
		return List.of(entityData.get(INFO_STATION), entityData.get(INFO_FILTER), entityData.get(INFO_STATUS));
	}

	// ---- configuration (robot station) ----

	/** Takes over range, filter, colour, name and station from a configured robot item. */
	public void applyConfig(RobotConfig config) {
		boolean sameDimension = config.stationDim().map(dim -> dim.equals(Rooms.id(level()))).orElse(false);
		station = sameDimension ? config.station().orElse(null) : null;

		if (station != null) {
			home = station;
		}

		applySettings(config, config.name());
	}

	private void applySettings(RobotConfig config, String name) {
		int newRange = Math.clamp(config.range(), MIN_RANGE, RANGE);

		if (newRange != range) {
			range = newRange;
			applyRange();
			release();
		}

		if (!filter.equals(config.filter()) || excludeFilter != config.exclude()) {
			filter.clear();
			filter.addAll(config.filter());
			excludeFilter = config.exclude();
			release();
		}

		entityData.set(COLOR, config.color());
		applyName(name);
		stationName = config.stationName();
	}

	private void applyName(String name) {
		if (!name.equals(getCustomName() == null ? "" : getCustomName().getString())) {
			setCustomName(name.isEmpty() ? null : Component.literal(name));
			setCustomNameVisible(!name.isEmpty());
		}
	}

	/**
	 * Keeps its own range, filter and colour (from the item it was placed from); from its station it only takes the
	 * station's name, its own entry in the station's list and the recall.
	 */
	private void syncWithStation() {
		StorageData data = StorageData.get();

		if (data == null || station == null) {
			return;
		}

		String dim = Rooms.id(level());
		String name = data.stationRobots(dim, station).get(getUUID());

		if (name == null) {
			name = StationRobots.register(dim, station, getUUID(), getCustomName() == null ? "" : getCustomName().getString());
		}

		applyName(name);
		stationName = data.station(dim, station).stationName();
		Long recall = RECALLS.get(dim + "@" + station.asLong());

		if (recall != null && recall > recallSeen && level().getGameTime() - recall < RECALL_VALID_TICKS) {
			recallSeen = recall;
			recaller = RECALLERS.get(dim + "@" + station.asLong());
			startRecall();
		}
	}

	/** The recall button of a station: all its robots come back, then wait there for a minute. Loaded ones set off at once. */
	public static void recall(ServerLevel level, String dim, BlockPos station, UUID player) {
		long now = level.getGameTime();
		RECALLS.put(dim + "@" + station.asLong(), now);
		RECALLERS.put(dim + "@" + station.asLong(), player);
		StorageData data = StorageData.get();

		if (data == null) {
			return;
		}

		for (UUID uuid : data.stationRobots(dim, station).keySet()) {
			if (level.getEntity(uuid) instanceof RobotEntity robot && station.equals(robot.station)) {
				robot.recallSeen = now;
				robot.recaller = player;
				robot.startRecall();
			}
		}
	}

	/** Called back, it counts as there inside 3x3 blocks around the station, or 5x5 when the station has more than 20 robots. */
	private boolean atStationForRecall() {
		BlockPos h = home();
		StorageData data = StorageData.get();
		int count = data == null || station == null ? 0 : data.stationRobots(Rooms.id(level()), station).size();
		double half = (count > RECALL_SMALL_AREA_ROBOTS ? 2 : 1) + 0.5;
		return Math.abs(getX() - (h.getX() + 0.5)) <= half && Math.abs(getZ() - (h.getZ() + 0.5)) <= half
				&& Math.abs(getY() - h.getY()) <= 1.5;
	}

	/** Called back and not getting closer: it only teleports when no way to the station can be found. */
	private void recallStuck() {
		Path path = getNavigation().createPath(home(), 1);

		if (path == null || !path.canReach()) {
			teleportHome();
		} else {
			getNavigation().moveTo(path, 1.0);
			recallBest = Double.MAX_VALUE;
			recallStuck = 0;
		}
	}

	private void startRecall() {
		release();
		getNavigation().stop();
		entityData.set(ACTION, (byte) 0);
		entityData.set(ACTION_TIME, 0);
		homeUnreachableUntil = 0;
		recallPhase = RECALL_RETURNING;
		recallBest = Double.MAX_VALUE;
		recallStuck = 0;
	}

	/** Called back: on the way to the station, or waiting there; stuck with no way there, it teleports to the station. */
	private void tickRecall() {
		long now = level().getGameTime();

		if (recallPhase == RECALL_RETURNING) {
			double distance = position().distanceTo(Vec3.atBottomCenterOf(home()));

			if (atStationForRecall()) {
				recallPhase = RECALL_WAITING;
				recallUntil = now + RECALL_WAIT_TICKS;
				getNavigation().stop();
			} else if (distance < recallBest - 0.5) {
				recallBest = distance;
				recallStuck = 0;
			} else if (++recallStuck >= RECALL_STUCK_TICKS) {
				recallStuck();
			}
		} else if (recallPhase == RECALL_WAITING && now >= recallUntil) {
			recallPhase = RECALL_NONE;
			recaller = null;
		} else if (recallPhase == RECALL_WAITING && now % 10 == getId() % 10 && getNavigation().isDone() && onGround()
				&& takenByRobot(blockPosition())) {
			// someone already waits on this block: it steps aside to a free spot by the station
			BlockPos spot = spreadSpot();
			getNavigation().moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0);
		}
	}

	/** Called back, it keeps its eyes on the player who called until it works again. */
	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		Player player = watchedPlayer();

		if (player != null) {
			getLookControl().setLookAt(player, 30, 30);
		}
	}

	private Player watchedPlayer() {
		if (recallPhase == RECALL_NONE || recaller == null) {
			return null;
		}

		Player player = level().getPlayerByUUID(recaller);
		return player != null && player.isAlive() && distanceToSqr(player) < 64 * 64 ? player : null;
	}

	/** Safety net: a station robot that wants home but has not got closer for 3 minutes teleports there. */
	private void tickHomeStuck() {
		boolean wantsHome = station != null && recallPhase == RECALL_NONE && !nearHome() && target == null
				&& getCarried().isEmpty() && getAction() == 0 && !stationMissing();

		if (!wantsHome) {
			homeBest = Double.MAX_VALUE;
			homeStuck = 0;
			return;
		}

		double distance = position().distanceTo(Vec3.atBottomCenterOf(home()));

		if (distance < homeBest - 0.5) {
			homeBest = distance;
			homeStuck = 0;
		} else if (++homeStuck >= HOME_STUCK_TICKS) {
			teleportHome();
			homeUnreachableUntil = 0;
			homeBest = Double.MAX_VALUE;
			homeStuck = 0;
		}
	}

	/**
	 * A spot by the station to stand on: free, on solid ground and with no other robot on it, so a recall
	 * spreads them out instead of piling them on one block. Closest ring first, a random one within it.
	 */
	private BlockPos spreadSpot() {
		BlockPos h = home();
		BlockPos fallback = null;

		for (int r = 1; r <= 3; r++) {
			List<BlockPos> ring = new ArrayList<>();

			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}

					for (int dy = -1; dy <= 1; dy++) {
						BlockPos p = h.offset(dx, dy, dz);

						if (free(p) && free(p.above()) && !free(p.below())) {
							if (fallback == null) {
								fallback = p;
							}

							if (!takenByRobot(p)) {
								ring.add(p);
							}

							break;
						}
					}
				}
			}

			if (!ring.isEmpty()) {
				return ring.get(getRandom().nextInt(ring.size()));
			}
		}

		return fallback != null ? fallback : h.above();
	}

	private boolean takenByRobot(BlockPos pos) {
		return !level().getEntitiesOfClass(RobotEntity.class, new AABB(pos).inflate(-0.1, 0, -0.1), robot -> robot != this).isEmpty();
	}

	private void teleportHome() {
		BlockPos spot = spreadSpot();

		ServerLevel server = (ServerLevel) level();
		server.sendParticles(ParticleTypes.PORTAL, getX(), getY() + 0.4, getZ(), 24, 0.3, 0.4, 0.3, 0.2);
		getNavigation().stop();
		teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		server.sendParticles(ParticleTypes.PORTAL, getX(), getY() + 0.4, getZ(), 24, 0.3, 0.4, 0.3, 0.2);
		playSound(SoundEvents.CHORUS_FRUIT_TELEPORT, 0.4F, 1.6F);
		recallBest = Double.MAX_VALUE;
		recallStuck = 0;
	}

	private boolean free(BlockPos pos) {
		return level().getBlockState(pos).getCollisionShape(level(), pos).isEmpty() && level().getFluidState(pos).isEmpty();
	}

	/** Its own settings (each robot keeps those of the item it was placed from), for the item it turns into when picked up. */
	public RobotConfig config() {
		RobotConfig config = new RobotConfig(range, getColor(), getCustomName() == null ? "" : getCustomName().getString(), List.copyOf(filter),
				Optional.empty(), Optional.empty(), excludeFilter, stationName);
		return station == null ? config : config.withStation(Rooms.id(level()), station);
	}

	public BlockPos station() {
		return station;
	}

	/** What it is doing, for its station's list and the look-at tooltip. */
	public Component status() {
		if (recallPhase == RECALL_RETURNING) {
			return Component.literal("Rückruf: auf dem Weg").withStyle(ChatFormatting.AQUA);
		}

		if (recallPhase == RECALL_WAITING) {
			long seconds = Math.max(0, (recallUntil - level().getGameTime()) / 20);
			return Component.literal("Rückruf: Pause " + seconds + " s").withStyle(ChatFormatting.AQUA);
		}

		if (stationMissing()) {
			return Component.literal("Fehler: Station fehlt").withStyle(ChatFormatting.RED);
		}

		// while it is busy it shows what it does; an old error from before does not count
		if (!getCarried().isEmpty() && delivery != null) {
			return Component.literal("Bringt ").append(getCarried().getHoverName()).append(" weg").withStyle(ChatFormatting.GREEN);
		}

		if (target != null) {
			return Component.literal("Holt ").append(target.getItem().getHoverName()).withStyle(ChatFormatting.GREEN);
		}

		if (noChest) {
			return Component.literal("Fehler: keine Kiste an der Station").withStyle(ChatFormatting.RED);
		}

		if (chestsFull) {
			return Component.literal("Fehler: Kisten voll").withStyle(ChatFormatting.RED);
		}

		if (level().getGameTime() < homeUnreachableUntil) {
			return Component.literal("Fehler: Weg versperrt").withStyle(ChatFormatting.RED);
		}

		if (!getCarried().isEmpty()) {
			return Component.literal("Bringt ").append(getCarried().getHoverName()).append(" weg").withStyle(ChatFormatting.GREEN);
		}

		if (target != null) {
			return Component.literal("Holt ").append(target.getItem().getHoverName()).withStyle(ChatFormatting.GREEN);
		}

		return Component.literal(isSitting() ? "Wartet fröhlich" : "Wartet").withStyle(ChatFormatting.YELLOW);
	}

	private void updateInfo() {
		entityData.set(INFO_STATION, Component.literal("Station: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(station == null ? "keine" : stationName.isEmpty() ? station.toShortString() : stationName).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" · " + range / 16 + (range == 16 ? " Chunk" : " Chunks")).withStyle(ChatFormatting.GRAY)));
		entityData.set(INFO_FILTER, Component.literal("Sammelt: ").withStyle(ChatFormatting.GRAY)
				.append(RobotConfig.describeFilter(filter, excludeFilter).withStyle(ChatFormatting.WHITE)));
		entityData.set(INFO_STATUS, status());
		entityData.set(ERROR, recallPhase == RECALL_NONE && (stationMissing()
				|| !isBusy() && (noChest || chestsFull || level().getGameTime() < homeUnreachableUntil)));
	}

	/** On its way to an item or a chest, or picking up / putting in right now. */
	private boolean isBusy() {
		return target != null || delivery != null || getAction() != 0;
	}

	public boolean hasError() {
		return entityData.get(ERROR);
	}

	/** The name with an error icon on each side while it cannot work; shown on the name tag, in Jade and in the station's list. */
	public static Component withErrorIcons(Component name) {
		// the icon is a red "no entry" sign from the mod's font glyph; uncoloured so it keeps its own colours
		return Component.literal(ERROR_ICON + " ").append(name).append(" " + ERROR_ICON);
	}

	/** The name in the robot's colour (name tag, Jade, station list); error icons keep their own colours. */
	@Override
	public Component getName() {
		Component name = hasCustomName() ? super.getName().copy().withColor(nameColor(getColor())) : super.getName();
		return hasError() ? withErrorIcons(name) : name;
	}

	/** The robot's colour for its name, lightened when very dark so it can still be read on the dark name tag. */
	public static int nameColor(int color) {
		int r = color >> 16 & 0xFF;
		int g = color >> 8 & 0xFF;
		int b = color & 0xFF;

		if (0.299 * r + 0.587 * g + 0.114 * b >= 60) {
			return color & 0xFFFFFF;
		}

		r += (255 - r) * 45 / 100;
		g += (255 - g) * 45 / 100;
		b += (255 - b) * 45 / 100;
		return r << 16 | g << 8 | b;
	}

	/** The robot as an item, keeping its settings. */
	public ItemStack toItem() {
		ItemStack item = new ItemStack(Robots.ROBOT_ITEM);
		config().write(item, level().registryAccess());
		return item;
	}

	/** Its station was broken (only checked where the chunk is loaded). */
	private boolean stationMissing() {
		return station != null && level().isLoaded(station) && !level().getBlockState(station).is(RobotStationBlock.BLOCK);
	}

	private boolean nearHome() {
		return position().distanceTo(Vec3.atBottomCenterOf(home())) <= HOME_RADIUS + 0.5;
	}

	/** Has nothing to do right now, so another robot may take an item this one would otherwise get. */
	private boolean isFree() {
		return target == null && getCarried().isEmpty() && getAction() == 0;
	}

	/** Would collect this item: in range and allowed by the filter. */
	private boolean wants(ItemEntity item) {
		return inRange(item.position()) && allows(item.getItem());
	}

	/** Allowed by its filter: everything without one, else only the listed items (or all but them). */
	private boolean allows(ItemStack stack) {
		return filter.isEmpty() || FilterChests.matches(filter, stack) != excludeFilter;
	}

	/** Where its chests stand: around its station, or around where it was put down when it has none. */
	private BlockPos storageCentre() {
		return station != null ? station : home();
	}

	/** The chests and other storage blocks right next to its station (the six touching sides), in a fixed order. */
	private List<BlockPos> stationContainers() {
		List<BlockPos> containers = new ArrayList<>();
		BlockPos centre = storageCentre();

		for (Direction side : Direction.values()) {
			BlockPos pos = centre.relative(side);

			if (level().isLoaded(pos) && isContainer(pos, side.getOpposite())) {
				containers.add(pos);
			}
		}

		return containers;
	}

	/** A storage block the robots may fill: dimension, filter and ME blocks, chests, barrels, shulker boxes, ender chests, mod storage. */
	private boolean isContainer(BlockPos pos, Direction side) {
		StorageData data = StorageData.get();
		String dim = Rooms.id(level());

		if (data != null && (data.chestOwner(dim, pos) != null || data.filterChest(dim, pos) != null || owner != null && data.isTerminal(dim, pos))) {
			return true;
		}

		BlockState state = level().getBlockState(pos);
		return !state.is(NOT_STORAGE) && (state.is(Blocks.ENDER_CHEST) || looksLikeStorage(state) && ItemStorage.SIDED.find(level(), pos, side) != null);
	}

	private static boolean looksLikeStorage(BlockState state) {
		if (state.is(STORAGE)) {
			return true;
		}

		String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return STORAGE_WORDS.stream().anyMatch(name::contains);
	}

	/**
	 * Puts as much of the stack as fits into the block at {@code pos} (or only works it out when simulating) and says how many
	 * that is. ME storage takes everything; a plain ender chest is the robot owner's, so only while they are online.
	 */
	private int store(BlockPos pos, ItemStack stack, boolean simulate) {
		StorageNetwork network = networkAt(pos, stack);

		if (network != null) {
			if (!simulate) {
				network.insert(stack.copy());
				StorageMenu.refreshAll(network);
			}

			return stack.getCount();
		}

		StorageData data = StorageData.get();
		String dim = Rooms.id(level());

		// a filter chest for other items, or a storage block whose owner is gone
		if (data != null && (data.chestOwner(dim, pos) != null || data.filterChest(dim, pos) != null || data.isTerminal(dim, pos))) {
			return 0;
		}

		BlockState state = level().getBlockState(pos);
		Storage<ItemVariant> storage = null;

		if (state.is(NOT_STORAGE)) {
			return 0;
		} else if (state.is(Blocks.ENDER_CHEST)) {
			Player player = owner == null ? null : level().getPlayerByUUID(owner);
			storage = player == null ? null : ContainerStorage.of(player.getEnderChestInventory(), null);
		} else if (looksLikeStorage(state)) {
			// filled from the side facing the station, like a hopper there would
			Direction side = null;

			for (Direction direction : Direction.values()) {
				if (pos.relative(direction).equals(storageCentre())) {
					side = direction;
				}
			}

			storage = ItemStorage.SIDED.find(level(), pos, side);
		}

		if (storage == null || stack.isEmpty()) {
			return 0;
		}

		try (Transaction transaction = Transaction.openOuter()) {
			long stored = storage.insert(ItemVariant.of(stack), stack.getCount(), transaction);

			if (!simulate) {
				transaction.commit();
			}

			return (int) stored;
		}
	}

	public ItemStack getCarried() {
		return getItemBySlot(EquipmentSlot.MAINHAND);
	}

	/** The items it collects (empty = everything), or with the exclude switch those it leaves lying. */
	public List<ItemStack> filter() {
		return filter;
	}

	/** For debugging and the client test: what the robot is doing right now. */
	public String describe() {
		return String.format("speed=%.3f water=%s name=%s color=%06x error=%s range=%d filter=%s%s station=%s mood=%d noChest=%s full=%s ", getDeltaMovement().horizontalDistance(), isInWater(), getCustomName() == null ? "" : getCustomName().getString(), getColor(), hasError(), range, excludeFilter ? "except " : "", filter, station, getMood(), noChest, chestsFull) + String.format("pos=%.1f/%.1f/%.1f home=%s target=%s delivery=%s carried=%s action=%d sitting=%s pathDone=%s goals=%s",
				getX(), getY(), getZ(), home, target == null ? null : target.position(), delivery, getCarried(), getAction(), isSitting(),
				getNavigation().isDone(), goalSelector.getAvailableGoals().stream().filter(g -> g.isRunning()).map(g -> g.getGoal().getClass().getSimpleName()).toList());
	}

	// ---- behaviour ----

	@Override
	public void tick() {
		super.tick();

		if (level().isClientSide() || getAction() == 0) {
			return;
		}

		int time = entityData.get(ACTION_TIME) - 1;
		entityData.set(ACTION_TIME, time);

		if (time <= 0) {
			if (getAction() == 1) {
				finishPickup();
			} else {
				finishDelivery();
			}

			entityData.set(ACTION, (byte) 0);
		}
	}

	private void startAction(int action) {
		getNavigation().stop();
		entityData.set(ACTION, (byte) action);
		entityData.set(ACTION_TIME, ACTION_TICKS);

		if (action == 2 && delivery != null) {
			setLid(delivery, true);
		}
	}

	/** The chest it opened to put something in, closed again when it is done (or interrupted). */
	private BlockPos openLid;

	/**
	 * Opens or closes the lid of the chest, barrel, shulker or ender chest it hands in to, with the sound a player
	 * makes. Blocks of other mods without a lid stay as they are.
	 */
	/** A chest with a solid block (or a cat) on top cannot open, nor can a shulker box with something in front of its lid. */
	private boolean lidBlocked(BlockPos pos, BlockState state, boolean shulker) {
		if (shulker) {
			BlockPos front = pos.relative(state.getValue(net.minecraft.world.level.block.ShulkerBoxBlock.FACING));
			return !level().getBlockState(front).getCollisionShape(level(), front).isEmpty();
		}

		return net.minecraft.world.level.block.ChestBlock.isChestBlockedAt(level(), pos);
	}

	private void setLid(BlockPos pos, boolean open) {
		if (open && openLid != null) {
			setLid(openLid, false);
		}

		openLid = open ? pos.immutable() : null;

		if (!level().isLoaded(pos)) {
			return;
		}

		BlockState state = level().getBlockState(pos);

		if (state.hasProperty(net.minecraft.world.level.block.BarrelBlock.OPEN)) {
			level().setBlock(pos, state.setValue(net.minecraft.world.level.block.BarrelBlock.OPEN, open), Block.UPDATE_ALL);

			if (StorageConfig.get().robotChestSound) {
				level().playSound(null, pos, open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE, net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 1.0F);
			}

			return;
		}

		var entity = level().getBlockEntity(pos);
		boolean shulker = entity instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

		if (!(entity instanceof net.minecraft.world.level.block.entity.LidBlockEntity) && !shulker) {
			return;
		}

		// like for players, a lid with a block on it stays shut; the robot still puts its items in
		if (open && lidBlocked(pos, state, shulker)) {
			openLid = null;
			return;
		}

		// a player looking into it keeps it open
		if (!open && level().getEntitiesOfClass(Player.class, new AABB(pos).inflate(8), player -> player.containerMenu != player.inventoryMenu).size() > 0) {
			return;
		}

		level().blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);

		if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
				!= net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
			BlockPos other = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
			level().blockEvent(other, level().getBlockState(other).getBlock(), 1, open ? 1 : 0);
		}

		if (!StorageConfig.get().robotChestSound) {
			return;
		}

		var sound = shulker ? (open ? SoundEvents.SHULKER_BOX_OPEN : SoundEvents.SHULKER_BOX_CLOSE)
				: state.is(Blocks.ENDER_CHEST) ? (open ? SoundEvents.ENDER_CHEST_OPEN : SoundEvents.ENDER_CHEST_CLOSE)
				: open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE;
		level().playSound(null, pos, sound, net.minecraft.sounds.SoundSource.BLOCKS, 0.5F, 1.0F);
	}

	private BlockPos home() {
		if (home == null) {
			home = blockPosition();
		}

		return home;
	}

	private boolean inRange(Vec3 pos) {
		BlockPos h = home();
		double dx = pos.x - h.getX();
		double dz = pos.z - h.getZ();
		return dx * dx + dz * dz <= range * range && Math.abs(pos.y - h.getY()) <= VERTICAL_RANGE;
	}

	/**
	 * The closest item it can walk to. Items whose way is blocked (walled in, behind a robot barrier, across lava)
	 * are found out here, before it sets off, and left alone for a minute.
	 */
	private ItemEntity findItem() {
		// a way can only be worked out standing (or swimming); try again on the next search
		if (!onGround() && !isInLiquid()) {
			return null;
		}

		BlockPos h = home();
		AABB area = new AABB(h).inflate(range, VERTICAL_RANGE, range);
		long now = level().getGameTime();
		ignoredItems.values().removeIf(until -> until < now);
		List<ItemEntity> items = new ArrayList<>(level().getEntitiesOfClass(ItemEntity.class, area,
				item -> item.isAlive() && !CLAIMED.contains(item.getUUID()) && !ignoredItems.containsKey(item.getUUID())
						&& inRange(item.position()) && allows(item.getItem())));
		// only what a chest next to the station has room for; one check per kind of item
		Map<ItemVariant, Boolean> fits = new HashMap<>();
		boolean anyWanted = !items.isEmpty();
		items.removeIf(item -> !fits.computeIfAbsent(ItemVariant.of(item.getItem()), kind -> hasRoom(item.getItem())));
		chestsFull = anyWanted && items.isEmpty();
		items.sort(java.util.Comparator.comparingDouble(this::distanceToSqr));
		// swarm: an item a free robot is clearly closer to is left for that one
		List<RobotEntity> others = level().getEntitiesOfClass(RobotEntity.class, area, robot -> robot != this && robot.isFree());
		items.removeIf(item -> others.stream().anyMatch(other -> other.wants(item) && other.distanceToSqr(item) + 2 < distanceToSqr(item)));

		for (int i = 0; i < Math.min(items.size(), PATH_CHECKS); i++) {
			ItemEntity item = items.get(i);
			Path path = getNavigation().createPath(item, 1);

			if (path != null && path.canReach()) {
				plannedPath = path;
				return item;
			}

			// deep down in water no walking path leads there, but it can swim: as far as the water allows, then straight
			if (item.isInWater()) {
				path = intoWater(item, path);

				if (path != null) {
					plannedPath = path;
					return item;
				}
			}

			ignoredItems.put(item.getUUID(), now + IGNORE_TICKS);
		}

		return null;
	}

	/** Picks the closest chest for what it carries that it can walk to; unreachable ones are left alone for a minute. */
	private BlockPos planDelivery() {
		// a way can only be worked out standing (or swimming); try again on the next search
		if (!onGround() && !isInLiquid()) {
			return null;
		}

		long now = level().getGameTime();
		noChest = stationContainers().isEmpty();

		for (int i = 0; i < PATH_CHECKS; i++) {
			BlockPos chest = findDelivery(getCarried());

			if (chest == null) {
				// nothing next to the station has room for it: it waits there and looks again and again (a chest it
				// only cannot get to right now does not count as full)
				chestsFull = !noChest && !hasRoom(getCarried());
				return null;
			}

			chestsFull = false;

			Path path = getNavigation().createPath(chest, 3);

			if (path != null && path.canReach()) {
				plannedPath = path;
				return chest;
			}

			// down in deep water no walking path leads out; it swims up and towards the chest instead
			if (isInWater()) {
				plannedPath = path;
				return chest;
			}

			ignoredChests.put(chest, now + IGNORE_TICKS);
		}

		return null;
	}

	/** Some chest next to its station takes (some of) this stack, also one it cannot get to right now. */
	private boolean hasRoom(ItemStack stack) {
		return !stack.isEmpty() && stationContainers().stream().anyMatch(pos -> store(pos, stack, true) > 0);
	}

	/**
	 * The chest next to its station that takes (some of) this stack, or null when none has room. Matching filter chests
	 * come first, then the others in a fixed order, so they fill up one after the other.
	 */
	private BlockPos findDelivery(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}

		long now = level().getGameTime();
		ignoredChests.values().removeIf(until -> until < now);
		StorageData data = StorageData.get();
		String dim = Rooms.id(level());
		BlockPos fallback = null;

		for (BlockPos pos : stationContainers()) {
			if (ignoredChests.containsKey(pos) || store(pos, stack, true) <= 0) {
				continue;
			}

			if (data != null && data.filterChest(dim, pos) != null) {
				return pos;
			}

			if (fallback == null) {
				fallback = pos;
			}
		}

		return fallback;
	}

	private void finishPickup() {
		if (target != null && target.isAlive() && distanceToSqr(target) < 6.25 && getCarried().isEmpty()) {
			ItemStack stack = target.getItem();
			ItemStack taken = stack.split(stack.getMaxStackSize());
			setItemSlot(EquipmentSlot.MAINHAND, taken);

			if (stack.isEmpty()) {
				target.discard();
			} else {
				target.setItem(stack);
			}

			playSound(SoundEvents.ITEM_PICKUP, 0.4F, 1.4F);
		}

		release();
	}

	private void finishDelivery() {
		ItemStack carried = getCarried();
		int stored = delivery == null || carried.isEmpty() ? 0 : store(delivery, carried, false);

		if (stored > 0) {
			// what did not fit stays in its beak and goes to the next chest
			ItemStack rest = carried.copy();
			rest.shrink(stored);
			setItemSlot(EquipmentSlot.MAINHAND, rest);
			// it stood at its station's chests, so the way home is clearly open again
			homeUnreachableUntil = 0;
			chestsFull = false;
		}

		if (openLid != null) {
			setLid(openLid, false);
		}

		delivery = null;
		nextSearch = 0;
	}

	/** The network the chest at {@code pos} feeds, if it is still there and takes the stack. */
	private StorageNetwork networkAt(BlockPos pos, ItemStack stack) {
		StorageData data = StorageData.get();

		if (data == null) {
			return null;
		}

		String dim = Rooms.id(level());
		UUID chestOwner = data.chestOwner(dim, pos);

		if (chestOwner != null && level().getBlockState(pos).is(Blocks.CHEST)) {
			return data.networkFor(chestOwner);
		}

		FilterChests.FilterChest filter = data.filterChest(dim, pos);

		if (filter != null && FilterChests.matches(filter.filters(), stack)) {
			return data.networkFor(filter.owner());
		}

		if (owner != null && data.isTerminal(dim, pos)) {
			return data.networkFor(owner);
		}

		return null;
	}

	private void release() {
		plannedPath = null;

		if (openLid != null) {
			setLid(openLid, false);
		}

		if (target != null) {
			CLAIMED.remove(target.getUUID());
			target = null;
		}
	}

	/** Goes for items, carries them home, or walks back when it strayed too far. */
	private class WorkGoal extends Goal {
		private int cooldown;
		private Object lastGoal;
		/** The spot it last aimed for on the way to a chest (an open side of it, or open water to come up). */
		private Vec3 lastSteer;
		private double bestDistance;
		private Path lastPath;
		private int lastNode;
		private int stuckTicks;

		WorkGoal() {
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			if (getAction() != 0) {
				return true;
			}

			if (cooldown-- > 0) {
				return false;
			}

			cooldown = SEARCH_TICKS;
			return hasWork();
		}

		@Override
		public boolean canContinueToUse() {
			return getAction() != 0 || hasWork();
		}

		private boolean hasWork() {
			if (recallPhase == RECALL_RETURNING) {
				return true;
			}

			if (recallPhase == RECALL_WAITING) {
				return false;
			}

			if (!getCarried().isEmpty()) {
				if (delivery == null && level().getGameTime() >= nextSearch) {
					nextSearch = level().getGameTime() + SEARCH_TICKS;
					delivery = planDelivery();
				}

				// no chest has room: it carries the item home and waits there until there is
				return delivery != null || (!nearHome() || isInWater()) && level().getGameTime() >= homeUnreachableUntil;
			}

			if (stationMissing()) {
				release();
				return false;
			}

			if ((target == null || !target.isAlive()) && level().getGameTime() >= nextSearch) {
				nextSearch = level().getGameTime() + SEARCH_TICKS;
				release();
				noChest = stationContainers().isEmpty();
				target = noChest ? null : findItem();

				if (noChest) {
					chestsFull = false;
				}

				if (target != null) {
					CLAIMED.add(target.getUUID());
				}
			}

			// nothing to collect: back to the station (or where it was placed) to wait there
			return target != null || (!nearHome() || isInWater()) && level().getGameTime() >= homeUnreachableUntil;
		}

		@Override
		public void start() {
			setSitting(false);
			idleTicks = 0;
		}

		@Override
		public void stop() {
			getNavigation().stop();
			release();
		}

		/**
		 * Gives up on an item or chest it has not got any closer to for a while (walled in, in lava, out of reach),
		 * so it does not stand there forever; it leaves that one alone for a minute and takes the next.
		 */
		private boolean gaveUp(Object goalKey, double distance) {
			if (goalKey != lastGoal) {
				lastGoal = goalKey;
				bestDistance = distance;
				stuckTicks = 0;
				return false;
			}

			if (distance < bestDistance - 0.5) {
				bestDistance = distance;
				stuckTicks = 0;
				return false;
			}

			// a way round (out of a deep pool, up a ramp and back down) first leads away: getting on along it counts too
			Path path = getNavigation().getPath();

			if (path != lastPath) {
				lastPath = path;
				lastNode = path == null ? 0 : path.getNextNodeIndex();
			} else if (path != null && path.getNextNodeIndex() > lastNode) {
				lastNode = path.getNextNodeIndex();
				stuckTicks = 0;
				return false;
			}

			if (++stuckTicks < STUCK_TICKS) {
				return false;
			}

			stuckTicks = 0;
			lastGoal = null;
			getNavigation().stop();
			long until = level().getGameTime() + IGNORE_TICKS;

			if (goalKey == delivery) {
				ignoredChests.put(delivery, until);
				delivery = planDelivery();
			} else if (goalKey == home) {
				// crowded by the other robots right at the station is fine; really stuck further away is not
				if (distance > HOME_RADIUS + 3 && !isInWater()) {
					homeUnreachableUntil = level().getGameTime() + RETRY_HOME_TICKS;
				}
			} else if (goalKey == target) {
				ignoredItems.put(target.getUUID(), until);
				release();
				target = findItem();

				if (target != null) {
					CLAIMED.add(target.getUUID());
				}
			}

			return true;
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			if (getAction() != 0) {
				Vec3 look = target != null ? target.position() : delivery != null ? Vec3.atCenterOf(delivery) : null;

				if (look != null) {
					getLookControl().setLookAt(look.x, look.y, look.z);
				}

				return;
			}

			Vec3 goal;
			double reach;
			Object goalKey;

			if (recallPhase == RECALL_RETURNING) {
				goal = Vec3.atBottomCenterOf(home());
				reach = HOME_RADIUS;
				goalKey = home();
			} else if (!getCarried().isEmpty() && delivery != null) {
				goal = Vec3.atCenterOf(delivery);
				reach = 1.2;
				goalKey = delivery;
			} else if (target != null) {
				goal = target.position();
				reach = 1.8;
				goalKey = target;
			} else if (nearHome() && isInWater()) {
				// waits only on dry, solid ground: out of the water onto the closest such spot by the station
				goal = Vec3.atBottomCenterOf(restSpot());
				reach = 0.6;
				goalKey = restSpot();
			} else {
				goal = Vec3.atBottomCenterOf(home());
				reach = HOME_RADIUS;
				goalKey = home();
			}

			getLookControl().setLookAt(goal.x, goal.y, goal.z);
			double distance = position().distanceTo(goal);
			steeredAt = level().getGameTime();
			boolean arrived = recallPhase == RECALL_RETURNING ? atStationForRecall()
					: goalKey == target ? standsOn(target)
					: goalKey == delivery ? (distance <= reach || canReachChest(goal)) && seesChest(delivery) : distance <= reach;

			if (arrived) {
				if (recallPhase == RECALL_RETURNING) {
					getNavigation().stop();
				} else if (!getCarried().isEmpty() && delivery != null) {
					startAction(2);
				} else if (target != null) {
					startAction(1);
				} else {
					getNavigation().stop();
				}

				return;
			}

			// a chest it cannot see from here (e.g. from below, through the slabs it stands on): aim for an open side of it,
			// and when it swims below a ceiling, first for open water where it can come up
			Vec3 approach = goal;

			if (goalKey == delivery && !seesChest(delivery)) {
				BlockPos side = openSide(delivery);
				approach = side == null ? goal : Vec3.atBottomCenterOf(side);

				if (side != null && isInWater() && !clearTo(getEyePosition(), new Vec3(getX(), approach.y + 0.5, getZ()), null, null)) {
					Vec3 exit = waterExit(approach);

					if (exit != null) {
						approach = exit;
					}
				}
			}

			// called back, it keeps trying (and teleports when stuck) instead of giving up; on the way to a chest, getting
			// closer to the spot it aims for counts
			if (goalKey == delivery && (lastSteer == null || lastSteer.distanceToSqr(approach) > 1)) {
				// a new spot to aim for: the progress so far is measured afresh
				lastSteer = approach;
				lastGoal = null;
			}

			if (recallPhase != RECALL_RETURNING && gaveUp(goalKey, goalKey == delivery ? position().distanceTo(approach) : distance)) {
				return;
			}

			// the path ends on the block next to the goal; walk the last bit straight
			if (getNavigation().isDone() && position().distanceTo(approach) < reach + 2) {
				getMoveControl().setWantedPosition(approach.x, approach.y, approach.z, 1.0);
				return;
			}

			// in water it swims straight to its goal once no walking path is left, up, down and across; standing at the
			// edge of the water with the goal below it, it jumps in
			if (plannedPath == null && getNavigation().isDone() && (isInWater() || goalInWaterBelow(goal))) {
				if (approach != goal) {
					// out of the water to the open side, where it can stand next to the chest
					Path path = getNavigation().createPath(BlockPos.containing(approach), 0);

					if (path != null && path.canReach()) {
						getNavigation().moveTo(path, 1.0);
						return;
					}
				}

				getMoveControl().setWantedPosition(approach.x, approach.y, approach.z, 1.0);
				return;
			}

			// follow the way worked out when the goal was chosen; only look for a new one when it ends early
			if (plannedPath != null) {
				getNavigation().moveTo(plannedPath, 1.0);
				plannedPath = null;
			} else if (getNavigation().isDone() && goalKey == home) {
				Path path = getNavigation().createPath(home, 2);

				if (path == null) {
					// no way can be worked out in mid-air; try again next tick
				} else if (!path.canReach() && recallPhase == RECALL_RETURNING) {
					// called back and no way leads to the station: teleports there
					teleportHome();
				} else if (!path.canReach() && isInWater()) {
					// deep in water: swims up and towards the station instead of giving up
					getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
				} else if (!path.canReach()) {
					// cannot get back to its station: sits down sadly and tries again later
					homeUnreachableUntil = level().getGameTime() + RETRY_HOME_TICKS;
					getNavigation().stop();
				} else {
					getNavigation().moveTo(path, 1.0);
				}
			} else if (getNavigation().isDone() && goalKey == delivery) {
				// out of the water again (or its way ended a bit off): a new way right up to the chest, or as close as it goes
				Path path = getNavigation().createPath(delivery, 1);
				BlockPos side = openSide(delivery);

				if ((path == null || !path.canReach()) && side != null) {
					path = getNavigation().createPath(side, 0);
				}

				if (path == null || !path.canReach()) {
					path = getNavigation().createPath(delivery, 3);
				}

				if (path != null) {
					getNavigation().moveTo(path, 1.0);
				}
			} else if (getNavigation().isDone() && goalKey == target && target.isInWater() && !isInWater()) {
				// an item in deep water: up to the rim or the surface, then in
				Path path = intoWater(target, getNavigation().createPath(target, 1));

				if (path != null) {
					getNavigation().moveTo(path, 1.0);
				}
			} else if (getNavigation().isDone()) {
				getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0);
			}
		}
	}

	/**
	 * For an item in deep water: of the way to the item itself and the way to the water's surface above it, the one
	 * that ends closer to the item (up a ramp to the rim of a pool, say), so it can jump in from there.
	 */
	private Path intoWater(ItemEntity item, Path direct) {
		BlockPos surface = item.blockPosition();

		while (level().getFluidState(surface.above()).is(net.minecraft.tags.FluidTags.WATER) && surface.getY() < item.getBlockY() + 64) {
			surface = surface.above();
		}

		Path toSurface = getNavigation().createPath(surface, 1);

		if (toSurface == null) {
			return direct;
		}

		if (direct == null || direct.getEndNode() == null || toSurface.canReach()) {
			return toSurface;
		}

		if (toSurface.getEndNode() == null) {
			return direct;
		}

		return toSurface.getEndNode().distanceTo(item.blockPosition()) < direct.getEndNode().distanceTo(item.blockPosition()) ? toSurface : direct;
	}

	private boolean goalInWaterBelow(Vec3 goal) {
		double dx = goal.x - getX();
		double dz = goal.z - getZ();
		return dx * dx + dz * dz < 3.5 * 3.5 && level().getFluidState(BlockPos.containing(goal)).is(net.minecraft.tags.FluidTags.WATER);
	}

	/** Right at the item, so it picks it up from under its beak: nothing in between, about half a block away at most. */
	private boolean standsOn(ItemEntity item) {
		double dx = item.getX() - getX();
		double dz = item.getZ() - getZ();
		double dy = item.getY() - getY();

		// items in water drift and bob, so there anything within reach of its beak counts
		if (isInWater() || item.isInWater()) {
			return dx * dx + dz * dz + dy * dy <= 1.6 * 1.6 && hasLineOfSight(item);
		}

		// standing on a fence post or wall right above it, it is half a block further down
		if (dx * dx + dz * dz <= 0.5 * 0.5 && dy > -1.6 && dy < 1 && hasLineOfSight(item)) {
			return true;
		}

		// tucked into a corner (between the arms of a fence, say) where it cannot step onto it: once it cannot get any
		// closer, it reaches over from right next to it; a whole block in between keeps it further away than that
		return dx * dx + dz * dz <= 1.0 && dy > -1.6 && dy < 1 && getNavigation().isDone()
				&& (horizontalCollision || getDeltaMovement().horizontalDistanceSqr() < 0.0025);
	}

	/**
	 * Where it waits: the closest spot by its station with solid ground below and no water, found again every ten seconds.
	 * Falls back to the station itself when there is none.
	 */
	private BlockPos restSpot() {
		long now = level().getGameTime();

		if (restSpot != null && now < restSpotUntil) {
			return restSpot;
		}

		BlockPos h = home();
		BlockPos best = null;

		for (BlockPos pos : BlockPos.betweenClosed(h.offset(-4, -2, -4), h.offset(4, 2, 4))) {
			if (dryGround(pos) && (best == null || pos.distSqr(h) < best.distSqr(h))) {
				best = pos.immutable();
			}
		}

		restSpot = best == null ? h : best;
		restSpotUntil = now + 200;
		return restSpot;
	}

	private boolean dryGround(BlockPos pos) {
		Level level = level();
		return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
				&& level.getFluidState(pos).isEmpty() && level.getFluidState(pos.above()).isEmpty()
				&& level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
				&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
	}

	/** Paths may be as long as its range, so it can work out the whole way to anything in range. */
	private void applyRange() {
		getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(range + 16);
		getNavigation().updatePathfinderMaxVisitedNodes();
	}

	/** Walks like any mob, but treats robot barriers as walls when working out a way. */
	@Override
	protected PathNavigation createNavigation(Level level) {
		return new GroundPathNavigation(this, level) {
			@Override
			protected PathFinder createPathFinder(int maxVisitedNodes) {
				nodeEvaluator = new BarrierAwareEvaluator();
				nodeEvaluator.setCanPassDoors(true);
				nodeEvaluator.setCanWalkOverFences(true);
				return new PathFinder(nodeEvaluator, maxVisitedNodes);
			}
		};
	}

	private static final class BarrierAwareEvaluator extends WalkNodeEvaluator {
		@Override
		public PathType getPathType(PathfindingContext context, int x, int y, int z) {
			// neither through a barrier nor on top of one
			if (context.getBlockState(new BlockPos(x, y, z)).is(RobotBarrierBlock.BLOCK)
					|| context.getBlockState(new BlockPos(x, y - 1, z)).is(RobotBarrierBlock.BLOCK)) {
				return PathType.BLOCKED;
			}

			return super.getPathType(context, x, y, z);
		}
	}

	/** Next to a robot barrier it only takes normal small steps, so it cannot hop over the barrier. */
	@Override
	public float maxUpStep() {
		return nearBarrier ? 0.6F : super.maxUpStep();
	}

	private boolean barrierNearby() {
		AABB box = getBoundingBox().inflate(1.2, 0, 1.2).expandTowards(0, 2, 0).expandTowards(0, -1, 0);

		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (level().getBlockState(pos).is(RobotBarrierBlock.BLOCK)) {
				return true;
			}
		}

		return false;
	}

	/** Chests stacked in a wall: like a player it reaches up to a few blocks above its head. */
	/**
	 * Right next to the chest: it walks up to it to put things in. Only when it cannot get any closer (the sides are
	 * taken by other blocks or robots) does it reach in from a little further away.
	 */
	private boolean canReachChest(Vec3 chest) {
		double dx = chest.x - getX();
		double dz = chest.z - getZ();
		double dy = chest.y - getY();
		double reach = getNavigation().isDone() && getDeltaMovement().horizontalDistanceSqr() < 1.0E-3 ? 2.5 : 1.5;
		return dx * dx + dz * dz <= reach * reach && dy > -2 && dy < 4.5;
	}

	/**
	 * Nothing stands between it and the chest, like a player who has to see what they open: no wall, no slab, no
	 * floor (e.g. when it swims below a chest standing on slabs over water).
	 */
	private boolean seesChest(BlockPos chest) {
		BlockState state = level().getBlockState(chest);
		BlockPos partner = state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
				&& state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE
				? chest.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state)) : chest;
		Vec3 target = Vec3.atCenterOf(chest);

		for (Vec3 from : new Vec3[] {getEyePosition(), position().add(0, getBbHeight() * 0.3, 0)}) {
			if (clearTo(from, target, chest, partner)) {
				return true;
			}
		}

		// a chest higher up in a wall: it reaches straight up through free air, then across to the chest
		Vec3 up = new Vec3(getX(), target.y, getZ());
		return target.y > getEyeY() && clearTo(getEyePosition(), up, null, null) && clearTo(up, target, chest, partner);
	}

	/**
	 * The free spot next to the chest (beside it, or one lower when the chest stands on something) closest to the
	 * robot, from which nothing blocks the way to the chest.
	 */
	private BlockPos openSide(BlockPos chest) {
		BlockPos best = null;

		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			for (int dy = 0; dy >= -1; dy--) {
				BlockPos spot = chest.relative(side).above(dy);

				if (!level().getBlockState(spot).getCollisionShape(level(), spot).isEmpty()
						|| !level().getBlockState(spot.above()).getCollisionShape(level(), spot.above()).isEmpty() && dy == 0) {
					continue;
				}

				Vec3 eye = Vec3.atBottomCenterOf(spot).add(0, getEyeHeight(), 0);

				if (clearTo(eye, Vec3.atCenterOf(chest), chest, chest) && (best == null || distanceToSqr(Vec3.atCenterOf(spot)) < distanceToSqr(Vec3.atCenterOf(best)))) {
					best = spot;
				}
			}
		}

		return best;
	}

	/**
	 * Swimming below a ceiling (slabs, a floor over the water): the nearest spot at its depth with open water up to the
	 * height of {@code above}, closest to it, where it can come up.
	 */
	private Vec3 waterExit(Vec3 above) {
		Vec3 best = null;
		BlockPos here = blockPosition();

		for (int dx = -6; dx <= 6; dx++) {
			for (int dz = -6; dz <= 6; dz++) {
				BlockPos column = here.offset(dx, 0, dz);
				Vec3 bottom = new Vec3(column.getX() + 0.5, getY() + getBbHeight() * 0.5, column.getZ() + 0.5);

				if (!level().getFluidState(column).is(net.minecraft.tags.FluidTags.WATER)
						|| !clearTo(bottom, new Vec3(bottom.x, above.y + 0.5, bottom.z), null, null)
						|| !clearTo(getEyePosition(), bottom, null, null)) {
					continue;
				}

				if (best == null || bottom.distanceToSqr(above) < best.distanceToSqr(above)) {
					best = bottom;
				}
			}
		}

		return best;
	}

	/** The line from {@code from} to {@code to} runs through air until it meets the chest (or, without a chest, all the way). */
	private boolean clearTo(Vec3 from, Vec3 to, BlockPos chest, BlockPos partner) {
		var hit = level().clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, this));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
				|| chest != null && (hit.getBlockPos().equals(chest) || hit.getBlockPos().equals(partner));
	}

	@Override
	public void aiStep() {
		nearBarrier = barrierNearby();
		super.aiStep();

		if (level().isClientSide()) {
			splash();
			return;
		}

		swim();

		if (tickCount % SYNC_TICKS == 0) {
			syncWithStation();
			updateInfo();
		}

		tickRecall();
		tickHomeStuck();

		// a boat or minecart the robot walked into would hold it forever: it climbs straight out again
		if (isPassenger()) {
			stopRiding();
		}

		// Sits down when there is nothing to do: happily looking around at its station, sadly staring at the ground
		// when it cannot work (no chest in range, station broken or out of reach).
		boolean idle = getAction() == 0 && getNavigation().isDone()
				&& (recallPhase == RECALL_WAITING || (getCarried().isEmpty() || delivery == null) && target == null);

		if (nearHome()) {
			homeUnreachableUntil = 0;
		}

		boolean sad = recallPhase == RECALL_NONE && (noChest || chestsFull || stationMissing() || level().getGameTime() < homeUnreachableUntil);

		// never sits down in water: it waits on solid ground
		if (idle && !isInWater() && (sad || nearHome())) {
			if (++idleTicks > (sad ? 20 : 40)) {
				setSitting(true);
				setMood(sad ? MOOD_SAD : recallPhase == RECALL_WAITING && watchedPlayer() != null ? MOOD_WATCHING : MOOD_HAPPY);
			}
		} else {
			idleTicks = 0;

			if (isSitting()) {
				setSitting(false);
				setMood(MOOD_NONE);
			}
		}
	}

	/** Swims fast towards where it wants to go, diving down or coming up as needed. */
	private void swim() {
		// the move control forgets its goal as soon as it has steered once, so this follows the goal of the last tick
		if (!isInWater() || level().getGameTime() - steeredAt > 1 || getAction() != 0) {
			return;
		}

		double dy = getMoveControl().getWantedY() - getY();

		if (dy > 0.4 || horizontalCollision) {
			setDeltaMovement(getDeltaMovement().add(0, 0.05, 0));
		} else if (dy < -0.6) {
			// dives down to things lying on the bottom
			setDeltaMovement(getDeltaMovement().add(0, -0.03, 0));
		}

		double dx = getMoveControl().getWantedX() - getX();
		double dz = getMoveControl().getWantedZ() - getZ();
		double length = Math.sqrt(dx * dx + dz * dz);

		if (length < 0.1) {
			return;
		}

		// as fast as walking on land: there a mob ends up at speed² / (1 - 0.546) blocks per tick
		double speed = getAttributeValue(Attributes.MOVEMENT_SPEED);
		double wanted = Math.min(speed * speed / 0.454, length);
		Vec3 motion = getDeltaMovement();
		setDeltaMovement(motion.x + (dx / length * wanted - motion.x) * 0.6, motion.y, motion.z + (dz / length * wanted - motion.z) * 0.6);
	}

	/** A trail of splashes and bubbles behind it while it swims or wades (client side). */
	private void splash() {
		double dx = getX() - xo;
		double dz = getZ() - zo;

		if (!isInWater() || dx * dx + dz * dz < 0.002) {
			return;
		}

		// under water a stream of bubbles
		if (isUnderWater()) {
			for (int i = 0; i < 3; i++) {
				level().addParticle(ParticleTypes.BUBBLE, getX() - dx * 2 + (random.nextDouble() - 0.5) * 0.5, getY() + getBbHeight() * random.nextDouble(),
						getZ() - dz * 2 + (random.nextDouble() - 0.5) * 0.5, 0, 0.08, 0);
			}

			if (random.nextInt(4) == 0) {
				level().addParticle(ParticleTypes.BUBBLE_COLUMN_UP, getX(), getY() + getBbHeight(), getZ(), 0, 0.1, 0);
			}

			return;
		}

		// on the surface: splashes to the sides, a wake behind it and bubbles popping
		double surface = getY() + getBbHeight() * 0.6;

		for (int i = 0; i < 6; i++) {
			level().addParticle(ParticleTypes.SPLASH, getX() - dx * 2 + (random.nextDouble() - 0.5) * 0.6, surface,
					getZ() - dz * 2 + (random.nextDouble() - 0.5) * 0.6, -dx, 0.15, -dz);
		}

		level().addParticle(ParticleTypes.FISHING, getX() - dx * 3, surface - 0.1, getZ() - dz * 3, 0, 0, 0);

		for (int i = 0; i < 2; i++) {
			level().addParticle(ParticleTypes.BUBBLE, getX() - dx * 3 + (random.nextDouble() - 0.5) * 0.4, getY() + 0.1,
					getZ() - dz * 3 + (random.nextDouble() - 0.5) * 0.4, 0, 0.05, 0);
		}

		if (random.nextInt(3) == 0) {
			level().addParticle(ParticleTypes.BUBBLE_POP, getX() - dx * 2, surface, getZ() - dz * 2, 0, 0.02, 0);
		}
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);

		// settings are changed only at the robot station; sneak + empty hand picks the robot up
		if (player.isSecondaryUseActive() && held.isEmpty()) {
			if (!level().isClientSide()) {
				ItemStack item = toItem();
				player.getInventory().add(item);

				if (!item.isEmpty()) {
					spawnAtLocation((ServerLevel) level(), item);
				}

				ItemStack carried = getCarried();

				if (!carried.isEmpty()) {
					player.getInventory().add(carried);

					if (!carried.isEmpty()) {
						spawnAtLocation((ServerLevel) level(), carried);
					}
				}

				release();
				discard();
			}

			return InteractionResult.SUCCESS;
		}

		return super.mobInteract(player, hand);
	}

	/** Only players (and the void) can hurt it. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (!(source.getEntity() instanceof Player) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}

		return super.hurtServer(level, source, amount);
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
		super.dropCustomDeathLoot(level, source, recentlyHit);
		spawnAtLocation(level, toItem());

		if (!getCarried().isEmpty()) {
			spawnAtLocation(level, getCarried().copy());
		}
	}

	@Override
	public void remove(RemovalReason reason) {
		release();

		// picked up or killed: off its station's list (unloading keeps it there)
		if (reason.shouldDestroy() && !level().isClientSide() && station != null) {
			StationRobots.unregister(Rooms.id(level()), station, getUUID());
		}
		super.remove(reason);
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.storeNullable("Owner", UUIDUtil.CODEC, owner);
		output.storeNullable("Home", BlockPos.CODEC, home);
		output.putInt("Color", getColor());
		output.putInt("Range", range);
		output.storeNullable("Station", BlockPos.CODEC, station);
		output.store("Filter", ItemStack.CODEC.listOf(), List.copyOf(filter));
		output.putBoolean("FilterExclude", excludeFilter);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		home = input.read("Home", BlockPos.CODEC).orElse(null);
		entityData.set(COLOR, input.getIntOr("Color", DEFAULT_COLOR));
		station = input.read("Station", BlockPos.CODEC).orElse(null);
		range = Math.clamp(input.getIntOr("Range", RANGE), MIN_RANGE, RANGE);
		applyRange();
		filter.clear();
		filter.addAll(input.read("Filter", ItemStack.CODEC.listOf()).orElse(List.of()));
		excludeFilter = input.getBooleanOr("FilterExclude", false);
	}
}
