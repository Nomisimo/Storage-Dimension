package de.nomisimo.storagedim.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;

/**
 * Two things the library only has while the matching items are in ME storage:
 * <ul>
 * <li>The dragon egg altar in the middle of the hall, with the egg floating over it.</li>
 * <li>The trading hall behind the south wall of the hall: every villager stored in a bucket (any mod that keeps the
 * villager's data on the item, e.g. "Villager In A Bucket") stands at its own stall, with a wooden sign naming its
 * most valuable trade. The villagers can be traded with; they keep their state as long as their bucket stays in ME
 * storage and restock now and then.</li>
 * </ul>
 */
public final class LibraryExtras {
	/** Tag on the villagers of the trading hall. */
	public static final String TRADER_TAG = "storagedim_trader";
	/** Most villagers the trading hall shows. */
	public static final int MAX_TRADERS = 64;

	/** The altar, in the middle of the hall between the entrance and the aisles. */
	static final int ALTAR_DX = 0;
	static final int ALTAR_DZ = 2;
	/** Middle of the trading hall and of its door in the south wall of the hall (odd, so no project chest is in the way). */
	static final int TRADE_X = 1;
	/** First row of the trading hall, right behind the south wall of the hall. */
	private static final int TRADE_Z = 10;
	private static final int HALF_WIDTH = 5;
	private static final int CEILING = 6;
	/** How often the villagers restock, in ticks. */
	private static final long RESTOCK_EVERY = 12_000;

	/** One villager of the trading hall: its entity id, its saved data and its stall. */
	record Trader(UUID id, CompoundTag data, String profession, int stall) {
	}

	/** What the library should show right now. */
	record State(boolean egg, List<Trader> traders) {
		int rows() {
			return (traders.size() + 1) / 2;
		}
	}

	private LibraryExtras() {
	}

