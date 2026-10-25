package de.nomisimo.storagedim.tb;

import java.lang.reflect.Method;

import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds an ME upgrade for Traveler's Backpack. Clicking it in the backpack screen opens the Storage Dimension terminal.
 */
public class StorageTbMod implements ModInitializer {
	public static final String MOD_ID = "storagedim_tb";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static Item ME_UPGRADE;
	private static Method openStorage;

	@Override
	public void onInitialize() {
		Identifier id = id("me_upgrade");
		ME_UPGRADE = Registry.register(BuiltInRegistries.ITEM, id,
				new MeUpgradeItem(new Item.Properties().stacksTo(1).setId(ResourceKey.create(Registries.ITEM, id))));

		PayloadTypeRegistry.serverboundPlay().register(OpenStoragePayload.TYPE, OpenStoragePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(OpenStoragePayload.TYPE, (payload, context) -> open(context.player()));
	}

	private static void open(ServerPlayer player) {
		if (!(player.containerMenu instanceof BackpackBaseMenu menu)
				|| menu.getWrapper().getUpgradeManager().getUpgrade(MeUpgrade.class).isEmpty()) {
			return;
		}

		try {
			if (openStorage == null) {
				openStorage = Class.forName("de.nomisimo.storagedim.api.StorageApi").getMethod("openStorage", ServerPlayer.class);
			}

			openStorage.invoke(null, player);
		} catch (ReflectiveOperationException e) {
			LOGGER.error("Storage Dimension konnte nicht geöffnet werden", e);
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
