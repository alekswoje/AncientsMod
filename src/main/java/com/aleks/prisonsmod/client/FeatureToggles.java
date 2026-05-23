package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.PrisonsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side feature toggles. Persists to {@code config/prisonsmod.properties}
 * so A/B tests survive restarts. Each toggle has a sane default so fresh
 * installs behave predictably.
 *
 * <p>Toggles here only gate rendering/display — they never affect what the
 * server emits or does. Turning everything off = mod is effectively invisible.
 */
public final class FeatureToggles {

    private static final String FILE_NAME = "prisonsmod.properties";

    // ── Toggles (defaults below) ─────────────────────────────────────────────

    /** Client-side break-crack prediction. When off, the real server packets drive the crack animation (lag returns, for A/B comparison). */
    private static volatile boolean minePredict = true;

    /** Collapse marked enchant tooltip lines behind Shift. Off by default — full enchant list always visible. */
    private static volatile boolean enchantCollapse = false;

    /** Scroll oversized tooltips with the mouse wheel. Off = vanilla bottom-pin (top spills off-screen). */
    private static volatile boolean scrollableTooltips = true;

    /** While holding a pickaxe, fade nearby player-shaped entities (players + NPCs) so they don't obscure the block you're mining. */
    private static volatile boolean peacefulMining = true;

    /** While holding a sword/axe, fade gang teammates within 5 blocks AND skip them in the attack raycast so you can hit enemies past them. Off in duels. */
    private static volatile boolean peacefulPvp = false;

    /** Show the draggable booster-timers HUD. Off = mod widget hidden; the server's bossbar (if not also disabled in /toggles) still renders. */
    private static volatile boolean boosterHud = true;

    /** Show the floating "247 Emerald" label above each known meteorite block. Off = no world-space label; the chat line still fires. */
    private static volatile boolean meteoriteHud = true;

    /** Show the draggable Events HUD (KOTH / BAH / Meteor / Rift / etc. countdowns). */
    private static volatile boolean eventsHud = true;

    /** Show the draggable Cooldowns HUD (/fix, /eat, /feed, /jet, combat tag). */
    private static volatile boolean cooldownsHud = true;

    /** Show the draggable Stats HUD (session kill + drop counts, auto-context per world). */
    private static volatile boolean statsHud = true;

    /** Check GitHub for a newer mod release on server join and alert (chat + toast + sound) once per session if one's out. */
    private static volatile boolean updateAlert = true;

    /** Intercept {@code /bugreport} and open the in-game UI instead of filing immediately. Off = vanilla command flow. */
    private static volatile boolean bugReportUi = true;

    /** Auto-load the bundled rift texture pack (tints stone + ores white so the four special blocks pop) while in {@code tartarus_rift}. Drives {@link RiftTexturePackManager}. */
    private static volatile boolean riftTexturePack = false;

