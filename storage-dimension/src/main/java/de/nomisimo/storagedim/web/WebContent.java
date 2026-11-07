package de.nomisimo.storagedim.web;

import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import de.nomisimo.storagedim.storage.FilterChests;
import de.nomisimo.storagedim.storage.Library;
import de.nomisimo.storagedim.storage.Projects;
import de.nomisimo.storagedim.storage.StorageData;
import de.nomisimo.storagedim.storage.StorageMenu;
import de.nomisimo.storagedim.storage.StorageNetwork;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The JSON of the web view. Every method runs on the server thread. Items are identified by a key: the item
 * encoded with its components, so the page can send exactly that item back (e.g. one enchanted book).
 */
final class WebContent {
	private static final Gson GSON = new Gson();
	private static final long MAX_TARGET = 1_000_000_000L;

	/** Every item a project can ask for, built once per server. */
	private static MinecraftServer catalogServer;
	private static String catalog;

	private WebContent() {
	}

	// ---- storage ----

	static String items(MinecraftServer server, UUID player) {
		StorageData data = StorageData.get();

		if (data == null) {
			return error("Der Speicher ist noch nicht geladen.");
		}

		DynamicOps<JsonElement> ops = ops(server);
		StorageNetwork network = data.networkFor(player);
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		JsonObject root = new JsonObject();
		root.addProperty("player", online != null ? online.getGameProfile().name() : "");
		root.addProperty("shared", network.owner.equals(StorageData.SHARED));
		root.addProperty("types", network.typeCount());
		root.addProperty("total", network.totalCount());
		JsonArray items = new JsonArray();

		for (StorageNetwork.StoredItem entry : network.sorted()) {
			JsonObject item = item(ops, entry.prototype);
			item.addProperty("count", entry.count);
			item.addProperty("available", Projects.available(network, entry, null));
			item.addProperty("category", Library.category(entry.prototype).title);
			items.add(item);
		}

		root.add("items", items);
		return GSON.toJson(root);
	}

	// ---- projects ----

	static String projects(MinecraftServer server, UUID player) {
		StorageData data = StorageData.get();

		if (data == null) {
			return error("Der Speicher ist noch nicht geladen.");
		}

		DynamicOps<JsonElement> ops = ops(server);
		StorageNetwork network = data.networkFor(player);
		JsonArray list = new JsonArray();

		for (Projects.Project project : Projects.of(network)) {
			JsonObject p = new JsonObject();
			p.addProperty("id", project.id.toString());
			p.addProperty("name", project.name);
			p.addProperty("block", project.block);
			JsonArray entries = new JsonArray();

			for (Projects.Entry entry : project.entries) {
				JsonObject e = item(ops, entry.item);
				e.addProperty("target", entry.target);
				e.addProperty("have", stored(network, entry));
				entries.add(e);
			}

			p.add("entries", entries);
			list.add(p);
		}

		JsonObject root = new JsonObject();
		root.add("projects", list);
		return GSON.toJson(root);
	}

	/**
	 * One change from the page: {@code create} (name), {@code name} (id, name), {@code block} (id, block) or
	 * {@code set} (id, key, target; 0 removes the item). Answers with all projects, like {@link #projects}.
	 */
	static String changeProject(MinecraftServer server, UUID player, JsonObject body) {
		StorageData data = StorageData.get();

		if (data == null || body == null || !body.has("action")) {
			return error("Ungültige Anfrage.");
		}

		StorageNetwork network = data.networkFor(player);
		String action = body.get("action").getAsString();

		if (action.equals("create")) {
			Projects.Project project = Projects.create(player);
			String name = name(body);

			if (name != null) {
				project.name = name;
			}

			changed(network);
			return projects(server, player);
		}

		Projects.Project project = body.has("id") ? own(network, body.get("id").getAsString()) : null;

		if (project == null) {
			return error("Projekt nicht gefunden.");
		}

		switch (action) {
			case "name" -> {
				String name = name(body);

				if (name == null) {
					return error("Der Name darf nicht leer sein.");
				}

				project.name = name;
			}
			case "block" -> project.block = body.has("block") && body.get("block").getAsBoolean();
			case "set" -> {
				ItemStack stack = body.has("key") ? decode(server, body.get("key").getAsString()) : ItemStack.EMPTY;

				if (stack.isEmpty() || !body.has("target")) {
					return error("Item nicht erkannt.");
				}

				long target = Math.min(body.get("target").getAsLong(), MAX_TARGET);
				Projects.Entry entry = project.entryFor(stack);

				if (target <= 0) {
					if (entry != null) {
						project.entries.remove(entry);
					}
				} else if (entry == null) {
					project.entries.add(new Projects.Entry(FilterChests.matchKey(stack), target));
				} else {
					entry.target = target;
				}
			}
			default -> {
				return error("Unbekannte Aktion.");
			}
		}

		changed(network);
		return projects(server, player);
	}

