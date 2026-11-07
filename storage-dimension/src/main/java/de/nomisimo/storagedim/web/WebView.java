package de.nomisimo.storagedim.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.nomisimo.storagedim.StorageConfig;
import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.Library;
import de.nomisimo.storagedim.storage.Projects;
import de.nomisimo.storagedim.storage.StorageData;
import de.nomisimo.storagedim.storage.StorageNetwork;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A small read-only web page that lists what is in a player's ME storage, for a browser or phone next to the game.
 * <p>
 * The server runs on {@code webPort} from config/storagedim.json. Every player gets a secret link with
 * {@code /storage web}; the token in it decides whose storage the page shows. Tokens are kept in
 * storagedim-web.json in the world folder, {@code /storage web neu} replaces a player's token.
 */
public final class WebView {
	private static final Gson GSON = new Gson();
	private static final SecureRandom RANDOM = new SecureRandom();
	/** Token to player. */
	private static final Map<String, UUID> TOKENS = new ConcurrentHashMap<>();

	private static MinecraftServer server;
	private static HttpServer http;
	private static ExecutorService executor;
	private static Path tokenFile;

	private WebView() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(WebView::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
	}

	/** Port the web view listens on, or 0 while it is not running. */
	public static int port() {
		return http == null ? 0 : http.getAddress().getPort();
	}

	private static void start(MinecraftServer started) {
		server = started;
		tokenFile = started.getWorldPath(LevelResource.ROOT).resolve("storagedim-web.json");
		loadTokens();
		int port = StorageConfig.get().webPort;

		if (port <= 0) {
			return;
		}

		try {
			http = HttpServer.create(new InetSocketAddress(port), 0);
			executor = Executors.newFixedThreadPool(2, runnable -> {
				Thread thread = new Thread(runnable, "storagedim-web");
				thread.setDaemon(true);
				return thread;
			});
			http.setExecutor(executor);
			http.createContext("/", WebView::page);
			http.createContext("/api/items", WebView::items);
			http.start();
			StorageDimensionMod.LOGGER.info("ME-Webansicht läuft auf Port {}", port);
		} catch (IOException e) {
			StorageDimensionMod.LOGGER.error("ME-Webansicht konnte Port {} nicht öffnen (webPort in config/storagedim.json ändern)", port, e);
			stop();
		}
	}

	private static void stop() {
		if (http != null) {
			http.stop(0);
			http = null;
		}

		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}

