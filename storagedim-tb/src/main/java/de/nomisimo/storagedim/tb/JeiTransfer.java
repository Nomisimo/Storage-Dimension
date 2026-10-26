package de.nomisimo.storagedim.tb;

import java.util.ArrayList;
import java.util.List;

import com.tiviacz.travelersbackpack.common.ServerActions;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.inventory.upgrades.crafting.CraftingUpgrade;
import com.tiviacz.travelersbackpack.network.ServerboundActionTagPacket;

import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Client side of the JEI recipe transfer with ME storage, called from {@code JeiTransferMixin}. */
@Environment(EnvType.CLIENT)
public final class JeiTransfer {
	/** Returned when JEI's own answer stands. */
	public static final Object JEI_DECIDES = new Object();

	private JeiTransfer() {
	}

	/**
	 * Decides "+" for the backpack crafting grid when the backpack has the crafting and the ME upgrade: the recipe can be
	 * moved when backpack, inventory, crafting grid and (with "ME als Quelle") ME storage hold every ingredient at once.
	 * Returns {@code null} (allowed, and with {@code doTransfer} the server fills the grid), an error marking the missing
	 * ingredients, or {@link #JEI_DECIDES} when the backpack has no ME upgrade.
	 */
	public static Object handle(AbstractContainerMenu container, IRecipeSlotsView view, Player player, boolean max, boolean doTransfer,
			IRecipeTransferError jeiError) {
		if (!(container instanceof BackpackBaseMenu menu)) {
			return JEI_DECIDES;
		}

		MeUpgrade me = menu.getWrapper().getUpgradeManager().getUpgrade(MeUpgrade.class).orElse(null);
		CraftingUpgrade crafting = menu.getWrapper().getUpgradeManager().getUpgrade(CraftingUpgrade.class).orElse(null);
		List<IRecipeSlotView> inputs = view.getSlotViews(RecipeIngredientRole.INPUT);
		boolean meView = MeView.isOpen(menu);
		boolean useMe = me != null && me.isEnabled(me);

		// without the ME upgrade, or with it on "nur Inventar" and the backpack showing, JEI works as usual
		if (me == null || crafting == null || inputs.size() != 9 || !useMe && !meView) {
			return JEI_DECIDES;
		}

		// JEI could not even work out the recipe (not a missing item): leave that answer alone
		if (jeiError != null && jeiError.getType() == IRecipeTransferError.Type.INTERNAL) {
			return jeiError;
		}

		IngredientPool pool = new IngredientPool();
		player.getInventory().getNonEquipmentItems().forEach(pool::add);
		menu.getWrapper().getStorage().stacks.forEach(pool::add);

		if (crafting.craftSlots != null) {
			crafting.craftSlots.getItems().forEach(pool::add);
		}

		if (useMe) {
			List<ItemStack> items = StorageTbClient.stockItems;
			List<Long> counts = StorageTbClient.stockCounts;

			for (int i = 0; i < Math.min(items.size(), counts.size()); i++) {
				pool.add(items.get(i), counts.get(i));
			}
		}

		List<IRecipeSlotView> missing = new ArrayList<>();
		List<List<ItemStack>> slots = new ArrayList<>();

		for (IRecipeSlotView input : inputs) {
			List<ItemStack> wanted = input.getItemStacks().filter(stack -> !stack.isEmpty()).limit(64).map(ItemStack::copy).toList();
			slots.add(wanted);

			if (!wanted.isEmpty() && pool.take(wanted).isEmpty()) {
				missing.add(input);
			}
		}

		if (!missing.isEmpty()) {
			Component message = Component.translatable("jei.tooltip.error.recipe.transfer.missing");

			if (TbJeiPlugin.transferHelper != null) {
				return TbJeiPlugin.transferHelper.createUserErrorForMissingSlots(message, missing);
			}

			return jeiError != null ? jeiError : JEI_DECIDES;
		}

		if (!ClientPlayNetworking.canSend(FillGridPayload.TYPE)) {
			return JEI_DECIDES;
		}

		if (doTransfer) {
			if (!crafting.isTabOpened()) {
				ServerboundActionTagPacket.create(ServerboundActionTagPacket.UPGRADE_TAB, crafting.getDataHolderSlot(), true, ServerActions.TAB_OPEN);
			}

			ClientPlayNetworking.send(new FillGridPayload(slots, max));
		}

		return null;
	}
}
