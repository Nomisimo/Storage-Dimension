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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.nomisimo.storagedim.StorageConfig;
import de.nomisimo.storagedim.StorageDimensionMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A small web page for a browser or phone next to the game: the player's ME storage, projects, robot stations and
 * mailboxes.
 * <p>
 * The server runs on {@code webPort} from config/storagedim.json. Every player gets a secret link with
 * {@code /storage web}; the token in it decides whose storage the page shows. With a password
 * ({@code /storage web passwort <passwort>} or on the page) a player can also log in with their name, on any device,
 * and gets the same token. Tokens, names and password hashes are kept in storagedim-web.json in the world folder,
 * {@code /storage web neu} replaces a player's token (and so logs out every device).
 */
public final class WebView {
	private static final Gson GSON = new Gson();
	private static final SecureRandom RANDOM = new SecureRandom();
	/** Token to player. */
	private static final Map<String, UUID> TOKENS = new ConcurrentHashMap<>();
	private static final int MAX_BODY = 16 * 1024;
	/** Player to their account: name (for the login) and password hash. */
	private static final Map<UUID, Account> ACCOUNTS = new ConcurrentHashMap<>();
	/** Lower-case name to failed logins in a row and until when the name is locked. */
	private static final Map<String, long[]> FAILURES = new ConcurrentHashMap<>();
	private static final int MAX_FAILURES = 5;
	private static final long LOCK_MS = 60_000;
	public static final int MIN_PASSWORD = 6;
	public static final int MAX_PASSWORD = 64;
	private static final int ITERATIONS = 120_000;

	/** {@code salt} and {@code hash} are hex, empty while no password is set. */
	private record Account(String name, String salt, String hash) {
		boolean hasPassword() {
			return !hash.isEmpty();
		}
	}

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
			http.createContext("/api/", WebView::api);
			http.createContext("/icon/", WebView::icon);
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

		remember(player);
		String token = tokenOf(player.getUUID());

		if (token == null || renew) {
			if (token != null) {
				TOKENS.remove(token);
			}

			token = HexFormat.of().formatHex(randomBytes());
			TOKENS.put(token, player.getUUID());
			saveTokens();
		}

		String link = baseUrl() + "/#" + token;

