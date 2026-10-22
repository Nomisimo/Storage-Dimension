package de.nomisimo.storagedim;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import de.nomisimo.storagedim.mixin.MannequinAccessor;
import de.nomisimo.storagedim.storage.Rooms;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.block.Blocks;

/**
 * Started by CI with -Dstoragedim.smoketest=true: boots a dev server, checks that the
 * dimension, recipes and mixins loaded, then shuts the server down.
 */
final class SmokeTest {
	private SmokeTest() {
	}

	static void registerIfEnabled() {
		if (!Boolean.getBoolean("storagedim.smoketest")) {
			return;
		}

		ServerLifecycleEvents.SERVER_STARTED.register(SmokeTest::run);
	}

	private static void run(MinecraftServer server) {
		List<String> errors = new ArrayList<>();

		try {
			ServerLevel level = Rooms.level(server);

			if (level == null) {
				errors.add("Dimension storagedim:storage fehlt");
			} else {
				Rooms.ensureBuilt(level, 0);

				if (!level.getBlockState(Rooms.origin(0).offset(6, 1, 1)).is(Blocks.ENDER_CHEST)) {
					errors.add("Raum wurde nicht gebaut");
				}
			}

			for (String recipe : List.of("storage_chest", "token_ohrstoepsel", "token_stimmgabel", "token_schweigegeld",
					"token_schallplatte", "token_autogramm")) {
				if (server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, StorageDimensionMod.id(recipe))).isEmpty()) {
					errors.add("Rezept fehlt: " + recipe);
				}
			}

			Mannequin mannequin = EntityType.MANNEQUIN.create(server.overworld(), EntitySpawnReason.COMMAND);

			if (mannequin == null) {
				errors.add("Mannequin konnte nicht erzeugt werden");
			} else {
				((MannequinAccessor) mannequin).storagedim$setDescription(Component.literal("test"));
				((MannequinAccessor) mannequin).storagedim$setHideDescription(false);
				mannequin.discard();
			}

			for (String json : List.of("{\"name\":\"Notch\"}", "{\"texture\":\"storagedim:entity/bard/song_01\",\"model\":\"wide\"}")) {
				var parsed = ResolvableProfile.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));

				if (parsed.error().isPresent()) {
					errors.add("Profil " + json + ": " + parsed.error().get().message());
				}
			}

		} catch (Throwable t) {
			StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme", t);
			errors.add(t.toString());
		}

		if (errors.isEmpty()) {
			StorageDimensionMod.LOGGER.info("SMOKETEST OK");
			server.halt(false);
		} else {
			errors.forEach(e -> StorageDimensionMod.LOGGER.error("SMOKETEST FEHLER: {}", e));
			Runtime.getRuntime().halt(1);
		}
	}
}
