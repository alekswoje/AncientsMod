package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.hud.HudEditScreen;
import com.aleks.prisonsmod.client.hud.WidgetSettingsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Top-level PrisonsMod options screen (F9). Built on the same scrollable +
 * searchable + sectioned base as the per-widget settings popups so the whole
 * mod has one consistent settings UX. Each row is a CyclingButtonWidget
 * (ON/OFF) wired to a {@link FeatureToggles} setter; edits persist to
 * {@code config/prisonsmod.properties} immediately.
 *
 * <p>Only the toggles players actually change session-to-session live here. The
 * "set-and-forget" features that ~everyone leaves on (the custom screens,
 * scrollable tooltips, item wiki, peaceful mining, item lock, etc.) are tucked
 * behind the <b>Advanced settings…</b> button into {@link AdvancedSettingsScreen}
 * so this list stays short.
 */
public final class SettingsScreen extends WidgetSettingsScreen {

    public SettingsScreen(Screen parent) {
        super(parent,
                Text.literal("PrisonsMod Settings"),
                Text.literal("Search the list or scroll to find a setting"));
    }

    @Override
    protected int buttonWidth() {
        // Wider than the per-HUD popups so the long F9 list isn't a thin strip,
        // but clamped so it never overruns a small window.
        return Math.min(320, this.width - 60);
    }

    @Override
    protected void addRows() {
        addSection("More");
        addAction("Advanced settings…", () -> {
            if (this.client != null) this.client.setScreen(new AdvancedSettingsScreen(this));
        });

        addSection("Audio & Particles");
        addAction("Sound & particle muffler…", () -> {
            if (this.client != null) this.client.setScreen(new MufflerScreen(this));
        });

        addSection("Appearance");
        addToggle("Light glass theme",
                FeatureToggles::isGlassLightThemeEnabled, FeatureToggles::setGlassLightTheme);

        addSection("Tooltips");
        addToggle("Collapse enchants on gear",
                FeatureToggles::isEnchantCollapseEnabled, FeatureToggles::setEnchantCollapse);

        addSection("Item Display");
        addToggle("Amount on currency items",
                FeatureToggles::isCurrencyAmountOverlayEnabled, FeatureToggles::setCurrencyAmountOverlay);
        addToggle("Level / prestige on gear & picks",
                FeatureToggles::isGearStatsOverlayEnabled, FeatureToggles::setGearStatsOverlay);
        addToggle("Multiplier / duration on boosters",
                FeatureToggles::isBoosterInfoOverlayEnabled, FeatureToggles::setBoosterInfoOverlay);
        addToggle("% on enchant / calcified dust",
                FeatureToggles::isDustPercentOverlayEnabled, FeatureToggles::setDustPercentOverlay);

        addSection("PvP");
        addToggle("Peaceful PvP",
                FeatureToggles::isPeacefulPvpEnabled, FeatureToggles::setPeacefulPvp);

        addSection("HUDs");
        addToggle("Show booster HUD",
                FeatureToggles::isBoosterHudEnabled, FeatureToggles::setBoosterHud);
        addToggle("Show events HUD",
                FeatureToggles::isEventsHudEnabled, FeatureToggles::setEventsHud);
        addToggle("Show cooldowns HUD",
                FeatureToggles::isCooldownsHudEnabled, FeatureToggles::setCooldownsHud);
        addToggle("Show stats HUD",
                FeatureToggles::isStatsHudEnabled, FeatureToggles::setStatsHud);
        addToggle("Show outpost HUD",
                FeatureToggles::isOutpostHudEnabled, FeatureToggles::setOutpostHud);
        addToggle("Show meteorite count on block",
                FeatureToggles::isMeteoriteHudEnabled, FeatureToggles::setMeteoriteHud);
        addAction("Edit HUD positions...", () -> {
            if (this.client != null) this.client.setScreen(new HudEditScreen(this));
        });

        addSection("World");
        addToggle("Fullbright",
                FeatureToggles::isFullbrightEnabled, FeatureToggles::setFullbright);
        addToggle("Mining rush pings",
                FeatureToggles::isMiningRushPingsEnabled, FeatureToggles::setMiningRushPings);
        addToggle("Hot zone indicator",
                FeatureToggles::isHotZoneIndicatorEnabled, FeatureToggles::setHotZoneIndicator);
        addToggle("Rift texture pack",
                FeatureToggles::isRiftTexturePackEnabled, FeatureToggles::setRiftTexturePack);

        addSection("Custom Screens");
        addToggle("Cell terminal on vault chest",
                FeatureToggles::isCellTerminalEnabled, FeatureToggles::setCellTerminal);

        addSection("Network");
        addToggle("Update alert on server join",
                FeatureToggles::isUpdateAlertEnabled, FeatureToggles::setUpdateAlert);
    }
}
