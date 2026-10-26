package de.nomisimo.storagedim.tb;

import java.util.List;

import com.tiviacz.travelersbackpack.client.screens.BackpackScreen;
import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;

import de.nomisimo.storagedim.tb.mixin.ScreenTitleAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of the ME view in the backpack window: the window title, a search field above the window (coupled with
 * JEI's search bar when the terminal's "J" switch is on), and the stored-item list JEI's "+" checks against.
 */
@Environment(EnvType.CLIENT)
public class StorageTbClient implements ClientModInitializer {
	private static final boolean JEI = FabricLoader.getInstance().isModLoaded("jei");
	private static final int SEARCH_WIDTH = 120;

	/** What ME storage holds, as last sent by the server while a backpack with the ME upgrade was open. */
	static volatile List<ItemStack> stockItems = List.of();
	static volatile List<Long> stockCounts = List.of();
	/** The backpack window's own title while it is renamed for the ME view. */
	private static Component backpackTitle;
	private static SearchBox searchBox;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(MeNetworking.ViewPayload.TYPE, (payload, context) -> {
			if (context.player().containerMenu instanceof BackpackBaseMenu menu && menu.containerId == payload.containerId()) {
				MeView.set(menu, payload.on(), true);

				if (context.client().screen instanceof BackpackScreen screen && screen.getMenu() == menu) {
					// the window is called "Storage Dimension" while it shows ME storage
					if (payload.on()) {
						backpackTitle = screen.getTitle();
						((ScreenTitleAccessor) screen).storagedimTb$setTitle(Component.literal("Storage Dimension"));
					} else if (backpackTitle != null) {
						((ScreenTitleAccessor) screen).storagedimTb$setTitle(backpackTitle);
					}

					// a fresh ME view starts without a search
					searchBox = null;
					updateSearch(screen, payload.on());
				}
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(MeNetworking.StockPayload.TYPE, (payload, context) -> {
			stockItems = payload.items();
			stockCounts = payload.counts();
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof BackpackScreen backpack) {
				// Traveler's Backpack re-initialises its screen when upgrades change; the field must come back
				updateSearch(backpack, MeView.isOpen(backpack.getMenu()));

				// the backpack screen only draws its own widget types, so the field is drawn here
				ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, partialTick) -> {
					if (searchBox != null && Screens.getWidgets(current).contains(searchBox)) {
						searchBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
					}
				});

				// with the search sync on, text typed into JEI's search bar also searches ME storage
				ScreenEvents.afterTick(screen).register(current -> {
					if (searchBox != null && Screens.getWidgets(current).contains(searchBox) && jeiSync()) {
						String jei = TbJeiPlugin.searchText();

						if (jei != null && !jei.equals(searchBox.getValue())) {
							searchBox.setValue(jei);
						}
					}
				});
			}
		});
	}

	/** Shows the search field above the window while the ME view is on, and removes it otherwise. */
	private static void updateSearch(BackpackScreen screen, boolean on) {
		Screens.getWidgets(screen).removeIf(widget -> widget instanceof SearchBox);

		if (!on) {
			searchBox = null;
			return;
		}

		String text = searchBox != null ? searchBox.getValue() : "";
		SearchBox search = new SearchBox(Minecraft.getInstance(), screen.getGuiLeft(), screen.getGuiTop() - 14, SEARCH_WIDTH, 12);
		search.setMaxLength(MeView.MAX_SEARCH);
		search.setHint(Component.literal("Storage Dimension durchsuchen …"));
		search.setValue(text);
		search.setResponder(value -> {
			ClientPlayNetworking.send(new MeNetworking.SearchPayload(value));

			if (jeiSync()) {
				TbJeiPlugin.setSearchText(value);
			}
		});
		searchBox = search;
		Screens.getWidgets(screen).add(search);

		String jei = jeiSync() ? TbJeiPlugin.searchText() : null;

		if (jei != null && !jei.isEmpty()) {
			search.setValue(jei);
		}
	}

	/** The "J" switch of the ME terminal (Storage Dimension's config), only with JEI. */
	private static boolean jeiSync() {
		if (!JEI) {
			return false;
		}

		try {
			Object config = Class.forName("de.nomisimo.storagedim.StorageConfig").getMethod("get").invoke(null);
			return config.getClass().getField("jeiSearchSync").getBoolean(config);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	/** A search field that keeps every key while focused, so typing "e" or a number does not close the screen or swap items. */
	private static final class SearchBox extends EditBox {
		SearchBox(Minecraft minecraft, int x, int y, int width, int height) {
			super(minecraft.font, x, y, width, height, Component.literal("Suchen"));
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (super.keyPressed(event)) {
				return true;
			}

			return isFocused() && isVisible() && event.key() != GLFW.GLFW_KEY_ESCAPE;
		}
	}
}
