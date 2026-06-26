package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.client.FeatureToggles;
import net.minecraft.client.gui.screen.Screen;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Per-widget settings for {@link StatsHud}. Two layers:
 * <ul>
 *   <li>Section toggles (world / hunter / mining / blocks / kills / drops) — show or hide whole blocks</li>
 *   <li>Per-mob whitelist for the kills section — empty = show everything,
 *       otherwise hide anything not in the set</li>
 * </ul>
 */
public final class StatsHudSettingsScreen extends WidgetSettingsScreen {

    private final StatsHud stats;

    public StatsHudSettingsScreen(Screen parent, StatsHud stats) {
        super(parent, stats);
        this.stats = stats;
    }

    @Override
    protected void addRows() {
        addToggle("Show this HUD",
                FeatureToggles::isStatsHudEnabled, FeatureToggles::setStatsHud);
        addSection("Sections");
        // Section toggles.
        for (String section : StatsHud.ALL_SECTIONS) {
            String label = switch (section) {
                case "world"  -> "Show world name";
                case "hunter" -> "Show Hunter XP (XP/h + session total)";
                case "mining" -> "Show mining (XP/h, Energy/h, $/h)";
                case "session" -> "Show mining session (/miningtrack totals)";
                case "blocks" -> "Show blocks (per-ore counts)";
                case "kills"  -> "Show kills section";
                case "drops"  -> "Show drops section (by rarity)";
                default       -> "Show " + section;
            };
            addToggle(label,
                    () -> stats.enabledSections().contains(section),
                    v -> {
                        Set<String> current = new LinkedHashSet<>(stats.enabledSections());
                        if (v) current.add(section); else current.remove(section);
                        HudSettings.setStringSet(stats.id(), StatsHud.KEY_SECTIONS, current);
                        // Re-notify the server so it can flip the action-bar
                        // XP/h / Energy/h / $/h trio off (or back on) to match.
                        if ("mining".equals(section)) {
                            com.aleks.prisonsmod.net.NetworkHandler.sendMiningHudState(
                                    StatsHud.isMiningEffectivelyEnabled());
                        }
                    });
        }

        addSection("Mining session");
        addToggle("Show session-average rate",
                stats::sessionShowAvg,
                v -> HudSettings.setBoolean(stats.id(), StatsHud.KEY_SESSION_SHOW_AVG, v));
        addToggle("Also show live rolling rate",
                stats::sessionShowLive,
                v -> HudSettings.setBoolean(stats.id(), StatsHud.KEY_SESSION_SHOW_LIVE, v));

        addSection("Blocks");
        addToggle("Count blocks this session",
                stats::blocksSessionMode,
                v -> HudSettings.setBoolean(stats.id(), StatsHud.KEY_BLOCKS_SESSION, v));
        // Per-ore visibility — curated default set; toggle which ores show.
        for (String ore : StatsHud.CANDIDATE_BLOCKS) {
            addToggle("Show " + StatsHud.prettyOre(ore),
                    () -> stats.visibleBlocks().contains(ore),
                    v -> {
                        Set<String> current = new LinkedHashSet<>(stats.visibleBlocks());
                        if (v) current.add(ore); else current.remove(ore);
                        HudSettings.setStringSet(stats.id(), StatsHud.KEY_VISIBLE_BLOCKS, current);
                    });
        }

        addSection("Visible kills");
        // Per-mob visibility — empty set means "show every kind".
        for (String mob : StatsHud.KILL_ORDER) {
            addToggle("Show " + StatsHud.killDisplayName(mob) + " kills",
                    () -> {
                        Set<String> wl = stats.visibleKills();
                        return wl.isEmpty() || wl.contains(mob);
                    },
                    v -> {
                        Set<String> current = new LinkedHashSet<>(stats.visibleKills());
                        // Switch from "empty=show all" to an explicit set the first
                        // time the player flips any toggle. Once explicit, removing
                        // last entry leaves "show none".
                        if (current.isEmpty() && !v) {
                            for (String key : StatsHud.KILL_ORDER) current.add(key);
                            current.remove(mob);
                        } else {
                            if (v) current.add(mob); else current.remove(mob);
                        }
                        HudSettings.setStringSet(stats.id(), StatsHud.KEY_VISIBLE_KILLS, current);
                    });
        }

        BoosterHudSettingsScreen.addLookRows(this, stats);
    }
}
