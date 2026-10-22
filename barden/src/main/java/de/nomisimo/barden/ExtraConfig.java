package de.nomisimo.barden;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import de.nomisimo.barden.BardenMod;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/barden/barden.json. Created with defaults on first start.
 */
public final class ExtraConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static ExtraConfig instance;

	/** true once the resource pack with song_XX.ogg and skins is installed as server resource pack. */
	public boolean useResourcePack = false;
	/** Volume of each copy; the sound is sent once per audio category. */
	public float volumePerChannel = 0.15f;
	/** Audio categories to play on. Leave all so that only muting everything helps. */
	public List<String> channels = new ArrayList<>(List.of(
			"MASTER", "MUSIC", "RECORDS", "WEATHER", "BLOCKS", "HOSTILE", "NEUTRAL", "PLAYERS", "AMBIENT", "VOICE", "UI"));
	public double followDistance = 2.5;
	public double hearingRange = 24;
	public List<Character> characters = new ArrayList<>();

	public static final class Character {
		public String id;
		public String name;
		public String song;
		public int cost = 5;
		/** Minecraft account whose skin is used when no resource pack is installed. Empty = default skin. */
		public String skinPlayer = "";
		/** Texture in the resource pack, e.g. barden:entity/bard/song_01 -> textures/entity/bard/song_01.png */
		public String skinTexture = "";
		/** "wide" or "slim" */
		public String skinModel = "wide";
		/** Sound event in the resource pack, e.g. barden:bard.song_01 */
		public String sound = "";
		/** Length of the sound file in seconds, it restarts afterwards. */
		public int soundSeconds = 180;

		Character() {
		}

		Character(int nr, String name, String song) {
			String n = String.format("%02d", nr);
			this.id = "song_" + n;
			this.name = name;
			this.song = song;
			this.skinTexture = BardenMod.MOD_ID + ":entity/bard/song_" + n;
			this.sound = BardenMod.MOD_ID + ":bard.song_" + n;
		}
	}

	public static ExtraConfig get() {
		if (instance == null) {
			load();
		}

		return instance;
	}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve(BardenMod.MOD_ID).resolve("barden.json");

		try {
			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					instance = GSON.fromJson(reader, ExtraConfig.class);
				}
			}
		} catch (Exception e) {
			BardenMod.LOGGER.error("barden.json fehlerhaft, nutze Standardwerte", e);
			instance = null;
		}

		if (instance == null || instance.characters == null || instance.characters.isEmpty()) {
			instance = defaults();

			try {
				Files.createDirectories(file.getParent());

				try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
					GSON.toJson(instance, writer);
				}
			} catch (Exception e) {
				BardenMod.LOGGER.error("barden.json konnte nicht geschrieben werden", e);
			}
		}
	}

	public Character find(String id) {
		for (Character c : characters) {
			if (c.id.equals(id)) {
				return c;
			}
		}

		return null;
	}

	private static ExtraConfig defaults() {
		ExtraConfig config = new ExtraConfig();
		String[][] songs = {
				{"Der Wendler", "Egal"},
				{"Schnappi", "Schnappi, das kleine Krokodil"},
				{"Markus Becker", "Das rote Pferd"},
				{"Gottlieb Wendehals", "Polonäse Blankenese"},
				{"Ski Aggu & Otto", "Friesenjung"},
				{"Helene Fischer", "Atemlos durch die Nacht"},
				{"Wolfgang Petry", "Wahnsinn (Hölle, Hölle, Hölle)"},
				{"Scooter", "How Much Is the Fish?"},
				{"Tim Toupet", "Fliegerlied (So ein schöner Tag)"},
				{"Matthias Reim", "Verdammt, ich lieb' dich"},
				{"DJ Ötzi", "Hey Baby (Uhh, Ahh)"},
				{"Heino", "Blau blüht der Enzian"},
				{"Rick Astley", "Never Gonna Give You Up"},
				{"Smash Mouth", "All Star"},
				{"Crazy Frog", "Axel F"},
				{"Coffin Dance", "Astronomia"},
				{"Nyan Cat", "Nyan Cat"},
				{"Darude", "Sandstorm"},
				{"PSY", "Gangnam Style"},
				{"Ylvis", "The Fox (What Does the Fox Say?)"},
				{"Toto", "Africa"},
				{"Vitas", "7th Element"},
				{"Eduard Khil", "Trololo"},
				{"O-Zone", "Dragostea Din Tei"},
				{"Caramell", "Caramelldansen"},
				{"El Chombo", "Chacarron Macarron"},
				{"Rednex", "Cotton Eye Joe"},
				{"Las Ketchup", "Aserejé (The Ketchup Song)"},
				{"Haddaway", "What Is Love"},
				{"Aqua", "Barbie Girl"},
		};

		for (int i = 0; i < songs.length; i++) {
			config.characters.add(new Character(i + 1, songs[i][0], songs[i][1]));
		}

		return config;
	}
}