	/**
	 * The villagers of the trading hall can be traded with, but not hit, caught in a bucket, leashed or renamed: that
	 * would copy a villager whose bucket is still in ME storage. Runs before other mods' handlers.
	 */
	public static void register() {
		Identifier early = StorageDimensionMod.id("trading_hall");
		UseEntityCallback.EVENT.addPhaseOrdering(early, Event.DEFAULT_PHASE);
		UseEntityCallback.EVENT.register(early, (player, level, hand, entity, hit) -> {
			if (!entity.entityTags().contains(TRADER_TAG)) {
				return InteractionResult.PASS;
			}

			ItemStack held = player.getItemInHand(hand);
			String path = BuiltInRegistries.ITEM.getKey(held.getItem()).getPath();
			return held.isEmpty() || held.is(Items.EMERALD) || !path.contains("bucket") && !held.is(Items.NAME_TAG) && !held.is(Items.LEAD)
					&& !path.contains("spawn_egg") ? InteractionResult.PASS : InteractionResult.FAIL;
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> entity.entityTags().contains(TRADER_TAG) ? InteractionResult.FAIL
				: InteractionResult.PASS);
	}

	// ---- what is stored ----

	static State state(ServerLevel level, StorageNetwork network) {
		boolean egg = false;
		List<Trader> traders = new ArrayList<>();

		for (StorageNetwork.StoredItem entry : network.entries()) {
			long available = entry.count <= 0 ? 0 : Projects.available(network, entry, null);

			if (available <= 0) {
				continue;
			}

			if (entry.prototype.is(Items.DRAGON_EGG)) {
				egg = true;
				continue;
			}

			CompoundTag data = villagerData(level, entry.prototype);

			if (data == null) {
				continue;
			}

			String profession = data.getCompound("VillagerData").flatMap(d -> d.getString("profession")).orElse("minecraft:none");
			String key = data.toString();

			for (long i = 0; i < available && traders.size() < MAX_TRADERS * 2; i++) {
				UUID id = UUID.nameUUIDFromBytes(("storagedim:trader:" + network.room + ":" + i + ":" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
				traders.add(new Trader(id, data, profession, 0));
			}
		}

		// jobless and nitwits at the end, the others grouped by profession
		traders.sort(Comparator.comparing((Trader t) -> t.profession().endsWith(":none") || t.profession().endsWith(":nitwit"))
				.thenComparing(Trader::profession).thenComparing(t -> t.id().toString()));
		List<Trader> placed = new ArrayList<>();

		for (int i = 0; i < Math.min(MAX_TRADERS, traders.size()); i++) {
			Trader t = traders.get(i);
			placed.add(new Trader(t.id(), t.data(), t.profession(), i));
		}

		return new State(egg, placed);
	}

	/**
	 * The saved villager inside an item, or null. Bucket mods keep it in different components, so the whole item is
	 * searched for the villager's data; only items whose id says "villager" or "bucket" are looked at.
	 */
	static CompoundTag villagerData(ServerLevel level, ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();

		if (!path.contains("villager") && !path.contains("bucket") || stack.getComponentsPatch().isEmpty()) {
			return null;
		}

		try {
			Tag tag = ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack.copyWithCount(1))
					.result().orElse(null);
			return find(tag, 0);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static CompoundTag find(Tag tag, int depth) {
		if (tag == null || depth > 8) {
			return null;
		}

		if (tag instanceof CompoundTag compound) {
			if (compound.contains("VillagerData") && !compound.contains("ConversionTime")) {
				String id = compound.getString("id").orElse("minecraft:villager");
				return id.equals("minecraft:villager") || id.equals("villager") ? compound : null;
			}

			for (String key : compound.keySet()) {
				CompoundTag found = find(compound.get(key), depth + 1);

				if (found != null) {
					return found;
				}
			}
		} else if (tag instanceof ListTag list) {
			for (Tag element : list) {
				CompoundTag found = find(element, depth + 1);

				if (found != null) {
					return found;
				}
			}
		}

		return null;
	}

	// ---- geometry ----

	private static int tradeEnd(int rows) {
		return TRADE_Z + 2 * rows + 1;
	}

	/** The southernmost z of the library, relative to its centre, for the given network. */
	static int southEnd(StorageNetwork network) {
		return network.tradeHallRows > 0 ? tradeEnd(network.tradeHallRows) : 0;
	}

	private static int side(int stall) {
		return stall % 2 == 0 ? -1 : 1;
	}

	private static int stallZ(int stall) {
		return TRADE_Z + 1 + 2 * (stall / 2);
	}

	private static BlockPos signPos(int stall) {
		return new BlockPos(TRADE_X + side(stall), 1, stallZ(stall));
	}

	// ---- blocks ----

	/**
	 * Adds the altar and the trading hall to the plan of the library. Where a bigger trading hall stood before,
	 * everything becomes air.
	 */
	static void plan(Map<BlockPos, BlockState> plan, StorageNetwork network, State state) {
		int rows = state.rows();

		if (state.egg()) {
			plan.put(new BlockPos(ALTAR_DX, 1, ALTAR_DZ), Blocks.CRYING_OBSIDIAN.defaultBlockState());

			for (int dx = -1; dx <= 1; dx += 2) {
				for (int dz = -1; dz <= 1; dz += 2) {
					plan.put(new BlockPos(ALTAR_DX + dx, 1, ALTAR_DZ + dz), Blocks.END_ROD.defaultBlockState());
				}
			}

			plan.put(new BlockPos(ALTAR_DX, 1, ALTAR_DZ - 1), Blocks.PURPUR_STAIRS.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
			plan.put(new BlockPos(ALTAR_DX, 1, ALTAR_DZ + 1), Blocks.PURPUR_STAIRS.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
			plan.put(new BlockPos(ALTAR_DX - 1, 1, ALTAR_DZ), Blocks.PURPUR_STAIRS.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
			plan.put(new BlockPos(ALTAR_DX + 1, 1, ALTAR_DZ), Blocks.PURPUR_STAIRS.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		}

		// clear what an earlier, bigger trading hall left
		int built = Math.max(rows, network.tradeHallRows);

		if (built > 0) {
			for (int dz = TRADE_Z; dz <= tradeEnd(built); dz++) {
				for (int dx = TRADE_X - HALF_WIDTH; dx <= TRADE_X + HALF_WIDTH; dx++) {
					for (int dy = 0; dy <= CEILING; dy++) {
						plan.put(new BlockPos(dx, dy, dz), Blocks.AIR.defaultBlockState());
					}
				}
			}
		}

		if (rows == 0) {
			return;
		}

		BlockState floor = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState ceiling = Blocks.DARK_OAK_PLANKS.defaultBlockState();
		BlockState wall = Blocks.SPRUCE_PLANKS.defaultBlockState();
		BlockState pillar = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
		BlockState fence = Blocks.SPRUCE_FENCE.defaultBlockState();
		BlockState counter = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
		BlockState lantern = Blocks.LANTERN.defaultBlockState();
		BlockState hanging = Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true);
		BlockState carpet = Blocks.GREEN_CARPET.defaultBlockState();
		int end = tradeEnd(rows);

		// the door in the south wall of the hall
		plan.put(new BlockPos(TRADE_X, 1, TRADE_Z - 1), Blocks.AIR.defaultBlockState());
		plan.put(new BlockPos(TRADE_X, 2, TRADE_Z - 1), Blocks.AIR.defaultBlockState());

		for (int dz = TRADE_Z; dz <= end; dz++) {
			boolean divider = (dz - TRADE_Z) % 2 == 0;

			for (int dx = TRADE_X - HALF_WIDTH; dx <= TRADE_X + HALF_WIDTH; dx++) {
				int off = Math.abs(dx - TRADE_X);
				plan.put(new BlockPos(dx, 0, dz), floor);
				plan.put(new BlockPos(dx, CEILING, dz), ceiling);

				if (off == HALF_WIDTH || dz == end) {
					for (int dy = 1; dy < CEILING; dy++) {
						plan.put(new BlockPos(dx, dy, dz), divider && dz != end ? pillar : wall);
					}

					continue;
				}

				if (divider && off >= 2) {
					plan.put(new BlockPos(dx, 1, dz), fence);

					if (off == 2) {
						plan.put(new BlockPos(dx, 2, dz), lantern);
					}
				}
			}

			if (dz < end) {
				plan.put(new BlockPos(TRADE_X, 1, dz), carpet);

				if (divider) {
					plan.put(new BlockPos(TRADE_X, CEILING - 1, dz), hanging);
				}
			}
		}

		// the stalls: a counter, behind it the villager in front of its workstation, the sign on the counter
		for (int stall = 0; stall < rows * 2; stall++) {
			int side = side(stall);
			int dz = stallZ(stall);
			plan.put(new BlockPos(TRADE_X + side * 2, 1, dz), counter);
		}

		for (Trader trader : state.traders()) {
			int side = side(trader.stall());
			int dz = stallZ(trader.stall());
			Direction towardsCorridor = side < 0 ? Direction.EAST : Direction.WEST;
			plan.put(new BlockPos(TRADE_X + side * 4, 1, dz), workstation(trader.profession(), towardsCorridor));
			plan.put(signPos(trader.stall()), Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, towardsCorridor));
		}
	}

	private static BlockState workstation(String profession, Direction facing) {
		String p = profession.substring(profession.indexOf(':') + 1);
		Block block = switch (p) {
			case "armorer" -> Blocks.BLAST_FURNACE;
			case "butcher" -> Blocks.SMOKER;
			case "cartographer" -> Blocks.CARTOGRAPHY_TABLE;
			case "cleric" -> Blocks.BREWING_STAND;
			case "farmer" -> Blocks.COMPOSTER;
			case "fisherman" -> Blocks.BARREL;
			case "fletcher" -> Blocks.FLETCHING_TABLE;
			case "leatherworker" -> Blocks.CAULDRON;
			// not the lectern: lecterns in the library open the catalogue
			case "librarian" -> Blocks.CHISELED_BOOKSHELF;
			case "mason" -> Blocks.STONECUTTER;
			case "shepherd" -> Blocks.LOOM;
			case "toolsmith" -> Blocks.SMITHING_TABLE;
			case "weaponsmith" -> Blocks.GRINDSTONE;
			default -> Blocks.HAY_BLOCK;
		};
		BlockState state = block.defaultBlockState();

		if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
		}

		if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
			state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
		}

		if (state.hasProperty(BlockStateProperties.FACING)) {
			state = state.setValue(BlockStateProperties.FACING, facing);
		}

		return state;
	}

	/** True while the blocks in the world match the state: the altar is there exactly when the egg is, the hall has the right size. */
	static boolean isBuilt(ServerLevel level, StorageNetwork network, State state) {
		BlockPos centre = Library.centre(network.room);
		boolean altar = level.getBlockState(centre.offset(ALTAR_DX, 1, ALTAR_DZ)).is(Blocks.CRYING_OBSIDIAN);
		return altar == state.egg() && network.tradeHallRows == state.rows();
	}

	// ---- entities ----

	static UUID eggId(StorageNetwork network) {
		return Library.id("altar:" + network.room);
	}

	static UUID doorId(StorageNetwork network) {
		return Library.id("tradehall:" + network.room);
	}

	/** Spawns, moves and removes the egg, the villagers and the label over the door, and writes the signs. */
	static void sync(ServerLevel level, StorageNetwork network, State state) {
		BlockPos centre = Library.centre(network.room);

		Entity egg = level.getEntity(eggId(network));

		if (state.egg()) {
			egg = Library.spawnDisplay(level, eggId(network), new ItemStack(Items.DRAGON_EGG), centre.getX() + ALTAR_DX + 0.5, centre.getY() + 2.45,
					centre.getZ() + ALTAR_DZ + 0.5, 0.8f);
			label(egg, Component.literal("Drachenei").withStyle(ChatFormatting.LIGHT_PURPLE));
		} else if (egg != null) {
			egg.discard();
		}

		Entity door = level.getEntity(doorId(network));

		if (!state.traders().isEmpty()) {
			door = Library.spawnDisplay(level, doorId(network), new ItemStack(Items.EMERALD), centre.getX() + TRADE_X + 0.5, centre.getY() + 3.4,
					centre.getZ() + TRADE_Z - 1.5, 0.5f);
			int n = state.traders().size();
			label(door, Component.literal("Handelshalle · " + n + " Dorfbewohner").withStyle(ChatFormatting.GREEN));
		} else if (door != null) {
			door.discard();
		}

		Set<UUID> wanted = new HashSet<>();
		boolean restock = level.getGameTime() % RESTOCK_EVERY < 20;

		for (Trader trader : state.traders()) {
			wanted.add(trader.id());
			int side = side(trader.stall());
			double x = centre.getX() + TRADE_X + side * 3 + 0.5;
			double y = centre.getY() + 1;
			double z = centre.getZ() + stallZ(trader.stall()) + 0.5;
			// face the corridor: east is -90, west is 90
			float yaw = side < 0 ? -90f : 90f;
			Entity entity = level.getEntity(trader.id());

			if (entity == null) {
				entity = spawnVillager(level, trader, x, y, z, yaw);
			} else if (entity.distanceToSqr(x, y, z) > 0.25) {
				entity.teleportTo(x, y, z);
				turn(entity, yaw);
			}

			if (entity instanceof net.minecraft.world.entity.npc.villager.AbstractVillager villager) {
				if (restock && villager instanceof net.minecraft.world.entity.npc.villager.Villager v) {
					v.restock();
				}

				writeSign(level, centre.offset(signPos(trader.stall())), villager);
			}
		}

		BlockPos min = centre.offset(TRADE_X - HALF_WIDTH - 2, -4, TRADE_Z - 2);
		BlockPos max = centre.offset(TRADE_X + HALF_WIDTH + 2, CEILING + 4, tradeEnd(MAX_TRADERS / 2) + 4);

		for (Entity entity : level.getEntities((Entity) null, new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1),
				e -> e.entityTags().contains(TRADER_TAG))) {
			if (!wanted.contains(entity.getUUID())) {
				entity.discard();
			}
		}
	}

	private static void label(Entity entity, Component name) {
		if (entity == null) {
			return;
		}

		if (entity.getCustomName() == null || !entity.getCustomName().getString().equals(name.getString())) {
			entity.setCustomName(name);
			entity.setCustomNameVisible(true);
		}
	}

	private static void turn(Entity entity, float yaw) {
		entity.setYRot(yaw);
		entity.setYHeadRot(yaw);
		entity.setYBodyRot(yaw);
		entity.setXRot(0);
	}

	private static Entity spawnVillager(ServerLevel level, Trader trader, double x, double y, double z, float yaw) {
		net.minecraft.world.entity.npc.villager.Villager villager = EntityType.VILLAGER.create(level, EntitySpawnReason.LOAD);

		if (villager == null) {
			return null;
		}

		CompoundTag data = trader.data().copy();

		for (String key : List.of("Pos", "Motion", "Rotation", "UUID", "Passengers", "leash", "Leash", "Brain", "sleeping_pos", "SleepingX",
				"SleepingY", "SleepingZ", "Tags", "NoAI", "Invulnerable")) {
			data.remove(key);
		}

		try {
			villager.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
		} catch (RuntimeException e) {
			StorageDimensionMod.LOGGER.warn("Dorfbewohner aus dem Eimer konnte nicht geladen werden", e);
		}

		villager.setUUID(trader.id());
		villager.setPos(x, y, z);
		turn(villager, yaw);
		villager.setNoAi(true);
		villager.setInvulnerable(true);
		villager.setSilent(true);
		villager.setPersistenceRequired();
		villager.addTag(TRADER_TAG);
		villager.addTag(Library.TAG);
		level.addFreshEntity(villager);
		return villager;
	}

	// ---- signs ----

	/** Writes the most valuable trade of a villager on the sign at its stall. */
	private static void writeSign(ServerLevel level, BlockPos pos, net.minecraft.world.entity.npc.villager.AbstractVillager villager) {
		if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
			return;
		}

		Component[] lines = signLines(villager);
		SignText old = sign.getFrontText();
		boolean same = true;

		for (int i = 0; i < 4; i++) {
			same &= old.getMessage(i, false).getString().equals(lines[i].getString());
		}

		if (same && sign.isWaxed()) {
			return;
		}

		SignText text = new SignText();

		for (int i = 0; i < 4; i++) {
			text = text.setMessage(i, lines[i]);
		}

		sign.setText(text, true);
		sign.setWaxed(true);
		sign.setChanged();
		level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), Block.UPDATE_CLIENTS);
	}

