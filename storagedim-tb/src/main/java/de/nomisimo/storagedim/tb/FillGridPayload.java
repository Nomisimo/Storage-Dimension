package de.nomisimo.storagedim.tb;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Sent when a JEI recipe is moved into the backpack crafting grid and the ME upgrade is installed.
 * Holds, for each of the 9 grid slots, the items JEI would accept there (empty list = slot stays empty).
 */
public record FillGridPayload(List<List<ItemStack>> slots, boolean max) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<FillGridPayload> TYPE = new CustomPacketPayload.Type<>(StorageTbMod.id("fill_grid"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FillGridPayload> CODEC = StreamCodec.composite(
			ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(64)).apply(ByteBufCodecs.list(9)), FillGridPayload::slots,
			ByteBufCodecs.BOOL, FillGridPayload::max,
			FillGridPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
