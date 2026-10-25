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
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Client side of the JEI recipe transfer with ME storage, called from {@code JeiTransferMixin}. */
@Environment(EnvType.CLIENT)
public final class JeiTransfer {
	private JeiTransfer() {
	}

	/**
	 * Returns true when the backpack has the crafting upgrade and the ME upgrade switched to "ME als Quelle", so JEI should treat the recipe as transferable.
	 * With {@code doTransfer} the server is asked to fill the grid.
	 */
	public static boolean handle(AbstractContainerMenu container, IRecipeSlotsView view, boolean max, boolean doTransfer) {
		if (!(container instanceof BackpackBaseMenu menu)) {
			return false;
		}

		MeUpgrade me = menu.getWrapper().getUpgradeManager().getUpgrade(MeUpgrade.class).orElse(null);

		// switch on the ME upgrade set to "nur Inventar": JEI behaves as without the upgrade
		if (me == null || !me.isEnabled(me)) {
			return false;
		}

		CraftingUpgrade crafting = menu.getWrapper().getUpgradeManager().getUpgrade(CraftingUpgrade.class).orElse(null);
		List<IRecipeSlotView> inputs = view.getSlotViews(RecipeIngredientRole.INPUT);

		if (crafting == null || inputs.size() != 9 || !ClientPlayNetworking.canSend(FillGridPayload.TYPE)) {
			return false;
		}

		if (doTransfer) {
			if (!crafting.isTabOpened()) {
				ServerboundActionTagPacket.create(ServerboundActionTagPacket.UPGRADE_TAB, crafting.getDataHolderSlot(), true, ServerActions.TAB_OPEN);
			}

			List<List<ItemStack>> slots = new ArrayList<>();

			for (IRecipeSlotView input : inputs) {
				slots.add(input.getItemStacks().filter(stack -> !stack.isEmpty()).limit(64).map(ItemStack::copy).toList());
			}

			ClientPlayNetworking.send(new FillGridPayload(slots, max));
		}

		return true;
	}
}
