package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import net.minecraft.client.gui.screen.Screen;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-widget settings popup for {@link EventsHud}. Each event key gets its own
 * ON/OFF toggle wired to the persisted enabled-set.
 */
public final class EventsHudSettingsScreen extends WidgetSettingsScreen {

    private final EventsHud events;

    public EventsHudSettingsScreen(Screen parent, EventsHud events) {
        super(parent, events);
        this.events = events;
    }

    @Override
    protected void addRows() {
        addToggle("Show this HUD",
                FeatureToggles::isEventsHudEnabled, FeatureToggles::setEventsHud);
        addSection("Visible events");
        for (String key : EventsHud.ALL_KEYS) {
            addToggle("Show " + EventsHud.displayNameFor(key),
                    () -> events.enabledKeys().contains(key),
                    v -> {
                        Set<String> current = new LinkedHashSet<>(events.enabledKeys());
                        if (v) current.add(key); else current.remove(key);
                        HudSettings.setStringSet(events.id(), EventsHud.KEY_ENABLED, current);
                    });
        }
        BoosterHudSettingsScreen.addLookRows(this, events);
    }
}
