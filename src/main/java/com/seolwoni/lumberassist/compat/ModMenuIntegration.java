package com.seolwoni.lumberassist.compat;

import com.seolwoni.lumberassist.gui.LumberConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return LumberConfigScreen::new;
	}
}
