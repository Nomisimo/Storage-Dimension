package de.nomisimo.storagedim.client;

import de.nomisimo.storagedim.StorageConfig;
import de.nomisimo.storagedim.robot.Robots;
import de.nomisimo.storagedim.storage.StorageMenu;
import de.nomisimo.storagedim.storage.TerminalNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Optional client part: draws a search field in the top left of the ME terminal window, where the title would be,
 * with the sort button right next to it and, with JEI, a switch that couples the search with JEI's search bar.
 * Without this on the client the terminal still works with its item buttons.
 */
@Environment(EnvType.CLIENT)
public class StorageDimensionClient implements ClientModInitializer {
	// the vanilla 6-row chest screen
	private static final int IMAGE_WIDTH = 176;
	private static final int IMAGE_HEIGHT = 222;

	/** Container id of the terminal the server told us about, or -1. */
	private static int terminalId = -1;
	private static StorageMenu.Sort sort = StorageMenu.Sort.AMOUNT;
	private static Button sortButton;
	private static Button syncButton;
	private static SearchBox searchBox;
	private static final boolean JEI = FabricLoader.getInstance().isModLoaded("jei");

	@Override
	public void onInitializeClient() {
		ModelLayerRegistry.registerModelLayer(RobotModel.LAYER, RobotModel::createBodyLayer);
		EntityRendererRegistry.register(Robots.ROBOT, RobotRenderer::new);
		ClientChestMarks.register();
		ClientPlayNetworking.registerGlobalReceiver(TerminalNetworking.OpenedPayload.TYPE, (payload, context) -> {
			terminalId = payload.containerId();
			sort = parse(payload.sort());
			Screen screen = context.client().screen;

			// the screen usually opened just before this packet arrived
			if (isTerminal(screen)) {
				addWidgets(screen);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(TerminalNetworking.SortedPayload.TYPE, (payload, context) -> {
			sort = parse(payload.sort());

			if (sortButton != null) {
				sortButton.setMessage(label(sort));
			}
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (isTerminal(screen)) {
				addWidgets(screen);
			}

			// with the search sync on, text typed into JEI's search bar also searches the terminal
			ScreenEvents.afterTick(screen).register(current -> {
				if (JEI && StorageConfig.get().jeiSearchSync && isTerminal(current) && searchBox != null) {
					String jei = StorageJeiPlugin.searchText();

					if (jei != null && !jei.equals(searchBox.getValue())) {
						searchBox.setValue(jei);
					}
				}
			});
		});
	}

	private static boolean isTerminal(Screen screen) {
		return terminalId >= 0 && screen instanceof MenuAccess<?> access && access.getMenu().containerId == terminalId;
	}

	private static void addWidgets(Screen screen) {
		int left = (screen.width - IMAGE_WIDTH) / 2;
		int top = (screen.height - IMAGE_HEIGHT) / 2;

		// re-initialising (e.g. after resizing) must not add a second set
		Screens.getWidgets(screen).removeIf(widget -> widget instanceof SearchBox || widget == sortButton || widget == syncButton);

		// with JEI there is a small switch between search field and sort button
		SearchBox search = new SearchBox(Minecraft.getInstance(), left + 7, top + 4, JEI ? 94 : 108, 12);
		search.setMaxLength(50);
		search.setHint(Component.literal("Suchen …"));
		search.setResponder(text -> {
			ClientPlayNetworking.send(new TerminalNetworking.SearchPayload(text));

			if (JEI && StorageConfig.get().jeiSearchSync) {
				StorageJeiPlugin.setSearchText(text);
			}
		});
		searchBox = search;
		Screens.getWidgets(screen).add(search);

		if (JEI) {
			syncButton = Button.builder(syncLabel(), button -> {
				StorageConfig config = StorageConfig.get();
				config.jeiSearchSync = !config.jeiSearchSync;
				StorageConfig.save();
				button.setMessage(syncLabel());
				button.setTooltip(syncTooltip());

				if (config.jeiSearchSync) {
					String jei = StorageJeiPlugin.searchText();

					// JEI's search wins when it has one, otherwise the terminal's goes to JEI
					if (jei != null && !jei.isEmpty()) {
						search.setValue(jei);
					} else {
						StorageJeiPlugin.setSearchText(search.getValue());
					}
				}
			}).bounds(left + 103, top + 4, 13, 12).tooltip(syncTooltip()).build();
			Screens.getWidgets(screen).add(syncButton);

			String jei = StorageConfig.get().jeiSearchSync ? StorageJeiPlugin.searchText() : null;

			if (jei != null && !jei.isEmpty()) {
				search.setValue(jei);
			}
		}

		sortButton = Button.builder(label(sort), button -> ClientPlayNetworking.send(TerminalNetworking.SortPayload.INSTANCE))
				.bounds(left + 118, top + 4, 51, 12)
				.build();
		Screens.getWidgets(screen).add(sortButton);
	}

	private static Component syncLabel() {
		return Component.literal("J").withStyle(StorageConfig.get().jeiSearchSync ? ChatFormatting.GREEN : ChatFormatting.GRAY);
	}

	private static Tooltip syncTooltip() {
		return Tooltip.create(Component.literal(StorageConfig.get().jeiSearchSync
				? "Suche mit JEI gekoppelt: an\nKlicken zum Trennen"
				: "Suche mit JEI gekoppelt: aus\nKlicken zum Koppeln"));
	}

	private static StorageMenu.Sort parse(String name) {
		try {
			return StorageMenu.Sort.valueOf(name);
		} catch (IllegalArgumentException e) {
			return StorageMenu.Sort.AMOUNT;
		}
	}

	private static Component label(StorageMenu.Sort sort) {
		return Component.literal(sort == StorageMenu.Sort.NAME ? "A–Z" : "Menge");
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
