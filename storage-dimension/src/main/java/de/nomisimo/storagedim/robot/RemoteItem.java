package de.nomisimo.storagedim.robot;

import java.util.List;

import de.nomisimo.storagedim.StorageDimensionMod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;

/**
 * The remote for antenna areas. Held in either hand it shows the outlines of the areas around; right-click opens
 * its screen, where areas are switched on and off and its auto mode is set: then the areas a player with this
 * remote stands in pause, and their robots wait at their station until the player has left.
 */
public class RemoteItem extends Item {
	public static final ResourceKey<Item> KEY = ResourceKey.create(Registries.ITEM, StorageDimensionMod.id("remote"));
	public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, KEY, new RemoteItem(new Item.Properties().stacksTo(1).setId(KEY)));
	private static final String AUTO = "storagedim_remote_auto";

	public RemoteItem(Properties properties) {
		super(properties);
	}

	/** Loads the class, which registers the item. */
	public static void register() {
	}

	public static boolean isRemote(ItemStack stack) {
		return stack.is(ITEM);
	}

	public static boolean isAuto(ItemStack stack) {
		if (!isRemote(stack)) {
			return false;
		}

		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(AUTO, false);
	}

	public static void setAuto(ItemStack stack, boolean auto) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(AUTO, auto);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Auto-Modus: ")
				.withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false))
				.append(Component.literal(auto ? "an (Areas pausieren, in denen du stehst)" : "aus").withStyle(auto ? ChatFormatting.GREEN : ChatFormatting.WHITE)))));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer serverPlayer) {
			Areas.open(serverPlayer, hand);
		}

		return InteractionResult.SUCCESS;
	}

	/** Shines while its auto mode is on. */
	@Override
	public boolean isFoil(ItemStack stack) {
		return isAuto(stack) || super.isFoil(stack);
	}
}
