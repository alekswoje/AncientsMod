package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ItemLocks;
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
 */
public final class SettingsScreen extends WidgetSettingsScreen {

    public SettingsScreen(Screen parent) {
        super(parent,
                Text.literal("PrisonsMod Settings"),
                Text.literal("Search the list or scroll to find a setting"));
    }

    @Override
    protected void addRows() {
        addSection("Mining");
        addToggle("Mine-crack prediction",
                FeatureToggles::isMinePredictEnabled, FeatureToggles::setMinePredict);
        addToggle("Peaceful mining",
                FeatureToggles::isPeacefulMiningEnabled, FeatureToggles::setPeacefulMining);

        addSection("Tooltips");
        addToggle("Collapse enchants on gear",
                FeatureToggles::isEnchantCollapseEnabled, FeatureToggles::setEnchantCollapse);
        addToggle("Scrollable tooltips",
                FeatureToggles::isScrollableTooltipsEnabled, FeatureToggles::setScrollableTooltips);

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
        addToggle("Show meteorite count on block",
                FeatureToggles::isMeteoriteHudEnabled, FeatureToggles::setMeteoriteHud);
        addAction("Edit HUD positions...", () -> {
            if (this.client != null) this.client.setScreen(new HudEditScreen(this));
        });

        addSection("World");
        addToggle("Rift texture pack (tints stone + ores white)",
                FeatureToggles::isRiftTexturePackEnabled, FeatureToggles::setRiftTexturePack);

        addSection("Custom Screens");
        addToggle("Bug-report UI on /bugreport",
                FeatureToggles::isBugReportUiEnabled, FeatureToggles::setBugReportUi);
        addToggle("PV overview screen on /pv (no args)",
                FeatureToggles::isPvOverviewEnabled, FeatureToggles::setPvOverview);

        addSection("Network");
        addToggle("Update alert on server join",
                FeatureToggles::isUpdateAlertEnabled, FeatureToggles::setUpdateAlert);
        addToggle("Auto-rejoin after kick (retry every 5s)",
                FeatureToggles::isAutoRejoinEnabled, FeatureToggles::setAutoRejoin);

        addSection("Inventory");
        addToggle("Item lock (Z while hovering blocks drop/move)",
                FeatureToggles::isItemLockEnabled, FeatureToggles::setItemLock);
        addAction("Clear all locked slots", ItemLocks::clear);
    }
}
