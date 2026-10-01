package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.hud.HudEditScreen;
import com.aleks.ancientsmod.client.hud.WidgetSettingsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Top-level AncientsMod options screen (F9). Built on {@link WidgetSettingsScreen} in its
 * sidebar layout: each section is a category on the left, the right pane shows one category,
 * and the search box finds a setting in any category. Edits persist to
 * {@code config/ancientsmod.properties} immediately.
 *
 * <p>Only the toggles players actually change session-to-session live here. The
 * set-and-forget features (custom screens, scrollable tooltips, item wiki, peaceful mining,
 * item lock, etc.) sit behind the <b>Advanced settings</b> row in {@link AdvancedSettingsScreen}.
 */
public final class SettingsScreen extends WidgetSettingsScreen {

    public SettingsScreen(Screen parent) {
        super(parent,
                Text.literal("AncientsMod"),
                Text.literal("Search the list or pick a category"));
    }

    @Override
    protected int buttonWidth() {
        return Math.min(260, this.width - 150);
    }

    @Override
    protected boolean useSidebar() {
        return true;
    }

    @Override
    protected void addRows() {
        addSection("General");
        addToggle("Light theme",
                FeatureToggles::isGlassLightThemeEnabled, FeatureToggles::setGlassLightTheme);
        addToggle("Update alert on server join",
                FeatureToggles::isUpdateAlertEnabled, FeatureToggles::setUpdateAlert);
        addAction("Sound and particle muffler", () -> {
            if (this.client != null) this.client.setScreen(new MufflerScreen(this));
        });
        addAction("Advanced settings", () -> {
            if (this.client != null) this.client.setScreen(new AdvancedSettingsScreen(this));
        });

        addSection("HUDs");
        addToggle("Booster HUD",
                FeatureToggles::isBoosterHudEnabled, FeatureToggles::setBoosterHud);
        addToggle("Events HUD",
                FeatureToggles::isEventsHudEnabled, FeatureToggles::setEventsHud);
        addToggle("Cooldowns HUD",
                FeatureToggles::isCooldownsHudEnabled, FeatureToggles::setCooldownsHud);
        addToggle("Stats HUD",
                FeatureToggles::isStatsHudEnabled, FeatureToggles::setStatsHud);
        addToggle("Outpost HUD",
                FeatureToggles::isOutpostHudEnabled, FeatureToggles::setOutpostHud);
        addToggle("Armor durability HUD",
                FeatureToggles::isArmorDurabilityHudEnabled, FeatureToggles::setArmorDurabilityHud);
        addToggle("Clock HUD",
                FeatureToggles::isClockHudEnabled, FeatureToggles::setClockHud);
        addToggle("Saturation on hunger bar",
                FeatureToggles::isSaturationOverlayEnabled, FeatureToggles::setSaturationOverlay);
        addToggle("Meteorite count on block",
                FeatureToggles::isMeteoriteHudEnabled, FeatureToggles::setMeteoriteHud);
        addAction("Edit HUD positions", () -> {
            if (this.client != null) this.client.setScreen(new HudEditScreen(this));
        });

        addSection("Item display");
        addToggle("Amount on currency items",
                FeatureToggles::isCurrencyAmountOverlayEnabled, FeatureToggles::setCurrencyAmountOverlay);
        addToggle("Level and prestige on gear",
                FeatureToggles::isGearStatsOverlayEnabled, FeatureToggles::setGearStatsOverlay);
        addToggle("Multiplier and time on boosters",
                FeatureToggles::isBoosterInfoOverlayEnabled, FeatureToggles::setBoosterInfoOverlay);
        addToggle("Percent on enchant dust",
                FeatureToggles::isDustPercentOverlayEnabled, FeatureToggles::setDustPercentOverlay);
        addToggle("Combine energy button in inventory",
                FeatureToggles::isCombineButtonEnabled, FeatureToggles::setCombineButton);

        addSection("Tooltips");
        addToggle("Collapse enchants on gear",
                FeatureToggles::isEnchantCollapseEnabled, FeatureToggles::setEnchantCollapse);

        addSection("Chat");
        addToggle("Hover a message to copy it",
                FeatureToggles::isChatCopyEnabled, FeatureToggles::setChatCopy);

        addSection("PvP");
        addToggle("Peaceful PvP",
                FeatureToggles::isPeacefulPvpEnabled, FeatureToggles::setPeacefulPvp);

        addSection("Camera");
        addToggle("Hold C to zoom",
                FeatureToggles::isZoomEnabled, FeatureToggles::setZoom);
        addSlider("Zoom FOV %", 10, 70,
                FeatureToggles::getZoomFovPercent, FeatureToggles::setZoomFovPercent);

        addSection("World");
        addToggle("Excavation artifact effects",
                FeatureToggles::isExcavationEffectsEnabled, FeatureToggles::setExcavationEffects);
        addToggle("Mining rush pings",
                FeatureToggles::isMiningRushPingsEnabled, FeatureToggles::setMiningRushPings);
        addToggle("Meteorite shower pings",
                FeatureToggles::isMeteoriteShowerPingsEnabled, FeatureToggles::setMeteoriteShowerPings);
        addToggle("Erebus tear pings",
                FeatureToggles::isTearPingsEnabled, FeatureToggles::setTearPings);
        addToggle("Rift texture pack",
                FeatureToggles::isRiftTexturePackEnabled, FeatureToggles::setRiftTexturePack);

        addSection("Screens");
        addToggle("Cell terminal on vault chest",
                FeatureToggles::isCellTerminalEnabled, FeatureToggles::setCellTerminal);
    }
}
