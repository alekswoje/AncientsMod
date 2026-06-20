package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ItemLocks;
import com.aleks.prisonsmod.client.hud.WidgetSettingsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Advanced PrisonsMod toggles — the "set-and-forget" features that ~everyone
 * leaves on, moved off the main {@link SettingsScreen} so the F9 list stays
 * short. Same scrollable/searchable base + wide layout as the main screen;
 * closing returns to whatever opened it (the main settings screen).
 *
 * <p>Everything here is on by default. The toggles still work exactly as before
 * — they're just one click further away.
 */
public final class AdvancedSettingsScreen extends WidgetSettingsScreen {

    public AdvancedSettingsScreen(Screen parent) {
        super(parent,
                Text.literal("PrisonsMod — Advanced"),
                Text.literal("On by default · most players leave these alone"));
    }

    @Override
    protected int buttonWidth() {
        // Match the main settings screen's width for a consistent feel.
        return Math.min(320, this.width - 60);
    }

    @Override
    protected void addRows() {
        addSection("Mining");
        addToggle("Mine-crack prediction",
                FeatureToggles::isMinePredictEnabled, FeatureToggles::setMinePredict);
        addToggle("Peaceful mining",
                FeatureToggles::isPeacefulMiningEnabled, FeatureToggles::setPeacefulMining);

        addSection("Tooltips");
        addToggle("Scrollable tooltips",
                FeatureToggles::isScrollableTooltipsEnabled, FeatureToggles::setScrollableTooltips);
        addToggle("Item wiki (Alt to pin tooltip, click terms)",
                FeatureToggles::isItemWikiEnabled, FeatureToggles::setItemWiki);
        addToggle("Block breakdown on pickaxes",
                FeatureToggles::isPickaxeBlocksEnabled, FeatureToggles::setPickaxeBlocks);

        addSection("Custom Screens");
        addToggle("Bug-report UI on /bugreport",
                FeatureToggles::isBugReportUiEnabled, FeatureToggles::setBugReportUi);
        addToggle("PV terminal view on /pv",
                FeatureToggles::isPvTerminalEnabled, FeatureToggles::setPvTerminal);
        addToggle("Auto-focus PV terminal search",
                FeatureToggles::isPvTerminalAutoFocusSearchEnabled, FeatureToggles::setPvTerminalAutoFocusSearch);
        addToggle("Loot browser on /loottables",
                FeatureToggles::isLootBrowserEnabled, FeatureToggles::setLootBrowser);

        addSection("Network");
        addToggle("Auto-rejoin after kick",
                FeatureToggles::isAutoRejoinEnabled, FeatureToggles::setAutoRejoin);

        addSection("Inventory");
        addToggle("Item lock",
                FeatureToggles::isItemLockEnabled, FeatureToggles::setItemLock);
        addAction("Clear all locked slots", ItemLocks::clear);
    }
}
