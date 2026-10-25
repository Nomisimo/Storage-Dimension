package de.nomisimo.storagedim.tb;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sent by the client when the ME upgrade icon in the backpack screen is clicked. */
public record OpenStoragePayload() implements CustomPacketPayload {
	public static final OpenStoragePayload INSTANCE = new OpenStoragePayload();
	public static final CustomPacketPayload.Type<OpenStoragePayload> TYPE = new CustomPacketPayload.Type<>(StorageTbMod.id("open_storage"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenStoragePayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
