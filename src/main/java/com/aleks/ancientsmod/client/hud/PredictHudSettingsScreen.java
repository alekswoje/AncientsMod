package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.render.MinePredictRenderer;
import net.minecraft.client.gui.screen.Screen;

/**
 * Per-widget settings popup for {@link PredictHud} — visibility, the rollback
 * log toggle, and a counter reset.
 */
public final class PredictHudSettingsScreen extends WidgetSettingsScreen {

    public PredictHudSettingsScreen(Screen parent, PredictHud hud) {
        super(parent, hud);
    }

    @Override
    protected void addRows() {
        addToggle("Show this HUD",
                FeatureToggles::isPredictHudEnabled, FeatureToggles::setPredictHud);

        addSection("Diagnostics");
        addToggle("Log each rollback to the game log",
                MinePredictRenderer::isDebugLog, MinePredictRenderer::setDebugLog);
        addAction("Reset counters", MinePredictRenderer::resetStats);

        BoosterHudSettingsScreen.addLookRows(this, PredictHud.INSTANCE);
    }
}
