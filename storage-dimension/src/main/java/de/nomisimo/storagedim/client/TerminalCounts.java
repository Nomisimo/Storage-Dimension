package de.nomisimo.storagedim.client;

import java.util.Locale;

import org.joml.Matrix3x2fStack;

import de.nomisimo.storagedim.storage.StorageMenu;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Draws the amount of a terminal icon in half-size digits, shortened to k, M, G … from 1000 on. */
@Environment(EnvType.CLIENT)
public final class TerminalCounts {
	private static final String[] UNITS = {"k", "M", "G", "T", "P", "E"};

	private TerminalCounts() {
	}

	/** The exact amount the server put on a terminal icon, or -1 for any other item. */
	public static long count(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? -1 : data.copyTag().getLongOr(StorageMenu.COUNT_KEY, -1);
	}

	/** Up to 999 in full, then 1k, 1.2k, 12k, 123k, 1.2M and so on (rounded down, so 999 never shows as 1k). */
	static String format(long count) {
		if (count < 1000) {
			return Long.toString(count);
		}

		double value = count;
		int unit = -1;

		while (value >= 1000 && unit < UNITS.length - 1) {
			value /= 1000;
			unit++;
		}

		String number = value < 10 ? String.format(Locale.ROOT, "%.1f", Math.floor(value * 10) / 10) : Long.toString((long) value);
		return (number.endsWith(".0") ? number.substring(0, number.length() - 2) : number) + UNITS[unit];
	}

	public static void draw(GuiGraphicsExtractor graphics, Font font, long count, int x, int y) {
		if (count <= 1) {
			return;
		}

		String text = format(count);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		// bottom right corner of the slot, like the normal count
		pose.translate(x + 16, y + 12);
		pose.scale(0.5F, 0.5F);
		graphics.text(font, text, -font.width(text), 0, 0xFFFFFFFF, true);
		pose.popMatrix();
	}
}
