package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import net.minecraft.client.gui.screen.Screen;

import java.util.Set;

public final class OutpostHudSettingsScreen extends WidgetSettingsScreen {

    private static final Set<String> DEFAULT_VISIBLE = Set.of("chain", "gold", "iron", "diamond", "netherite");

    public OutpostHudSettingsScreen(Screen parent, OutpostHud hud) {
        super(parent, hud);
    }

    @Override
    protected void addRows() {
        addToggle("Show this HUD",
                FeatureToggles::isOutpostHudEnabled, FeatureToggles::setOutpostHud);
        addSection("Visible Outposts");
        for (String id : new String[]{"chain", "gold", "iron", "diamond", "netherite"}) {
            String label = id.substring(0, 1).toUpperCase() + id.substring(1);
            addToggle("Show " + label,
                    () -> HudSettings.getStringSet(OutpostHud.INSTANCE.id(), OutpostHud.KEY_VISIBLE, DEFAULT_VISIBLE).contains(id),
                    v -> HudSettings.toggleSetMember(OutpostHud.INSTANCE.id(), OutpostHud.KEY_VISIBLE, id, DEFAULT_VISIBLE));
        }
        BoosterHudSettingsScreen.addLookRows(this, OutpostHud.INSTANCE);
    }
}
