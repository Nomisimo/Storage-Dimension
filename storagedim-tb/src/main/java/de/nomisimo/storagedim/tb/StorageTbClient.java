package de.nomisimo.storagedim.tb;

import java.util.List;

import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.item.ItemStack;

/** Client side of the ME view in the backpack window, and the stored-item list JEI's "+" checks against. */
@Environment(EnvType.CLIENT)
public class StorageTbClient implements ClientModInitializer {
	/** What ME storage holds, as last sent by the server while a backpack with the ME upgrade was open. */
	static volatile List<ItemStack> stockItems = List.of();
	static volatile List<Long> stockCounts = List.of();

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(MeNetworking.ViewPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof BackpackBaseMenu menu && menu.containerId == payload.containerId()) {
				MeView.set(menu, payload.on(), true);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(MeNetworking.StockPayload.TYPE, (payload, context) -> {
			stockItems = payload.items();
			stockCounts = payload.counts();
		});
	}
}
