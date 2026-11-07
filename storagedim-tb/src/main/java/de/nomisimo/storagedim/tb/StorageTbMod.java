package de.nomisimo.storagedim.tb;

import java.util.List;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
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

		// into Storage Dimension's creative tab, which JEI also takes its item list from; same as the recipe's result
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
				Identifier.fromNamespaceAndPath("storagedim", "items"))).register(output -> output.accept(upgradeStack()));

		MeNetworking.register();
		TbSettings.register();
		PayloadTypeRegistry.serverboundPlay().register(FillGridPayload.TYPE, FillGridPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(FillGridPayload.TYPE, (payload, context) -> GridFiller.fill(context.player(), payload));
	}

	/** Same result as data/storagedim_tb/recipe/me_upgrade.json. */
	public static ItemStack upgradeStack() {
		ItemStack stack = new ItemStack(ME_UPGRADE);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("storagedim_upgrade", true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
				Component.literal("In einen Upgrade-Slot vom Traveler's Backpack legen").withStyle(ChatFormatting.GRAY),
				Component.literal("oder mit einem Bündel kombinieren: ME-Rucksack").withStyle(ChatFormatting.GRAY))));
		return stack;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
