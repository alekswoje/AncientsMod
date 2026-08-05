package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import net.minecraft.client.gui.screen.Screen;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-widget settings popup for {@link ArmorDurabilityHud}: which slots to
 * track, how the number reads, and when a row is allowed to appear.
 */
public final class ArmorDurabilityHudSettingsScreen extends WidgetSettingsScreen {

    private final ArmorDurabilityHud armor;

    public ArmorDurabilityHudSettingsScreen(Screen parent, ArmorDurabilityHud armor) {
        super(parent, armor);
        this.armor = armor;
    }

    @Override
    protected void addRows() {
        addToggle("Show this HUD",
                FeatureToggles::isArmorDurabilityHudEnabled, FeatureToggles::setArmorDurabilityHud);

        addSection("Tracked slots");
        for (String key : ArmorDurabilityHud.ALL_SLOTS) {
            addToggle("Show " + ArmorDurabilityHud.displayNameForSlot(key),
                    () -> armor.enabledSlots().contains(key),
                    v -> {
                        Set<String> current = new LinkedHashSet<>(armor.enabledSlots());
                        if (v) current.add(key); else current.remove(key);
                        HudSettings.setStringSet(armor.id(), ArmorDurabilityHud.KEY_SLOTS, current);
                    });
        }

        addSection("Display");
        addToggle("Show percent instead of uses left",
                armor::showPercent,
                v -> HudSettings.setBoolean(armor.id(), ArmorDurabilityHud.KEY_SHOW_PERCENT, v));
        addToggle("Hide pieces at full durability",
                armor::hideFull,
                v -> HudSettings.setBoolean(armor.id(), ArmorDurabilityHud.KEY_HIDE_FULL, v));
        addSlider("Only show below % (0 = always)", 0, 100,
                armor::onlyBelowPercent,
                v -> HudSettings.setInt(armor.id(), ArmorDurabilityHud.KEY_ONLY_BELOW, v));

        BoosterHudSettingsScreen.addLookRows(this, armor);
    }
}