	static Component[] signLines(net.minecraft.world.entity.npc.villager.AbstractVillager villager) {
		Component[] lines = { Component.empty(), Component.empty(), Component.empty(), Component.empty() };
		MerchantOffer best = bestOffer(villager.getOffers());

		if (best == null) {
			lines[1] = Component.literal("Kein Handel");
			lines[2] = Component.literal("im Angebot").withStyle(ChatFormatting.DARK_GRAY);
			return lines;
		}

		ItemStack result = best.getResult();

		if (!result.is(Items.EMERALD) && !result.is(Items.EMERALD_BLOCK)) {
			lines[0] = Component.literal("Verkauft").withStyle(ChatFormatting.DARK_GRAY);
			lines[1] = name(result);
			lines[2] = extra(result);
			lines[3] = Component.literal("für " + emeralds(best) + " Smaragd" + (emeralds(best) == 1 ? "" : "e")).withStyle(ChatFormatting.DARK_GREEN);
		} else {
			ItemStack cost = best.getCostA();
			lines[0] = Component.literal("Kauft").withStyle(ChatFormatting.DARK_GRAY);
			lines[1] = name(cost);
			lines[2] = extra(cost);
			int paid = value(result);
			lines[3] = Component.literal("gibt " + paid + " Smaragd" + (paid == 1 ? "" : "e")).withStyle(ChatFormatting.DARK_GREEN);
		}

		return lines;
	}

