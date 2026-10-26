package de.nomisimo.storagedim.tb;

import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

/** Called from {@code BackpackMenuMixin}; keeps {@link MeView} package-private. */
public final class MeViewHooks {
	private MeViewHooks() {
	}

	public static boolean click(BackpackBaseMenu menu, int slotIndex, int button, ContainerInput input, Player player) {
		MeView view = MeView.of(menu);
		return view != null && view.click(slotIndex, button, input, player);
	}

	public static void beforeSlotChange(BackpackBaseMenu menu) {
		MeView view = MeView.of(menu);

		if (view != null) {
			view.restore();
		}
	}

	public static void afterSlotChange(BackpackBaseMenu menu) {
		MeView view = MeView.of(menu);

		if (view != null) {
			view.apply();
		}
	}
}
