package de.nomisimo.storagedim;

import java.util.ArrayList;
import java.util.List;

import de.nomisimo.storagedim.storage.Library;
import de.nomisimo.storagedim.storage.LibraryExtras;
import de.nomisimo.storagedim.storage.Projects;
import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageNetwork;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Started by CI with -Dstoragedim.smoketest=true: boots a dev server, checks that the
 * dimension, recipe and island building work, then shuts the server down.
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

	/** The dragon egg altar and the trading hall come with their items and go again when the items are taken out. */
	private static void extrasTest(ServerLevel level, StorageNetwork network, List<String> errors) {
		BlockPos centre = Library.centre(network.room);
		ItemStack bucket = villagerBucket("minecraft:librarian");
		network.insert(new ItemStack(Items.DRAGON_EGG));
		network.insert(bucket.copy());
		network.insert(villagerBucket("minecraft:farmer"));
		Library.prepare(level, network);

		if (!level.getBlockState(centre.offset(0, 1, 2)).is(Blocks.CRYING_OBSIDIAN)) {
			errors.add("Drachenei-Altar fehlt");
		}

		if (network.tradeHallRows != 1) {
			errors.add("Handelshalle hat " + network.tradeHallRows + " statt 1 Reihe");
		}

		long traders = level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(centre).inflate(40),
				e -> e.entityTags().contains(LibraryExtras.TRADER_TAG)).size();

		if (traders != 2) {
			errors.add("Handelshalle hat " + traders + " statt 2 Dorfbewohner");
		}

		// the villagers live: they walk around (only jobless ones stand still)
		long standing = level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(centre).inflate(40),
				e -> e.entityTags().contains(LibraryExtras.TRADER_TAG) && e instanceof net.minecraft.world.entity.Mob mob && mob.isNoAi()).size();

		if (standing != 0) {
			errors.add(standing + " Dorfbewohner der Handelshalle haben keine KI");
		}

		// the door is a fence gate, behind the one row of stalls is the village square with its bell
		if (!(level.getBlockState(centre.offset(1, 1, 9)).getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)) {
			errors.add("Tor der Handelshalle fehlt");
		}

		if (!level.getBlockState(centre.offset(1, 1, 16)).is(Blocks.BELL)) {
			errors.add("Glocke auf dem Dorfplatz der Handelshalle fehlt");
		}

		// stall 0 is west of the corridor, its sign hangs on the west wall over the workstation
		if (level.getBlockEntity(centre.offset(-3, 2, 11)) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
			StringBuilder text = new StringBuilder();

			for (int i = 0; i < 4; i++) {
				text.append(sign.getFrontText().getMessage(i, false).getString()).append(" | ");
			}

			StorageDimensionMod.LOGGER.info("SMOKETEST Schild: {}", text);

			if (sign.getFrontText().getMessage(1, false).getString().isEmpty()) {
				errors.add("Schild der Handelshalle ist leer");
			}
		} else {
			errors.add("Schild der Handelshalle fehlt");
		}

		network.extract(new ItemStack(Items.DRAGON_EGG), 1);
		network.extract(bucket, 1);
		network.extract(villagerBucket("minecraft:farmer"), 1);
		Library.prepare(level, network);

		if (level.getBlockState(centre.offset(0, 1, 2)).is(Blocks.CRYING_OBSIDIAN)) {
			errors.add("Drachenei-Altar ist noch da, obwohl das Ei weg ist");
		}

		if (network.tradeHallRows != 0 || !level.getBlockState(centre.offset(1, 0, 11)).isAir()) {
			errors.add("Handelshalle ist noch da, obwohl keine Dorfbewohner mehr gespeichert sind");
		}

		traders = level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(centre).inflate(40),
				e -> e.entityTags().contains(LibraryExtras.TRADER_TAG) && e.isAlive()).size();

		if (traders != 0) {
			errors.add(traders + " Dorfbewohner stehen noch in der Handelshalle");
		}
	}

	/** A project chest gives out only the amount set for an item; what is put back can be taken again. */
	private static void projectTest(List<String> errors) {
		StorageNetwork network = new StorageNetwork(java.util.UUID.randomUUID(), 251);
		network.insert(new ItemStack(Items.DIRT, 100));
		Projects.Project project = new Projects.Project(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "Test", false);
		project.entries.add(new Projects.Entry(new ItemStack(Items.DIRT), 10));
		int first = network.extract(new ItemStack(Items.DIRT), 64, project).getCount();
		int second = network.extract(new ItemStack(Items.DIRT), 64, project).getCount();

		if (first != 10 || second != 0) {
			errors.add("Projektkiste gibt " + first + " und dann " + second + " statt 10 und 0 Erde heraus");
		}

		project.returned(new ItemStack(Items.DIRT, 4), 4);
		int again = network.extract(new ItemStack(Items.DIRT), 64, project).getCount();

		if (again != 4) {
			errors.add("Projektkiste gibt nach dem Zurücklegen " + again + " statt 4 Erde heraus");
		}

		// outside the project chest everything is free
		if (network.extract(new ItemStack(Items.DIRT), 64).getCount() != 64) {
			errors.add("Erde außerhalb der Projektkiste nicht frei");
		}
	}

	/** A bucket with a villager in it, the way bucket mods keep it: the saved villager in the item's data. */
	static ItemStack villagerBucket(String profession) {
		net.minecraft.nbt.CompoundTag villagerData = new net.minecraft.nbt.CompoundTag();
		villagerData.putString("profession", profession);
		villagerData.putString("type", "minecraft:plains");
		villagerData.putInt("level", 2);
		net.minecraft.nbt.CompoundTag entity = new net.minecraft.nbt.CompoundTag();
		entity.putString("id", "minecraft:villager");
		entity.put("VillagerData", villagerData);
		net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
		root.put("EntityTag", entity);
		ItemStack stack = new ItemStack(Items.BUCKET);
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(root));
		return stack;
	}

	private static void run(MinecraftServer server) {
		List<String> errors = new ArrayList<>();

		try {
			ServerLevel level = Rooms.level(server);

			if (level == null) {
				errors.add("Dimension storagedim:storage fehlt");
			} else {
				// A network that is not saved, on an island far from the real ones.
				StorageNetwork network = new StorageNetwork(java.util.UUID.randomUUID(), 250);
				network.insert(new ItemStack(Items.COBBLESTONE, 500));
				network.insert(new ItemStack(Items.DIAMOND_SWORD));
				Library.prepare(level, network);
				BlockPos centre = Library.centre(network.room);

				if (!level.getBlockState(centre).is(Blocks.SPRUCE_PLANKS)) {
					errors.add("Bibliothek wurde nicht gebaut");
				}

				// 500 cobblestone are 8 stacks, one chest; the sword gets its own
				if (network.library.size() != 2 || network.aisles.size() != 2) {
					errors.add("Bibliothek hat " + network.library.size() + " statt 2 Kisten und " + network.aisles.size() + " statt 2 Gänge");
				} else {
					Library.Shelf first = network.library.get(0);

					if (!level.getBlockState(centre.offset(first.dx(), first.dy(), first.dz())).is(Blocks.CHEST)) {
						errors.add("Kiste fehlt in der Bibliothek");
					}

					// the kind is shown in a fixed item frame on the chest front
					BlockPos front = centre.offset(first.dx(), first.dy(), first.dz()).relative(first.facing());
					List<net.minecraft.world.entity.decoration.ItemFrame> frames = level.getEntitiesOfClass(
							net.minecraft.world.entity.decoration.ItemFrame.class, new net.minecraft.world.phys.AABB(front),
							f -> f.getDirection() == first.facing() && ItemStack.isSameItemSameComponents(f.getItem(), first.key()));

					if (frames.size() != 1) {
						errors.add(frames.size() + " statt 1 Itemrahmen an der ersten Kiste");
					}
				}

				network.insert(new ItemStack(Items.COBBLESTONE, 64 * 27 * 3));
				Library.prepare(level, network);

				if (network.library.stream().filter(s -> s.key().is(Items.COBBLESTONE)).count() != 4) {
					errors.add("Bruchstein sollte jetzt 4 Kisten haben");
				}

				extrasTest(level, network, errors);
				projectTest(errors);
			}

			de.nomisimo.storagedim.robot.StationChunks.smokeTest(server.overworld(), errors);

			for (String recipe : List.of("storage_chest", "me_terminal", "me_upgrade", "me_backpack", "filter_chest", "robot")) {
				if (server.getRecipeManager().byKey(ResourceKey.create(Registries.RECIPE, StorageDimensionMod.id(recipe))).isEmpty()) {
					errors.add("Rezept fehlt: " + recipe);
				}
			}

			int port = de.nomisimo.storagedim.web.WebView.port();

			if (port == 0) {
				errors.add("ME-Webansicht läuft nicht");
			} else {
				if (status(port, "/", null) != 200) {
					errors.add("ME-Webansicht liefert keine Seite");
				}

				// A wrong token must not show anything; a valid one would wait for this (the server) thread.
				if (status(port, "/api/items", "Bearer falsch") != 401) {
					errors.add("ME-Webansicht nimmt einen falschen Link an");
				}

				if (status(port, "/icon/storagedim/robot", null) != 200) {
					errors.add("ME-Webansicht liefert kein Bild für Mod-Items");
				}
			}
		} catch (Throwable t) {
			StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme", t);
			errors.add(t.toString());
		}

		if (!errors.isEmpty()) {
			report(server, errors);
			return;
		}

		// the mining robots need a few minutes of game time; the result comes later
		try {
			de.nomisimo.storagedim.robot.MiningSmokeTest.start(server, miningErrors -> {
				if (!miningErrors.isEmpty()) {
					report(server, miningErrors);
					return;
				}

				// then the ore search, also a few minutes
				try {
					de.nomisimo.storagedim.robot.OreSmokeTest.start(server, oreErrors -> report(server, oreErrors));
				} catch (Throwable t) {
					StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme", t);
					report(server, List.of(t.toString()));
				}
			});
		} catch (Throwable t) {
			StorageDimensionMod.LOGGER.error("SMOKETEST Ausnahme", t);
			report(server, List.of(t.toString()));
		}
	}

	private static void report(MinecraftServer server, List<String> errors) {
		if (errors.isEmpty()) {
			StorageDimensionMod.LOGGER.info("SMOKETEST OK");
			server.halt(false);
		} else {
			errors.forEach(e -> StorageDimensionMod.LOGGER.error("SMOKETEST FEHLER: {}", e));
			Runtime.getRuntime().halt(1);
		}
	}

	private static int status(int port, String path, String auth) throws java.io.IOException {
		java.net.HttpURLConnection connection = (java.net.HttpURLConnection) java.net.URI.create("http://127.0.0.1:" + port + path).toURL()
				.openConnection(java.net.Proxy.NO_PROXY);
		connection.setConnectTimeout(5000);
		connection.setReadTimeout(5000);

		if (auth != null) {
			connection.setRequestProperty("Authorization", auth);
		}

		try {
			return connection.getResponseCode();
		} finally {
			connection.disconnect();
		}
	}
}
