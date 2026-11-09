package de.nomisimo.storagedim.client;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * Only one search field has the keyboard at a time. A click into JEI's search bar does not reach the screen, so our
 * own search fields (ME terminal, backpack ME view, station and project windows) used to stay focused. JEI then
 * thinks the screen is typing and only looks at a key after the screen, as a shortcut: "o" hid JEI (its Ctrl+O),
 * other letters triggered other shortcuts. As soon as JEI's search has the focus, ours let go of it.
 *
 * <p>Runs before JEI's own key handling on every screen (an earlier event phase than JEI's), and once per tick.
 */
final class JeiSearchFocus {
	private static final Identifier BEFORE_JEI = Identifier.fromNamespaceAndPath("storagedim", "before_jei");

	private JeiSearchFocus() {
	}

	static void register() {
		ScreenEvents.BEFORE_INIT.addPhaseOrdering(BEFORE_JEI, Event.DEFAULT_PHASE);
		ScreenEvents.BEFORE_INIT.register(BEFORE_JEI, (client, screen, width, height) -> {
			ScreenKeyboardEvents.allowKeyPress(screen).register((current, event) -> {
				release(current);
				return true;
			});
			ScreenEvents.afterTick(screen).register(JeiSearchFocus::release);
		});
	}

	private static void release(Screen screen) {
		if (!StorageJeiPlugin.searchFocused()) {
			return;
		}

		for (EditBox box : editBoxes(screen.children())) {
			if (box.isFocused() && !isJei(box)) {
				box.setFocused(false);
			}
		}

		if (screen.getFocused() instanceof EditBox box && !isJei(box)) {
			screen.setFocused(null);
		}
	}

	/** JEI's own search field can sit among the screen's children too; it keeps the focus it just got. */
	private static boolean isJei(GuiEventListener listener) {
		return listener.getClass().getName().startsWith("mezz.jei.");
	}

	private static List<EditBox> editBoxes(List<? extends GuiEventListener> children) {
		List<EditBox> boxes = new ArrayList<>();

		for (GuiEventListener child : children) {
			if (child instanceof EditBox box) {
				boxes.add(box);
			} else if (child instanceof ContainerEventHandler container) {
				boxes.addAll(editBoxes(container.children()));
			}
		}

		return boxes;
	}
}