	/**
	 * The most valuable trade: of what the villager sells, the most expensive; enchanted items win a tie. A villager
	 * who only buys shows what pays the most emeralds.
	 */
	static MerchantOffer bestOffer(List<MerchantOffer> offers) {
		MerchantOffer best = null;
		long bestScore = Long.MIN_VALUE;

		for (MerchantOffer offer : offers) {
			ItemStack result = offer.getResult();
			boolean sells = !result.is(Items.EMERALD) && !result.is(Items.EMERALD_BLOCK);
			long score = sells ? 1_000_000L + emeralds(offer) * 100L + (enchanted(result) ? 50 : 0) : value(result) * 100L;

			if (score > bestScore) {
				best = offer;
				bestScore = score;
			}
		}

		return best;
	}

	private static int emeralds(MerchantOffer offer) {
		return value(offer.getCostA()) + value(offer.getCostB());
	}

	private static int value(ItemStack stack) {
		if (stack.is(Items.EMERALD)) {
			return stack.getCount();
		}

		return stack.is(Items.EMERALD_BLOCK) ? stack.getCount() * 9 : 0;
	}

	private static boolean enchanted(ItemStack stack) {
		return stack.isEnchanted() || stack.has(DataComponents.STORED_ENCHANTMENTS);
	}

	private static Component name(ItemStack stack) {
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);

