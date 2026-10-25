package de.nomisimo.storagedim.storage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * ME backpacks: bundles or Traveler's Backpacks linked to the storage with an ME upgrade.
 * Traveler's Backpack is optional and only touched through reflection.
 */
public final class Backpacks {
	private static final String TB_MOD = "travelersbackpack";
	private static final String TB_ITEM = "com.tiviacz.travelersbackpack.item.TravelersBackpackItem";
	private static Method wearingBackpack;
	private static boolean lookedUp;

	private Backpacks() {
	}

	/**
	 * Right-click with an item in hand.
	 * - ME upgrade in the off hand + sneaking with a bundle or Traveler's Backpack: links it.
	 * - Linked bundle: opens the storage. Linked Traveler's Backpack: opens it when sneaking
	 *   (a normal right-click still opens the backpack itself).
	 */
	public static InteractionResult onUseItem(ServerPlayer player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);

		if (hand == InteractionHand.MAIN_HAND && player.isShiftKeyDown() && StorageItems.isUpgrade(player.getOffhandItem())
				&& canLink(stack) && !StorageItems.isBackpack(stack)) {
			link(stack);
			player.getOffhandItem().shrink(1);
			player.sendSystemMessage(Component.literal("Rucksack ist jetzt mit dem ME-Speicher verbunden.").withStyle(ChatFormatting.LIGHT_PURPLE));
			return InteractionResult.SUCCESS;
		}

		if (!StorageItems.isBackpack(stack)) {
			return InteractionResult.PASS;
		}

		if (isTravelersBackpack(stack) && !player.isShiftKeyDown()) {
			return InteractionResult.PASS;
		}

		StorageMenu.open(player, StorageData.get().networkFor(player.getUUID()));
		return InteractionResult.SUCCESS;
	}

	/** For /storage me: a linked backpack worn on the back or anywhere in the inventory. */
	public static boolean hasLinkedBackpack(ServerPlayer player) {
		if (StorageItems.isBackpack(wornTravelersBackpack(player))) {
			return true;
		}

		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (StorageItems.isBackpack(player.getInventory().getItem(i))) {
				return true;
			}
		}

		return false;
	}

	static boolean canLink(ItemStack stack) {
		return stack.is(ItemTags.BUNDLES) || isTravelersBackpack(stack);
	}

	static void link(ItemStack stack) {
		CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
		stack.set(DataComponents.CUSTOM_DATA, data.update(tag -> tag.putBoolean(StorageItems.BACKPACK_TAG, true)));
		ItemLore old = stack.get(DataComponents.LORE);
		List<Component> lines = new ArrayList<>(old == null ? List.of() : old.lines());
		lines.add(Component.literal("ME-verbunden").withStyle(ChatFormatting.LIGHT_PURPLE));
		stack.set(DataComponents.LORE, new ItemLore(lines));
	}

	static boolean isTravelersBackpack(ItemStack stack) {
		for (Class<?> c = stack.getItem().getClass(); c != null; c = c.getSuperclass()) {
			if (c.getName().equals(TB_ITEM)) {
				return true;
			}
		}

		return false;
	}

	private static ItemStack wornTravelersBackpack(Player player) {
		if (!lookedUp) {
			lookedUp = true;

			if (FabricLoader.getInstance().isModLoaded(TB_MOD)) {
				try {
					wearingBackpack = Class.forName("com.tiviacz.travelersbackpack.attachment.AttachmentUtils")
							.getMethod("getWearingBackpack", Player.class);
				} catch (ReflectiveOperationException e) {
					wearingBackpack = null;
				}
			}
		}

		if (wearingBackpack == null) {
			return ItemStack.EMPTY;
		}

		try {
			Object result = wearingBackpack.invoke(null, player);
			return result instanceof ItemStack stack ? stack : ItemStack.EMPTY;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}
}
