package de.nomisimo.storagedim.tb;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds an ME upgrade for Traveler's Backpack. Clicking it in the backpack screen shows the Storage Dimension storage in place of the backpack slots,
 * and with its switch on "ME als Quelle", JEI's + button also takes crafting ingredients from ME storage.
 */
public class StorageTbMod implements ModInitializer {
	public static final String MOD_ID = "storagedim_tb";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static Item ME_UPGRADE;

	@Override
	public void onInitialize() {
		Identifier id = id("me_upgrade");
		ME_UPGRADE = Registry.register(BuiltInRegistries.ITEM, id,
				new MeUpgradeItem(new Item.Properties().stacksTo(1).setId(ResourceKey.create(Registries.ITEM, id))));

		MeNetworking.register();
		TbSettings.register();
		PayloadTypeRegistry.serverboundPlay().register(FillGridPayload.TYPE, FillGridPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(FillGridPayload.TYPE, (payload, context) -> GridFiller.fill(context.player(), payload));
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
