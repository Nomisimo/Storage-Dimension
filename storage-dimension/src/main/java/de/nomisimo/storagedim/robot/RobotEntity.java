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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
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
	/** Longest it goes for one block to dig before it gives up on it (it may take the long way round, but not forever). */
	private static final int WAY_TICKS = 600;
	private static final int IGNORE_TICKS = 1200;
	/** How many of the closest items or chests it checks for a way per search. */
	private static final int PATH_CHECKS = 4;
	/** Ticks between two searches for dropped items. */
	private static final int SEARCH_TICKS = 10;
	/**
	 * Time all robots together may spend working out ways in one server tick, and one robot alone. Ways that do not
	 * fit wait for a later tick; without this some 50 robots with unreachable goals could hold up a tick for over a
	 * minute, and the watchdog shut the server down (issue #43).
	 */
	private static final long PATH_BUDGET_NANOS = 40_000_000L;
	private static final long ROBOT_PATH_BUDGET_NANOS = 12_000_000L;
	/** A robot that used up its own share rests this many ticks before it works out ways again, so the others get their turn. */
	private static final int PATH_REST_TICKS = 2;
	/** Upper limit for the places one search for a way looks at, however far the robot's range reaches. */
	private static final int MAX_PATH_NODES = 6000;
	private static int pathBudgetTick = Integer.MIN_VALUE;
	private static long pathBudgetUsed;
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
	/** Outside its range (followed an item that drifted off, say) and finding no way back: it teleports home after 10 seconds. */
	private static final int OUT_OF_RANGE_STUCK_TICKS = 200;
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
	/** Mining mode: {@link #MINING_LOOK_HELMET} wears its helmet, {@link #MINING_LOOK_DIGGING} digs right now. */
	private static final EntityDataAccessor<Byte> MINING_LOOK = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BYTE);
	public static final int MINING_LOOK_NONE = 0;
	public static final int MINING_LOOK_HELMET = 1;
	public static final int MINING_LOOK_DIGGING = 2;
	/** Stacks a mining robot gathers before it takes them to the chests (besides the one in its beak). */
	public static final int BAG_SLOTS = 9;
	/** How far from its eyes a mining robot digs, like a player. */
	/** How far it reaches to dig: like a player. */
	private static final double MINE_REACH = 4.5;
	/** How high it builds itself up at most to get at a block of the mining plan. */
	private static final int MAX_CLIMB = 24;
	/**
	 * A chest it sees this close is in reach: with many robots crowding round the station's chests it does not have to
	 * squeeze right up to one (it used to stand behind the others with its load and wait for good).
	 */
	private static final double CROWD_REACH = 3.5;
	/**
	 * A chest of its station this close that it cannot get any closer to (other robots in the way, no way found
	 * up out of the mine right beside it): it hands its load or its tools in from where it is.
	 */
	private static final double HAND_IN_REACH = 6;
	/** Its tools, one of each: pickaxe, axe, shovel. */
	private static final int PICKAXE = 0;
	private static final int AXE = 1;
	private static final int SHOVEL = 2;
	/** Not a tool slot: torches, fetched like a tool (a stack at a time). */
	private static final int TORCH = 3;
	private static final int TORCH_STACK = 64;
	private static final String[] TOOL_NAMES = {"Spitzhacke", "Axt", "Schaufel"};
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
	/** Ticks since it last stood or swam. */
	private int airTicks;
	/** Ticks it has been stuck away from its station (waiting with no way to anything). */
	private int strandedTicks;
	/**
	 * Blocks to dig it gave up on one after the other without getting away from the spot, and where: a way the game
	 * finds but the robot cannot go (out of a hole it dug itself free from, say) keeps handing it new blocks that it
	 * never gets to, so it never waits and never counts as stuck.
	 */
	private int fruitlessDigs;
	private Vec3 fruitlessAt;
	/** It is digging itself out (for the smoke test's report). */
	private boolean rescuing;
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
	/** Open sides of chests it could not get to (another robot sitting there, say), left out for a while. */
	private final Map<BlockPos, Long> ignoredSides = new HashMap<>();
	/** The way to the current item or chest, worked out once when it was chosen. */
	private Path plannedPath;
	private long nextSearch;
	/** The robot station it belongs to, or null for a robot without one (its home is where it was placed). */
	private BlockPos station;
	/** The stations of its station's group, its own (the main station) first; just its station when it stands alone. */
	private List<BlockPos> groupMembers = List.of();
	/** The largest range of its station's group (10 chunks for a single station, one more per further station). */
	private int groupRange = RANGE;
	private long homeUnreachableUntil;
	/** Its own time for working out ways in the current server tick, and the tick it waits for after using it up. */
	private int ownPathTick = Integer.MIN_VALUE;
	private long ownPathUsed;
	private int pathRestUntil = Integer.MIN_VALUE;
	/** The last way it asked for was not worked out at all: no time was left in this tick (see {@link #mayPath()}). */
	private boolean pathDenied;
	/** No new way to the chest or block it goes for before this game time (after one that did not get there). */
	private long goalPathWait;
	private int goalPathMisses;
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
	/** The antenna area of its station, or null: then it works within its range around the station. */
	private AABB area;
	private String areaLabel = "";
	private int areaReach;
	/** Its station's area is switched off or paused: it waits at the station. */
	private boolean areaOff;
	private long recallSeen;
	/** The player who called it back, watched while coming back and waiting. */
	private UUID recaller;
	private int recallPhase = RECALL_NONE;
	private long recallUntil;
	private double recallBest;
	private int recallStuck;
	private double homeBest = Double.MAX_VALUE;
	private int homeStuck;
	/** Its station is in mining mode (taken from the station). */
	private boolean mining;
	/** Mining as ore search: staircase and tunnels down to the ores below the station, only ores kept. */
	private boolean oreSearch;
	/** Ore search: done with a vein, it takes the ores home before it digs on to the next one. */
	private boolean oreDelivery;
	/** The chest next to its station with the tools for mining, or null. */
	private BlockPos toolChest;
	/** The station's range, for the mining area without antennas (all robots of a station dig the same area). */
	private int stationRange = RANGE;
	/** Pickaxe, axe and shovel, taken from the tool chest; empty when it has none (or it broke). */
	private final ItemStack[] tools = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
	/** Torches for the tunnels of the strip mine, taken along with the tools when there are some. */
	private ItemStack torches = ItemStack.EMPTY;
	/** What it dug and has not taken to the chests yet. */
	private final List<ItemStack> bag = new ArrayList<>();
	/** The block it is going to dig or digging, and how far along it is. */
	private BlockPos mineTarget;
	private int digTicks;
	private int digTotal;
	/** 0: nothing, 1: fetching tools, 2: taking them back, 3: digging. */
	private int minePhase;
	/** Found nothing it could dig the last time it looked. */
	private boolean mineIdle;
	/** Why it cannot dig (no tool chest, tools missing), or null. */
	private Component mineProblem;
	/** Nothing is left to dig in its area. */
	private boolean mineFinished;
	/** Its station is in courier mode: it carries the post from mailbox to mailbox (taken from the station). */
	private boolean courier;
	/** The mailbox it is on its way to, to fetch the post waiting there. */
	private BlockPos pickupBox;
	/** The mailbox the post it carries goes to, or null (then it goes to the chests at its station). */
	private BlockPos courierTo;
	/** The machine slot (from 0) at {@code courierTo} the post it carries is addressed to, or -1. */
	private int courierSlot = -1;
	/** Since when the mailbox it brings post to has had no room or no way there (game time), or 0. */
	private long courierBlockedSince;
	/** The mailbox it brings post to has no room right now: it waits at its station. */
	private boolean courierFull;
	/** Mailboxes it found no way to, with the game time until which it leaves them alone. */
	private final Map<BlockPos, Long> ignoredMailboxes = new HashMap<>();
	/** A mailbox without room for a minute: the post goes to the chests at its station instead. */
	private static final int COURIER_GIVE_UP_TICKS = 1200;

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
		builder.define(MINING_LOOK, (byte) MINING_LOOK_NONE);
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(-1, new RescueGoal());
		goalSelector.addGoal(0, new MineGoal());
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

	/** Helmet on, digging, or neither (for the renderer). */
	public int getMiningLook() {
		return entityData.get(MINING_LOOK);
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
		int newRange = Math.clamp(config.range(), MIN_RANGE, StationCluster.MAX_RANGE);

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
		// its station joined a group (the robots belong to the main station) or was broken next to one: it moves there
		BlockPos moved = StationCluster.newHome(level(), station);

		if (moved != null && !moved.equals(station)) {
			StationRobots.unregister(dim, station, getUUID());
			station = moved;
			home = moved;
			release();
			dropMineTarget();
		}

		groupMembers = StationCluster.members(level(), station);
		groupRange = StationCluster.maxRange(groupMembers.size());
		String name = data.stationRobots(dim, station).get(getUUID());

		if (name == null) {
			name = StationRobots.register(dim, station, getUUID(), getCustomName() == null ? "" : getCustomName().getString());
		}

		applyName(name);
		RobotConfig stationConfig = data.station(dim, station);
		stationName = stationConfig.stationName();
		syncMining(stationConfig);
		syncArea(data.stationArea(dim, station), stationConfig, dim);
		Long recall = RECALLS.get(dim + "@" + station.asLong());

		if (recall != null && recall > recallSeen && level().getGameTime() - recall < RECALL_VALID_TICKS) {
			recallSeen = recall;
			recaller = RECALLERS.get(dim + "@" + station.asLong());
			startRecall();
		}
	}

	/**
	 * With both antennas standing it works only inside their box, in the area's colour (set at the station), and
	 * waits at the station while the area is switched off or paused.
	 */
	private void syncArea(StationArea stationArea, RobotConfig stationConfig, String dim) {
		AABB box = stationArea.connected() ? stationArea.box(station) : null;

		if (!java.util.Objects.equals(box, area)) {
			area = box;
			areaReach = box == null ? 0 : stationArea.reach(station);
			applyRange();
			release();
		}

		boolean off = box != null && !Areas.working(dim, station);

		if (off && !areaOff) {
			release();
		}

		areaOff = off;
		areaLabel = box == null ? "" : stationArea.name().isEmpty() ? stationArea.sizeText(station) : stationArea.name();

		if (box != null) {
			entityData.set(COLOR, stationConfig.color());
		}
	}

	/** Mining mode, tool chest and range come from the station, so all its robots dig the same area together. */
	private void syncMining(RobotConfig stationConfig) {
		boolean was = mining;
		boolean wasOres = oreSearch;
		mining = stationConfig.mining();
		oreSearch = mining && stationConfig.oreSearch();
		boolean wasCourier = courier;
		courier = !mining && stationConfig.courier();

		if (wasCourier != courier) {
			release();

			// switched off: what it carries goes to the chests at its station
			if (!courier) {
				courierTo = null;
				courierFull = false;
			}
		}
		toolChest = stationConfig.toolChest().orElse(null);
		int wasRange = workRange();
		stationRange = Math.clamp(stationConfig.range(), MIN_RANGE, Math.max(MIN_RANGE, groupRange));

		if (wasOres != oreSearch || was != mining || wasRange != workRange()) {
			applyRange();
		}

		if (was != mining || wasOres != oreSearch) {
			release();
			dropMineTarget();
			mineIdle = false;
			mineFinished = false;
			mineProblem = null;
		}

		updateMiningLook();
	}

	private void updateMiningLook() {
		int look = !mining && !hasAnyTool() ? MINING_LOOK_NONE : minePhase == 3 && digTicks > 0 ? MINING_LOOK_DIGGING : MINING_LOOK_HELMET;

		if (getMiningLook() != look) {
			entityData.set(MINING_LOOK, (byte) look);
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

		if (path == null && pathDenied) {
			// no time for a way in this tick: tries again shortly
			return;
		}

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
		airTicks = onGround() || isInLiquid() ? 0 : airTicks + 1;
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
		boolean wantsHome = station != null && recallPhase == RECALL_NONE && !nearHome() && target == null && pickupBox == null
				&& (getCarried().isEmpty() || delivery == null) && getAction() == 0 && minePhase == 0 && !stationMissing();

		if (!wantsHome) {
			homeBest = Double.MAX_VALUE;
			homeStuck = 0;
			return;
		}

		double distance = position().distanceTo(Vec3.atBottomCenterOf(home()));

		if (distance < homeBest - 0.5) {
			homeBest = distance;
			homeStuck = 0;
		} else if (++homeStuck >= (outOfRange() ? OUT_OF_RANGE_STUCK_TICKS : HOME_STUCK_TICKS)) {
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
				Optional.empty(), Optional.empty(), excludeFilter, stationName, false, Optional.empty(), false, false);
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

		if (areaOff && getCarried().isEmpty()) {
			return Component.literal("Pause: Area aus").withStyle(ChatFormatting.AQUA);
		}

		// while it is busy it shows what it does; an old error from before does not count
		if (!getCarried().isEmpty() && delivery != null && courierTo != null && delivery.equals(courierTo)) {
			return Component.literal("Bringt ").append(getCarried().getHoverName()).append(" zu " + Mailbox.label((ServerLevel) level(), courierTo)
					+ (courierSlot >= 0 ? ", Slot " + (courierSlot + 1) : ""))
					.withStyle(ChatFormatting.GREEN);
		}

		if (!getCarried().isEmpty() && delivery != null) {
			return Component.literal("Bringt ").append(getCarried().getHoverName()).append(" weg").withStyle(ChatFormatting.GREEN);
		}

		if (pickupBox != null) {
			return Component.literal("Holt Post bei " + Mailbox.label((ServerLevel) level(), pickupBox)).withStyle(ChatFormatting.GREEN);
		}

		if (courierFull && courierTo != null && !getCarried().isEmpty()) {
			return Component.literal("Fehler: Briefkasten " + Mailbox.label((ServerLevel) level(), courierTo) + " voll").withStyle(ChatFormatting.RED);
		}

		if (target != null) {
			return Component.literal("Holt ").append(target.getItem().getHoverName()).withStyle(ChatFormatting.GREEN);
		}

		if (minePhase == 3 && mineTarget != null) {
			return Component.literal("Baut ").append(level().getBlockState(mineTarget).getBlock().getName()).append(" ab").withStyle(ChatFormatting.GREEN);
		}

		if (minePhase == 4) {
			return Component.literal("Baut sich hoch").withStyle(ChatFormatting.GREEN);
		}

		if (minePhase == 1) {
			return Component.literal("Holt Werkzeug").withStyle(ChatFormatting.GREEN);
		}

		if (minePhase == 2) {
			return Component.literal("Bringt Werkzeug zurück").withStyle(ChatFormatting.GREEN);
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

		if (mining && mineProblem != null) {
			return mineProblem.copy().withStyle(ChatFormatting.RED);
		}

		if (mining && mineFinished) {
			return Component.literal(oreSearch ? "Fertig: keine Erze mehr" : "Fertig: alles abgebaut").withStyle(ChatFormatting.GREEN);
		}

		if (courier) {
			return Component.literal(isSitting() ? "Kurier: wartet fröhlich auf Post" : "Kurier: wartet auf Post").withStyle(ChatFormatting.YELLOW);
		}

		if (mining && mineIdle) {
			return Component.literal(oreSearch ? "Erzsuche: kein Erz erreichbar, wartet" : "Abbau: nichts erreichbar, wartet").withStyle(ChatFormatting.YELLOW);
		}

		return Component.literal(isSitting() ? "Wartet fröhlich" : "Wartet").withStyle(ChatFormatting.YELLOW);
	}

	private void updateInfo() {
		entityData.set(INFO_STATION, Component.literal("Station: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(station == null ? "keine" : stationName.isEmpty() ? station.toShortString() : stationName).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(area != null ? " · Area " + areaLabel : " · " + range / 16 + (range == 16 ? " Chunk" : " Chunks")).withStyle(ChatFormatting.GRAY)));
		entityData.set(INFO_FILTER, Component.literal("Sammelt: ").withStyle(ChatFormatting.GRAY)
				.append(RobotConfig.describeFilter(filter, excludeFilter).withStyle(ChatFormatting.WHITE)));
		entityData.set(INFO_STATUS, status());
		entityData.set(ERROR, recallPhase == RECALL_NONE && (stationMissing()
				|| !isBusy() && (noChest || chestsFull || courierFull && courierTo != null || level().getGameTime() < homeUnreachableUntil || mining && mineProblem != null)));
	}

	/** On its way to an item or a chest, or picking up / putting in right now. */
	private boolean isBusy() {
		return target != null || delivery != null || pickupBox != null || getAction() != 0 || minePhase != 0;
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
		return target == null && pickupBox == null && getCarried().isEmpty() && getAction() == 0;
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

	/**
	 * The chests and other storage blocks right next to its station (the six touching sides), in a fixed order; in a
	 * station group those next to every station of it, the main station's first.
	 */
	private List<BlockPos> stationContainers() {
		List<BlockPos> containers = new ArrayList<>();
		List<BlockPos> centres = station != null && groupMembers.size() > 1 && groupMembers.get(0).equals(station) ? groupMembers : List.of(storageCentre());
		BlockPos tools = toolSpot();

		for (BlockPos centre : centres) {
			for (Direction side : Direction.values()) {
				BlockPos pos = centre.relative(side);

				// the tool chest of the mining robots only holds tools; a chest between two stations counts once
				if (pos.equals(toolChest) || pos.equals(tools) || containers.contains(pos)) {
					continue;
				}

				if (level().isLoaded(pos) && isContainer(pos, side.getOpposite())) {
					containers.add(pos);
				}
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

	static boolean looksLikeStorage(BlockState state) {
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
		// a mailbox of the parcel post puts it into the chest beside it
		if (level().getBlockState(pos).is(MailboxBlock.BLOCK)) {
			return Mailbox.insert((ServerLevel) level(), pos, stack, simulate, pos.equals(courierTo) ? courierSlot : -1);
		}

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
				BlockPos beside = pos.relative(direction);

				// in a station group the side facing whichever of its stations it stands next to
				if (beside.equals(storageCentre()) || side == null && groupMembers.contains(beside)) {
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
	/** Where it last steered on the way to a chest it cannot see, for the test log. */
	private String steerInfo = "";

	public String describe() {
		return String.format("speed=%.3f water=%s name=%s color=%06x error=%s range=%d filter=%s%s station=%s mood=%d noChest=%s full=%s ", getDeltaMovement().horizontalDistance(), isInWater(), getCustomName() == null ? "" : getCustomName().getString(), getColor(), hasError(), range, excludeFilter ? "except " : "", filter, station, getMood(), noChest, chestsFull) + String.format("pos=%.1f/%.1f/%.1f home=%s target=%s delivery=%s carried=%s action=%d sitting=%s pathDone=%s goals=%s",
				getX(), getY(), getZ(), home, target == null ? null : target.position(), delivery, getCarried(), getAction(), isSitting(),
				getNavigation().isDone(), goalSelector.getAvailableGoals().stream().filter(g -> g.isRunning()).map(g -> g.getGoal().getClass().getSimpleName()).toList())
				+ (steerInfo.isEmpty() ? "" : " steer=" + steerInfo);
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
			} else if (getAction() == 3) {
				finishPost();
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

	/** Clearly outside its range or area (more than a few blocks). */
	private boolean outOfRange() {
		Vec3 pos = position();

		// in the ore search the ground below the station belongs to its range
		boolean below = mining && station != null && pos.y < station.getY();

		if (area != null) {
			AABB box = area.inflate(4);
			return !(below ? new AABB(box.minX, level().getMinY(), box.minZ, box.maxX, box.maxY, box.maxZ) : box).contains(pos);
		}

		if (mining && station != null) {
			// the chunks it digs, all the way up and down
			int[] square = chunkSquare(station, stationRange);
			return pos.x < square[0] - 4 || pos.x > square[2] + 5 || pos.z < square[1] - 4 || pos.z > square[3] + 5;
		}

		BlockPos h = home();
		double dx = pos.x - h.getX();
		double dz = pos.z - h.getZ();
		int reach = workRange();
		return dx * dx + dz * dz > (reach + 4) * (reach + 4) || !below && Math.abs(pos.y - h.getY()) > VERTICAL_RANGE + 4;
	}

	/**
	 * Where a station's robots dig without antennas, on chunk borders: the station's chunk and its range in chunks
	 * (at least one) all round. {minX, minZ, maxX, maxZ}, inclusive.
	 */
	public static int[] chunkSquare(BlockPos station, int range) {
		int chunks = Math.max(1, Math.round(Math.clamp(range, MIN_RANGE, StationCluster.MAX_RANGE) / 16.0F));
		int cx = station.getX() >> 4;
		int cz = station.getZ() >> 4;
		return new int[] {(cx - chunks) << 4, (cz - chunks) << 4, ((cx + chunks) << 4) + 15, ((cz + chunks) << 4) + 15};
	}

	private boolean inRange(Vec3 pos) {
		if (area != null) {
			return pos.x >= area.minX && pos.x <= area.maxX && pos.z >= area.minZ && pos.z <= area.maxZ
					&& pos.y >= area.minY - 0.01 && pos.y <= area.maxY;
		}

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
		if (!standing() || !mayPath()) {
			return null;
		}

		if (areaOff) {
			return null;
		}

		BlockPos h = home();
		AABB area = this.area != null ? this.area.inflate(0.5) : new AABB(h).inflate(range, VERTICAL_RANGE, range);
		long now = level().getGameTime();
		ignoredItems.values().removeIf(until -> until < now);
		List<ItemEntity> items = new ArrayList<>(level().getEntitiesOfClass(ItemEntity.class, area,
				item -> item.isAlive() && !CLAIMED.contains(item.getUUID()) && !ignoredItems.containsKey(item.getUUID())
						&& inRange(item.position()) && allows(item.getItem())
						// where the game does not move entities (too far from every player) it would stop and stay
						&& ((ServerLevel) level()).isPositionEntityTicking(item.blockPosition())));
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

			if (pathDenied) {
				return null;
			}

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
	/**
	 * It can work out a way: standing or swimming, or not falling for a while (wedged on a ledge or on gravel, where
	 * the game does not count it as on the ground; it would otherwise never plan again).
	 */
	private boolean standing() {
		return onGround() || isInLiquid() || airTicks > 40;
	}

	private BlockPos planDelivery() {
		// a way can only be worked out standing (or swimming); try again on the next search
		if (!standing() || !mayPath()) {
			return null;
		}

		long now = level().getGameTime();
		BlockPos post = planPostDelivery(now);

		if (post != null || courierTo != null) {
			return post;
		}

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

			if (pathDenied) {
				return null;
			}

			if (path != null && path.canReach()) {
				plannedPath = path;
				return chest;
			}

			// right below or beside it (up the staircase of the mine, crowded out): it hands its load in from there
			if (position().distanceTo(Vec3.atCenterOf(chest)) <= HAND_IN_REACH) {
				plannedPath = null;
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

	/**
	 * Courier: the mailbox the post it carries goes to, when it has room and a way leads there. With no room (or no
	 * way) it waits at the station, and after a minute it gives up: the post goes to the chests at its station.
	 */
	private BlockPos planPostDelivery(long now) {
		if (courierTo == null) {
			return null;
		}

		if (!level().isLoaded(courierTo) || !level().getBlockState(courierTo).is(MailboxBlock.BLOCK)) {
			// the mailbox is gone: to the station with it
			courierTo = null;
			courierFull = false;
			return null;
		}

		if (store(courierTo, getCarried(), true) > 0) {
			courierFull = false;
			chestsFull = false;
			Path path = getNavigation().createPath(courierTo, 3);

			if (pathDenied) {
				return null;
			}

			if (path != null && path.canReach()) {
				plannedPath = path;
				courierBlockedSince = 0;
				return courierTo;
			}

			if (position().distanceTo(Vec3.atCenterOf(courierTo)) <= HAND_IN_REACH || isInWater()) {
				plannedPath = isInWater() ? path : null;
				courierBlockedSince = 0;
				return courierTo;
			}
		} else {
			courierFull = true;
		}

		if (courierBlockedSince == 0) {
			courierBlockedSince = now;
		}

		if (now - courierBlockedSince >= COURIER_GIVE_UP_TICKS) {
			courierTo = null;
			courierFull = false;
			courierBlockedSince = 0;
		}

		return null;
	}

	/**
	 * Courier: the closest mailbox in its range with post waiting for a mailbox (also in its range) that has room,
	 * which no other courier is fetching and to which it finds a way.
	 */
	private BlockPos findPost() {
		StorageData data = StorageData.get();

		if (!standing() || areaOff || data == null || !mayPath()) {
			return null;
		}

		ServerLevel server = (ServerLevel) level();
		String dim = Rooms.id(server);
		long now = server.getGameTime();
		ignoredMailboxes.values().removeIf(until -> until < now);
		List<BlockPos> boxes = new ArrayList<>();

		for (Map.Entry<Long, Mailbox> entry : data.mailboxes(dim).entrySet()) {
			BlockPos pos = BlockPos.of(entry.getKey());

			if (!entry.getValue().destination().isEmpty() && !ignoredMailboxes.containsKey(pos) && inRange(Vec3.atCenterOf(pos)) && server.isLoaded(pos)
					&& !Mailbox.claimedByOther(dim, pos, getUUID())) {
				boxes.add(pos);
			}
		}

		boxes.sort(java.util.Comparator.comparingDouble(pos -> distanceToSqr(Vec3.atCenterOf(pos))));
		int checks = 0;

		for (BlockPos pos : boxes) {
			if (postTarget(server, pos) == null) {
				continue;
			}

			if (checks++ >= PATH_CHECKS) {
				break;
			}

			Path path = getNavigation().createPath(pos, 3);

			if (pathDenied) {
				return null;
			}

			if (path != null && path.canReach()) {
				plannedPath = path;
				return pos;
			}

			if (position().distanceTo(Vec3.atCenterOf(pos)) <= HAND_IN_REACH) {
				plannedPath = null;
				return pos;
			}

			ignoredMailboxes.put(pos, now + IGNORE_TICKS);
		}

		return null;
	}

	/** Where the post waiting at the mailbox goes: a mailbox in its range with room for it, or null (none, or no post). */
	private BlockPos postTarget(ServerLevel server, BlockPos box) {
		List<ItemStack> post = Mailbox.waiting(server, box, 1);

		if (post.isEmpty()) {
			return null;
		}

		int slot = postSlot(server, box);
		return Mailbox.resolve(server, box, other -> inRange(Vec3.atCenterOf(other)) && Mailbox.insert(server, other, post.get(0), true, slot) > 0);
	}

	/** The machine slot the mailbox's post is addressed to (from 0), or -1. */
	private static int postSlot(ServerLevel server, BlockPos box) {
		StorageData data = StorageData.get();
		Mailbox mailbox = data == null ? null : data.mailbox(Rooms.id(server), box);
		return mailbox == null ? -1 : Mailbox.slot(mailbox.destination());
	}

	/** At the mailbox: takes the post out of the chest beside it, one stack in the beak and the rest in its bag. */
	private void finishPost() {
		BlockPos box = pickupBox;
		dropPickup();

		if (box == null || !getCarried().isEmpty() || !bag.isEmpty() || !(level() instanceof ServerLevel server)
				|| position().distanceTo(Vec3.atCenterOf(box)) > HAND_IN_REACH + 1) {
			return;
		}

		BlockPos to = postTarget(server, box);

		if (to == null) {
			return;
		}

		List<ItemStack> taken = new ArrayList<>(Mailbox.take(server, box, Mailbox.MAX_STACKS));

		if (taken.isEmpty()) {
			return;
		}

		setItemSlot(EquipmentSlot.MAINHAND, taken.remove(0));
		bag.addAll(taken);
		courierTo = to;
		courierSlot = postSlot(server, box);
		courierBlockedSince = 0;
		courierFull = false;
		delivery = null;
		nextSearch = 0;
		playSound(SoundEvents.ITEM_PICKUP, 0.4F, 1.4F);
	}

	/** Gives up the mailbox it was going to fetch post from, so another courier may go there. */
	private void dropPickup() {
		if (pickupBox != null && !level().isClientSide()) {
			Mailbox.release(Rooms.id(level()), pickupBox, getUUID());
		}

		pickupBox = null;
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

		// a mining robot hands in what it gathered stack by stack
		if (getCarried().isEmpty() && !bag.isEmpty()) {
			setItemSlot(EquipmentSlot.MAINHAND, bag.remove(0));
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
		dropPickup();

		if (openLid != null) {
			setLid(openLid, false);
		}

		if (target != null) {
			CLAIMED.remove(target.getUUID());
			target = null;
		}
	}

	// ---- mining ----

	public boolean isMining() {
		return mining;
	}

	private boolean hasAnyTool() {
		if (!torches.isEmpty()) {
			return true;
		}

		for (ItemStack tool : tools) {
			if (!tool.isEmpty()) {
				return true;
			}
		}

		return false;
	}

	/** Its bag is (nearly) full, or it has nothing more to dig right now: off to the chests with what it gathered. */
	private boolean needsUnload() {
		return !bag.isEmpty() && (bag.size() >= BAG_SLOTS - 1 || mineIdle || oreDelivery || !mining || recallPhase != RECALL_NONE || areaOff);
	}

	/** Gives back the block it was going to dig, so another robot may take it. */
	private void dropMineTarget() {
		if (mineTarget != null && !level().isClientSide()) {
			level().destroyBlockProgress(getId(), mineTarget, -1);
			DigJob job = miningJob();

			if (job != null) {
				job.release(mineTarget, getUUID());
			}
		}

		mineTarget = null;
		digTicks = 0;
		digTotal = 0;

		if (minePhase == 3) {
			minePhase = 0;
		}
	}

	/**
	 * What all robots of its station dig together: the antenna area, or a square of the station's range from the
	 * station up; in the ore search the ores below that footprint, down to the bottom of the world.
	 */
	private DigJob miningJob() {
		if (station == null) {
			return null;
		}

		int minX;
		int minY;
		int minZ;
		int maxX;
		int maxY;
		int maxZ;

		if (area != null) {
			minX = (int) Math.floor(area.minX);
			minY = (int) Math.floor(area.minY);
			minZ = (int) Math.floor(area.minZ);
			maxX = (int) Math.ceil(area.maxX) - 1;
			maxY = (int) Math.ceil(area.maxY) - 1;
			maxZ = (int) Math.ceil(area.maxZ) - 1;
		} else {
			int[] square = chunkSquare(station, stationRange);
			minX = square[0];
			minZ = square[1];
			maxX = square[2];
			maxZ = square[3];
			// the whole square, from the top of the ground down to the bedrock (the ore search only looks below the station)
			minY = Math.max(level().getMinY(), Integer.getInteger("storagedim.mineBottom", level().getMinY()));
			maxY = level().getMaxY();
		}

		if (oreSearch) {
			// the smoke test strip-mines a block of stone high up instead of the whole ground
			int bottom = Math.max(level().getMinY(), Integer.getInteger("storagedim.stripBottom", level().getMinY()));
			lastOres = OreJob.get(Rooms.id(level()), station, minX, minZ, maxX, maxZ, bottom, level().getGameTime());
			return lastOres;
		}

		minY = Math.max(minY, level().getMinY());
		maxY = Math.min(maxY, level().getMaxY());
		MiningJob job = MiningJob.get(Rooms.id(level()), station, minX, minY, minZ, maxX, maxY, maxZ, level().getGameTime());
		// the platforms of the other stations of its group stay too
		job.setGroup(groupMembers);
		lastMine = job;
		return job;
	}

	/**
	 * Where it gets its tools: a filter chest right next to its station (placed there by the player; it hands out
	 * the pickaxes of the ME storage it is set to), otherwise the tool chest. Null when there is neither.
	 */
	private BlockPos toolSpot() {
		if (station == null || !(level() instanceof ServerLevel server)) {
			return null;
		}

		BlockPos filter = StationMining.filterChestBeside(server, Rooms.id(server), station);

		if (filter != null) {
			return filter;
		}

		return toolChest != null && StationMining.isToolChest(server, toolChest, station) ? toolChest : null;
	}

	/** Its tool chest or filter chest, to take tools from and bring them back to; null when there is none. */
	private ToolSource toolContainer() {
		BlockPos pos = toolSpot();

		if (pos == null) {
			return null;
		}

		StorageData data = StorageData.get();
		FilterChests.FilterChest filter = data == null ? null : data.filterChest(Rooms.id(level()), pos);

		if (filter != null) {
			return new ToolSource(pos, null, data.networkFor(filter.owner()), filter.filters());
		}

		return level().getBlockEntity(pos) instanceof Container container ? new ToolSource(pos, container, null, List.of()) : null;
	}

	/**
	 * A chest with tools in it, or a filter chest that hands out the tools of its ME storage: only those its filters
	 * let through (all tools while it has no filters set).
	 */
	private record ToolSource(BlockPos pos, Container chest, StorageNetwork network, List<ItemStack> filters) {
		boolean has(int kind) {
			return chest != null ? slotWith(chest, kind) >= 0 : stored(kind) != null;
		}

		ItemStack take(int kind) {
			int amount = kind == TORCH ? TORCH_STACK : 1;

			if (chest != null) {
				int slot = slotWith(chest, kind);
				return slot < 0 ? ItemStack.EMPTY : chest.removeItem(slot, amount);
			}

			ItemStack prototype = stored(kind);
			ItemStack taken = prototype == null ? ItemStack.EMPTY : network.extract(prototype, amount);

			if (!taken.isEmpty()) {
				StorageMenu.refreshAll(network);
			}

			return taken;
		}

		/** Puts a tool back; false when there is no room (then it is dropped). */
		boolean put(ItemStack tool) {
			if (chest == null) {
				network.insert(tool.copy());
				StorageMenu.refreshAll(network);
				return true;
			}

			for (int slot = 0; slot < chest.getContainerSize(); slot++) {
				if (chest.getItem(slot).isEmpty()) {
					chest.setItem(slot, tool);
					return true;
				}
			}

			return false;
		}

		void changed() {
			if (chest != null) {
				chest.setChanged();
			}
		}

		private ItemStack stored(int kind) {
			boolean any = filters.stream().allMatch(ItemStack::isEmpty);

			for (StorageNetwork.StoredItem entry : network.entries()) {
				if (isTool(entry.prototype, kind) && (any || FilterChests.matches(filters, entry.prototype)) && network.available(entry.prototype) > 0) {
					return entry.prototype;
				}
			}

			return null;
		}
	}

	private static boolean isTool(ItemStack stack, int kind) {
		return switch (kind) {
			case PICKAXE -> stack.is(ItemTags.PICKAXES);
			case AXE -> stack.is(ItemTags.AXES);
			case TORCH -> stack.is(net.minecraft.world.item.Items.TORCH);
			default -> stack.is(ItemTags.SHOVELS);
		};
	}

	/**
	 * What it lacks to dig at all and cannot get either, by name: only the pickaxe is needed (it digs everything,
	 * if more slowly); axe and shovel are taken along when there are some.
	 */
	private List<String> unavailableTools(ToolSource source) {
		return tools[PICKAXE].isEmpty() && (source == null || !source.has(PICKAXE)) ? List.of(TOOL_NAMES[PICKAXE]) : List.of();
	}

	/** A tool it has not got and could fetch: the pickaxe, or an axe or shovel that is there; torches for the strip mine. */
	private boolean wantsTools(ToolSource source) {
		if (source == null) {
			return false;
		}

		for (int kind = 0; kind < tools.length; kind++) {
			if (tools[kind].isEmpty() && source.has(kind)) {
				return true;
			}
		}

		return oreSearch && torches.isEmpty() && source.has(TORCH);
	}

	private static int slotWith(Container chest, int kind) {
		for (int slot = 0; slot < chest.getContainerSize(); slot++) {
			if (isTool(chest.getItem(slot), kind)) {
				return slot;
			}
		}

		return -1;
	}

	/** Takes what it lacks from the tool chest, one of each kind. */
	private void takeTools(ToolSource source) {
		for (int kind = 0; kind < tools.length; kind++) {
			if (tools[kind].isEmpty()) {
				tools[kind] = source.take(kind);
			}
		}

		if (oreSearch && torches.isEmpty()) {
			torches = source.take(TORCH);
		}

		source.changed();
		playSound(SoundEvents.ITEM_PICKUP, 0.4F, 1.0F);
	}

	/** Mining is switched off: its tools go back into the tool chest (what does not fit is dropped next to it). */
	private void returnTools(ToolSource source) {
		for (int kind = 0; kind < tools.length; kind++) {
			ItemStack tool = tools[kind];
			tools[kind] = ItemStack.EMPTY;

			if (!tool.isEmpty() && !source.put(tool)) {
				spawnAtLocation((ServerLevel) level(), tool);
			}
		}

		if (!torches.isEmpty() && !source.put(torches)) {
			spawnAtLocation((ServerLevel) level(), torches);
		}

		torches = ItemStack.EMPTY;
		source.changed();
	}

	/** Of its tools the one that digs this block fastest (empty when none helps, then it digs by beak). */
	private int bestTool(BlockState state) {
		int best = -1;
		float bestSpeed = 1;

		for (int kind = 0; kind < tools.length; kind++) {
			if (!tools[kind].isEmpty()) {
				float speed = tools[kind].getDestroySpeed(state);

				if (speed > bestSpeed || best < 0 && tools[kind].isCorrectToolForDrops(state)) {
					best = kind;
					bestSpeed = Math.max(bestSpeed, speed);
				}
			}
		}

		return best;
	}

	private int enchantmentLevel(ResourceKey<Enchantment> key, ItemStack stack) {
		return level().registryAccess().lookup(Registries.ENCHANTMENT).flatMap(registry -> registry.get(key))
				.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, stack)).orElse(0);
	}

	/** Ticks to dig the block, like a player with the same tool: hard blocks take longer, Efficiency makes it quicker. */
	private int digTime(BlockPos pos, BlockState state) {
		if (MiningJob.isWater(state)) {
			// pumped away
			return 4;
		}

		float hardness = state.getDestroySpeed(level(), pos);

		if (hardness <= 0) {
			return 3;
		}

		int kind = bestTool(state);
		ItemStack tool = kind < 0 ? ItemStack.EMPTY : tools[kind];
		float speed = tool.isEmpty() ? 1 : tool.getDestroySpeed(state);

		if (speed > 1) {
			int efficiency = enchantmentLevel(Enchantments.EFFICIENCY, tool);

			if (efficiency > 0) {
				speed += efficiency * efficiency + 1;
			}
		}

		boolean correct = !state.requiresCorrectToolForDrops() || !tool.isEmpty() && tool.isCorrectToolForDrops(state);
		return Math.max(4, (int) Math.ceil(hardness * (correct ? 30 : 100) / speed));
	}

	/**
	 * Breaks the block it dug: the drops (with Fortune, Silk Touch and so on of its tool) go into its bag, the tool
	 * wears down (Unbreaking counts), and experience from the block repairs tools with Mending.
	 */
	private void breakTarget(DigJob job) {
		ServerLevel server = (ServerLevel) level();
		fruitlessDigs = 0;
		BlockPos pos = mineTarget;
		BlockState state = server.getBlockState(pos);

		if (MiningJob.isWater(state)) {
			// water is not broken (that would leave it where it is) but filled with cobblestone, which is dug next
			server.setBlockAndUpdate(pos, Blocks.COBBLESTONE.defaultBlockState());
			playSound(SoundEvents.STONE_PLACE, 0.5F, 1.0F);
			server.destroyBlockProgress(getId(), pos, -1);

			if (job instanceof MiningJob area) {
				area.pumped(pos);
			}

			job.dug(server, pos);
			mineTarget = null;
			digTicks = 0;
			digTotal = 0;
			minePhase = 0;
			return;
		}

		int kind = bestTool(state);
		ItemStack tool = kind < 0 ? ItemStack.EMPTY : tools[kind];
		boolean harvests = !state.requiresCorrectToolForDrops() || !tool.isEmpty() && tool.isCorrectToolForDrops(state);
		List<ItemStack> drops = harvests && job.keepsDrops(state) ? Block.getDrops(state, server, pos, null, this, tool) : List.of();
		boolean wears = state.getDestroySpeed(server, pos) > 0;
		server.destroyBlock(pos, false, this);
		state.spawnAfterBreak(server, pos, tool, true);

		for (ItemStack drop : drops) {
			if (!addToBag(drop)) {
				Block.popResource(server, pos, drop);
			}
		}

		if (wears) {
			wear(server, kind, true);
		}

		mend(server, pos);
		server.destroyBlockProgress(getId(), pos, -1);
		job.dug(server, pos);

		if (job instanceof MiningJob area && state.is(net.minecraft.tags.BlockTags.LOGS)) {
			fell(server, area, pos);
		}

		mineTarget = null;
		digTicks = 0;
		digTotal = 0;
		minePhase = 0;
	}

	/** Blocks of a tree a robot fells at once: more are left for the next log. */
	private static final int TREE_BLOCKS = 256;

	/**
	 * Mining: a log it cut fells the whole tree above it, the logs and leaves joined to it (also those far above
	 * its reach, which no robot could get at otherwise). What they drop goes into its bag.
	 */
	private void fell(ServerLevel server, MiningJob area, BlockPos cut) {
		java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
		Set<BlockPos> seen = new HashSet<>();
		open.add(cut);
		seen.add(cut);
		int felled = 0;

		while (!open.isEmpty() && felled < TREE_BLOCKS) {
			BlockPos from = open.poll();

			for (BlockPos next : BlockPos.betweenClosed(from.offset(-1, 0, -1), from.offset(1, 1, 1))) {
				if (next.getY() < cut.getY() || !seen.add(next.immutable())) {
					continue;
				}

				BlockState state = server.getBlockState(next);
				boolean log = state.is(net.minecraft.tags.BlockTags.LOGS);

				int dx = next.getX() - cut.getX();
				int dz = next.getZ() - cut.getZ();
				int far = dx * dx + dz * dz;

				// the tree's own crown and branches, not the next tree of a forest whose leaves touch it
				if (!(log ? far <= 64 : state.is(net.minecraft.tags.BlockTags.LEAVES) && far <= 36) || !area.wanted(server, next)) {
					continue;
				}

				BlockPos pos = next.immutable();
				int kind = bestTool(state);
				ItemStack tool = kind < 0 ? ItemStack.EMPTY : tools[kind];

				for (ItemStack drop : Block.getDrops(state, server, pos, null, this, tool)) {
					if (!addToBag(drop)) {
						Block.popResource(server, pos, drop);
					}
				}

				server.destroyBlock(pos, false, this);
				area.dug(server, pos);
				felled++;

				if (log) {
					wear(server, kind, false);
				}

				open.add(pos);
			}
		}
	}

	/** Into its bag, onto stacks of the same kind first; false when the bag is full. */
	private boolean addToBag(ItemStack drop) {
		for (ItemStack stack : bag) {
			if (drop.isEmpty()) {
				return true;
			}

			if (ItemStack.isSameItemSameComponents(stack, drop) && stack.getCount() < stack.getMaxStackSize()) {
				int moved = Math.min(drop.getCount(), stack.getMaxStackSize() - stack.getCount());
				stack.grow(moved);
				drop.shrink(moved);
			}
		}

		if (drop.isEmpty()) {
			return true;
		}

		if (bag.size() >= BAG_SLOTS) {
			return false;
		}

		bag.add(drop.copy());
		drop.setCount(0);
		return true;
	}

	/**
	 * The tool of this kind wears down by one (Unbreaking counts) and breaks when it is used up. A tool with Mending
	 * never wears down at all: a robot's Mending tool lasts forever.
	 */
	private void wear(ServerLevel server, int kind, boolean sound) {
		ItemStack tool = kind < 0 ? ItemStack.EMPTY : tools[kind];

		if (tool.isEmpty() || !tool.isDamageableItem()) {
			return;
		}

		if (enchantmentLevel(Enchantments.MENDING, tool) > 0) {
			// one that was worn before (by a player or an older version) is whole again
			if (tool.isDamaged()) {
				tool.setDamageValue(0);
			}

			return;
		}

		tool.hurtAndBreak(1, server, (ServerPlayer) null, item -> {
		});

		if (tool.isEmpty()) {
			tools[kind] = ItemStack.EMPTY;

			if (sound) {
				playSound(SoundEvents.ITEM_PICKUP, 0.6F, 0.5F);
			}
		}
	}

	/** Experience orbs around the block it dug repair its tools with Mending; without such a tool they stay for the players. */
	private void mend(ServerLevel server, BlockPos pos) {
		for (ExperienceOrb orb : server.getEntitiesOfClass(ExperienceOrb.class, new AABB(pos).inflate(2))) {
			for (ItemStack tool : tools) {
				if (!tool.isEmpty() && tool.isDamaged() && orb.isAlive()) {
					int repair = EnchantmentHelper.modifyDurabilityToRepairFromXp(server, tool, orb.getValue());

					if (repair > 0) {
						tool.setDamageValue(Math.max(0, tool.getDamageValue() - repair));
						orb.discard();
					}
				}
			}
		}
	}

	/** Lights the strip mine: a torch on a spot of a tunnel within reach that wants one. */
	private void placeTorch(OreJob job) {
		ServerLevel server = (ServerLevel) level();
		BlockPos spot = job.torchSpot(server, getEyePosition(), MINE_REACH);

		if (spot != null) {
			server.setBlockAndUpdate(spot, Blocks.TORCH.defaultBlockState());
			torches.shrink(1);
			playSound(SoundEvents.WOOD_PLACE, 0.5F, 1.0F);
		}
	}

	/** For the smoke test: hacking at a block right now. */
	public boolean isDigging() {
		return minePhase == 3 && digTicks > 0;
	}

	/** For the smoke test: its tools and bag. */
	public String describeMining() {
		return "mining=" + mining + " phase=" + minePhase + " target=" + mineTarget + " idle=" + mineIdle + " finished=" + mineFinished
				+ " problem=" + (mineProblem == null ? "" : mineProblem.getString()) + " tools=" + java.util.Arrays.toString(tools) + " torches=" + torches + " bag=" + bag
				+ " carried=" + getCarried() + " rescue=" + rescuing + " stranded=" + strandedTicks + " fruitless=" + fruitlessDigs;
	}

	/** For the smoke test: it is digging itself out. */
	public boolean rescuing() {
		return rescuing;
	}

	/** How many blocks in a row it may give up on at one spot before it counts as stuck there. */
	private static final int FRUITLESS_DIGS = 4;

	/** It gave up on a block it got no closer to. */
	private void noteFruitless() {
		if (fruitlessAt == null || position().distanceTo(fruitlessAt) > 1.5) {
			fruitlessAt = position();
			fruitlessDigs = 0;
		}

		fruitlessDigs++;
	}

	/** It keeps giving up on the blocks it is handed and does not get away from the spot. */
	private boolean diggingInVain() {
		return fruitlessDigs >= FRUITLESS_DIGS && fruitlessAt != null && position().distanceTo(fruitlessAt) <= 1.5;
	}

	/** For the smoke test: nothing in its beak or bag, not digging. */
	public boolean miningDone() {
		return getCarried().isEmpty() && bag.isEmpty() && minePhase == 0 && getAction() == 0;
	}

	public ItemStack tool(int kind) {
		return tools[kind];
	}

	/**
	 * Mining mode: fetches pickaxe, axe and shovel from the tool chest, then digs the blocks the station's job hands
	 * out, highest first. With a full bag (or nothing to dig) it stops, and the work goal takes what it gathered to the
	 * chests. When mining is switched off, it takes its tools back to the chest.
	 */
	private class MineGoal extends Goal {
		private int cooldown;
		private double bestDistance;
		private int stuckTicks;
		private Path lastPath;
		private int lastNode;
		/** How long it has been on its way to the block it is to dig. */
		private int wayTicks;
		private int stillTicks;

		MineGoal() {
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		/** Free to dig: at a station that is there and switched on, not called back, nothing to hand in. */
		private boolean ready() {
			return station != null && recallPhase == RECALL_NONE && !areaOff && !stationMissing() && getCarried().isEmpty()
					&& getAction() == 0 && !needsUnload() && (mining || hasAnyTool());
		}

		@Override
		public boolean canUse() {
			if (!ready() || cooldown-- > 0) {
				return false;
			}

			cooldown = SEARCH_TICKS;
			return plan();
		}

		@Override
		public boolean canContinueToUse() {
			return minePhase != 0 && station != null && recallPhase == RECALL_NONE && !areaOff && getCarried().isEmpty() && getAction() == 0
					&& (mining || minePhase == 2);
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void start() {
			setSitting(false);
			idleTicks = 0;
			bestDistance = Double.MAX_VALUE;
			stuckTicks = 0;
			lastPath = null;
		}

		@Override
		public void stop() {
			getNavigation().stop();
			dropMineTarget();

			if (minePhase == 4) {
				stopClimb(miningJob(), false);
			}

			minePhase = 0;
			updateMiningLook();
		}

		/** Works out what to do next; false when there is nothing (then it waits at the station). */
		private boolean plan() {
			// a way can only be worked out standing (or swimming); try again on the next search (or when there is
			// time for working out ways again)
			if (!standing() || !mayPath()) {
				return false;
			}

			ToolSource chest = toolContainer();

			if (!mining) {
				if (chest != null && hasAnyTool()) {
					minePhase = 2;
					return true;
				}

				return false;
			}

			List<String> missing = unavailableTools(chest);

			if (!missing.isEmpty()) {
				// with a pickaxe it digs on without the chest; it needs the chest only for new tools
				mineProblem = Component.literal(chest == null ? "Fehler: Werkzeugkiste fehlt" : "Fehler: Werkzeug fehlt: " + String.join(", ", missing));
				mineIdle = true;
				return false;
			}

			if (wantsTools(chest)) {
				mineProblem = null;
				minePhase = 1;
				return true;
			}

			mineProblem = null;
			DigJob job = miningJob();
			long now = level().getGameTime();

			if (bag.isEmpty()) {
				oreDelivery = false;
			} else if (job instanceof OreJob ores && ores.tunnelDone(getUUID())) {
				// after each tunnel its ores go home before the next one
				oreDelivery = true;
				return false;
			}

			if (job instanceof OreJob ores && !torches.isEmpty()) {
				placeTorch(ores);
			}

			List<BlockPos> candidates = job.candidates((ServerLevel) level(), blockPosition(), getUUID(), PATH_CHECKS, now);
			candidates.addAll(job.nearby((ServerLevel) level(), blockPosition(), getUUID(), PATH_CHECKS, now));
			BlockPos climbTo = null;
			BlockPos climbFrom = null;
			BlockPos approach = null;
			Path approachPath = null;
			boolean denied = false;

			for (BlockPos pos : candidates) {
				if (getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= MINE_REACH) {
					plannedPath = null;
					return startDigging(job, pos);
				}

				Path path = digPath(pos);

				if (pathDenied) {
					// no time left for ways in this tick: the rest is looked at on the next search
					denied = true;
					break;
				}

				if (path != null && path.canReach()) {
					plannedPath = path;
					return startDigging(job, pos);
				}

				// (not for the leaves of a tree: cutting its trunk fells the whole tree)
				if (climbTo == null && job instanceof MiningJob area && pos.getY() > getY() + 1 && !level().getBlockState(pos).is(net.minecraft.tags.BlockTags.LEAVES)) {
					// too high to walk up to: it builds itself up there
					climbFrom = climbStand(area, pos);

					if (climbFrom != null) {
						climbTo = pos;
						continue;
					}

					if (pathDenied) {
						denied = true;
						break;
					}
				}

				if (approach == null && job instanceof MiningJob && closerAlong(path, pos)) {
					// too far for one way (a mountain top, say): it walks as far as the way goes and looks again from there
					approach = pos;
					approachPath = path;
					continue;
				}

				job.skip(pos, getUUID(), now);
			}

			if (climbTo != null) {
				startClimb(job, climbTo, climbFrom);
				return true;
			}

			if (approach != null) {
				plannedPath = approachPath;
				return startDigging(job, approach);
			}

			if (denied) {
				cooldown = 1 + getRandom().nextInt(SEARCH_TICKS);
				return false;
			}

			mineIdle = true;
			mineFinished = job.finished();
			return false;
		}

		/**
		 * A way to where it can reach a block to dig. Asked for the block itself, the game would aim for the first
		 * open block above it instead, which for a block deep in the rock (the next one of a tunnel) is a cave or the
		 * surface far above, so no way would ever get there and the robots gave up on every tunnel after a few blocks.
		 */
		private Path digPath(BlockPos pos) {
			return getNavigation().createPath(Set.of(pos.immutable()), 4);
		}

		/** A way that does not get there but ends a good bit closer to the block. */
		private boolean closerAlong(Path path, BlockPos target) {
			if (path == null || path.getNodeCount() < 3) {
				return false;
			}

			return path.getEndNode().asBlockPos().distSqr(target) + 16 < blockPosition().distSqr(target);
		}

		/** What it builds itself up to, and the spot it builds its pillar from. */
		private BlockPos climbTarget;
		private BlockPos climbFrom;
		private int climbTicks;
		private int climbWait;

		/**
		 * Where it can build itself up to a block it cannot walk up to: the ground right below it or beside it, inside
		 * the area and away from the staircase, that it can walk to. Null when there is none.
		 */
		private BlockPos climbStand(MiningJob area, BlockPos target) {
			ServerLevel server = (ServerLevel) level();

			for (BlockPos column : List.of(target, target.north(), target.south(), target.east(), target.west())) {
				if (!area.contains(column) || Math.abs(column.getX() - area.pillar().getX()) <= 1 && Math.abs(column.getZ() - area.pillar().getZ()) <= 1) {
					continue;
				}

				BlockPos.MutableBlockPos pos = column.mutable().move(Direction.DOWN);

				for (int i = 0; i < MAX_CLIMB && pos.getY() > server.getMinY(); i++, pos.move(Direction.DOWN)) {
					BlockPos below = pos.below();

					if (!server.getBlockState(pos).getCollisionShape(server, pos).isEmpty()) {
						break;
					}

					if (server.getBlockState(below).getCollisionShape(server, below).isEmpty()) {
						continue;
					}

					// ground: can it get there?
					if (server.getFluidState(pos).isEmpty() && !area.onPlatform(pos)) {
						Path path = getNavigation().createPath(pos.immutable(), 0);

						if (path != null && path.canReach()) {
							return pos.immutable();
						}

						if (pathDenied) {
							return null;
						}
					}

					break;
				}
			}

			return null;
		}

		private void startClimb(DigJob job, BlockPos target, BlockPos from) {
			job.claim(target, getUUID());
			climbTarget = target.immutable();
			climbFrom = from;
			climbTicks = 0;
			climbWait = 0;
			bestDistance = Double.MAX_VALUE;
			stuckTicks = 0;
			minePhase = 4;
			mineIdle = false;
			mineFinished = false;
		}

		private void stopClimb(DigJob job, boolean skip) {
			if (climbTarget != null && job != null) {
				if (skip) {
					job.skip(climbTarget, getUUID(), level().getGameTime());
				} else {
					job.release(climbTarget, getUUID());
				}
			}

			climbTarget = null;
			climbFrom = null;
			minePhase = 0;
		}

		/**
		 * Builds itself up to a block high above: walks to the foot of its pillar, then hops up a block at a time and
		 * puts cobblestone under itself, like a player, until it reaches the block. The pillar is dug again with its level.
		 */
		private void tickClimb() {
			DigJob job = miningJob();
			ServerLevel server = (ServerLevel) level();

			if (!(job instanceof MiningJob area) || climbTarget == null || !job.wanted(server, climbTarget)) {
				stopClimb(job, false);
				cooldown = 0;
				return;
			}

			Vec3 centre = Vec3.atCenterOf(climbTarget);
			getLookControl().setLookAt(centre.x, centre.y, centre.z);

			if (onGround() && getEyePosition().distanceTo(centre) <= MINE_REACH) {
				// up there: it digs it
				BlockPos target = climbTarget;
				climbTarget = null;
				plannedPath = null;
				startDigging(job, target);
				return;
			}

			if (++climbTicks > WAY_TICKS) {
				stopClimb(job, true);
				noteFruitless();
				return;
			}

			if (net.minecraft.util.Mth.floor(getX()) != climbFrom.getX() || net.minecraft.util.Mth.floor(getZ()) != climbFrom.getZ() || getY() < climbFrom.getY() - 0.5) {
				// on the way to the foot of the pillar
				Vec3 foot = Vec3.atBottomCenterOf(climbFrom);

				if (stuck(position().distanceTo(foot))) {
					stopClimb(job, true);
					noteFruitless();
					return;
				}

				if (getNavigation().isDone()) {
					Path path = getNavigation().createPath(climbFrom, 0);

					if (path != null && path.canReach() && path.getNodeCount() > 1) {
						getNavigation().moveTo(path, 1.0);
					} else {
						getMoveControl().setWantedPosition(foot.x, foot.y, foot.z, 1.0);
					}
				}

				return;
			}

			getNavigation().stop();

			if (!onGround() || ++climbWait < 6) {
				return;
			}

			climbWait = 0;
			BlockPos feet = BlockPos.containing(getX(), getY() + 0.1, getZ());
			BlockPos head = feet.above();
			BlockState overhead = server.getBlockState(head);

			if (!overhead.getCollisionShape(server, head).isEmpty()) {
				// something above its head: dug first, then it climbs on
				if (job.wanted(server, head)) {
					job.release(climbTarget, getUUID());
					climbTarget = null;
					plannedPath = null;
					startDigging(job, head);
				} else {
					stopClimb(job, true);
				}

				return;
			}

			if (feet.getY() - climbFrom.getY() >= MAX_CLIMB || !server.getBlockState(feet).canBeReplaced() || area.isStair(feet) || area.onPlatform(feet)) {
				stopClimb(job, true);
				return;
			}

			// hop up and put a block under itself
			setDeltaMovement(Vec3.ZERO);
			setPos(feet.getX() + 0.5, feet.getY() + 1, feet.getZ() + 0.5);
			server.setBlockAndUpdate(feet, Blocks.COBBLESTONE.defaultBlockState());
			playSound(SoundEvents.STONE_PLACE, 0.5F, 1.0F);
			bestDistance = Double.MAX_VALUE;
			stuckTicks = 0;
		}

		private boolean startDigging(DigJob job, BlockPos pos) {
			wayTicks = 0;
			goalPathWait = 0;
			goalPathMisses = 0;
			stillTicks = 0;
			job.claim(pos, getUUID());
			mineTarget = pos.immutable();
			digTicks = 0;
			digTotal = 0;
			minePhase = 3;
			mineIdle = false;
			mineFinished = false;
			return true;
		}

		/** Not getting any closer for a while (and not along its way either): gives up on it. */
		private boolean stuck(double distance) {
			if (distance < bestDistance - 0.5) {
				bestDistance = distance;
				stuckTicks = 0;
				return false;
			}

			Path path = getNavigation().getPath();

			if (path != lastPath) {
				lastPath = path;
				lastNode = path == null ? 0 : path.getNextNodeIndex();
			} else if (path != null && path.getNextNodeIndex() > lastNode) {
				lastNode = path.getNextNodeIndex();
				stuckTicks = 0;
				return false;
			}

			return ++stuckTicks >= STUCK_TICKS;
		}

		@Override
		public void tick() {
			if (minePhase == 1 || minePhase == 2) {
				tickToolChest();
			} else if (minePhase == 3) {
				tickDigging();
			} else if (minePhase == 4) {
				tickClimb();
			}

			updateMiningLook();
		}

		private void tickToolChest() {
			ToolSource chest = toolContainer();

			if (chest == null) {
				minePhase = 0;
				return;
			}

			Vec3 goal = Vec3.atCenterOf(chest.pos());
			getLookControl().setLookAt(goal.x, goal.y, goal.z);
			double distance = position().distanceTo(goal);

			if (distance <= 2.2 || (canReachChest(goal) || distance <= CROWD_REACH) && seesChest(chest.pos())) {
				getNavigation().stop();

				if (minePhase == 1) {
					takeTools(chest);
				} else {
					returnTools(chest);
				}

				minePhase = 0;
				cooldown = 0;
				return;
			}

			if (stuck(distance) && distance <= HAND_IN_REACH) {
				// crowded out by the others right at the chest: it reaches over from here
				getNavigation().stop();

				if (minePhase == 1) {
					takeTools(chest);
				} else {
					returnTools(chest);
				}

				minePhase = 0;
				cooldown = 0;
				return;
			}

			if (stuckTicks >= STUCK_TICKS) {
				mineProblem = Component.literal("Fehler: Werkzeugkiste nicht erreichbar");
				minePhase = 0;
				cooldown = RETRY_HOME_TICKS / 2;
				return;
			}

			if (getNavigation().isDone()) {
				Path path = getNavigation().createPath(chest.pos(), 1);

				if ((path == null || !path.canReach()) && station != null && getY() < station.getY() - 1) {
					// still down in the strip mine: first up to the station, the chest stands next to it
					Path home = getNavigation().createPath(station, 2);

					if (home != null && home.getNodeCount() > 1) {
						path = home;
					}
				}

				if (path != null) {
					getNavigation().moveTo(path, 1.0);
				} else {
					getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
				}
			}
		}

		private void tickDigging() {
			DigJob job = miningJob();
			ServerLevel server = (ServerLevel) level();

			if (job == null || mineTarget == null) {
				minePhase = 0;
				return;
			}

			if (!job.wanted(server, mineTarget)) {
				// gone already (dug by someone else, fallen, ...)
				server.destroyBlockProgress(getId(), mineTarget, -1);
				job.dug(server, mineTarget);
				mineTarget = null;
				minePhase = 0;
				cooldown = 0;
				return;
			}

			Vec3 centre = Vec3.atCenterOf(mineTarget);
			getLookControl().setLookAt(centre.x, centre.y, centre.z);
			double distance = getEyePosition().distanceTo(centre);

			// in reach and not getting any closer (crowded by the others on the staircase): it digs from where it is
			boolean still = getDeltaMovement().horizontalDistanceSqr() < 1.0E-4;

			if (distance <= MINE_REACH && (getNavigation().isDone() || distance <= 3 || still && digTicks == 0 && ++stillTicks > 20 || digTicks > 0)) {
				stillTicks = 0;
				getNavigation().stop();
				BlockState state = server.getBlockState(mineTarget);

				if (digTotal == 0) {
					digTotal = digTime(mineTarget, state);
				}

				digTicks++;
				server.destroyBlockProgress(getId(), mineTarget, Math.min(9, digTicks * 10 / digTotal));

				if (digTicks % 4 == 1) {
					var sound = state.getSoundType();
					level().playSound(null, mineTarget, sound.getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, sound.getVolume() * 0.25F,
							sound.getPitch() * 0.5F);
				}

				if (digTicks >= digTotal) {
					breakTarget(job);
					cooldown = 0;
				}

				return;
			}

			if (digTicks > 0) {
				// pushed away while digging: starts again once back
				digTicks = 0;
				server.destroyBlockProgress(getId(), mineTarget, -1);
			}

			// jostled about by the others it may seem to get on along ever new ways and never get there: after a while it
			// leaves that block too
			if (stuck(distance) || ++wayTicks > WAY_TICKS) {
				job.skip(mineTarget, getUUID(), level().getGameTime());
				dropMineTarget();
				noteFruitless();
				return;
			}

			if (plannedPath != null) {
				getNavigation().moveTo(plannedPath, 1.0);
				plannedPath = null;
			} else if (getNavigation().isDone()) {
				Path path = digPath(mineTarget);

				if (pathDenied) {
					// no time for ways in this tick: on the next one
					return;
				}

				BlockPos from;

				if (path != null && (path.canReach() || job instanceof MiningJob && closerAlong(path, mineTarget))) {
					getNavigation().moveTo(path, 1.0);
				} else if (job instanceof MiningJob area && mineTarget.getY() > getY() + 1 && (from = climbStand(area, mineTarget)) != null) {
					// walked as far as it could and the block is still above it: it builds itself up from here
					BlockPos target = mineTarget;
					dropMineTarget();
					startClimb(job, target, from);
				} else if (!pathDenied) {
					getMoveControl().setWantedPosition(centre.x, centre.y, centre.z, 1.0);
				}
			}
		}
	}

	/**
	 * When it is stuck down in the mine (no way home, nothing it can get to), it digs itself out with its pickaxe (in
	 * the quarry only over to the central staircase, on its own level): a staircase towards its station, up to the station's height and then on along the ground, with cobblestone where a
	 * step or the floor is missing and over water or lava beside the way. As soon as there is a way home again it
	 * stops and walks.
	 */
	private class RescueGoal extends Goal {
		private static final int STRANDED_TICKS = 200;
		private static final int MAX_STEPS = 400;
		private BlockPos digging;
		private int digTicks;
		private int digTotal;
		private BlockPos stepTo;
		private int stepTicks;
		private int steps;
		private int checkCooldown;
		private Vec3 strandedAt;
		/** Where it set off along a way home it found; it only stops digging once it really gets on along it. */
		private Vec3 walkFrom;
		private int walkTicks;

		RescueGoal() {
			setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
		}

		private boolean stranded() {
			long now = level().getGameTime();
			// also when mining was switched off and it cannot get its tools up to the chest
			// or it keeps being handed blocks by ways it cannot go and never gets away from the spot
			boolean stuck = now < homeUnreachableUntil || mineIdle && !mineFinished && minePhase == 0 || !mining && mineProblem != null || diggingInVain();
			strandedTicks = !tools[PICKAXE].isEmpty() && station != null && !nearHome() && stuck && getAction() == 0 ? strandedTicks + 1 : 0;

			if (strandedTicks == 1) {
				strandedAt = position();
			}

			return strandedTicks >= STRANDED_TICKS;
		}

		@Override
		public boolean canUse() {
			if (level().isClientSide() || !stranded() || !standing()) {
				return false;
			}

			// a way home after all: nothing to dig; unless it has not moved from the spot all the while (a way the
			// game finds but the robot cannot go, out of a hole with its head under a step, say)
			Path home = getNavigation().createPath(home(), 2);

			if (home == null && pathDenied) {
				// no time for ways in this tick: it looks again on the next one
				return false;
			}

			boolean moved = strandedAt == null || position().distanceTo(strandedAt) > 1.0;
			strandedTicks = home != null && home.canReach() && moved ? 0 : strandedTicks;
			return strandedTicks > 0;
		}

		@Override
		public boolean canContinueToUse() {
			return !tools[PICKAXE].isEmpty() && steps < MAX_STEPS && !nearHome() && getAction() == 0;
		}

		@Override
		public void start() {
			getNavigation().stop();
			dropMineTarget();
			digging = null;
			stepTo = null;
			walkFrom = null;
			steps = 0;
			checkCooldown = 0;
			rescuing = true;
		}

		@Override
		public void stop() {
			if (digging != null) {
				level().destroyBlockProgress(getId(), digging, -1);
			}

			digging = null;
			stepTo = null;
			walkFrom = null;
			strandedTicks = 0;
			homeUnreachableUntil = 0;
			mineIdle = false;
			fruitlessDigs = 0;
			fruitlessAt = null;
			rescuing = false;
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			ServerLevel server = (ServerLevel) level();

			if (digging != null) {
				BlockState state = server.getBlockState(digging);
				getLookControl().setLookAt(Vec3.atCenterOf(digging));

				if (state.getCollisionShape(server, digging).isEmpty() && !MiningJob.isWater(state)) {
					digging = null;
					return;
				}

				digTicks++;
				server.destroyBlockProgress(getId(), digging, Math.min(9, digTicks * 10 / Math.max(1, digTotal)));

				if (digTicks % 4 == 1) {
					var sound = state.getSoundType();
					server.playSound(null, digging, sound.getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS, sound.getVolume() * 0.25F, sound.getPitch() * 0.5F);
				}

				if (digTicks >= digTotal) {
					breakFree(server, digging, state);
					digging = null;
				}

				return;
			}

			if (stepTo != null) {
				Vec3 goal = Vec3.atBottomCenterOf(stepTo);
				getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);

				if (stepTo.getY() > getBlockY() && onGround()) {
					getJumpControl().jump();
				}

				if (position().distanceToSqr(goal) < 0.2 || ++stepTicks > 60) {
					stepTo = null;
				}

				return;
			}

			if (walkFrom != null) {
				if (position().distanceTo(walkFrom) > 2.0) {
					// on its way home: it walks the rest
					steps = MAX_STEPS;
				} else if (++walkTicks > 60 || getNavigation().isDone()) {
					// the game found a way it cannot go (its head under a step, say): it digs on and looks again later
					getNavigation().stop();
					walkFrom = null;
					checkCooldown = 200;
				}

				return;
			}

			// now and then: is there a way home again?
			if (steps > 2 && --checkCooldown <= 0 && standing()) {
				checkCooldown = 20;
				Path home = getNavigation().createPath(home(), 2);

				if (home != null && home.canReach()) {
					getNavigation().moveTo(home, 1.0);
					walkFrom = position();
					walkTicks = 0;
					return;
				}
			}

			if (!standing()) {
				return;
			}

			planStep(server);
		}

		/**
		 * The next step towards the station: what is in the way is dug, a missing step or floor filled. Where a block
		 * it may not dig is in the way (a step of the staircase, bedrock, a chest), it goes round it: along the other
		 * direction towards the station, to the side, or first on the level instead of up.
		 */
		private void planStep(ServerLevel server) {
			BlockPos at = blockPosition();
			BlockPos h = home();

			if (!oreSearch && lastMine != null && lastMine.contains(at) && station != null && at.getY() < station.getY() - 1) {
				// down in the quarry: not a staircase of its own, but a way on its level over to the central staircase,
				// which leads up to the station
				BlockPos pillar = lastMine.pillar();
				int sx = Integer.signum(at.getX() - pillar.getX());
				int sz = Integer.signum(at.getZ() - pillar.getZ());
				h = new BlockPos(pillar.getX() + 2 * (sx == 0 && sz == 0 ? 1 : sx), at.getY(), pillar.getZ() + 2 * sz);
			}

			int dx = h.getX() - at.getX();
			int dz = h.getZ() - at.getZ();
			boolean up = at.getY() < h.getY();
			boolean down = at.getY() > h.getY() + 1;

			if (dx == 0 && dz == 0 && !up) {
				steps = MAX_STEPS;
				return;
			}

			boolean alongX = Math.abs(dx) >= Math.abs(dz);
			Direction main = alongX ? (dx >= 0 ? Direction.EAST : Direction.WEST) : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
			List<Direction> dirs = new ArrayList<>(List.of(main));
			int across = alongX ? dz : dx;

			if (across != 0) {
				dirs.add(alongX ? (across > 0 ? Direction.SOUTH : Direction.NORTH) : (across > 0 ? Direction.EAST : Direction.WEST));
			}

			for (Direction dir : List.of(main.getClockWise(), main.getCounterClockWise(), main.getOpposite())) {
				if (!dirs.contains(dir)) {
					dirs.add(dir);
				}
			}

			int rise = up ? 1 : down ? -1 : 0;

			for (int dy : rise == 0 ? new int[] {0} : new int[] {rise, 0}) {
				for (Direction dir : dirs) {
					if (tryStep(server, at, dir, dy)) {
						return;
					}
				}
			}

			// shut in for good on all sides: it waits a little and tries again
			steps += 10;
		}

		/** A step one block in that direction (and up or down): false when a block it may not dig is in the way. */
		private boolean tryStep(ServerLevel server, BlockPos at, Direction dir, int dy) {
			BlockPos next = at.relative(dir).above(dy);
			List<BlockPos> clearing = new ArrayList<>(List.of(next, next.above()));

			if (dy > 0) {
				// room to jump up (also right over its head: it is low enough to stand under a step of the staircase)
				clearing.add(at.above());
				clearing.add(at.above(2));
			} else if (dy < 0) {
				clearing.add(next.above(2));
			}

			for (BlockPos pos : clearing) {
				BlockState state = server.getBlockState(pos);

				if (!state.getCollisionShape(server, pos).isEmpty() && !mayBreak(server, pos, state)) {
					return false;
				}
			}

			for (BlockPos pos : clearing) {
				sealLiquid(server, pos);
				BlockState state = server.getBlockState(pos);

				if (!state.getCollisionShape(server, pos).isEmpty()) {
					digging = pos.immutable();
					digTicks = 0;
					digTotal = digTime(pos, state);
					return true;
				}
			}

			// something to stand on there
			BlockPos floor = next.below();

			if (server.getBlockState(floor).canBeReplaced() || server.getBlockState(floor).getCollisionShape(server, floor).isEmpty()) {
				server.setBlockAndUpdate(floor, Blocks.COBBLESTONE.defaultBlockState());
				playSound(SoundEvents.STONE_PLACE, 0.5F, 1.0F);
			}

			steps++;
			stepTo = next.immutable();
			stepTicks = 0;
			return true;
		}

		/** Water or lava there or right beside it becomes cobblestone, so it does not run onto the way out. */
		private void sealLiquid(ServerLevel server, BlockPos pos) {
			for (BlockPos near : List.of(pos, pos.north(), pos.south(), pos.east(), pos.west(), pos.above())) {
				if (server.getBlockState(near).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
					server.setBlockAndUpdate(near, Blocks.COBBLESTONE.defaultBlockState());
				}
			}
		}

		private boolean mayBreak(ServerLevel server, BlockPos pos, BlockState state) {
			return state.getDestroySpeed(server, pos) >= 0 && !state.hasBlockEntity() && !state.is(RobotStationBlock.BLOCK) && !state.is(AntennaBlock.BLOCK)
					&& !state.is(RobotBarrierBlock.BLOCK) && !(miningJob() instanceof OreJob ores && ores.isStair(pos)) && !(miningJob() instanceof MiningJob area && (area.isStair(pos) || area.onPlatform(pos)));
		}

		/** Breaks the block; ores go into its bag (with the tool's enchantments), the rest is left. */
		private void breakFree(ServerLevel server, BlockPos pos, BlockState state) {
			int kind = bestTool(state);
			ItemStack tool = kind < 0 ? ItemStack.EMPTY : tools[kind];
			List<ItemStack> drops = OreJob.isOre(state) ? Block.getDrops(state, server, pos, null, RobotEntity.this, tool) : List.of();
			server.destroyBlock(pos, false, RobotEntity.this);

			for (ItemStack drop : drops) {
				if (!addToBag(drop)) {
					Block.popResource(server, pos, drop);
				}
			}

			if (state.getDestroySpeed(server, pos) > 0) {
				wear(server, kind, true);
			}
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

			// its area is switched off: back to the station, waiting there until it is on again
			if (areaOff) {
				release();
				return (!nearHome() || isInWater()) && level().getGameTime() >= homeUnreachableUntil;
			}

			// mining robots do not collect: they take what they dug to the chests, or wait at the station
			if (mining || hasAnyTool()) {
				release();

				if (needsUnload()) {
					setItemSlot(EquipmentSlot.MAINHAND, bag.remove(0));
					delivery = null;
					nextSearch = 0;
					return true;
				}

				return (mineIdle || !mining) && (!nearHome() || isInWater()) && level().getGameTime() >= homeUnreachableUntil;
			}

			// couriers do not collect: they fetch the post from mailboxes and bring it to the mailbox it is for
			if (courier) {
				if (target != null) {
					CLAIMED.remove(target.getUUID());
					target = null;
				}

				// the rest of the post in its bag, stack by stack
				if (!bag.isEmpty()) {
					setItemSlot(EquipmentSlot.MAINHAND, bag.remove(0));
					delivery = null;
					nextSearch = 0;
					return true;
				}

				courierTo = null;
				courierFull = false;
				// it needs no chest at its station as long as every post arrives
				noChest = false;
				chestsFull = false;

				// the post was taken away or has nowhere to go any more (looked at twice a second)
				if (pickupBox != null && level().getGameTime() % 10 == getId() % 10 && postTarget((ServerLevel) level(), pickupBox) == null) {
					dropPickup();
				}

				if (pickupBox == null && level().getGameTime() >= nextSearch) {
					nextSearch = level().getGameTime() + SEARCH_TICKS;
					pickupBox = findPost();

					if (pickupBox != null) {
						Mailbox.claim(Rooms.id(level()), pickupBox, getUUID());
					}
				}

				return pickupBox != null || (!nearHome() || isInWater()) && level().getGameTime() >= homeUnreachableUntil;
			}

			// an item that drifted out of its range (down a river, say) is not followed any further
			if (target != null && target.isAlive() && !wants(target)) {
				release();
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
				goalPathWait = 0;
				goalPathMisses = 0;
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

			BlockPos failedSide = goalKey == delivery && lastSteer != null && !lastSteer.equals(Vec3.atCenterOf(delivery))
					? BlockPos.containing(lastSteer) : null;

			if (failedSide != null) {
				// it could not get to that side of the chest: it tries another side before it gives up on the chest
				ignoredSides.put(failedSide, until);
			}

			if (goalKey == pickupBox) {
				// no way to that mailbox: another one first, this one again in a minute
				ignoredMailboxes.put(pickupBox, until);
				dropPickup();
				nextSearch = 0;
			} else if (failedSide != null && openSide(delivery) != null) {
				// on to the next side
			} else if (goalKey == delivery) {
				if (failedSide != null) {
					ignoredSides.remove(failedSide);
				}

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
				Vec3 look = target != null ? target.position() : delivery != null ? Vec3.atCenterOf(delivery) : pickupBox != null ? Vec3.atCenterOf(pickupBox) : null;

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
			} else if (pickupBox != null && getCarried().isEmpty()) {
				goal = Vec3.atCenterOf(pickupBox);
				reach = 1.2;
				goalKey = pickupBox;
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
					: goalKey == delivery || goalKey == pickupBox ? (distance <= reach || canReachChest(goal) || distance <= CROWD_REACH) && seesChest((BlockPos) goalKey)
							|| distance <= HAND_IN_REACH && stuckTicks >= STUCK_TICKS / 2 && lastGoal == goalKey
					: distance <= reach;

			if (arrived) {
				if (recallPhase == RECALL_RETURNING) {
					getNavigation().stop();
				} else if (!getCarried().isEmpty() && delivery != null) {
					startAction(2);
				} else if (goalKey == pickupBox) {
					startAction(3);
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

			if ((goalKey == delivery || goalKey == pickupBox) && !seesChest((BlockPos) goalKey)) {
				BlockPos side = openSide((BlockPos) goalKey);
				approach = side == null ? goal : Vec3.atBottomCenterOf(side);

				// its whole body has to fit up through the gap, or it bobs at the edge of the ceiling
				if (side != null && isInWater() && !level().noCollision(RobotEntity.this, getBoundingBox().expandTowards(0, Math.max(0, approach.y + 0.5 - getY()), 0))) {
					Vec3 exit = waterExit(approach);

					if (exit != null) {
						approach = exit;
					}
				}
			}

			steerInfo = goalKey == delivery && approach != goal ? String.format(java.util.Locale.ROOT, "%.1f/%.1f/%.1f", approach.x, approach.y, approach.z) : "";

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
				// an open side of the chest behind something (the station, another chest): walk round it on a path
				if (approach != goal && position().distanceTo(approach) > 0.5 && !straightTo(approach)) {
					Path path = getNavigation().createPath(BlockPos.containing(approach), 0);

					if (path != null && path.canReach() && path.getNodeCount() > 1) {
						getNavigation().moveTo(path, 1.0);
						return;
					}
				}

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
			} else if (getNavigation().isDone() && (goalKey == delivery || goalKey == pickupBox) && level().getGameTime() < goalPathWait) {
				// the last ways did not get to the chest: straight towards it for a bit before working out the next one
				getMoveControl().setWantedPosition(approach.x, approach.y, approach.z, 1.0);
			} else if (getNavigation().isDone() && (goalKey == delivery || goalKey == pickupBox)) {
				// out of the water again (or its way ended a bit off): a new way right up to the chest, or as close as it goes
				BlockPos box = (BlockPos) goalKey;
				Path path = getNavigation().createPath(box, 1);
				BlockPos side = openSide(box);

				if ((path == null || !path.canReach()) && side != null && !pathDenied) {
					path = getNavigation().createPath(side, 0);
				}

				if ((path == null || !path.canReach()) && !pathDenied) {
					path = getNavigation().createPath(box, 3);
				}

				if (path != null && path.canReach()) {
					goalPathMisses = 0;
				} else if (!pathDenied) {
					// none gets there: the next try only after a while, a little longer each time
					missedGoalPath();
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

	/** How far from its station it works: its own range, while mining at least the station's (the area all its robots dig). */
	private int workRange() {
		return mining ? Math.max(range, stationRange) : range;
	}

	/** Whether it may work out a way now: there is time left in this server tick for all robots and for itself. */
	private boolean mayPath() {
		return pathTimeLeft(0);
	}

	private boolean pathTimeLeft(long running) {
		if (!(level() instanceof ServerLevel server)) {
			return true;
		}

		int tick = server.getServer().getTickCount();

		if (tick != pathBudgetTick) {
			pathBudgetTick = tick;
			pathBudgetUsed = 0;
		}

		if (tick != ownPathTick) {
			ownPathTick = tick;
			ownPathUsed = 0;
		}

		if (tick < pathRestUntil) {
			return false;
		}

		return pathBudgetUsed + running < PATH_BUDGET_NANOS && ownPathUsed + running < ROBOT_PATH_BUDGET_NANOS;
	}

	/** Time all robots spent on working out ways in the given server tick (0 for another tick), for the smoke tests. */
	static long pathTimeInTick(int tick) {
		return tick == pathBudgetTick ? pathBudgetUsed : 0;
	}

	private void notePathTime(long nanos) {
		pathTimeLeft(0);
		pathBudgetUsed += nanos;
		ownPathUsed += nanos;

		if (ownPathUsed >= ROBOT_PATH_BUDGET_NANOS && level() instanceof ServerLevel server) {
			pathRestUntil = server.getServer().getTickCount() + 1 + PATH_REST_TICKS;
		}
	}

	/** After a way that did not get to the chest or block: waits a little longer each time before the next try. */
	private void missedGoalPath() {
		goalPathMisses = Math.min(goalPathMisses + 1, 4);
		goalPathWait = level().getGameTime() + (5L << goalPathMisses);
	}

	/**
	 * Paths may be as long as its range, so it can work out the whole way to anything in range; in the ore search
	 * also all the way down the staircase.
	 */
	private void applyRange() {
		int depth = mining && station != null ? Math.max(0, station.getY() - level().getMinY()) : 0;
		// to the far corner of the chunks it digs
		int reach = area != null ? areaReach : mining ? Math.max(range, (int) Math.ceil((Math.round(stationRange / 16.0F) + 1) * 16 * 1.42)) : workRange();
		getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(reach + 16 + depth);
		getNavigation().updatePathfinderMaxVisitedNodes();
	}

	/** The strip mine it last worked in: its staircase still leads it up when the ore search is switched off. */
	private OreJob lastOres;
	/** The quarry it last worked in: its central staircase still leads it up when mining is switched off. */
	private MiningJob lastMine;

	/** Walks like any mob, but treats robot barriers as walls when working out a way. */
	@Override
	protected PathNavigation createNavigation(Level level) {
		return new GroundPathNavigation(this, level) {
			@Override
			protected PathFinder createPathFinder(int maxVisitedNodes) {
				nodeEvaluator = new BarrierAwareEvaluator();
				nodeEvaluator.setCanPassDoors(true);
				nodeEvaluator.setCanWalkOverFences(true);
				return new PathFinder(nodeEvaluator, Math.min(maxVisitedNodes, MAX_PATH_NODES));
			}

			/** A block changed next to its way: with no time left in this tick it keeps the way it has instead of losing it. */
			@Override
			public void recomputePath() {
				if (mayPath()) {
					super.recomputePath();
				}
			}

			/**
			 * In the ore search a way far up or down the staircase often finds none: caves that cross the staircase
			 * use up the search. Then it goes by the staircase instead, a stretch of it at a time.
			 */
			@Override
			protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int reachRange, float maxPathLength) {
				// no time left in this tick: no way now, the caller tries again later
				if (!mayPath()) {
					pathDenied = true;
					return null;
				}

				pathDenied = false;
				long start = System.nanoTime();

				try {
					return createPathTimed(targets, regionOffset, offsetUpward, reachRange, maxPathLength, start);
				} finally {
					notePathTime(System.nanoTime() - start);
				}
			}

			private Path createPathTimed(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int reachRange, float maxPathLength, long start) {
				Path path = super.createPath(targets, regionOffset, offsetUpward, reachRange, maxPathLength);

				if (path != null && path.canReach() || targets.isEmpty() || station == null || !(level() instanceof ServerLevel server)) {
					return path;
				}

				int toY = targets.iterator().next().getY();

				// in the quarry: up or down the central staircase, a stretch at a time (also just after mining was switched off)
				if (!oreSearch && lastMine != null && Math.abs(toY - getBlockY()) > 3) {
					for (BlockPos waypoint : lastMine.waypoints(server, blockPosition(), toY)) {
						if (!pathTimeLeft(System.nanoTime() - start)) {
							return path;
						}

						Path part = super.createPath(Set.of(waypoint), regionOffset, false, 1, maxPathLength);

						if (part != null && part.canReach() && part.getNodeCount() > 1) {
							return part;
						}
					}

					return path;
				}

				// also when the ore search was just switched off and it is still down the staircase with its tools
				OreJob ores = oreSearch && miningJob() instanceof OreJob current ? current : lastOres;

				if (ores == null || getY() >= station.getY() && targets.iterator().next().getY() >= station.getY()) {
					return path;
				}

				for (BlockPos waypoint : ores.waypoints(server, blockPosition(), toY)) {
					if (!pathTimeLeft(System.nanoTime() - start)) {
						return path;
					}

					Path part = super.createPath(Set.of(waypoint), regionOffset, false, 1, maxPathLength);

					if (part != null && part.canReach() && part.getNodeCount() > 1) {
						return part;
					}
				}

				return path;
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
		return target.y > getEyeY() && level().noCollision(this, getBoundingBox().expandTowards(0, target.y - getY(), 0))
				&& clearTo(up, target, chest, partner);
	}

	/**
	 * The free spot next to the chest (beside it, or one lower when the chest stands on something) closest to the
	 * robot, from which nothing blocks the way to the chest.
	 */
	private BlockPos openSide(BlockPos chest) {
		BlockPos best = null;
		long now = level().getGameTime();
		ignoredSides.values().removeIf(until -> until < now);

		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			for (int dy = 0; dy >= -1; dy--) {
				BlockPos spot = chest.relative(side).above(dy);

				if (ignoredSides.containsKey(spot)) {
					continue;
				}

				if (!level().getBlockState(spot).getCollisionShape(level(), spot).isEmpty()
						|| !level().getBlockState(spot.above()).getCollisionShape(level(), spot.above()).isEmpty() && dy == 0) {
					continue;
				}

				// something to stand on (not over the staircase of the ore search, which goes down right beside the station)
				BlockPos floor = spot.below();

				if (level().getBlockState(floor).getCollisionShape(level(), floor).isEmpty() && level().getFluidState(spot).isEmpty()) {
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
	/** Nothing solid between its feet (and its head) and that spot, so it can walk there in a straight line. */
	private boolean straightTo(Vec3 spot) {
		for (double dy : new double[] {0.3, getBbHeight() - 0.1}) {
			if (!clearTo(position().add(0, dy, 0), spot.add(0, dy, 0), null, null)) {
				return false;
			}
		}

		return true;
	}

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
				&& (recallPhase == RECALL_WAITING || (getCarried().isEmpty() || delivery == null) && target == null && minePhase == 0);

		if (nearHome()) {
			homeUnreachableUntil = 0;
		}

		boolean sad = recallPhase == RECALL_NONE && (noChest || chestsFull || stationMissing() || level().getGameTime() < homeUnreachableUntil
				|| mining && mineProblem != null);

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

				for (ItemStack stack : belongings()) {
					player.getInventory().add(stack);

					if (!stack.isEmpty()) {
						spawnAtLocation((ServerLevel) level(), stack);
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

		for (ItemStack stack : belongings()) {
			spawnAtLocation(level, stack);
		}
	}

	/** What it carries in its beak, its bag and its tools, taken out of it (picked up or killed). */
	private List<ItemStack> belongings() {
		List<ItemStack> all = new ArrayList<>();

		if (!getCarried().isEmpty()) {
			all.add(getCarried().copy());
			setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}

		all.addAll(bag);
		bag.clear();

		for (int i = 0; i < tools.length; i++) {
			if (!tools[i].isEmpty()) {
				all.add(tools[i]);
				tools[i] = ItemStack.EMPTY;
			}
		}

		if (!torches.isEmpty()) {
			all.add(torches);
			torches = ItemStack.EMPTY;
		}

		return all;
	}

	@Override
	public void remove(RemovalReason reason) {
		release();
		dropMineTarget();

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
		output.store("Tools", ItemStack.OPTIONAL_CODEC.listOf(), List.of(tools));
		output.store("Bag", ItemStack.CODEC.listOf(), List.copyOf(bag));
		output.store("Torches", ItemStack.OPTIONAL_CODEC, torches);
		output.storeNullable("CourierTo", BlockPos.CODEC, courierTo);
		output.putInt("CourierSlot", courierSlot);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		home = input.read("Home", BlockPos.CODEC).orElse(null);
		entityData.set(COLOR, input.getIntOr("Color", DEFAULT_COLOR));
		station = input.read("Station", BlockPos.CODEC).orElse(null);
		range = Math.clamp(input.getIntOr("Range", RANGE), MIN_RANGE, StationCluster.MAX_RANGE);
		applyRange();
		filter.clear();
		filter.addAll(input.read("Filter", ItemStack.CODEC.listOf()).orElse(List.of()));
		excludeFilter = input.getBooleanOr("FilterExclude", false);
		List<ItemStack> savedTools = input.read("Tools", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());

		for (int i = 0; i < tools.length; i++) {
			tools[i] = i < savedTools.size() ? savedTools.get(i) : ItemStack.EMPTY;
		}

		bag.clear();
		bag.addAll(input.read("Bag", ItemStack.CODEC.listOf()).orElse(List.of()));
		torches = input.read("Torches", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
		courierTo = input.read("CourierTo", BlockPos.CODEC).orElse(null);
		courierSlot = input.getIntOr("CourierSlot", -1);
	}
}
