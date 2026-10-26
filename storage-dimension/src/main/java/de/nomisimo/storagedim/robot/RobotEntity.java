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

import de.nomisimo.storagedim.storage.FilterChests;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import de.nomisimo.storagedim.storage.StorageMenu;
import de.nomisimo.storagedim.storage.StorageNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
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
import net.minecraft.world.level.block.Blocks;
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
	private static final double SWIM_PUSH = 0.03;
	private static final double SWIM_SPEED = 0.3;
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
	/** Called back: teleports to the station after this many ticks without getting closer, then waits there this long. */
	private static final int RECALL_STUCK_TICKS = 200;
	private static final int RECALL_WAIT_TICKS = 1200;
	/** A robot loaded later than this after the button press does not follow the recall any more. */
	private static final int RECALL_VALID_TICKS = 2400;
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
	public static final int MOOD_NONE = 0;
	public static final int MOOD_HAPPY = 1;
	public static final int MOOD_SAD = 2;
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
	/** No dimension chest, terminal or filter chest in its range. */
	private boolean noChest;
	private String stationName = "";
	private long recallSeen;
	private int recallPhase = RECALL_NONE;
	private long recallUntil;
	private double recallBest;
	private int recallStuck;

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

		if (!name.equals(getCustomName() == null ? "" : getCustomName().getString())) {
			setCustomName(name.isEmpty() ? null : Component.literal(name));
			setCustomNameVisible(!name.isEmpty());
		}

		stationName = config.stationName();
	}

	/** Follows the settings of its station, which can be changed there at any time, and takes its name from the station's list. */
	private void syncWithStation() {
		StorageData data = StorageData.get();

		if (data == null || station == null) {
			return;
		}

		String dim = Rooms.id(level());
		RobotConfig config = data.station(dim, station);
		String name = data.stationRobots(dim, station).get(getUUID());

		if (name == null) {
			name = StationRobots.register(dim, station, getUUID(), config.name());
		}

		applySettings(config, name);
		Long recall = RECALLS.get(dim + "@" + station.asLong());

		if (recall != null && recall > recallSeen && level().getGameTime() - recall < RECALL_VALID_TICKS) {
			recallSeen = recall;
			startRecall();
		}
	}

	/** The recall button of a station: all its robots come back, then wait there for a minute. */
	public static void recall(String dim, BlockPos station, long gameTime) {
		RECALLS.put(dim + "@" + station.asLong(), gameTime);
	}

	private void startRecall() {
		release();
		getNavigation().stop();
		homeUnreachableUntil = 0;
		recallPhase = RECALL_RETURNING;
		recallBest = Double.MAX_VALUE;
		recallStuck = 0;
	}

	/** Called back: on the way to the station, or waiting there; stuck for 10 seconds, it teleports to the station. */
	private void tickRecall() {
		long now = level().getGameTime();

		if (recallPhase == RECALL_RETURNING) {
			double distance = position().distanceTo(Vec3.atBottomCenterOf(home()));

			if (nearHome()) {
				recallPhase = RECALL_WAITING;
				recallUntil = now + RECALL_WAIT_TICKS;
				getNavigation().stop();
			} else if (distance < recallBest - 0.5) {
				recallBest = distance;
				recallStuck = 0;
			} else if (++recallStuck >= RECALL_STUCK_TICKS) {
				teleportHome();
			}
		} else if (recallPhase == RECALL_WAITING && now >= recallUntil) {
			recallPhase = RECALL_NONE;
		}
	}

	private void teleportHome() {
		BlockPos h = home();
		BlockPos spot = h.above();

		search:
		for (int r = 1; r <= 2; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					for (int dy = -1; dy <= 1; dy++) {
						BlockPos p = h.offset(dx, dy, dz);

						if (free(p) && free(p.above()) && !free(p.below())) {
							spot = p;
							break search;
						}
					}
				}
			}
		}

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

	/** Its current settings, for the item it turns into when picked up: those of its station. */
	public RobotConfig config() {
		StorageData data = StorageData.get();

		if (station != null && data != null) {
			return data.station(Rooms.id(level()), station);
		}

		return new RobotConfig(range, getColor(), getCustomName() == null ? "" : getCustomName().getString(), List.copyOf(filter),
				Optional.empty(), Optional.empty(), excludeFilter, "");
	}

	public BlockPos station() {
		return station;
	}

	/** What it is doing, for its station's list and the look-at tooltip. */
	public Component status() {
		if (recallPhase == RECALL_RETURNING) {
			return Component.literal("Kommt zur Station zurück").withStyle(ChatFormatting.AQUA);
		}

		if (recallPhase == RECALL_WAITING) {
			long seconds = Math.max(0, (recallUntil - level().getGameTime()) / 20);
			return Component.literal("Wartet nach dem Rückruf (" + seconds + " s)").withStyle(ChatFormatting.AQUA);
		}

		if (stationMissing()) {
			return Component.literal("Traurig: Station fehlt").withStyle(ChatFormatting.RED);
		}

		if (noChest) {
			return Component.literal("Traurig: keine Kiste in Reichweite").withStyle(ChatFormatting.RED);
		}

		if (level().getGameTime() < homeUnreachableUntil) {
			return Component.literal("Traurig: kommt nicht zur Station").withStyle(ChatFormatting.RED);
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

	/** Whether any chest it could deliver to exists in its range (ignoring the filter of filter chests). */
	private boolean hasAnyChest() {
		StorageData data = StorageData.get();

		if (data == null) {
			return false;
		}

		String dim = Rooms.id(level());

		for (long packed : data.chests.getOrDefault(dim, Map.of()).keySet()) {
			if (inRange(Vec3.atCenterOf(BlockPos.of(packed)))) {
				return true;
			}
		}

		for (long packed : data.filterChests.getOrDefault(dim, Map.of()).keySet()) {
			if (inRange(Vec3.atCenterOf(BlockPos.of(packed)))) {
				return true;
			}
		}

		if (owner != null) {
			for (long packed : data.terminals.getOrDefault(dim, Set.of())) {
				if (inRange(Vec3.atCenterOf(BlockPos.of(packed)))) {
					return true;
				}
			}
		}

		return false;
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
		return String.format("range=%d filter=%s%s station=%s mood=%d noChest=%s ", range, excludeFilter ? "except " : "", filter, station, getMood(), noChest) + String.format("pos=%.1f/%.1f/%.1f home=%s target=%s delivery=%s carried=%s action=%d sitting=%s pathDone=%s goals=%s",
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
						&& inRange(item.position()) && allows(item.getItem())
						&& findDelivery(item.getItem()) != null));
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

		for (int i = 0; i < PATH_CHECKS; i++) {
			BlockPos chest = findDelivery(getCarried());

			if (chest == null) {
				return null;
			}

			Path path = getNavigation().createPath(chest, 3);

			if (path != null && path.canReach()) {
				plannedPath = path;
				return chest;
			}

			ignoredChests.put(chest, now + IGNORE_TICKS);
		}

		return null;
	}

	/** The closest chest in range that takes this stack, or null. */
	private BlockPos findDelivery(ItemStack stack) {
		StorageData data = StorageData.get();

		if (data == null || stack.isEmpty()) {
			return null;
		}

		String dim = Rooms.id(level());
		BlockPos best = null;
		long now = level().getGameTime();
		ignoredChests.values().removeIf(until -> until < now);
		Map<Long, UUID> chests = data.chests.get(dim);

		if (chests != null) {
			for (long packed : chests.keySet()) {
				best = closer(best, BlockPos.of(packed));
			}
		}

		Set<Long> terminals = data.terminals.get(dim);

		if (owner != null && terminals != null) {
			for (long packed : terminals) {
				best = closer(best, BlockPos.of(packed));
			}
		}

		Map<Long, FilterChests.FilterChest> filters = data.filterChests.get(dim);

		if (filters != null) {
			for (Map.Entry<Long, FilterChests.FilterChest> entry : filters.entrySet()) {
				if (FilterChests.matches(entry.getValue().filters(), stack)) {
					best = closer(best, BlockPos.of(entry.getKey()));
				}
			}
		}

		return best;
	}

	private BlockPos closer(BlockPos best, BlockPos pos) {
		if (!inRange(Vec3.atCenterOf(pos)) || ignoredChests.containsKey(pos)) {
			return best;
		}

		return best == null || distanceToSqr(Vec3.atCenterOf(pos)) < distanceToSqr(Vec3.atCenterOf(best)) ? pos : best;
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
		StorageNetwork network = delivery == null ? null : networkAt(delivery, carried);

		if (network != null && !carried.isEmpty()) {
			network.insert(carried.copy());
			StorageMenu.refreshAll(network);
			setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
			playSound(SoundEvents.CHEST_CLOSE, 0.3F, 1.6F);
		}

		delivery = null;
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

		if (target != null) {
			CLAIMED.remove(target.getUUID());
			target = null;
		}
	}

	/** Goes for items, carries them home, or walks back when it strayed too far. */
	private class WorkGoal extends Goal {
		private int cooldown;
		private Object lastGoal;
		private double bestDistance;
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

				return delivery != null;
			}

			if (stationMissing()) {
				release();
				return false;
			}

			if ((target == null || !target.isAlive()) && level().getGameTime() >= nextSearch) {
				nextSearch = level().getGameTime() + SEARCH_TICKS;
				release();
				noChest = !hasAnyChest();
				target = noChest ? null : findItem();

				if (target != null) {
					CLAIMED.add(target.getUUID());
				}
			}

			// nothing to collect: back to the station (or where it was placed) to wait there
			return target != null || !nearHome() && level().getGameTime() >= homeUnreachableUntil;
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
				if (distance > HOME_RADIUS + 3) {
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
				reach = 2.2;
				goalKey = delivery;
			} else if (target != null) {
				goal = target.position();
				reach = 1.8;
				goalKey = target;
			} else {
				goal = Vec3.atBottomCenterOf(home());
				reach = HOME_RADIUS;
				goalKey = home();
			}

			getLookControl().setLookAt(goal.x, goal.y, goal.z);
			double distance = position().distanceTo(goal);
			boolean arrived = goalKey == target ? standsOn(target)
					: goalKey == delivery ? distance <= reach || canReachChest(goal) : distance <= reach;

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

			// called back, it keeps trying (and teleports when stuck) instead of giving up
			if (recallPhase != RECALL_RETURNING && gaveUp(goalKey, distance)) {
				return;
			}

			// the path ends on the block next to the goal; walk the last bit straight
			if (getNavigation().isDone() && position().distanceTo(goal) < reach + 2) {
				getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
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
				} else if (!path.canReach() && recallPhase != RECALL_RETURNING) {
					// cannot get back to its station: sits down sadly and tries again later
					homeUnreachableUntil = level().getGameTime() + RETRY_HOME_TICKS;
					getNavigation().stop();
				} else {
					getNavigation().moveTo(path, 1.0);
				}
			} else if (getNavigation().isDone()) {
				getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0);
			}
		}
	}

	/** Right at the item, so it picks it up from under its beak: nothing in between, about half a block away at most. */
	private boolean standsOn(ItemEntity item) {
		double dx = item.getX() - getX();
		double dz = item.getZ() - getZ();
		double dy = item.getY() - getY();
		return dx * dx + dz * dz <= 0.5 * 0.5 && dy > -1 && dy < 1 && hasLineOfSight(item);
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
	private boolean canReachChest(Vec3 chest) {
		double dx = chest.x - getX();
		double dz = chest.z - getZ();
		double dy = chest.y - getY();
		return dx * dx + dz * dz <= 2.5 * 2.5 && dy > -2 && dy < 4.5;
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

		// a boat or minecart the robot walked into would hold it forever: it climbs straight out again
		if (isPassenger()) {
			stopRiding();
		}

		// Sits down when there is nothing to do: happily looking around at its station, sadly staring at the ground
		// when it cannot work (no chest in range, station broken or out of reach).
		boolean idle = getAction() == 0 && getNavigation().isDone()
				&& (recallPhase == RECALL_WAITING || getCarried().isEmpty() && target == null);

		if (nearHome()) {
			homeUnreachableUntil = 0;
		}

		boolean sad = recallPhase == RECALL_NONE && (noChest || stationMissing() || level().getGameTime() < homeUnreachableUntil);

		if (idle && (sad || nearHome())) {
			if (++idleTicks > (sad ? 20 : 40)) {
				setSitting(true);
				setMood(sad ? MOOD_SAD : MOOD_HAPPY);
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
		if (!isInWater() || !getMoveControl().hasWanted()) {
			return;
		}

		double dy = getMoveControl().getWantedY() - getY();

		if (dy > 0.4 || horizontalCollision) {
			setDeltaMovement(getDeltaMovement().add(0, 0.05, 0));
		}

		double dx = getMoveControl().getWantedX() - getX();
		double dz = getMoveControl().getWantedZ() - getZ();
		double length = Math.sqrt(dx * dx + dz * dz);

		if (length < 0.1) {
			return;
		}

		Vec3 motion = getDeltaMovement().add(dx / length * SWIM_PUSH, 0, dz / length * SWIM_PUSH);
		double speed = motion.horizontalDistance();

		if (speed > SWIM_SPEED) {
			motion = new Vec3(motion.x / speed * SWIM_SPEED, motion.y, motion.z / speed * SWIM_SPEED);
		}

		setDeltaMovement(motion);
	}

	/** A little trail of splashes behind it while it swims (client side). */
	private void splash() {
		double dx = getX() - xo;
		double dz = getZ() - zo;

		if (!isInWater() || dx * dx + dz * dz < 0.002) {
			return;
		}

		// under water only a trail of bubbles
		if (isUnderWater()) {
			level().addParticle(ParticleTypes.BUBBLE, getX() - dx * 2, getY() + getBbHeight() * 0.5, getZ() - dz * 2, 0, 0.05, 0);
			return;
		}

		for (int i = 0; i < 3; i++) {
			level().addParticle(ParticleTypes.SPLASH, getX() - dx * 2 + (random.nextDouble() - 0.5) * 0.4, getY() + getBbHeight() * 0.6,
					getZ() - dz * 2 + (random.nextDouble() - 0.5) * 0.4, -dx, 0.1, -dz);
		}

		if (random.nextInt(3) == 0) {
			level().addParticle(ParticleTypes.BUBBLE, getX() - dx * 3, getY() + 0.1, getZ() - dz * 3, 0, 0.05, 0);
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
