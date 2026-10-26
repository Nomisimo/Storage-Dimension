package de.nomisimo.storagedim.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * What a robot station sets up: range, item filter, colour and name, plus the station it belongs to.
 * Stored on the station, copied onto robot items and from there onto the placed robot.
 */
public record RobotConfig(int range, int color, String name, List<ItemStack> filter, Optional<String> stationDim, Optional<BlockPos> station) {
	public static final RobotConfig DEFAULT = new RobotConfig(RobotEntity.RANGE, RobotEntity.DEFAULT_COLOR, "", List.of(), Optional.empty(), Optional.empty());
	public static final Codec<RobotConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.optionalFieldOf("range", RobotEntity.RANGE).forGetter(RobotConfig::range),
			Codec.INT.optionalFieldOf("color", RobotEntity.DEFAULT_COLOR).forGetter(RobotConfig::color),
			Codec.STRING.optionalFieldOf("name", "").forGetter(RobotConfig::name),
			ItemStack.CODEC.listOf().optionalFieldOf("filter", List.of()).forGetter(RobotConfig::filter),
			Codec.STRING.optionalFieldOf("stationDim").forGetter(RobotConfig::stationDim),
			BlockPos.CODEC.optionalFieldOf("station").forGetter(RobotConfig::station)
	).apply(i, RobotConfig::new));
	private static final String KEY = "storagedim_robot";
	private static final Map<String, String> COLOR_NAMES = Map.ofEntries(
			Map.entry("white", "Weiß"), Map.entry("orange", "Orange"), Map.entry("magenta", "Magenta"),
			Map.entry("light_blue", "Hellblau"), Map.entry("yellow", "Gelb"), Map.entry("lime", "Hellgrün"),
			Map.entry("pink", "Rosa"), Map.entry("gray", "Grau"), Map.entry("light_gray", "Hellgrau"),
			Map.entry("cyan", "Türkis"), Map.entry("purple", "Violett"), Map.entry("blue", "Blau"),
			Map.entry("brown", "Braun"), Map.entry("green", "Grün"), Map.entry("red", "Rot"),
			Map.entry("black", "Schwarz"));

	public RobotConfig withRange(int range) {
		return new RobotConfig(range, color, name, filter, stationDim, station);
	}

	public RobotConfig withColor(int color) {
		return new RobotConfig(range, color, name, filter, stationDim, station);
	}

	public RobotConfig withName(String name) {
		return new RobotConfig(range, color, name, filter, stationDim, station);
	}

	public RobotConfig withFilter(List<ItemStack> filter) {
		return new RobotConfig(range, color, name, List.copyOf(filter), stationDim, station);
	}

	public RobotConfig withStation(String dim, BlockPos pos) {
		return new RobotConfig(range, color, name, filter, Optional.of(dim), Optional.of(pos));
	}

	/** The config written on a robot item, or null for a plain one. */
	public static RobotConfig read(ItemStack stack, HolderLookup.Provider registries) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return null;
		}

		CompoundTag tag = data.copyTag();
		Tag config = tag.get(KEY);
		return config == null ? null
				: CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), config).result().orElse(null);
	}

	/** Writes the config onto a robot item and lists it in the tooltip. */
	public void write(ItemStack stack, HolderLookup.Provider registries) {
		CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), this).result().ifPresent(tag -> {
			CompoundTag root = new CompoundTag();
			root.put(KEY, tag);
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		});

		List<Component> lore = new ArrayList<>();

		if (!name.isEmpty()) {
			lore.add(line("Name: ", Component.literal(name).withStyle(ChatFormatting.WHITE)));
		}

		lore.add(line("Station: ", Component.literal(station.map(p -> p.getX() + " " + p.getY() + " " + p.getZ()).orElse("keine"))
				.withStyle(ChatFormatting.WHITE)));
		lore.add(line("Reichweite: ", Component.literal(rangeText(range)).withStyle(ChatFormatting.WHITE)));
		lore.add(line("Farbe: ", Component.literal(colorName(color)).withStyle(s -> s.withColor(color))));
		MutableComponent items = Component.empty();

		if (filter.isEmpty()) {
			items.append(Component.literal("alles").withStyle(ChatFormatting.WHITE));
		}

		for (int i = 0; i < filter.size(); i++) {
			items.append(i == 0 ? Component.empty() : Component.literal(", ")).append(filter.get(i).getHoverName().copy().withStyle(ChatFormatting.WHITE));
		}

		lore.add(line("Sammelt: ", items));
		stack.set(DataComponents.LORE, new ItemLore(lore));
	}

	private static Component line(String label, Component value) {
		return Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)).append(value);
	}

	public static String rangeText(int range) {
		return range / 16 + (range == 16 ? " Chunk" : " Chunks") + " (" + range + " Blöcke)";
	}

	/** Robot colours: the default orange and the 16 dyes. */
	public static int[] colors() {
		int[] colors = new int[DyeColor.values().length + 1];
		colors[0] = RobotEntity.DEFAULT_COLOR;

		for (DyeColor dye : DyeColor.values()) {
			colors[dye.ordinal() + 1] = dye.getTextureDiffuseColor() & 0xFFFFFF;
		}

		return colors;
	}

	public static DyeColor dyeFor(int color) {
		for (DyeColor dye : DyeColor.values()) {
			if ((dye.getTextureDiffuseColor() & 0xFFFFFF) == color) {
				return dye;
			}
		}

		return null;
	}

	public static String colorName(int color) {
		DyeColor dye = dyeFor(color);
		return dye == null ? "Standard (orange)" : COLOR_NAMES.getOrDefault(dye.getName(), dye.getName());
	}
}