    // ─────────────────────────────────────────────────────────────────────────

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            save(); // write defaults so the user can hand-edit
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
            minePredict = parseBool(props.getProperty("minePredict"), minePredict);
            enchantCollapse = parseBool(props.getProperty("enchantCollapse"), enchantCollapse);
            scrollableTooltips = parseBool(props.getProperty("scrollableTooltips"), scrollableTooltips);
            peacefulMining = parseBool(props.getProperty("peacefulMining"), peacefulMining);
            peacefulPvp = parseBool(props.getProperty("peacefulPvp"), peacefulPvp);
            boosterHud = parseBool(props.getProperty("boosterHud"), boosterHud);
            meteoriteHud = parseBool(props.getProperty("meteoriteHud"), meteoriteHud);
            eventsHud = parseBool(props.getProperty("eventsHud"), eventsHud);
            cooldownsHud = parseBool(props.getProperty("cooldownsHud"), cooldownsHud);
            statsHud = parseBool(props.getProperty("statsHud"), statsHud);
            updateAlert = parseBool(props.getProperty("updateAlert"), updateAlert);
            bugReportUi = parseBool(props.getProperty("bugReportUi"), bugReportUi);
            riftTexturePack = parseBool(props.getProperty("riftTexturePack"), riftTexturePack);
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to load {}: {}", FILE_NAME, e.getMessage());
        }
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("minePredict", Boolean.toString(minePredict));
        props.setProperty("enchantCollapse", Boolean.toString(enchantCollapse));
        props.setProperty("scrollableTooltips", Boolean.toString(scrollableTooltips));
        props.setProperty("peacefulMining", Boolean.toString(peacefulMining));
        props.setProperty("peacefulPvp", Boolean.toString(peacefulPvp));
        props.setProperty("boosterHud", Boolean.toString(boosterHud));
        props.setProperty("meteoriteHud", Boolean.toString(meteoriteHud));
        props.setProperty("eventsHud", Boolean.toString(eventsHud));
        props.setProperty("cooldownsHud", Boolean.toString(cooldownsHud));
        props.setProperty("statsHud", Boolean.toString(statsHud));
        props.setProperty("updateAlert", Boolean.toString(updateAlert));
        props.setProperty("bugReportUi", Boolean.toString(bugReportUi));
        props.setProperty("riftTexturePack", Boolean.toString(riftTexturePack));
        try {
            Files.createDirectories(configPath().getParent());
            try (var out = Files.newOutputStream(configPath())) {
                props.store(out, "PrisonsMod client feature toggles");
            }
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to save {}: {}", FILE_NAME, e.getMessage());
        }
    }

    public static boolean isMinePredictEnabled() { return minePredict; }

    public static boolean toggleMinePredict() {
        minePredict = !minePredict;
        save();
        return minePredict;
    }

    public static void setMinePredict(boolean value) {
        if (minePredict == value) return;
        minePredict = value;
        save();
    }

    public static boolean isEnchantCollapseEnabled() { return enchantCollapse; }

    public static boolean toggleEnchantCollapse() {
        enchantCollapse = !enchantCollapse;
        save();
        return enchantCollapse;
    }

    public static void setEnchantCollapse(boolean value) {
        if (enchantCollapse == value) return;
        enchantCollapse = value;
        save();
    }

    public static boolean isScrollableTooltipsEnabled() { return scrollableTooltips; }

    public static boolean toggleScrollableTooltips() {
        scrollableTooltips = !scrollableTooltips;
        save();
        return scrollableTooltips;
    }

    public static void setScrollableTooltips(boolean value) {
        if (scrollableTooltips == value) return;
        scrollableTooltips = value;
        save();
    }

    public static boolean isPeacefulMiningEnabled() { return peacefulMining; }

    public static boolean togglePeacefulMining() {
        peacefulMining = !peacefulMining;
        save();
        return peacefulMining;
    }

    public static void setPeacefulMining(boolean value) {
        if (peacefulMining == value) return;
        peacefulMining = value;
        save();
    }

    public static boolean isPeacefulPvpEnabled() { return peacefulPvp; }

    public static boolean togglePeacefulPvp() {
        peacefulPvp = !peacefulPvp;
        save();
        return peacefulPvp;
    }

    public static void setPeacefulPvp(boolean value) {
        if (peacefulPvp == value) return;
        peacefulPvp = value;
        save();
    }

    public static boolean isBoosterHudEnabled() { return boosterHud; }

    public static void setBoosterHud(boolean value) {
        if (boosterHud == value) return;
        boosterHud = value;
        save();
    }

    public static boolean isMeteoriteHudEnabled() { return meteoriteHud; }

    public static void setMeteoriteHud(boolean value) {
        if (meteoriteHud == value) return;
        meteoriteHud = value;
        save();
    }

    public static boolean isEventsHudEnabled() { return eventsHud; }

    public static void setEventsHud(boolean value) {
        if (eventsHud == value) return;
        eventsHud = value;
        save();
    }

    public static boolean isCooldownsHudEnabled() { return cooldownsHud; }

    public static void setCooldownsHud(boolean value) {
        if (cooldownsHud == value) return;
        cooldownsHud = value;
        save();
    }

    public static boolean isStatsHudEnabled() { return statsHud; }

    public static void setStatsHud(boolean value) {
        if (statsHud == value) return;
        statsHud = value;
        save();
    }

    public static boolean isUpdateAlertEnabled() { return updateAlert; }

    public static void setUpdateAlert(boolean value) {
        if (updateAlert == value) return;
        updateAlert = value;
        save();
    }

    public static boolean isBugReportUiEnabled() { return bugReportUi; }

    public static void setBugReportUi(boolean value) {
        if (bugReportUi == value) return;
        bugReportUi = value;
        save();
    }

    public static boolean isRiftTexturePackEnabled() { return riftTexturePack; }

    public static void setRiftTexturePack(boolean value) {
        if (riftTexturePack == value) return;
        riftTexturePack = value;
        save();
    }

    private static boolean parseBool(String s, boolean fallback) {
        if (s == null) return fallback;
        s = s.trim().toLowerCase();
        if (s.equals("true") || s.equals("yes") || s.equals("on") || s.equals("1")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("off") || s.equals("0")) return false;
        return fallback;
    }

    private FeatureToggles() {}
}
