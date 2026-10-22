package de.nomisimo.storagedim.extra;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Picker that opens on sneak + right-click on another player while holding emeralds.
 */
public class GiftMenu extends ChestMenu {
	private final ServerPlayer target;
	private final List<ExtraConfig.Character> characters;

	private GiftMenu(int containerId, Inventory inventory, SimpleContainer display, ServerPlayer target) {
		super(MenuType.GENERIC_9x6, containerId, inventory, display, 6);
		this.target = target;
		this.characters = new ArrayList<>(ExtraConfig.get().characters);

		for (int i = 0; i < Math.min(54, characters.size()); i++) {
			ExtraConfig.Character c = characters.get(i);
			ItemStack icon = new ItemStack(Items.JUKEBOX);
			icon.set(DataComponents.CUSTOM_NAME, Component.literal(c.name).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.LIGHT_PURPLE)));
			icon.set(DataComponents.LORE, new ItemLore(List.of(
					Component.literal("♪ " + c.song).withStyle(ChatFormatting.GRAY),
					Component.literal(c.cost + " Emeralds").withStyle(ChatFormatting.GREEN))));
			display.setItem(i, icon);
		}
	}

	public static void open(ServerPlayer player, ServerPlayer target) {
		player.openMenu(new SimpleMenuProvider(
				(id, inventory, p) -> new GiftMenu(id, inventory, new SimpleContainer(54), target),
				Component.literal("Ständchen für " + target.getGameProfile().name())));
	}

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
		if (slotIndex >= 0 && slotIndex < characters.size() && slotIndex < 54 && player instanceof ServerPlayer sender) {
			buy(sender, characters.get(slotIndex));
		}

		sendAllDataToRemote();
	}

	private void buy(ServerPlayer sender, ExtraConfig.Character character) {
		Inventory inv = sender.getInventory();

		if (target.isRemoved()) {
			sender.closeContainer();
			return;
		}

		if (countEmeralds(inv) < character.cost) {
			sender.sendSystemMessage(Component.literal("Du brauchst " + character.cost + " Emeralds.").withStyle(ChatFormatting.RED));
			return;
		}

		if (!Performers.book(sender, target, character)) {
			sender.sendSystemMessage(Component.literal("Für " + target.getGameProfile().name() + " singen schon genug.").withStyle(ChatFormatting.RED));
			return;
		}

		removeEmeralds(inv, character.cost);
		sender.sendSystemMessage(Component.literal(character.name + " ist unterwegs zu " + target.getGameProfile().name() + ".")
				.withStyle(ChatFormatting.LIGHT_PURPLE));
		sender.closeContainer();
	}

	private static int countEmeralds(Inventory inv) {
		int count = 0;

		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(Items.EMERALD)) {
				count += inv.getItem(i).getCount();
			}
		}

		return count;
	}

	private static void removeEmeralds(Inventory inv, int amount) {
		for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
			ItemStack stack = inv.getItem(i);

			if (stack.is(Items.EMERALD)) {
				int take = Math.min(amount, stack.getCount());
				stack.shrink(take);
				amount -= take;
			}
		}

		inv.setChanged();
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		return ItemStack.EMPTY;
	}
}
