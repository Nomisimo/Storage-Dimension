package de.nomisimo.storagedim.client;

import java.util.List;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Our JEI plugin. It no longer has anything to do with recipes or the item list: all our items are real items
 * (see {@link de.nomisimo.storagedim.storage.StorageItems}) with plain vanilla crafting recipes, so JEI finds them
 * on its own, like those of any other mod. On a dedicated server Fabric API's recipe sync brings the recipes to the
 * client ({@link de.nomisimo.storagedim.RecipeSync}).
 *
 * <p>What is left is the screen side: items dragged from JEI onto the project list, JEI's list keeping clear of the
 * robot list, and JEI's search bar for the ME terminal's search sync. The game works the same without JEI.
 */
public class StorageJeiPlugin implements IModPlugin {
	private static IJeiRuntime runtime;

	@Override
	public Identifier getPluginUid() {
		return Identifier.fromNamespaceAndPath("storagedim", "jei");
	}

	/** JEI's item list keeps clear of the robot list beside the robot station window. */
	@Override
	public void registerGuiHandlers(IGuiHandlerRegistration registration) {
		registration.addGhostIngredientHandler(ProjectScreen.class, PROJECT_DROP);
		registration.addGuiContainerHandler(StationScreen.class, new IGuiContainerHandler<StationScreen>() {
			@Override
			public List<Rect2i> getGuiExtraAreas(StationScreen screen) {
				return List.of(screen.listArea());
			}
		});
	}

	/** Items dragged from JEI's list onto the project settings' list join the project. */
	private static final IGhostIngredientHandler<ProjectScreen> PROJECT_DROP = new IGhostIngredientHandler<>() {
		@Override
		public <I> List<Target<I>> getTargetsTyped(ProjectScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
			var stack = ingredient.getIngredient(VanillaTypes.ITEM_STACK);

			if (stack.isEmpty()) {
				return List.of();
			}

			Rect2i area = screen.listArea();
			ItemStack item = stack.get().copyWithCount(1);
			return List.of(new Target<I>() {
				@Override
				public Rect2i getArea() {
					return area;
				}

				@Override
				public void accept(I dropped) {
					screen.addFromJei(item);
				}
			});
		}

		@Override
		public void onComplete() {
		}
	};

	/** For the client test: drops an item on the project list the way JEI does at the end of a drag. Returns the number of targets. */
	public static int dropOnProject(ProjectScreen screen, ItemStack stack) {
		if (runtime == null) {
			return -1;
		}

		var typed = runtime.getIngredientManager().createTypedIngredient(VanillaTypes.ITEM_STACK, stack);

		if (typed.isEmpty()) {
			return -2;
		}

		var targets = PROJECT_DROP.getTargetsTyped(screen, typed.get(), true);
		targets.forEach(target -> target.accept(stack));
		PROJECT_DROP.onComplete();
		return targets.size();
	}

	@Override
	public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
		runtime = jeiRuntime;
	}

	@Override
	public void onRuntimeUnavailable() {
		runtime = null;
	}

	/** Whether JEI's search bar has the keyboard focus. */
	static boolean searchFocused() {
		return runtime != null && runtime.getIngredientListOverlay().hasKeyboardFocus();
	}

	/** The text in JEI's search bar, or null while JEI is not ready. */
	static String searchText() {
		return runtime == null ? null : runtime.getIngredientFilter().getFilterText();
	}

	static void setSearchText(String text) {
		if (runtime != null && !text.equals(runtime.getIngredientFilter().getFilterText())) {
			runtime.getIngredientFilter().setFilterText(text);
		}
	}
}