		server = null;
	}

	// ---- command ----

	/** {@code /storage web [neu]}: sends the player their link, with {@code renew} a fresh one that replaces the old. */
	public static int command(ServerPlayer player, boolean renew) {
		if (http == null) {
			player.sendSystemMessage(Component.literal("Die ME-Webansicht ist aus. Ein Admin kann sie in config/storagedim.json mit webPort einschalten.")
					.withStyle(ChatFormatting.RED));
			return 0;
		}

		String token = tokenOf(player.getUUID());

		if (token == null || renew) {
			if (token != null) {
				TOKENS.remove(token);
			}

			token = HexFormat.of().formatHex(randomBytes());
			TOKENS.put(token, player.getUUID());
			saveTokens();
		}

		String address = StorageConfig.get().webAddress.isBlank() ? "localhost" : StorageConfig.get().webAddress.strip();
		String link = "http://" + address + ":" + port() + "/#" + token;

		player.sendSystemMessage(Component.literal(renew ? "Neuer Link zu deinem ME-Speicher (der alte geht nicht mehr):" : "Dein ME-Speicher im Browser:")
				.withStyle(ChatFormatting.AQUA));
		player.sendSystemMessage(Component.literal(link)
				.withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(URI.create(link))).withUnderlined(true).withColor(ChatFormatting.YELLOW)));
		player.sendSystemMessage(Component.literal("Der Link ist geheim, wer ihn hat, sieht deinen Speicher. Neuer Link: /storage web neu")
				.withStyle(ChatFormatting.GRAY));

		if (StorageConfig.get().webAddress.isBlank()) {
			player.sendSystemMessage(Component.literal("Läuft der Server nicht auf diesem PC, trage in config/storagedim.json bei webAddress die Adresse des Servers ein.")
					.withStyle(ChatFormatting.GRAY));
		}

		return 1;
	}

	private static byte[] randomBytes() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	private static String tokenOf(UUID player) {
		for (Map.Entry<String, UUID> entry : TOKENS.entrySet()) {
			if (entry.getValue().equals(player)) {
				return entry.getKey();
			}
		}

		return null;
	}

	private static void loadTokens() {
		TOKENS.clear();

		if (!Files.exists(tokenFile)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(tokenFile, StandardCharsets.UTF_8)) {
			JsonObject root = GSON.fromJson(reader, JsonObject.class);

			if (root != null) {
				root.entrySet().forEach(entry -> TOKENS.put(entry.getValue().getAsString(), UUID.fromString(entry.getKey())));
			}
		} catch (Exception e) {
			StorageDimensionMod.LOGGER.error("storagedim-web.json konnte nicht gelesen werden", e);
		}
	}

	private static void saveTokens() {
		JsonObject root = new JsonObject();
		TOKENS.forEach((token, player) -> root.addProperty(player.toString(), token));

		try (Writer writer = Files.newBufferedWriter(tokenFile, StandardCharsets.UTF_8)) {
			GSON.toJson(root, writer);
		} catch (IOException e) {
			StorageDimensionMod.LOGGER.error("storagedim-web.json konnte nicht gespeichert werden", e);
		}
	}

	// ---- http ----

	private static void page(HttpExchange exchange) throws IOException {
		try (exchange) {
			String path = exchange.getRequestURI().getPath();

			if (!path.equals("/") && !path.equals("/index.html")) {
				send(exchange, 404, "text/plain", "Nicht gefunden");
				return;
			}

			try (InputStream in = WebView.class.getResourceAsStream("/storagedim_web/index.html")) {
				if (in == null) {
					send(exchange, 500, "text/plain", "Seite fehlt in der Mod");
					return;
				}

				send(exchange, 200, "text/html", new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
	}

	private static void items(HttpExchange exchange) throws IOException {
		try (exchange) {
			String auth = exchange.getRequestHeaders().getFirst("Authorization");
			UUID player = auth != null && auth.startsWith("Bearer ") ? TOKENS.get(auth.substring(7).strip()) : null;

			if (player == null) {
				send(exchange, 401, "application/json", "{\"error\":\"Link ungültig. Hol dir im Spiel mit /storage web einen neuen.\"}");
				return;
			}

			MinecraftServer current = server;

			if (current == null) {
				send(exchange, 503, "application/json", "{\"error\":\"Server fährt herunter.\"}");
				return;
			}

			String json;

			try {
				// Storage is only touched on the server thread.
				json = current.submit(() -> snapshot(current, player)).get(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				send(exchange, 503, "application/json", "{\"error\":\"Server antwortet gerade nicht.\"}");
				return;
			}

			send(exchange, 200, "application/json", json);
		}
	}

	private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
		exchange.sendResponseHeaders(status, bytes.length);

		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	// ---- content ----

	/** Everything the page shows, as JSON. Runs on the server thread. */
	private static String snapshot(MinecraftServer server, UUID player) {
		StorageData data = StorageData.get();
		JsonObject root = new JsonObject();

		if (data == null) {
			root.addProperty("error", "Der Speicher ist noch nicht geladen.");
			return GSON.toJson(root);
		}

		StorageNetwork network = data.networkFor(player);
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		root.addProperty("player", online != null ? online.getGameProfile().name() : "");
		root.addProperty("shared", network.owner.equals(StorageData.SHARED));
		root.addProperty("types", network.typeCount());
		root.addProperty("total", network.totalCount());
		JsonArray items = new JsonArray();

		for (StorageNetwork.StoredItem entry : network.sorted()) {
			ItemStack stack = entry.prototype;
			JsonObject item = new JsonObject();
			item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			item.addProperty("name", stack.getHoverName().getString());
			item.addProperty("count", entry.count);
			item.addProperty("available", Projects.available(network, entry, null));
			item.addProperty("category", Library.category(stack).title);
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
			items.add(item);
		}

		root.add("items", items);
		return GSON.toJson(root);
	}

	private static void enchantments(ItemEnchantments enchantments, JsonArray details) {
		if (enchantments == null) {
			return;
		}

		for (Holder<Enchantment> enchantment : enchantments.keySet()) {
			details.add(Enchantment.getFullname(enchantment, enchantments.getLevel(enchantment)).getString());
		}
	}
}
