package de.nomisimo.barden;

import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The rare items that make a performer stop. Each one is crafted from overworld items,
 * see data/barden/recipe/token_*.json. They carry custom_data {barden_token: "<id>"}.
 */
public final class Tokens {
	public static final String TAG = "barden_token";

	public record Token(String id, String name) {
	}

	public static final List<Token> ALL = List.of(
			new Token("ohrstoepsel", "Ohrstöpsel"),
			new Token("stimmgabel", "Goldene Stimmgabel"),
			new Token("schweigegeld", "Schweigegeld-Scheck"),
			new Token("schallplatte", "Zerkratzte Schallplatte"),
			new Token("autogramm", "Signierte Autogrammkarte"));

	private Tokens() {
	}

	public static Token byId(String id) {
		for (Token token : ALL) {
			if (token.id().equals(id)) {
				return token;
			}
		}

		return ALL.getFirst();
	}

	public static String idOf(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);

		if (data == null || !data.copyTag().contains(TAG)) {
			return null;
		}

		CompoundTag tag = data.copyTag();
		return tag.getStringOr(TAG, null);
	}
}
