package de.nomisimo.barden;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import de.nomisimo.barden.mixin.MannequinAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;

/**
 * Started by CI with -Dbarden.smoketest=true: boots a dev server, checks recipes, mixins and
 * skin profiles, then shuts the server down.
 */
final class SmokeTest {
	private SmokeTest() {
	}

	static void registerIfEnabled() {
		if (!Boolean.getBoolean("barden.smoketest")) {
			return;
		}

		ServerLifecycleEvents.SERVER_STARTED.register(SmokeTest::run);
	}

	private static void run(MinecraftServer server) {
		List<String> errors = new ArrayList<>();

		try {
			for (Tokens.Token token : Tokens.ALL) {
				if (server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, BardenMod.id("token_" + token.id()))).isEmpty()) {
					errors.add("Rezept fehlt: token_" + token.id());
				}
			}

			Mannequin mannequin = EntityType.MANNEQUIN.create(server.overworld(), EntitySpawnReason.COMMAND);

			if (mannequin == null) {
				errors.add("Mannequin konnte nicht erzeugt werden");
			} else {
				((MannequinAccessor) mannequin).barden$setDescription(Component.literal("test"));
				((MannequinAccessor) mannequin).barden$setHideDescription(false);
				mannequin.discard();
			}

			for (String json : List.of("{\"name\":\"Notch\"}", "{\"texture\":\"barden:entity/bard/song_01\",\"model\":\"wide\"}")) {
				var parsed = ResolvableProfile.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));

				if (parsed.error().isPresent()) {
					errors.add("Profil " + json + ": " + parsed.error().get().message());
				}
			}

			if (ExtraConfig.get().characters.size() != 30) {
				errors.add("Es sind nicht 30 Charaktere");
			}

			new Melody(42L);
		} catch (Throwable t) {
			BardenMod.LOGGER.error("SMOKETEST Ausnahme", t);
			errors.add(t.toString());
		}

		if (errors.isEmpty()) {
			BardenMod.LOGGER.info("SMOKETEST OK");
			server.halt(false);
		} else {
			errors.forEach(e -> BardenMod.LOGGER.error("SMOKETEST FEHLER: {}", e));
			Runtime.getRuntime().halt(1);
		}
	}
}
