package de.nomisimo.storagedim.tb;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Mod Menu shows a settings button for the add-on that opens {@link TbConfigScreen}. Only loaded when Mod Menu is installed. */
@Environment(EnvType.CLIENT)
public class TbModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return TbConfigScreen::new;
	}
}
