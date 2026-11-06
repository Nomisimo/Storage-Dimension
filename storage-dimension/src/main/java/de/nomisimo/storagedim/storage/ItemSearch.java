package de.nomisimo.storagedim.storage;

import java.util.Locale;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * The text search of the ME terminal and the project item picker. Besides the name and id of an item it also finds
 * potions by their effects and enchanted items and books by their enchantments, e.g. "heilung", "healing" or "sharpness".
 */
public final class ItemSearch {
	private ItemSearch() {
	}

	/** Lower case and trimmed, the form {@link #matches} expects. */
	public static String query(String text) {
		return text.strip().toLowerCase(Locale.ROOT);
	}

	public static boolean matches(ItemStack stack, String query) {
		if (query.isEmpty()) {
			return true;
		}

		if (has(stack.getHoverName().getString(), query) || BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(query)) {
			return true;
		}

		PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);

		if (potion != null) {
			if (potion.potion().isPresent() && potion.potion().get().unwrapKey().map(key -> key.identifier().getPath().contains(query)).orElse(false)) {
				return true;
			}

			for (MobEffectInstance effect : potion.getAllEffects()) {
				if (has(effect.getEffect().value().getDisplayName().getString(), query)) {
					return true;
				}
			}
		}

		return enchanted(stack.get(DataComponents.ENCHANTMENTS), query) || enchanted(stack.get(DataComponents.STORED_ENCHANTMENTS), query);
	}

	private static boolean enchanted(ItemEnchantments enchantments, String query) {
		if (enchantments == null) {
			return false;
		}

		for (Holder<Enchantment> enchantment : enchantments.keySet()) {
			if (has(Enchantment.getFullname(enchantment, enchantments.getLevel(enchantment)).getString(), query)
					|| enchantment.unwrapKey().map(key -> key.identifier().getPath().contains(query)).orElse(false)) {
				return true;
			}
		}

		return false;
	}

	private static boolean has(String text, String query) {
		return text.toLowerCase(Locale.ROOT).contains(query);
	}
}
