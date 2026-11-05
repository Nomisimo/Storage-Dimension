package de.nomisimo.storagedim.robot;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import de.nomisimo.storagedim.storage.Rooms;
import de.nomisimo.storagedim.storage.StorageData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/**
 * The antenna as an item. Laid into a robot station's input slot it comes out paired with that station; only a
 * paired antenna can be placed. Placed right on top of its station it is the station's own antenna, placed anywhere
 * else it marks the opposite corner of the work area.
 */
public class AntennaItem extends BlockItem {
	private static final String KEY = "storagedim_antenna";

	public AntennaItem(Block block, Properties properties) {
		super(block, properties);
	}

	/** The station an antenna item is paired with. */
	public record Link(String dim, BlockPos station, String stationName) {
		public static final Codec<Link> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("dim").forGetter(Link::dim),
				BlockPos.CODEC.fieldOf("station").forGetter(Link::station),
				Codec.STRING.optionalFieldOf("stationName", "").forGetter(Link::stationName)
		).apply(i, Link::new));
	}

	public static Link link(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null) {
			return null;
		}

		Tag tag = data.copyTag().get(KEY);
		return tag == null ? null : Link.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
	}

	/** Pairs the antenna item with a station and says so in its tooltip. */
	public static void pair(ItemStack stack, Link link) {
		Link.CODEC.encodeStart(NbtOps.INSTANCE, link).result().ifPresent(tag -> {
			CompoundTag root = new CompoundTag();
			root.put(KEY, tag);
			stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		});

		BlockPos p = link.station();
		String pos = p.getX() + " " + p.getY() + " " + p.getZ();
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("Station: ").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
						.append(Component.literal(link.stationName().isEmpty() ? pos : link.stationName() + " (" + pos + ")").withStyle(ChatFormatting.WHITE)),
				Component.literal("Auf die Station setzen, dann eine zweite als Gegenecke").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(true)))));
	}

	@Override
	public InteractionResult place(BlockPlaceContext context) {
		if (!(context.getLevel() instanceof ServerLevel level)) {
			return super.place(context);
		}

		Link link = link(context.getItemInHand());
		BlockPos pos = context.getClickedPos();
		String dim = Rooms.id(level);
		String problem = problem(level, dim, link, pos);

		if (problem != null) {
			if (context.getPlayer() instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.literal(problem).withStyle(ChatFormatting.RED), true);
			}

			return InteractionResult.FAIL;
		}

		InteractionResult result = super.place(context);

		if (result.consumesAction() && level.getBlockState(pos).is(AntennaBlock.BLOCK)) {
			StorageData data = StorageData.get();
			BlockPos station = link.station();
			StationArea area = data.stationArea(dim, station);
			boolean base = station.above().equals(pos);
			area = base ? area.withBase(true) : area.withCorner(pos);
			data.setStationArea(dim, station, area);

			if (context.getPlayer() instanceof ServerPlayer player) {
				player.sendSystemMessage(Component.literal(area.connected()
						? "Antenne verbunden: Die Roboter arbeiten jetzt im Bereich " + area.sizeText(station) + "."
						: base ? "Antenne auf der Station. Jetzt die zweite Antenne als Gegenecke setzen."
						: "Gegenecke gesetzt. Jetzt noch eine Antenne direkt auf die Station setzen.")
						.withStyle(ChatFormatting.GREEN), true);
			}
		}

		return result;
	}

	/** Why the antenna cannot go here, or null when it can. */
	private static String problem(ServerLevel level, String dim, Link link, BlockPos pos) {
		if (link == null) {
			return "Leg die Antenne erst in eine Roboter-Station, dort wird sie gepaart.";
		}

		BlockPos station = link.station();

		if (!link.dim().equals(dim) || level.isLoaded(station) && !level.getBlockState(station).is(RobotStationBlock.BLOCK)) {
			return "Die Station dieser Antenne steht nicht (mehr) in dieser Welt. Paare sie neu.";
		}

		if (pos.equals(station)) {
			return "Auf den Platz der Station kann keine Antenne.";
		}

		StationArea area = StorageData.get().stationArea(dim, station);

		if (station.above().equals(pos)) {
			return null;
		}

		if (area.corner().isPresent() && !area.corner().get().equals(pos) && level.getBlockState(area.corner().get()).is(AntennaBlock.BLOCK)) {
			BlockPos c = area.corner().get();
			return "Diese Station hat schon eine Gegenecke (" + c.getX() + " " + c.getY() + " " + c.getZ() + "). Bau sie erst ab.";
		}

		if (StationArea.tooFar(station, pos)) {
			return "Zu weit von der Station: höchstens " + StationArea.MAX_SIZE + " Blöcke in jede Richtung.";
		}

		return null;
	}
}
