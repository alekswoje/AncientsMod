package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.client.FeatureToggles;
import net.minecraft.client.gui.screen.Screen;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-widget settings for {@link CooldownsHud}. One ON/OFF toggle per
 * category (Commands / Combat tags / Enchant procs / Pickaxe abilities).
 */
public final class CooldownsHudSettingsScreen extends WidgetSettingsScreen {

    private final CooldownsHud cooldowns;

    public CooldownsHudSettingsScreen(Screen parent, CooldownsHud cooldowns) {
        super(parent, cooldowns);
        this.cooldowns = cooldowns;
    }

    @Override
    protected void addRows() {
        addToggle("Show this HUD",
                FeatureToggles::isCooldownsHudEnabled, FeatureToggles::setCooldownsHud);

        addSection("Categories");
        for (String cat : CooldownsHud.CATEGORY_KEYS) {
            addToggle("Show " + CooldownsHud.displayNameForCategory(cat),
                    () -> cooldowns.enabledCategories().contains(cat),
                    v -> {
                        Set<String> current = new LinkedHashSet<>(cooldowns.enabledCategories());
                        if (v) current.add(cat); else current.remove(cat);
                        HudSettings.setStringSet(cooldowns.id(), CooldownsHud.KEY_CATEGORIES, current);
                    });
        }

        addSection("Per-cooldown filter");
        // Empty whitelist = show everything in the enabled categories. The first
        // time the player flips one off we switch to an explicit whitelist that
        // excludes just that one entry.
        for (String key : CooldownsHud.ALL_KEYS) {
            addToggle("Show " + CooldownsHud.displayNameForKey(key),
                    () -> {
                        Set<String> wl = cooldowns.visibleCooldowns();
                        return wl.isEmpty() || wl.contains(key);
                    },
                    v -> {
                        Set<String> current = new LinkedHashSet<>(cooldowns.visibleCooldowns());
                        if (current.isEmpty() && !v) {
                            for (String k : CooldownsHud.ALL_KEYS) current.add(k);
                            current.remove(key);
                        } else {
                            if (v) current.add(key); else current.remove(key);
                        }
                        HudSettings.setStringSet(cooldowns.id(), CooldownsHud.KEY_VISIBLE, current);
                    });
        }

        BoosterHudSettingsScreen.addLookRows(this, cooldowns);
    }
}
