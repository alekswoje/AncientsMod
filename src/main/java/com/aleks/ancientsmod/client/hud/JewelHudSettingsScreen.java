package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import net.minecraft.client.gui.screen.Screen;

/**
 * Per-widget settings popup for {@link JewelHud} — slot layout and how much
 * detail rides under the sockets.
 */
public final class JewelHudSettingsScreen extends WidgetSettingsScreen {

    private final JewelHud jewels;

    public JewelHudSettingsScreen(Screen parent, JewelHud jewels) {
        super(parent, jewels);
        this.jewels = jewels;
    }

    @Override
    protected void addRows() {
        addToggle("Show this HUD",
                FeatureToggles::isJewelHudEnabled, FeatureToggles::setJewelHud);

        addSection("Layout");
        addToggle("Stack slots vertically",
                jewels::vertical,
                v -> HudSettings.setBoolean(jewels.id(), JewelHud.KEY_VERTICAL, v));

        addSection("Detail");
        addToggle("Show unlock requirement",
                jewels::showRequirement,
                v -> HudSettings.setBoolean(jewels.id(), JewelHud.KEY_SHOW_REQUIREMENT, v));
        addToggle("Show socketed stats",
                jewels::showStats,
                v -> HudSettings.setBoolean(jewels.id(), JewelHud.KEY_SHOW_STATS, v));
        addToggle("Show while all slots empty",
                jewels::showWhenEmpty,
                v -> HudSettings.setBoolean(jewels.id(), JewelHud.KEY_SHOW_WHEN_EMPTY, v));
        addToggle("Solid slot background",
                jewels::opaque,
                v -> HudSettings.setBoolean(jewels.id(), JewelHud.KEY_OPAQUE, v));

        BoosterHudSettingsScreen.addLookRows(this, jewels);
    }
}
