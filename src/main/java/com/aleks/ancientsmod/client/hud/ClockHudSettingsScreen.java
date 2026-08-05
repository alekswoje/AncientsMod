package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import net.minecraft.client.gui.screen.Screen;

/**
 * Per-widget settings popup for {@link ClockHud} — clock format and what extra
 * bits ride along with the time.
 */
public final class ClockHudSettingsScreen extends WidgetSettingsScreen {

    private final ClockHud clock;

    public ClockHudSettingsScreen(Screen parent, ClockHud clock) {
        super(parent, clock);
        this.clock = clock;
    }

    @Override
    protected void addRows() {
        addToggle("Show this HUD",
                FeatureToggles::isClockHudEnabled, FeatureToggles::setClockHud);

        addSection("Format");
        addToggle("24-hour clock",
                clock::use24Hour,
                v -> HudSettings.setBoolean(clock.id(), ClockHud.KEY_24H, v));
        addToggle("Show seconds",
                clock::showSeconds,
                v -> HudSettings.setBoolean(clock.id(), ClockHud.KEY_SECONDS, v));
        addToggle("Show timezone",
                clock::showZone,
                v -> HudSettings.setBoolean(clock.id(), ClockHud.KEY_SHOW_ZONE, v));
        addToggle("Show date",
                clock::showDate,
                v -> HudSettings.setBoolean(clock.id(), ClockHud.KEY_SHOW_DATE, v));

        BoosterHudSettingsScreen.addLookRows(this, clock);
    }
}
