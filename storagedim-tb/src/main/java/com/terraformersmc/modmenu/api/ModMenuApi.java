package com.terraformersmc.modmenu.api;

/**
 * Compile-time copy of Mod Menu's API interface, so the add-on can offer its settings screen without depending on
 * Mod Menu. Left out of the jar (see build.gradle): at run time Mod Menu's own class is used, if it is installed.
 */
public interface ModMenuApi {
	default ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> null;
	}
}
