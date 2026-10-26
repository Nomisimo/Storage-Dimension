package de.nomisimo.storagedim.client;

import java.util.HashMap;
import java.util.Map;

import de.nomisimo.storagedim.StorageDimensionMod;
import de.nomisimo.storagedim.storage.ChestMarks;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Which chests around the player are dimension chests, filter chests or ME terminals, and their textures. */
public final class ClientChestMarks {
	private enum Kind {
		STORAGE, FILTER, TERMINAL
	}

	private static final SpriteId STORAGE = sprite("dimension_chest");
	private static final SpriteId FILTER = sprite("filter_chest");
	private static final SpriteId FILTER_LEFT = sprite("filter_chest_left");
	private static final SpriteId FILTER_RIGHT = sprite("filter_chest_right");
	private static final SpriteId TERMINAL = sprite("me_terminal");

	private static String dimension = "";
	private static Map<Long, Kind> marks = new HashMap<>();

	private ClientChestMarks() {
	}

	private static SpriteId sprite(String name) {
		return Sheets.CHEST_MAPPER.apply(StorageDimensionMod.id(name));
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ChestMarks.MarksPayload.TYPE, (payload, context) -> {
			Map<Long, Kind> next = new HashMap<>();
			payload.storage().forEach(pos -> next.put(pos, Kind.STORAGE));
			payload.filter().forEach(pos -> next.put(pos, Kind.FILTER));
			payload.terminal().forEach(pos -> next.put(pos, Kind.TERMINAL));
			dimension = payload.dimension();
			marks = next;
		});
	}

	/** Our texture for the chest at this position, or null to draw it normally. */
	public static SpriteId spriteFor(ChestRenderState state) {
		Minecraft minecraft = Minecraft.getInstance();
		BlockPos pos = state.blockPos;

		if (pos == null || minecraft.level == null || marks.isEmpty()
				|| !minecraft.level.dimension().identifier().toString().equals(dimension)) {
			return null;
		}

		Kind kind = marks.get(pos.asLong());

		if (kind == null) {
			return null;
		}

		return switch (kind) {
			case STORAGE -> STORAGE;
			case TERMINAL -> TERMINAL;
			case FILTER -> state.type == ChestType.LEFT ? FILTER_LEFT : state.type == ChestType.RIGHT ? FILTER_RIGHT : FILTER;
		};
	}
}
