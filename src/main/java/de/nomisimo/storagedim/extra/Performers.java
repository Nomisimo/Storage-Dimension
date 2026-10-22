package de.nomisimo.storagedim.extra;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.serialization.JsonOps;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Singing characters that follow their target around until paid off with the right token.
 */
public final class Performers {
	public static final String ENTITY_TAG = "storagedim_performer";
	private static final int MAX_PER_TARGET = 3;
	private static final Gson GSON = new Gson();

	/** Persistent part: who is being followed by whom. */
	static final class Booking {
		UUID target;
		String character;
		String token;
		String sender;
	}

	/** Runtime part, only while the target is online. */
	private static final class Live {
		final Booking booking;
		Mannequin entity;
		int songTick;
		int stuckTicks;
		int respawnDelay;
		Vec3 lastPos = Vec3.ZERO;

		Live(Booking booking) {
			this.booking = booking;
		}
	}

	private static final List<Booking> BOOKINGS = new ArrayList<>();
	private static final Map<Booking, Live> LIVE = new HashMap<>();
	private static final Map<String, Melody> MELODIES = new HashMap<>();
	private static Path file;

	private Performers() {
	}

	// ---- lifecycle ----

	static void load(MinecraftServer server) {
		BOOKINGS.clear();
		LIVE.clear();
		file = server.getWorldPath(LevelResource.ROOT).resolve("storagedim_extra.json");

		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					List<Booking> list = GSON.fromJson(reader, new TypeToken<List<Booking>>() { }.getType());

					if (list != null) {
						BOOKINGS.addAll(list);
					}
				}
			}
		} catch (Exception e) {
			StorageDimensionMod.LOGGER.error("storagedim_extra.json konnte nicht gelesen werden", e);
		}
	}

	static void save() {
		if (file == null) {
			return;
		}

		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(BOOKINGS, writer);
		} catch (Exception e) {
			StorageDimensionMod.LOGGER.error("storagedim_extra.json konnte nicht gespeichert werden", e);
		}
	}

	static void stop() {
		for (Live live : LIVE.values()) {
			if (live.entity != null) {
				live.entity.discard();
			}
		}

		LIVE.clear();
		save();
		file = null;
	}

	static void onJoin(ServerPlayer player) {
		for (Booking booking : BOOKINGS) {
			if (booking.target.equals(player.getUUID())) {
				LIVE.computeIfAbsent(booking, Live::new);
			}
		}
	}

	static void onLeave(ServerPlayer player) {
		LIVE.entrySet().removeIf(entry -> {
			if (!entry.getKey().target.equals(player.getUUID())) {
				return false;
			}

			if (entry.getValue().entity != null) {
				entry.getValue().entity.discard();
			}

			return true;
		});
	}

	/** Discards leftover performer entities from chunks saved before a crash. */
	static void onEntityLoad(Entity entity) {
		if (!(entity instanceof Mannequin) || !entity.entityTags().contains(ENTITY_TAG)) {
			return;
		}

		for (Live live : LIVE.values()) {
			if (live.entity == entity) {
				return;
			}
		}

		entity.discard();
	}

	public static boolean isPerformer(Entity entity) {
		return entity instanceof Mannequin && entity.entityTags().contains(ENTITY_TAG);
	}

	// ---- booking ----

	static boolean book(ServerPlayer sender, ServerPlayer target, ExtraConfig.Character character) {
		long count = BOOKINGS.stream().filter(b -> b.target.equals(target.getUUID())).count();

		if (count >= MAX_PER_TARGET) {
			return false;
		}

		Booking booking = new Booking();
		booking.target = target.getUUID();
		booking.character = character.id;
		booking.token = Tokens.ALL.get(ThreadLocalRandom.current().nextInt(Tokens.ALL.size())).id();
		booking.sender = sender.getGameProfile().name();
		BOOKINGS.add(booking);
		LIVE.put(booking, new Live(booking));
		save();

		Tokens.Token token = Tokens.byId(booking.token);
		target.sendSystemMessage(Component.literal(booking.sender + " schenkt dir ein Ständchen von " + character.name + "! ")
				.withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Component.literal("Es hört erst auf, wenn du " + token.name() + " überreichst.").withStyle(ChatFormatting.GRAY)));
		return true;
	}

	/** Target right-clicked their performer. Returns true when it was paid off. */
	static boolean tryPayOff(ServerPlayer player, Entity entity) {
		for (Live live : new ArrayList<>(LIVE.values())) {
			if (live.entity != entity) {
				continue;
			}

			ExtraConfig.Character character = character(live.booking);
			Tokens.Token token = Tokens.byId(live.booking.token);

			if (!live.booking.target.equals(player.getUUID())) {
				player.connection.send(new ClientboundSetActionBarTextPacket(
						Component.literal(character.name + " singt nicht für dich.").withStyle(ChatFormatting.GRAY)));
				return false;
			}

			var held = player.getMainHandItem();

			if (!token.id().equals(Tokens.idOf(held))) {
				player.connection.send(new ClientboundSetActionBarTextPacket(
						Component.literal(character.name + " will nur eins: " + token.name()).withStyle(ChatFormatting.GOLD)));
				return false;
			}

			held.shrink(1);
			dismiss(live, player.level().getServer());
			player.sendSystemMessage(Component.literal(character.name + " verbeugt sich und verschwindet.").withStyle(ChatFormatting.GREEN));
			return true;
		}

		return false;
	}

	private static void dismiss(Live live, MinecraftServer server) {
		BOOKINGS.remove(live.booking);
		LIVE.remove(live.booking);

		if (live.entity != null) {
			stopSound(live, server);
			live.entity.discard();
		}

		save();
	}

	// ---- ticking ----

	static void tick(MinecraftServer server) {
		ExtraConfig config = ExtraConfig.get();

		for (Live live : new ArrayList<>(LIVE.values())) {
			ServerPlayer target = server.getPlayerList().getPlayer(live.booking.target);
			ExtraConfig.Character character = character(live.booking);

			if (target == null || character == null) {
				continue;
			}

			if (live.entity == null || live.entity.isRemoved()) {
				if (live.respawnDelay-- > 0) {
					continue;
				}

				spawn(live, target, character, config);
				continue;
			}

			if (live.entity.level() != target.level() || live.entity.distanceTo(target) > 24) {
				live.entity.discard();
				spawn(live, target, character, config);
				continue;
			}

			follow(live, target, config);
			sing(live, character, config);
		}
	}

	private static void spawn(Live live, ServerPlayer target, ExtraConfig.Character character, ExtraConfig config) {
		ServerLevel level = target.level();
		Mannequin entity = EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);

		if (entity == null) {
			return;
		}

		Vec3 behind = target.position().subtract(target.getLookAngle().multiply(2, 0, 2));
		entity.snapTo(behind.x, target.getY(), behind.z, target.getYRot(), 0f);

		ResolvableProfile profile = profile(character, config);

		if (profile != null) {
			entity.setProfile(profile);
		}

		entity.setCustomName(Component.literal(character.name).withStyle(ChatFormatting.LIGHT_PURPLE));
		entity.setCustomNameVisible(true);
		entity.setDescription(Component.literal("♪ " + character.song + " ♪ · will: " + Tokens.byId(live.booking.token).name())
				.withStyle(ChatFormatting.GRAY));
		entity.setHideDescription(false);
		entity.setInvulnerable(true);
		entity.addTag(ENTITY_TAG);

		live.entity = entity;
		live.songTick = 0;
		live.stuckTicks = 0;
		live.respawnDelay = 100;
		level.addFreshEntity(entity);
	}

	private static ResolvableProfile profile(ExtraConfig.Character character, ExtraConfig config) {
		JsonObject json = new JsonObject();

		if (config.useResourcePack && !character.skinTexture.isEmpty()) {
			json.addProperty("texture", character.skinTexture);
			json.addProperty("model", character.skinModel);
		} else if (!character.skinPlayer.isEmpty()) {
			json.addProperty("name", character.skinPlayer);
		} else {
			return null;
		}

		return ResolvableProfile.CODEC.parse(JsonOps.INSTANCE, json).resultOrPartial(StorageDimensionMod.LOGGER::warn).orElse(null);
	}

	private static void follow(Live live, ServerPlayer target, ExtraConfig config) {
		Mannequin entity = live.entity;
		double dx = target.getX() - entity.getX();
		double dz = target.getZ() - entity.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
		entity.setYRot(yaw);
		entity.setYHeadRot(yaw);
		entity.setYBodyRot(yaw);

		Vec3 motion = entity.getDeltaMovement();

		if (dist > config.followDistance) {
			double speed = dist > 8 ? 0.4 : 0.25;
			double vy = motion.y;

			if (entity.horizontalCollision && entity.onGround()) {
				vy = 0.45;
			}

			entity.setDeltaMovement(dx / dist * speed, vy, dz / dist * speed);

			if (entity.position().distanceToSqr(live.lastPos) < 0.0025) {
				live.stuckTicks++;
			} else {
				live.stuckTicks = 0;
			}

			if (live.stuckTicks > 60) {
				Vec3 behind = target.position().subtract(target.getLookAngle().multiply(1.5, 0, 1.5));
				entity.teleportTo(behind.x, target.getY(), behind.z);
				live.stuckTicks = 0;
			}
		} else {
			entity.setDeltaMovement(motion.x * 0.5, motion.y, motion.z * 0.5);
			live.stuckTicks = 0;
		}

		live.lastPos = entity.position();
	}

	private static void sing(Live live, ExtraConfig.Character character, ExtraConfig config) {
		Mannequin entity = live.entity;
		ServerLevel level = (ServerLevel) entity.level();
		List<ServerPlayer> listeners = new ArrayList<>();
		double range = config.hearingRange * config.hearingRange;

		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(entity) <= range) {
				listeners.add(player);
			}
		}

		if (config.useResourcePack && !character.sound.isEmpty()) {
			if (live.songTick % (Math.max(5, character.soundSeconds) * 20) == 0) {
				Holder<SoundEvent> sound = Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse(character.sound)));

				for (ServerPlayer player : listeners) {
					for (SoundSource source : channels(config)) {
						player.connection.send(new ClientboundSoundEntityPacket(sound, source, entity, config.volumePerChannel, 1f, level.getRandom().nextLong()));
					}
				}
			}
		} else {
			Melody melody = MELODIES.computeIfAbsent(character.id, id -> new Melody(id.hashCode() * 31L + character.name.hashCode()));
			int t = live.songTick % melody.length;

			for (Melody.Note note : melody.notes) {
				if (note.tick() != t) {
					continue;
				}

				float pitch = note.instrument().isTunable() ? Melody.pitchToFloat(note.pitch()) : 1f;

				for (ServerPlayer player : listeners) {
					for (SoundSource source : channels(config)) {
						player.connection.send(new ClientboundSoundPacket(note.instrument().getSoundEvent(), source,
								entity.getX(), entity.getEyeY(), entity.getZ(), config.volumePerChannel * 3, pitch, 0L));
					}
				}
			}
		}

		live.songTick++;
	}

	private static void stopSound(Live live, MinecraftServer server) {
		ExtraConfig.Character character = character(live.booking);

		if (character == null || character.sound.isEmpty() || live.entity == null) {
			return;
		}

		Identifier id = Identifier.parse(character.sound);

		for (ServerPlayer player : ((ServerLevel) live.entity.level()).players()) {
			player.connection.send(new ClientboundStopSoundPacket(id, null));
		}
	}

	private static List<SoundSource> channels(ExtraConfig config) {
		List<SoundSource> list = new ArrayList<>();

		for (String name : config.channels) {
			try {
				list.add(SoundSource.valueOf(name));
			} catch (IllegalArgumentException ignored) {
				// unknown category name in config
			}
		}

		return list;
	}

	private static ExtraConfig.Character character(Booking booking) {
		return ExtraConfig.get().find(booking.character);
	}
}