		player.sendSystemMessage(Component.literal(renew ? "Neuer Link zu deinem ME-Speicher (der alte geht nicht mehr):" : "Dein ME-Speicher im Browser:")
				.withStyle(ChatFormatting.AQUA));
		player.sendSystemMessage(Component.literal(link)
				.withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(URI.create(link))).withUnderlined(true).withColor(ChatFormatting.YELLOW)));
		player.sendSystemMessage(Component.literal("Der Link ist geheim, wer ihn hat, sieht deinen Speicher. Neuer Link: /storage web neu")
				.withStyle(ChatFormatting.GRAY));

		if (!hasPassword(player.getUUID())) {
			player.sendSystemMessage(Component.literal("Ohne Link anmelden (mit deinem Namen): /storage web passwort <passwort>")
					.withStyle(ChatFormatting.GRAY));
		}

		if (StorageConfig.get().webAddress.isBlank()) {
			player.sendSystemMessage(Component.literal("Läuft der Server nicht auf diesem PC, trage in config/storagedim.json bei webAddress die Adresse des Servers ein.")
					.withStyle(ChatFormatting.GRAY));
		}

		return 1;
	}

	/** {@code /storage web passwort <passwort>}: sets the password for logging in with the player's name. */
	public static int passwordCommand(ServerPlayer player, String password) {
		String problem = setPassword(player.getUUID(), player.getGameProfile().name(), password);

		if (problem != null) {
			player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED));
			return 0;
		}

		player.sendSystemMessage(Component.literal("Passwort gesetzt. Auf der Webseite meldest du dich jetzt mit „" + player.getGameProfile().name()
				+ "“ und diesem Passwort an.").withStyle(ChatFormatting.AQUA));

		if (http != null) {
			String link = baseUrl() + "/";
			player.sendSystemMessage(Component.literal(link)
					.withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(URI.create(link))).withUnderlined(true).withColor(ChatFormatting.YELLOW)));
		}

		return 1;
	}

	/** Keeps the player's current name, so they can log in with it. */
	private static void remember(ServerPlayer player) {
		String name = player.getGameProfile().name();
		Account account = ACCOUNTS.get(player.getUUID());

		if (account == null || !account.name().equals(name)) {
			ACCOUNTS.put(player.getUUID(), account == null ? new Account(name, "", "") : new Account(name, account.salt(), account.hash()));
			saveTokens();
		}
	}

	private static boolean hasPassword(UUID player) {
		Account account = ACCOUNTS.get(player);
		return account != null && account.hasPassword();
	}

	/** Sets the password; returns what is wrong with it, or null. */
	private static String setPassword(UUID player, String name, String password) {
		if (password == null || password.length() < MIN_PASSWORD) {
			return "Das Passwort braucht mindestens " + MIN_PASSWORD + " Zeichen.";
		}

		if (password.length() > MAX_PASSWORD) {
			return "Das Passwort darf höchstens " + MAX_PASSWORD + " Zeichen haben.";
		}

		byte[] salt = randomBytes();
		ACCOUNTS.put(player, new Account(name, HexFormat.of().formatHex(salt), HexFormat.of().formatHex(hash(password, salt))));

		if (tokenOf(player) == null) {
			TOKENS.put(HexFormat.of().formatHex(randomBytes()), player);
		}

		saveTokens();
		return null;
	}

	private static byte[] hash(String password, byte[] salt) {
		try {
			KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
		} catch (Exception e) {
			throw new IllegalStateException("PBKDF2 fehlt", e);
		}
	}

	/** The player's token for a right name and password, otherwise an error JSON (wrong, or locked after several tries). */
	private static String login(JsonObject body) {
		String name = body != null && body.has("name") ? body.get("name").getAsString().strip() : "";
		String password = body != null && body.has("password") ? body.get("password").getAsString() : "";
		String key = name.toLowerCase(Locale.ROOT);
		long now = System.currentTimeMillis();
		long[] failures = FAILURES.get(key);

		if (failures != null && failures[1] > now) {
			return error("Zu viele Versuche. Warte eine Minute.");
		}

		boolean known = false;

		for (Map.Entry<UUID, Account> entry : ACCOUNTS.entrySet()) {
			Account account = entry.getValue();

			if (account.hasPassword() && account.name().equalsIgnoreCase(name) && password.length() <= MAX_PASSWORD) {
				known = true;
				byte[] expected = HexFormat.of().parseHex(account.hash());

				if (MessageDigest.isEqual(expected, hash(password, HexFormat.of().parseHex(account.salt())))) {
					FAILURES.remove(key);
					String token = tokenOf(entry.getKey());

					if (token == null) {
						token = HexFormat.of().formatHex(randomBytes());
						TOKENS.put(token, entry.getKey());
						saveTokens();
					}

					JsonObject result = new JsonObject();
					result.addProperty("token", token);
					result.addProperty("name", account.name());
					return GSON.toJson(result);
				}
			}
		}

		// only names with a password count, so made-up names do not fill the map
		if (known) {
			long[] count = FAILURES.computeIfAbsent(key, k -> new long[2]);
			count[0]++;

			if (count[0] >= MAX_FAILURES) {
				count[0] = 0;
				count[1] = now + LOCK_MS;
			}
		}

		return error("Name oder Passwort falsch. Kein Passwort? Setz eins im Spiel mit /storage web passwort <passwort>.");
	}

	/** {@code /api/account}: the player's name and whether a password is set; POST sets a new password. */
	private static String account(MinecraftServer server, UUID player, JsonObject body) {
		Account account = ACCOUNTS.get(player);
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		String name = online != null ? online.getGameProfile().name() : account != null ? account.name() : "";

		if (body != null) {
			if (name.isEmpty()) {
				return error("Dein Name ist noch nicht bekannt. Gib einmal im Spiel /storage web ein.");
			}

			String problem = setPassword(player, name, body.has("password") ? body.get("password").getAsString() : "");

			if (problem != null) {
				return error(problem);
			}
		}

		JsonObject result = new JsonObject();
		result.addProperty("name", name);
		result.addProperty("password", hasPassword(player));
		return GSON.toJson(result);
	}

	private static String error(String text) {
		JsonObject result = new JsonObject();
		result.addProperty("error", text);
		return GSON.toJson(result);
	}

	private static byte[] randomBytes() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return bytes;
	}

	/**
	 * Start of the link without "/#token". A webAddress with "http://" or "https://" is used as it is, without adding
	 * the port, e.g. behind a reverse proxy or tunnel with HTTPS ("https://mc.example.de:10000").
	 * Otherwise "http://<webAddress or localhost>:<webPort>".
	 */
	static String baseUrl() {
		String address = StorageConfig.get().webAddress.strip();

		if (address.startsWith("http://") || address.startsWith("https://")) {
			while (address.endsWith("/")) {
				address = address.substring(0, address.length() - 1);
			}

			return address;
		}

		return "http://" + (address.isEmpty() ? "localhost" : address) + ":" + port();
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
		ACCOUNTS.clear();

		if (!Files.exists(tokenFile)) {
			return;
		}

		try (Reader reader = Files.newBufferedReader(tokenFile, StandardCharsets.UTF_8)) {
			JsonObject root = GSON.fromJson(reader, JsonObject.class);

			if (root == null) {
				return;
			}

			if (!root.has("tokens")) {
				// up to 0.8.6 the file only held player to token
				root.entrySet().forEach(entry -> TOKENS.put(entry.getValue().getAsString(), UUID.fromString(entry.getKey())));
				return;
			}

			root.getAsJsonObject("tokens").entrySet().forEach(entry -> TOKENS.put(entry.getValue().getAsString(), UUID.fromString(entry.getKey())));

			if (root.has("accounts")) {
				root.getAsJsonObject("accounts").entrySet().forEach(entry -> {
					JsonObject a = entry.getValue().getAsJsonObject();
					ACCOUNTS.put(UUID.fromString(entry.getKey()), new Account(string(a, "name"), string(a, "salt"), string(a, "hash")));
				});
			}
		} catch (Exception e) {
			StorageDimensionMod.LOGGER.error("storagedim-web.json konnte nicht gelesen werden", e);
		}
	}

	private static String string(JsonObject object, String key) {
		return object.has(key) ? object.get(key).getAsString() : "";
	}

	private static synchronized void saveTokens() {
		if (tokenFile == null) {
			return;
		}

		JsonObject root = new JsonObject();
		JsonObject tokens = new JsonObject();
		TOKENS.forEach((token, player) -> tokens.addProperty(player.toString(), token));
		root.add("tokens", tokens);
		JsonObject accounts = new JsonObject();
		ACCOUNTS.forEach((player, account) -> {
			JsonObject a = new JsonObject();
			a.addProperty("name", account.name());
			a.addProperty("salt", account.salt());
			a.addProperty("hash", account.hash());
			accounts.add(player.toString(), a);
		});
		root.add("accounts", accounts);

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

	private static void api(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (exchange.getRequestURI().getPath().equals("/api/login")) {
				if (!exchange.getRequestMethod().equals("POST")) {
					send(exchange, 404, "application/json", "{\"error\":\"Nicht gefunden.\"}");
					return;
				}

				JsonObject body;

				try {
					body = GSON.fromJson(new String(exchange.getRequestBody().readNBytes(MAX_BODY), StandardCharsets.UTF_8), JsonObject.class);
				} catch (Exception e) {
					send(exchange, 400, "application/json", "{\"error\":\"Ungültige Anfrage.\"}");
					return;
				}

				String json = login(body);
				send(exchange, json.startsWith("{\"error\"") ? 403 : 200, "application/json", json);
				return;
			}

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

			String path = exchange.getRequestURI().getPath();
			boolean post = exchange.getRequestMethod().equals("POST");
			JsonObject body;

			try {
				body = post ? GSON.fromJson(new String(exchange.getRequestBody().readNBytes(MAX_BODY), StandardCharsets.UTF_8), JsonObject.class) : null;
			} catch (Exception e) {
				send(exchange, 400, "application/json", "{\"error\":\"Ungültige Anfrage.\"}");
				return;
			}

			java.util.function.Supplier<String> task = switch (path) {
				case "/api/items" -> post ? null : () -> WebContent.items(current, player);
				case "/api/projects" -> post ? () -> WebContent.changeProject(current, player, body) : () -> WebContent.projects(current, player);
				case "/api/catalog" -> post ? null : () -> WebContent.catalog(current);
				case "/api/account" -> () -> account(current, player, body);
				case "/api/stations" -> post ? null : () -> WebRobots.stations(current);
				case "/api/mailboxes" -> post ? () -> WebRobots.changeMailbox(current, body) : () -> WebRobots.mailboxes(current);
				default -> null;
			};

			if (task == null) {
				send(exchange, 404, "application/json", "{\"error\":\"Nicht gefunden.\"}");
				return;
			}

			String json;

			try {
				// Storage is only touched on the server thread.
				json = current.submit(task).get(10, TimeUnit.SECONDS);
			} catch (Exception e) {
				StorageDimensionMod.LOGGER.warn("ME-Webansicht: Anfrage {} fehlgeschlagen", path, e);
				send(exchange, 503, "application/json", "{\"error\":\"Server antwortet gerade nicht.\"}");
				return;
			}

			send(exchange, json.startsWith("{\"error\"") ? 400 : 200, "application/json", json);
		}
	}

	/**
	 * {@code /icon/<namespace>/<path>}: the texture of a mod item from the jar. Vanilla textures are not on the
	 * server; the page loads those from a public copy of the game assets.
	 */
	private static void icon(HttpExchange exchange) throws IOException {
		try (exchange) {
			String[] parts = exchange.getRequestURI().getPath().substring("/icon/".length()).split("/", 2);

			if (parts.length == 2 && parts[0].matches("[a-z0-9_.-]+") && parts[1].matches("[a-z0-9_./-]+") && !parts[1].contains("..")) {
				for (String folder : new String[] { "item/", "block/" }) {
					for (String suffix : new String[] { "", "_side", "_top" }) {
						try (InputStream in = WebView.class.getResourceAsStream("/assets/" + parts[0] + "/textures/" + folder + parts[1] + suffix + ".png")) {
							if (in != null) {
								byte[] bytes = in.readAllBytes();
								exchange.getResponseHeaders().set("Content-Type", "image/png");
								exchange.getResponseHeaders().set("Cache-Control", "max-age=86400");
								exchange.sendResponseHeaders(200, bytes.length);

								try (OutputStream out = exchange.getResponseBody()) {
									out.write(bytes);
								}

								return;
							}
						}
					}
				}
			}

			send(exchange, 404, "text/plain", "Kein Bild");
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
}