		// an enchanted book is named by its enchantment
		if (stored != null && !stored.isEmpty()) {
			var first = stored.entrySet().iterator().next();
			return Enchantment.getFullname(first.getKey(), first.getIntValue()).copy().withStyle(ChatFormatting.DARK_PURPLE);
		}

		MutableComponent name = stack.getHoverName().copy();
		return name.withStyle(ChatFormatting.BLACK);
	}

	private static Component extra(ItemStack stack) {
		ItemEnchantments enchantments = stack.get(DataComponents.STORED_ENCHANTMENTS);

		if (enchantments != null && !enchantments.isEmpty()) {
			return enchantments.size() > 1 ? Component.literal("+ " + (enchantments.size() - 1) + " weitere").withStyle(ChatFormatting.DARK_PURPLE)
					: Component.literal("Buch").withStyle(ChatFormatting.DARK_GRAY);
		}

		enchantments = stack.get(DataComponents.ENCHANTMENTS);

		if (enchantments != null && !enchantments.isEmpty()) {
			var first = enchantments.entrySet().iterator().next();
			return Enchantment.getFullname(first.getKey(), first.getIntValue()).copy().withStyle(ChatFormatting.DARK_PURPLE);
		}

		return stack.getCount() > 1 ? Component.literal("× " + stack.getCount()).withStyle(ChatFormatting.DARK_GRAY) : Component.empty();
	}
}