	private static Projects.Project own(StorageNetwork network, String id) {
		for (Projects.Project project : Projects.of(network)) {
			if (project.id.toString().equals(id)) {
				return project;
			}
		}

		return null;
	}

	private static String name(JsonObject body) {
		String name = body.has("name") ? body.get("name").getAsString().strip() : "";

		if (name.isEmpty()) {
			return null;
		}

		return name.length() > Projects.MAX_NAME ? name.substring(0, Projects.MAX_NAME) : name;
	}

	private static void changed(StorageNetwork network) {
		StorageData.markDirty();
		StorageMenu.refreshAll(network);
	}

	/** How many of the entry's item the network holds, like the project chest counts it. */
	private static long stored(StorageNetwork network, Projects.Entry entry) {
		long count = 0;

		for (StorageNetwork.StoredItem stored : network.entries()) {
			if (entry.matches(stored.prototype)) {
				count += stored.count;
			}
		}

		return count;
	}

	// ---- catalog ----

	/** Every item of the game plus each potion and enchanted book, for adding items to a project. */
	static String catalog(MinecraftServer server) {
		if (catalogServer != server || catalog == null) {
			DynamicOps<JsonElement> ops = ops(server);
			List<ItemStack> all = Projects.allItems(server.overworld().enabledFeatures(), server.registryAccess());
			JsonArray items = new JsonArray();

			for (ItemStack stack : all) {
				items.add(item(ops, stack));
			}

			JsonObject root = new JsonObject();
			root.add("items", items);
			catalog = GSON.toJson(root);
			catalogServer = server;
		}

		return catalog;
	}

	// ---- items ----

	private static JsonObject item(DynamicOps<JsonElement> ops, ItemStack stack) {
		JsonObject item = new JsonObject();
		ItemStack one = stack.copyWithCount(1);
		item.addProperty("key", ItemStack.CODEC.encodeStart(ops, one).result().map(JsonElement::toString).orElse(""));
		item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		Identifier model = stack.get(DataComponents.ITEM_MODEL);
		item.addProperty("icon", model != null ? model.toString() : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		item.addProperty("name", stack.getHoverName().getString());
		item.addProperty("foil", stack.hasFoil());
		JsonArray details = new JsonArray();
		enchantments(stack.get(DataComponents.ENCHANTMENTS), details);
		enchantments(stack.get(DataComponents.STORED_ENCHANTMENTS), details);
		PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);

		if (potion != null) {
			for (MobEffectInstance effect : potion.getAllEffects()) {
				details.add(effect.getEffect().value().getDisplayName().getString());
			}
		}

		if (stack.isDamaged()) {
			details.add("Haltbarkeit " + (stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage());
		}

		item.add("details", details);
		return item;
	}

	private static void enchantments(ItemEnchantments enchantments, JsonArray details) {
		if (enchantments == null) {
			return;
		}

		for (Holder<Enchantment> enchantment : enchantments.keySet()) {
			details.add(Enchantment.getFullname(enchantment, enchantments.getLevel(enchantment)).getString());
		}
	}

	private static ItemStack decode(MinecraftServer server, String key) {
		try {
			return ItemStack.CODEC.parse(ops(server), JsonParser.parseString(key)).result().orElse(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	private static DynamicOps<JsonElement> ops(MinecraftServer server) {
		return server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
	}

	private static String error(String message) {
		JsonObject root = new JsonObject();
		root.addProperty("error", message);
		return GSON.toJson(root);
	}
}
