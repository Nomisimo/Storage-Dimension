package de.nomisimo.storagedim.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Mod Menu shows a settings button for the mod that opens {@link StorageConfigScreen}. Only loaded when Mod Menu is installed. */
@Environment(EnvType.CLIENT)
public class StorageModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return StorageConfigScreen::new;
	}
}
