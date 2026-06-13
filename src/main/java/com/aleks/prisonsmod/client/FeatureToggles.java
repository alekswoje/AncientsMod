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

    /** Client-side break-crack prediction. Off by default: on this server the plugin
     *  already sends its own crack the same tick mining starts, so prediction adds no
     *  head-start and just renders a second, overlapping crack (the doubled/choppy look).
     *  On = opt-in A/B comparison; leave off for the smooth single-source server crack. */
    private static volatile boolean minePredict = false;

    /** Collapse marked enchant tooltip lines behind Shift. Off by default — full enchant list always visible. */
    private static volatile boolean enchantCollapse = false;

    /** Scroll oversized tooltips with the mouse wheel. Off = vanilla bottom-pin (top spills off-screen). */
    private static volatile boolean scrollableTooltips = true;

    /** In-mod item wiki: hold Alt over a wired item (any armor trim) to pin its tooltip and
     *  click underlined terms for a plain-English explainer. On by default. */
    private static volatile boolean itemWiki = true;

    /** Render the per-ore-type "Blocks Mined" breakdown on pickaxe tooltips, client-side,
     *  from the pickaxe's ore-mined data. The server keeps a compact tooltip (no ore lines)
     *  for performance, so this is how the full breakdown is shown. Only active on
     *  compact-lore pickaxes. On by default. */
    private static volatile boolean pickaxeBlocks = true;

    /** While holding a pickaxe, fade nearby player-shaped entities (players + NPCs) so they don't obscure the block you're mining. */
    private static volatile boolean peacefulMining = true;

    /** While holding a sword/axe, fade gang teammates within 5 blocks AND skip them in the attack raycast so you can hit enemies past them. Off in duels. */
    private static volatile boolean peacefulPvp = false;

    /** Show the draggable booster-timers HUD. Off = mod widget hidden; the server's bossbar (if not also disabled in /toggles) still renders. */
    private static volatile boolean boosterHud = true;

    /** Show the floating "247 Emerald" label above each known meteorite block. Off = no world-space label; the chat line still fires. */
    private static volatile boolean meteoriteHud = true;

    /** Show a world-space beam + label pinging where a mining rush spawned in your tier's mine (same look as meteor pings). Off = no beam; the chat announcement still fires. */
    private static volatile boolean miningRushPings = true;

    /** Show the draggable Events HUD (KOTH / BAH / Meteor / Rift / etc. countdowns). */
    private static volatile boolean eventsHud = true;

    /** Show the draggable Cooldowns HUD (/fix, /eat, /feed, /jet, combat tag). */
    private static volatile boolean cooldownsHud = true;

    /** Show the draggable Stats HUD (session kill + drop counts, auto-context per world). */
    private static volatile boolean statsHud = true;

    /** Show the draggable Outpost HUD (name, gang, capture % for all 5 outposts). */
    private static volatile boolean outpostHud = true;

    /** Check GitHub for a newer mod release on server join and alert (chat + toast + sound) once per session if one's out. */
    private static volatile boolean updateAlert = true;

    /** Intercept {@code /bugreport} and open the in-game UI instead of filing immediately. Off = vanilla command flow. */
    private static volatile boolean bugReportUi = true;

    /** Intercept {@code /suggest} and open the in-game GUI. Off = command goes to the server (which will tell vanilla users to install the mod). */
    private static volatile boolean suggestUi = true;

    /** Intercept {@code /pv} (no args) and open the mod's overview screen showing all 7 PVs at once. Off = vanilla server menu. */
    private static volatile boolean pvOverview = true;

    /** Use the ME-terminal style PV view (single grid of every stack across every unlocked PV, click to extract, drag to deposit) instead of the per-PV card overview. On by default — the terminal is the standard {@code /pv} view. Off = card overview (alternative view). Only takes effect when {@link #pvOverview} is also on. */
    private static volatile boolean pvTerminal = true;

    /** Auto-focus the PV terminal's search box the moment the terminal opens, so
     *  the player can start typing a query without clicking the field first. Only
     *  has any effect when {@link #pvTerminal} is on. */
    private static volatile boolean pvTerminalAutoFocusSearch = true;

    /** PV terminal sort order for the aggregated item grid: 0 = Quantity (most
     *  first), 1 = Alphabetical (A→Z), 2 = Category (by item type). Cycled with
     *  the in-terminal sort button; persisted so the chosen order survives
     *  restarts. Default Quantity. */
    private static volatile int pvTerminalSortMode = 0;
    /** Number of terminal sort modes (keep in sync with ItemTerminalScreen).
     *  Shared by the PV terminal and the cell terminal — same sort button. */
    public static final int PV_TERMINAL_SORT_MODES = 3;

    /** Cell Vault Terminal: when ON (default), the server cancels the vanilla
     *  chest GUI on the player's cell vault chest and pushes the mod's
     *  aggregated terminal (all cell containers in one searchable grid)
     *  instead. The state is reported to the server on join and on every flip
     *  ({@code PKT_CELLTERM_STATE}); when OFF the server leaves the vanilla
     *  chest alone. */
    private static volatile boolean cellTerminal = true;

    /** Cell terminal sort order — same modes as {@link #pvTerminalSortMode}
     *  (0 = Quantity, 1 = A→Z, 2 = Category) but persisted separately so the
     *  two terminals can hold different orders. */
    private static volatile int cellTermSortMode = 0;

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

    /** Client-side fullbright. Overrides the gamma slider so dark areas render fully lit. Disabled in worlds the server includes in the {@code prisonsmod.fullbright.blacklist-worlds} config (see {@link Fullbright}). Default on — server NV used to do this for everyone. */
    private static volatile boolean fullbright = true;

    /** Draw a compact amount (e.g. "1m", "1.1k") in the top-right corner of currency item slots
     *  (currently Ancient Energy). Parsed from the synced display name — no server change needed. */
    private static volatile boolean currencyAmountOverlay = true;

    /** Draw item level (top-right) and pickaxe prestige (top-left) on gear/picks, from synced custom_data. */
    private static volatile boolean gearStatsOverlay = true;

    /** Draw a booster's multiplier (top-left) and total duration (bottom-right) on its item slot,
     *  color-matched to the boost type. Parsed from the synced name + lore — no server change. */
    private static volatile boolean boosterInfoOverlay = true;

    /** Draw the % value (bottom-right) on Enchant Dust and Calcified Dust slots.
     *  Parsed from the synced lore — no server change needed. */
    private static volatile boolean dustPercentOverlay = true;

    // Client-side Powerball rendering is always on (no user toggle): the mod draws
    // the ball as a true item-model fire charge that matches the server 1:1, and the
    // server suppresses its per-tick ItemDisplay packet flood in return.
    // See PowerballRenderer + NetworkHandler.sendPowerballState.

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
            itemWiki = parseBool(props.getProperty("itemWiki"), itemWiki);
            pickaxeBlocks = parseBool(props.getProperty("pickaxeBlocks"), pickaxeBlocks);
            peacefulMining = parseBool(props.getProperty("peacefulMining"), peacefulMining);
            peacefulPvp = parseBool(props.getProperty("peacefulPvp"), peacefulPvp);
            boosterHud = parseBool(props.getProperty("boosterHud"), boosterHud);
            meteoriteHud = parseBool(props.getProperty("meteoriteHud"), meteoriteHud);
            miningRushPings = parseBool(props.getProperty("miningRushPings"), miningRushPings);
            eventsHud = parseBool(props.getProperty("eventsHud"), eventsHud);
            cooldownsHud = parseBool(props.getProperty("cooldownsHud"), cooldownsHud);
            statsHud   = parseBool(props.getProperty("statsHud"),   statsHud);
            outpostHud = parseBool(props.getProperty("outpostHud"), outpostHud);
            updateAlert = parseBool(props.getProperty("updateAlert"), updateAlert);
            bugReportUi = parseBool(props.getProperty("bugReportUi"), bugReportUi);
            suggestUi = parseBool(props.getProperty("suggestUi"), suggestUi);
            pvOverview = parseBool(props.getProperty("pvOverview"), pvOverview);
            pvTerminal = parseBool(props.getProperty("pvTerminal"), pvTerminal);
            pvTerminalAutoFocusSearch = parseBool(props.getProperty("pvTerminalAutoFocusSearch"), pvTerminalAutoFocusSearch);
            pvTerminalSortMode = clampSortMode(parseInt(props.getProperty("pvTerminalSortMode"), pvTerminalSortMode));
            cellTerminal = parseBool(props.getProperty("cellTerminal"), cellTerminal);
            cellTermSortMode = clampSortMode(parseInt(props.getProperty("cellTermSortMode"), cellTermSortMode));
            lootBrowser = parseBool(props.getProperty("lootBrowser"), lootBrowser);
            riftTexturePack = parseBool(props.getProperty("riftTexturePack"), riftTexturePack);
            evenSpacingSnap = parseBool(props.getProperty("evenSpacingSnap"), evenSpacingSnap);
            autoRejoin = parseBool(props.getProperty("autoRejoin"), autoRejoin);
            itemLock = parseBool(props.getProperty("itemLock"), itemLock);
            fullbright = parseBool(props.getProperty("fullbright"), fullbright);
            currencyAmountOverlay = parseBool(props.getProperty("currencyAmountOverlay"), currencyAmountOverlay);
            gearStatsOverlay = parseBool(props.getProperty("gearStatsOverlay"), gearStatsOverlay);
            boosterInfoOverlay = parseBool(props.getProperty("boosterInfoOverlay"), boosterInfoOverlay);
            dustPercentOverlay = parseBool(props.getProperty("dustPercentOverlay"), dustPercentOverlay);
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to load {}: {}", FILE_NAME, e.getMessage());
        }
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("minePredict", Boolean.toString(minePredict));
        props.setProperty("enchantCollapse", Boolean.toString(enchantCollapse));
        props.setProperty("scrollableTooltips", Boolean.toString(scrollableTooltips));
        props.setProperty("itemWiki", Boolean.toString(itemWiki));
        props.setProperty("pickaxeBlocks", Boolean.toString(pickaxeBlocks));
        props.setProperty("peacefulMining", Boolean.toString(peacefulMining));
        props.setProperty("peacefulPvp", Boolean.toString(peacefulPvp));
        props.setProperty("boosterHud", Boolean.toString(boosterHud));
        props.setProperty("meteoriteHud", Boolean.toString(meteoriteHud));
        props.setProperty("miningRushPings", Boolean.toString(miningRushPings));
        props.setProperty("eventsHud", Boolean.toString(eventsHud));
        props.setProperty("cooldownsHud", Boolean.toString(cooldownsHud));
        props.setProperty("statsHud",   Boolean.toString(statsHud));
        props.setProperty("outpostHud", Boolean.toString(outpostHud));
        props.setProperty("updateAlert", Boolean.toString(updateAlert));
        props.setProperty("bugReportUi", Boolean.toString(bugReportUi));
        props.setProperty("suggestUi", Boolean.toString(suggestUi));
        props.setProperty("pvOverview", Boolean.toString(pvOverview));
        props.setProperty("pvTerminal", Boolean.toString(pvTerminal));
        props.setProperty("pvTerminalAutoFocusSearch", Boolean.toString(pvTerminalAutoFocusSearch));
        props.setProperty("pvTerminalSortMode", Integer.toString(pvTerminalSortMode));
        props.setProperty("cellTerminal", Boolean.toString(cellTerminal));
        props.setProperty("cellTermSortMode", Integer.toString(cellTermSortMode));
        props.setProperty("lootBrowser", Boolean.toString(lootBrowser));
        props.setProperty("riftTexturePack", Boolean.toString(riftTexturePack));
        props.setProperty("evenSpacingSnap", Boolean.toString(evenSpacingSnap));
        props.setProperty("autoRejoin", Boolean.toString(autoRejoin));
        props.setProperty("itemLock", Boolean.toString(itemLock));
        props.setProperty("fullbright", Boolean.toString(fullbright));
        props.setProperty("currencyAmountOverlay", Boolean.toString(currencyAmountOverlay));
        props.setProperty("gearStatsOverlay", Boolean.toString(gearStatsOverlay));
        props.setProperty("boosterInfoOverlay", Boolean.toString(boosterInfoOverlay));
        props.setProperty("dustPercentOverlay", Boolean.toString(dustPercentOverlay));
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

    public static boolean isPickaxeBlocksEnabled() { return pickaxeBlocks; }

    public static boolean togglePickaxeBlocks() {
        pickaxeBlocks = !pickaxeBlocks;
        save();
        return pickaxeBlocks;
    }

    public static void setPickaxeBlocks(boolean value) {
        if (pickaxeBlocks == value) return;
        pickaxeBlocks = value;
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

    public static boolean isItemWikiEnabled() { return itemWiki; }

    public static void setItemWiki(boolean value) {
        if (itemWiki == value) return;
        itemWiki = value;
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

    public static boolean isMiningRushPingsEnabled() { return miningRushPings; }

    public static void setMiningRushPings(boolean value) {
        if (miningRushPings == value) return;
        miningRushPings = value;
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

    public static boolean isOutpostHudEnabled() { return outpostHud; }

    public static void setOutpostHud(boolean value) {
        if (outpostHud == value) return;
        outpostHud = value;
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
    }

    public static boolean isPvTerminalEnabled() { return pvTerminal; }

    public static void setPvTerminal(boolean value) {
        if (pvTerminal == value) return;
        pvTerminal = value;
        save();
    }

    public static boolean isPvTerminalAutoFocusSearchEnabled() { return pvTerminalAutoFocusSearch; }

    public static void setPvTerminalAutoFocusSearch(boolean value) {
        if (pvTerminalAutoFocusSearch == value) return;
        pvTerminalAutoFocusSearch = value;
        save();
    }

    public static int getPvTerminalSortMode() { return pvTerminalSortMode; }

    public static void setPvTerminalSortMode(int mode) {
        int m = clampSortMode(mode);
        if (pvTerminalSortMode == m) return;
        pvTerminalSortMode = m;
        save();
    }

    /** Advance to the next sort mode (wraps), persist, and return the new mode. */
    public static int cyclePvTerminalSortMode() {
        setPvTerminalSortMode(pvTerminalSortMode + 1);
        return pvTerminalSortMode;
    }

    private static int clampSortMode(int mode) {
        int m = mode % PV_TERMINAL_SORT_MODES;
        return m < 0 ? m + PV_TERMINAL_SORT_MODES : m;
    }

    public static boolean isCellTerminalEnabled() { return cellTerminal; }

    public static void setCellTerminal(boolean value) {
        if (cellTerminal == value) return;
        cellTerminal = value;
        save();
        // Re-notify the server so it knows whether to intercept this player's
        // vault-chest opens with the cell terminal. No-op when not connected.
        com.aleks.prisonsmod.net.NetworkHandler.sendCellTermState(value);
    }

    public static int getCellTermSortMode() { return cellTermSortMode; }

    public static void setCellTermSortMode(int mode) {
        int m = clampSortMode(mode);
        if (cellTermSortMode == m) return;
        cellTermSortMode = m;
        save();
    }

    /** Advance to the next cell-terminal sort mode (wraps), persist, and
     *  return the new mode. */
    public static int cycleCellTermSortMode() {
        setCellTermSortMode(cellTermSortMode + 1);
        return cellTermSortMode;
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

    public static boolean isFullbrightEnabled() { return fullbright; }

    public static void setFullbright(boolean value) {
        if (fullbright == value) return;
        fullbright = value;
        save();
    }

    public static boolean isCurrencyAmountOverlayEnabled() { return currencyAmountOverlay; }

    public static void setCurrencyAmountOverlay(boolean value) {
        if (currencyAmountOverlay == value) return;
        currencyAmountOverlay = value;
        save();
    }

    public static boolean isGearStatsOverlayEnabled() { return gearStatsOverlay; }

    public static void setGearStatsOverlay(boolean value) {
        if (gearStatsOverlay == value) return;
        gearStatsOverlay = value;
        save();
    }

    public static boolean isBoosterInfoOverlayEnabled() { return boosterInfoOverlay; }

    public static void setBoosterInfoOverlay(boolean value) {
        if (boosterInfoOverlay == value) return;
        boosterInfoOverlay = value;
        save();
    }

    public static boolean isDustPercentOverlayEnabled() { return dustPercentOverlay; }

    public static void setDustPercentOverlay(boolean value) {
        if (dustPercentOverlay == value) return;
        dustPercentOverlay = value;
        save();
    }

    /** Always on — client-side Powerball rendering is no longer a user toggle. */
    public static boolean isPowerballRenderEnabled() { return true; }

    private static boolean parseBool(String s, boolean fallback) {
        if (s == null) return fallback;
        s = s.trim().toLowerCase();
        if (s.equals("true") || s.equals("yes") || s.equals("on") || s.equals("1")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("off") || s.equals("0")) return false;
        return fallback;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private FeatureToggles() {}
}
