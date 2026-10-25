package de.nomisimo.storagedim.tb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Calls into Storage Dimension's {@code StorageApi} by reflection, so this add-on does not need its classes to compile.
 */
final class MeBridge {
	private static final String API = "de.nomisimo.storagedim.api.StorageApi";
	private static Map<String, Method> methods;

	private MeBridge() {
	}

	static void openStorage(ServerPlayer player) {
		call("openStorage", player);
	}

	static ItemStack findMatching(ServerPlayer player, Predicate<ItemStack> filter) {
		return call("findMatching", player, filter) instanceof ItemStack stack ? stack : ItemStack.EMPTY;
	}

	static long count(ServerPlayer player, ItemStack prototype) {
		return call("count", player, prototype) instanceof Long value ? value : 0;
	}

	static ItemStack extract(ServerPlayer player, ItemStack prototype, int amount) {
		return call("extract", player, prototype, amount) instanceof ItemStack stack ? stack : ItemStack.EMPTY;
	}

	/** Empties the stack into storage. Whatever is left (e.g. on error) stays in the stack. */
	static void insert(ServerPlayer player, ItemStack stack) {
		call("insert", player, stack);
	}

	private static Object call(String name, Object... args) {
		try {
			if (methods == null) {
				Class<?> api = Class.forName(API);
				Map<String, Method> found = new HashMap<>();
				found.put("openStorage", api.getMethod("openStorage", ServerPlayer.class));
				found.put("findMatching", api.getMethod("findMatching", ServerPlayer.class, Predicate.class));
				found.put("count", api.getMethod("count", ServerPlayer.class, ItemStack.class));
				found.put("extract", api.getMethod("extract", ServerPlayer.class, ItemStack.class, int.class));
				found.put("insert", api.getMethod("insert", ServerPlayer.class, ItemStack.class));
				methods = found;
			}

			return methods.get(name).invoke(null, args);
		} catch (InvocationTargetException e) {
			StorageTbMod.LOGGER.error("Fehler im ME-Speicher", e.getCause());
		} catch (ReflectiveOperationException e) {
			StorageTbMod.LOGGER.error("Storage Dimension ist nicht erreichbar", e);
		}

		return null;
	}
}
