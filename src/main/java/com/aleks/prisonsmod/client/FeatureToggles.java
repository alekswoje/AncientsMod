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

    /** Intercept {@code /suggest} and open the in-game GUI. Off = command goes to the server (which will tell vanilla users to install the mod). */
    private static volatile boolean suggestUi = true;

    /** Intercept {@code /pv} (no args) and open the mod's overview screen showing all 7 PVs at once. Off = vanilla server menu. */
    private static volatile boolean pvOverview = true;

    /** Use the ME-terminal style PV view (single grid of every stack across every unlocked PV, click to extract, drag to deposit) instead of the per-PV card overview. Off = card overview (current behavior). Only takes effect when {@link #pvOverview} is also on. */
    private static volatile boolean pvTerminal = false;

    /** Intercept {@code /loottables} (and its {@code /loot} alias) and open the mod's searchable loot browser instead of the server chest GUI. Off = vanilla server menu. */
    private static volatile boolean lootBrowser = true;

    /** Auto-load the bundled rift texture pack (tints stone + ores white so the four special blocks pop) while in {@code tartarus_rift}. Drives {@link RiftTexturePackManager}. */
    private static volatile boolean riftTexturePack = false;

    /** When dragging a widget in the HUD editor, snap to positions that make spacing relative to other widgets equal (midpoint between two, or continuing a pattern of three). */
    private static volatile boolean evenSpacingSnap = true;

    /** Auto-rejoin the server after an involuntary disconnect (kick, restart, network drop). Retries every 5s while the disconnect screen is showing. Backend routing + queueing on the way back in is handled by the proxy. */
    private static volatile boolean autoRejoin = false;

    /** Item lock — block Q-drop, Ctrl+Q drop-stack, inventory drag-out, and 1-9 hotbar swap on player-inv slots flagged via the lock keybind ({@link KeyBinds#TOGGLE_ITEM_LOCK}). Per-slot state lives in {@link ItemLocks} (separate file). When off, locks are ignored but not forgotten. */
    private static volatile boolean itemLock = true;

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
            suggestUi = parseBool(props.getProperty("suggestUi"), suggestUi);
            pvOverview = parseBool(props.getProperty("pvOverview"), pvOverview);
            pvTerminal = parseBool(props.getProperty("pvTerminal"), pvTerminal);
            lootBrowser = parseBool(props.getProperty("lootBrowser"), lootBrowser);
            riftTexturePack = parseBool(props.getProperty("riftTexturePack"), riftTexturePack);
            evenSpacingSnap = parseBool(props.getProperty("evenSpacingSnap"), evenSpacingSnap);
            autoRejoin = parseBool(props.getProperty("autoRejoin"), autoRejoin);
            itemLock = parseBool(props.getProperty("itemLock"), itemLock);
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
        props.setProperty("suggestUi", Boolean.toString(suggestUi));
        props.setProperty("pvOverview", Boolean.toString(pvOverview));
        props.setProperty("pvTerminal", Boolean.toString(pvTerminal));
        props.setProperty("lootBrowser", Boolean.toString(lootBrowser));
        props.setProperty("riftTexturePack", Boolean.toString(riftTexturePack));
        props.setProperty("evenSpacingSnap", Boolean.toString(evenSpacingSnap));
        props.setProperty("autoRejoin", Boolean.toString(autoRejoin));
        props.setProperty("itemLock", Boolean.toString(itemLock));
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
        // Re-notify the server so it can keep the action-bar booster default in
        // sync with the widget being on/off. No-op when not connected.
        com.aleks.prisonsmod.net.NetworkHandler.sendBoosterHudState(value);
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
        // Mining suppression on the server keys off whether the widget is
        // effectively rendering the mining section — re-send when the master
        // toggle flips so the action-bar default flips with it.
        com.aleks.prisonsmod.net.NetworkHandler.sendMiningHudState(
                com.aleks.prisonsmod.client.hud.StatsHud.isMiningEffectivelyEnabled());
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

    public static boolean isSuggestUiEnabled() { return suggestUi; }

    public static void setSuggestUi(boolean value) {
        if (suggestUi == value) return;
        suggestUi = value;
        save();
    }

    public static boolean isPvOverviewEnabled() { return pvOverview; }

    public static void setPvOverview(boolean value) {
        if (pvOverview == value) return;
        pvOverview = value;
        save();
        // Notify the server so it can gate server-side affinity routing —
        // when this is off, vanilla shift-click behavior applies and the
        // mod's shift-click mixin also stops intercepting. No-op when not
        // connected.
        com.aleks.prisonsmod.net.NetworkHandler.sendPvFeaturesState(value);
    }

    public static boolean isPvTerminalEnabled() { return pvTerminal; }

    public static void setPvTerminal(boolean value) {
        if (pvTerminal == value) return;
        pvTerminal = value;
        save();
    }

    public static boolean isLootBrowserEnabled() { return lootBrowser; }

    public static void setLootBrowser(boolean value) {
        if (lootBrowser == value) return;
        lootBrowser = value;
        save();
    }

    public static boolean isRiftTexturePackEnabled() { return riftTexturePack; }

    public static void setRiftTexturePack(boolean value) {
        if (riftTexturePack == value) return;
        riftTexturePack = value;
        save();
    }

    public static boolean isEvenSpacingSnapEnabled() { return evenSpacingSnap; }

    public static void setEvenSpacingSnap(boolean value) {
        if (evenSpacingSnap == value) return;
        evenSpacingSnap = value;
        save();
    }

    public static boolean isAutoRejoinEnabled() { return autoRejoin; }

    public static void setAutoRejoin(boolean value) {
        if (autoRejoin == value) return;
        autoRejoin = value;
        save();
    }

    public static boolean isItemLockEnabled() { return itemLock; }

    public static void setItemLock(boolean value) {
        if (itemLock == value) return;
        itemLock = value;
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
