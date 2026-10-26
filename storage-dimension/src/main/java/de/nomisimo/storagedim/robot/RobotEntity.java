package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Walks around its home (up to {@link #RANGE} blocks, about 10 chunks, adjustable), picks up one stack of dropped items with
 * its beak and carries it to the nearest dimension chest, ME terminal or matching filter chest.
 * Right-click with an empty hand changes the range, sneak + right-click with an item adds or removes it from its filter.
 */
public class RobotEntity extends PathfinderMob {
	public static final int RANGE = 160;
	/** Ranges a right-click cycles through, in blocks (1, 2, 4, 6, 8 and 10 chunks). */
	private static final int[] RANGES = {16, 32, 64, 96, 128, RANGE};
	private static final int MAX_FILTERS = 9;
	/** Ticks without getting closer before it gives up on an item or chest for a while. */
	private static final int STUCK_TICKS = 100;
	private static final int IGNORE_TICKS = 1200;
	private static final int VERTICAL_RANGE = 48;
	public static final int DEFAULT_COLOR = 0xF06E1E;
	public static final int ACTION_TICKS = 14;
	/** Item entities some robot is already walking to, so two robots do not chase the same one. */
	private static final Set<UUID> CLAIMED = new HashSet<>();

	private static final EntityDataAccessor<Integer> COLOR = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
	/** 0 = nothing, 1 = picking up, 2 = handing in; counts down from {@link #ACTION_TICKS}. */
	private static final EntityDataAccessor<Byte> ACTION = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Integer> ACTION_TIME = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Byte> SITTING = SynchedEntityData.defineId(RobotEntity.class, EntityDataSerializers.BYTE);

	private UUID owner;
	private BlockPos home;
	private ItemEntity target;
	private BlockPos delivery;
	private int idleTicks;
	private int range = RANGE;
	/** Items it collects; empty = everything. */
	private final List<ItemStack> filter = new ArrayList<>();
	/** Items and chests it could not reach, with the game time until which it leaves them alone. */
	private final Map<UUID, Long> ignoredItems = new HashMap<>();
	private final Map<BlockPos, Long> ignoredChests = new HashMap<>();

	public RobotEntity(EntityType<? extends PathfinderMob> type, Level level) {
		super(type, level);
		setPersistenceRequired();
		setDropChance(EquipmentSlot.MAINHAND, 0);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 10)
				.add(Attributes.MOVEMENT_SPEED, 0.28)
				.add(Attributes.FOLLOW_RANGE, 64)
				.add(Attributes.STEP_HEIGHT, 1.0)
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
	}

	@Override
	protected void registerGoals() {
		goalSelector.addGoal(0, new FloatGoal(this));
		goalSelector.addGoal(1, new WorkGoal());
		goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6) {
			@Override
			public boolean canUse() {
				return !isSitting() && super.canUse();
			}
		});
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

	public ItemStack getCarried() {
		return getItemBySlot(EquipmentSlot.MAINHAND);
	}

	/** The items it collects (empty = everything); changed in game with sneak + right-click. */
	public List<ItemStack> filter() {
		return filter;
	}

	/** For debugging and the client test: what the robot is doing right now. */
	public String describe() {
		return String.format("range=%d filter=%s ", range, filter) + String.format("pos=%.1f/%.1f/%.1f home=%s target=%s delivery=%s carried=%s action=%d sitting=%s pathDone=%s goals=%s",
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

	private ItemEntity findItem() {
		BlockPos h = home();
		AABB area = new AABB(h).inflate(range, VERTICAL_RANGE, range);
		long now = level().getGameTime();
		ignoredItems.values().removeIf(until -> until < now);
		List<ItemEntity> items = level().getEntitiesOfClass(ItemEntity.class, area,
				item -> item.isAlive() && !item.hasPickUpDelay() && !CLAIMED.contains(item.getUUID()) && !ignoredItems.containsKey(item.getUUID())
						&& inRange(item.position()) && (filter.isEmpty() || FilterChests.matches(filter, item.getItem()))
						&& findDelivery(item.getItem()) != null);
		ItemEntity best = null;

		for (ItemEntity item : items) {
			if (best == null || distanceToSqr(item) < distanceToSqr(best)) {
				best = item;
			}
		}

		return best;
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
		if (target != null) {
			CLAIMED.remove(target.getUUID());
			target = null;
		}
	}

	/** Goes for items, carries them home, or walks back when it strayed too far. */
	private class WorkGoal extends Goal {
		private int cooldown;
		private int repath;
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

			cooldown = 40;
			return hasWork();
		}

		@Override
		public boolean canContinueToUse() {
			return getAction() != 0 || hasWork();
		}

		private boolean hasWork() {
			if (!getCarried().isEmpty()) {
				if (delivery == null) {
					delivery = findDelivery(getCarried());
				}

				return delivery != null;
			}

			if (target == null || !target.isAlive()) {
				release();
				target = findItem();

				if (target != null) {
					CLAIMED.add(target.getUUID());
				}
			}

			return target != null || !inRange(position());
		}

		@Override
		public void start() {
			setSitting(false);
			idleTicks = 0;
			repath = 0;
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
				delivery = findDelivery(getCarried());
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

			if (!getCarried().isEmpty() && delivery != null) {
				goal = Vec3.atCenterOf(delivery);
				reach = 2.2;
				goalKey = delivery;
			} else if (target != null) {
				goal = target.position();
				reach = 1.8;
				goalKey = target;
			} else {
				goal = Vec3.atBottomCenterOf(home());
				reach = 3;
				goalKey = home();
			}

			getLookControl().setLookAt(goal.x, goal.y, goal.z);
			double distance = position().distanceTo(goal);

			if (distance <= reach || goalKey == delivery && canReachChest(goal)) {
				if (!getCarried().isEmpty() && delivery != null) {
					startAction(2);
				} else if (target != null) {
					startAction(1);
				} else {
					getNavigation().stop();
				}

				return;
			}

			if (gaveUp(goalKey, distance)) {
				return;
			}

			// the path ends on the block next to the goal; walk the last bit straight
			if (getNavigation().isDone() && position().distanceTo(goal) < reach + 2) {
				getMoveControl().setWantedPosition(goal.x, goal.y, goal.z, 1.0);
				return;
			}

			if (--repath <= 0 || getNavigation().isDone()) {
				repath = 30;
				getNavigation().moveTo(goal.x, goal.y, goal.z, 1.0);
			}
		}
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
		super.aiStep();

		if (level().isClientSide()) {
			return;
		}

		// Sits down after a while without anything to do, like the real one.
		if (getAction() == 0 && getNavigation().isDone() && getCarried().isEmpty() && target == null) {
			if (++idleTicks > 200 && !isSitting()) {
				setSitting(true);
			}
		} else {
			idleTicks = 0;

			if (isSitting()) {
				setSitting(false);
			}
		}
	}

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		var id = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();

		if (id.endsWith("_dye") && !player.isSecondaryUseActive()) {
			DyeColor color = DyeColor.byName(id.substring(0, id.length() - 4), null);

			if (color != null) {
				if (!level().isClientSide()) {
					entityData.set(COLOR, color.getTextureDiffuseColor() & 0xFFFFFF);

					if (!player.isCreative()) {
						held.shrink(1);
					}
				}

				return InteractionResult.SUCCESS;
			}
		}

		if (player.isSecondaryUseActive() && held.isEmpty()) {
			if (!level().isClientSide()) {
				ItemStack item = new ItemStack(Robots.ROBOT_ITEM);
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

		if (hand == InteractionHand.MAIN_HAND && player.isSecondaryUseActive() && !held.isEmpty()) {
			if (player instanceof ServerPlayer serverPlayer) {
				toggleFilter(serverPlayer, held);
			}

			return InteractionResult.SUCCESS;
		}

		if (hand == InteractionHand.MAIN_HAND && held.isEmpty()) {
			if (player instanceof ServerPlayer serverPlayer) {
				int next = 0;

				while (next < RANGES.length && RANGES[next] <= range) {
					next++;
				}

				range = RANGES[next % RANGES.length];
				release();
				serverPlayer.sendSystemMessage(Component.literal("Reichweite: " + range / 16 + (range == 16 ? " Chunk" : " Chunks")
						+ " (" + range + " Blöcke)").withStyle(ChatFormatting.GOLD));
			}

			return InteractionResult.SUCCESS;
		}

		return super.mobInteract(player, hand);
	}

	private void toggleFilter(ServerPlayer player, ItemStack held) {
		boolean removed = filter.removeIf(stack -> ItemStack.isSameItem(stack, held));

		if (!removed) {
			if (filter.size() >= MAX_FILTERS) {
				player.sendSystemMessage(Component.literal("Der Filter ist voll (" + MAX_FILTERS + " Items).").withStyle(ChatFormatting.RED));
				return;
			}

			filter.add(held.copyWithCount(1));
		}

		release();
		MutableComponent names = Component.empty();

		if (filter.isEmpty()) {
			names.append(Component.literal("alles").withStyle(ChatFormatting.GRAY));
		}

		for (int i = 0; i < filter.size(); i++) {
			names.append(i == 0 ? Component.empty() : Component.literal(", ")).append(filter.get(i).getHoverName());
		}

		player.sendSystemMessage(Component.literal(removed ? "Aus dem Filter genommen: " : "Zum Filter hinzugefügt: ")
				.append(held.getHoverName()).withStyle(ChatFormatting.GOLD));
		player.sendSystemMessage(Component.literal("Sammelt: ").withStyle(ChatFormatting.GOLD).append(names));
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
		spawnAtLocation(level, new ItemStack(Robots.ROBOT_ITEM));

		if (!getCarried().isEmpty()) {
			spawnAtLocation(level, getCarried().copy());
		}
	}

	@Override
	public void remove(RemovalReason reason) {
		release();
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
		output.store("Filter", ItemStack.CODEC.listOf(), List.copyOf(filter));
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		home = input.read("Home", BlockPos.CODEC).orElse(null);
		entityData.set(COLOR, input.getIntOr("Color", DEFAULT_COLOR));
		range = Math.clamp(input.getIntOr("Range", RANGE), RANGES[0], RANGE);
		filter.clear();
		filter.addAll(input.read("Filter", ItemStack.CODEC.listOf()).orElse(List.of()));
	}
}
