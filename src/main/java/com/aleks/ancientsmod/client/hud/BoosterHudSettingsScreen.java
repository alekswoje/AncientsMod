package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import net.minecraft.client.gui.screen.Screen;

/**
 * Per-widget settings popup for {@link BoosterHud}. Currently just the
 * "Collapse when matching" toggle; new toggles append to {@link #addRows()}
 * without further wiring.
 */
public final class BoosterHudSettingsScreen extends WidgetSettingsScreen {

    private final BoosterHud booster;

    public BoosterHudSettingsScreen(Screen parent, BoosterHud booster) {
        super(parent, booster);
        this.booster = booster;
    }

    @Override
    protected void addRows() {
        addToggle("Show this HUD",
                FeatureToggles::isBoosterHudEnabled, FeatureToggles::setBoosterHud);
        addSection("Display");
        addToggle("Collapse matching boosters",
                () -> HudSettings.getBoolean(booster.id(), BoosterHud.KEY_COLLAPSE, true),
                v -> HudSettings.setBoolean(booster.id(), BoosterHud.KEY_COLLAPSE, v));
        addLookRows(booster);
    }

    /** Shared look-customization rows for any HUD widget. */
    static void addLookRows(WidgetSettingsScreen screen, HudElement el) {
        screen.addSection("Look");
        screen.addToggle("Always show",
                () -> HudSettings.getBoolean(el.id(), HudStyle.KEY_ALWAYS_SHOW, false),
                v -> HudSettings.setBoolean(el.id(), HudStyle.KEY_ALWAYS_SHOW, v));
        screen.addToggle("Hide header",
                () -> HudSettings.getBoolean(el.id(), HudStyle.KEY_HIDE_HEADER, false),
                v -> HudSettings.setBoolean(el.id(), HudStyle.KEY_HIDE_HEADER, v));
        screen.addToggle("Hide border",
                () -> HudSettings.getBoolean(el.id(), HudStyle.KEY_HIDE_BORDER, false),
                v -> HudSettings.setBoolean(el.id(), HudStyle.KEY_HIDE_BORDER, v));
        screen.addToggle("Compact mode",
                () -> HudSettings.getBoolean(el.id(), HudStyle.KEY_COMPACT, false),
                v -> HudSettings.setBoolean(el.id(), HudStyle.KEY_COMPACT, v));
        screen.addSlider("Background opacity %", 0, 100,
                () -> HudSettings.getInt(el.id(), HudStyle.KEY_BG_OPACITY, HudStyle.DEFAULT_OPACITY),
                v -> HudSettings.setInt(el.id(), HudStyle.KEY_BG_OPACITY, v));
    }

    private void addLookRows(HudElement el) {
        addLookRows(this, el);
    }
}
