package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.client.FeatureToggles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Session PvE stats HUD. Reads from {@link PveStatsState} every frame and
 * renders kill / drop tallies plus the live Hunter XP rate. Drops are grouped
 * by <b>rarity</b> (the server classifies each drop with the same tiers as the
 * chat drop announcements) rather than per-item, so the panel stays compact
 * during a long farm. The current world is shown as a sub-header so the widget
 * auto-contexts to where the player actually is.
 */
public final class StatsHud extends HudElement {

    public static final StatsHud INSTANCE = new StatsHud();

    /** Setting key: comma-separated list of section keys to show. */
    public static final String KEY_SECTIONS = "sections";
    /** Setting key: comma-separated list of kill kinds the player wants visible (empty = all). */
    public static final String KEY_VISIBLE_KILLS = "visible_kills";
    /** Setting key: comma-separated list of ore-ids (Bukkit Material names) the
     *  player wants shown in the blocks section. Empty = curated default. */
    public static final String KEY_VISIBLE_BLOCKS = "visible_blocks";
    /** Setting key: when true the blocks section shows this-session counts; when
     *  false it shows lifetime totals on the held pickaxe (prestige-style). */
    public static final String KEY_BLOCKS_SESSION = "blocks_session";
    /** Setting key: show the session-average per-hour rate (total ÷ elapsed) next
     *  to each mining-session total. Default on. */
    public static final String KEY_SESSION_SHOW_AVG = "session_show_avg";
    /** Setting key: also show the live rolling per-hour rate (the same figure the
     *  "mining" section shows) on each mining-session row. Default off. */
    public static final String KEY_SESSION_SHOW_LIVE = "session_show_live";

    /**
     * Drop rarity tiers, rarest → most common. The server tallies each drop
     * into one of these buckets (mirroring the plugin's {@code LootRarity}); the
     * HUD renders one row per non-empty tier in this order.
     */
    private static final List<String> RARITY_ORDER =
            List.of("mythic", "legendary", "epic", "rare", "uncommon", "common");

    public static final List<String> ALL_SECTIONS = List.of("world", "hunter", "mining", "session", "blocks", "kills", "drops");
    /** Blocks and session are opt-in, so they are NOT in the default set —
     *  existing users don't suddenly get a new section until they enable it. */
    public static final Set<String> DEFAULT_SECTIONS =
            new LinkedHashSet<>(List.of("world", "hunter", "mining", "kills", "drops"));

    /** Mining-session row accent colours, matched to the live mining section. */
    private static final int SESSION_ACCENT_XP     = 0xFF8AE08A;
    private static final int SESSION_ACCENT_ENERGY = 0xFF8AC2FF;
    private static final int SESSION_ACCENT_MONEY  = 0xFFE6B05A;
    private static final int SESSION_ACCENT_BLOCKS = 0xFFC8C0B0;

    /** Ores offered as toggles in the blocks-section settings (display order). */
    public static final List<String> CANDIDATE_BLOCKS = List.of(
            "COAL_ORE", "IRON_ORE", "COPPER_ORE", "GOLD_ORE", "REDSTONE_ORE",
            "LAPIS_ORE", "DIAMOND_ORE", "EMERALD_ORE",
            "NETHER_GOLD_ORE", "NETHER_QUARTZ_ORE", "ANCIENT_DEBRIS"
    );
    /** Curated "not too big" default — the headline ores most players care about. */
    public static final Set<String> DEFAULT_VISIBLE_BLOCKS =
            new LinkedHashSet<>(List.of("IRON_ORE", "GOLD_ORE", "DIAMOND_ORE", "EMERALD_ORE"));

    /** Display order for kill kinds — anything not listed renders after these, in arrival order. */
    public static final List<String> KILL_ORDER = List.of(
            "shade", "wraith", "pit_guard",
            "caravan", "warlord_caravan", "caravan_escort",
            "echidna", "lamia", "hephaestus", "thanatos"
    );

    private static final int MIN_WIDTH    = 158;
    private static final int VALUE_COLOR  = 0xFFFFFFFF;
    private static final int SUBHEADER    = 0xFFA0A8B4;
    /** Hunter-section strip colour — the Polis/hunter violet (ServerTheme accent). */
    private static final int HUNTER_ACCENT = 0xFFA78BFA;
    /** Fallback drop-row colour for keys that aren't a known rarity tier. */
    private static final int DROP_DEFAULT_ACCENT = 0xFF8AC2FF;

    private StatsHud() {}

    @Override public String id() { return "stats"; }
    @Override public String displayName() { return "Stats"; }

    @Override
    public boolean isVisible() {
        if (!FeatureToggles.isStatsHudEnabled()) return false;
        if (HudStyle.isAlwaysShown(id())) return true;
        // Only show when the player has something to look at — bare world
        // name on its own meant the widget hung around in spawn/hub with
        // no rows. Kills/drops/hunter are session-only, mining/blocks are
        // live-only, so the HUD naturally disappears once they're all quiet.
        Set<String> sections = enabledSections();
        boolean miningLive = sections.contains("mining") && MiningStatsState.isLive();
        boolean blocksLive = sections.contains("blocks") && MiningBlocksState.isLive() && !blockRows().isEmpty();
        boolean sessionLive = sections.contains("session") && MiningSessionState.isLive();
        return miningLive
            || blocksLive
            || sessionLive
            || !PveStatsState.kills().isEmpty()
            || !PveStatsState.drops().isEmpty()
            || PveStatsState.sessionHunterXp() > 0
            || PveStatsState.hunterXpPerHour() > 0;
    }

    @Override
    public String editorPlaceholder() { return "Stats"; }

    @Override
    public Screen openSettings(Screen parent) {
        return new StatsHudSettingsScreen(parent, this);
    }

    public Set<String> enabledSections() {
        return HudSettings.getStringSet(id(), KEY_SECTIONS, DEFAULT_SECTIONS);
    }

    /**
     * True when the mining section is configured to render — i.e. the Stats HUD
     * widget is enabled AND the "mining" section is in the enabled set. This is
     * what the server cares about when deciding whether to suppress its own
     * action-bar XP/h / Energy/h / $/h trio; the live-data check is intentionally
     * NOT folded in here so the server gets a stable on/off signal that doesn't
     * flap as the player starts/stops mining.
     */
    public static boolean isMiningEffectivelyEnabled() {
        if (!FeatureToggles.isStatsHudEnabled()) return false;
        return INSTANCE.enabledSections().contains("mining");
    }

    /** Empty set = show every kill kind. Otherwise = whitelist. */
    public Set<String> visibleKills() {
        return HudSettings.getStringSet(id(), KEY_VISIBLE_KILLS, new LinkedHashSet<>());
    }

    /** Ores the player wants shown in the blocks section (curated default when unset). */
    public Set<String> visibleBlocks() {
        return HudSettings.getStringSet(id(), KEY_VISIBLE_BLOCKS, DEFAULT_VISIBLE_BLOCKS);
    }

    /** True = blocks section shows this-session counts; false = lifetime totals. */
    public boolean blocksSessionMode() {
        return HudSettings.getBoolean(id(), KEY_BLOCKS_SESSION, false);
    }

    /** True = show the session-average /h rate beside each mining-session total. */
    public boolean sessionShowAvg() {
        return HudSettings.getBoolean(id(), KEY_SESSION_SHOW_AVG, true);
    }

    /** True = also show the live rolling /h rate on each mining-session row. */
    public boolean sessionShowLive() {
        return HudSettings.getBoolean(id(), KEY_SESSION_SHOW_LIVE, false);
    }

    @Override
    public int width() {
        TextRenderer fr = textRenderer();
        if (fr == null) return MIN_WIDTH;
        int padX = HudStyle.padX(id());
        int colGap = HudStyle.columnGap(id());
        int leftPad = padX + HudStyle.stripW() + HudStyle.stripGap(id());
        int widest = fr.getWidth("STATS");

        Set<String> sections = enabledSections();
        if (sections.contains("world")) {
            widest = Math.max(widest, fr.getWidth(prettyWorld(PveStatsState.worldName())));
        }
        if (sections.contains("hunter")) {
            for (MiningRow r : hunterRows()) {
                int w = fr.getWidth(r.label) + colGap + fr.getWidth(r.value);
                if (w > widest) widest = w;
            }
        }
        if (sections.contains("mining") && MiningStatsState.isLive()) {
            for (MiningRow r : miningRows()) {
                int w = fr.getWidth(r.label) + colGap + fr.getWidth(r.value);
                if (w > widest) widest = w;
            }
        }
        if (sections.contains("session") && MiningSessionState.isLive()) {
            widest = Math.max(widest, fr.getWidth(sessionHeader()));
            for (MiningRow r : sessionRows()) {
                int w = fr.getWidth(r.label) + colGap + fr.getWidth(r.value);
                if (w > widest) widest = w;
            }
        }
        if (sections.contains("blocks") && MiningBlocksState.isLive()) {
            widest = Math.max(widest, fr.getWidth(blocksHeader()));
            for (MiningRow r : blockRows()) {
                int w = fr.getWidth(r.label) + colGap + fr.getWidth(r.value);
                if (w > widest) widest = w;
            }
        }
        if (sections.contains("kills")) {
            for (Row r : killRows()) {
                int w = fr.getWidth(r.label) + colGap + fr.getWidth(String.valueOf(r.value));
                if (w > widest) widest = w;
            }
        }
        if (sections.contains("drops")) {
            for (Row r : dropRows()) {
                int w = fr.getWidth(r.label) + colGap + fr.getWidth(String.valueOf(r.value));
                if (w > widest) widest = w;
            }
        }
        return Math.max(MIN_WIDTH, leftPad + widest + padX);
    }

    @Override
    public int height() {
        Set<String> sections = enabledSections();
        int rows = 0;
        if (sections.contains("world") && !PveStatsState.worldName().isEmpty()) rows += 1;
        if (sections.contains("hunter")) rows += hunterRows().size();
        if (sections.contains("mining") && MiningStatsState.isLive()) rows += miningRows().size();
        if (sections.contains("session") && MiningSessionState.isLive()) {
            int sr = sessionRows().size();
            if (sr > 0) rows += 1 + sr; // sub-header + metric rows
        }
        if (sections.contains("blocks") && MiningBlocksState.isLive()) {
            int br = blockRows().size();
            if (br > 0) rows += 1 + br; // sub-header + ore rows
        }
        if (sections.contains("kills")) rows += Math.max(1, killRows().size());
        if (sections.contains("drops") && !dropRows().isEmpty()) rows += dropRows().size();
        rows = Math.max(rows, 1);

        int padY = HudStyle.padY(id());
        return HudStyle.effectiveHeaderH(id()) + padY + HudStyle.rowH(id()) * rows + padY;
    }

    @Override public int defaultX(int screenWidth)  { return 10; }
    @Override public int defaultY(int screenHeight) { return 220; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        int w = width();
        int h = height();
        int rowY = HudStyle.drawChrome(ctx, fr, id(), w, h, "STATS");

        int padX = HudStyle.padX(id());
        int stripW = HudStyle.stripW();
        int stripGap = HudStyle.stripGap(id());
        int rowH = HudStyle.rowH(id());

        Set<String> sections = enabledSections();

        if (sections.contains("world") && !PveStatsState.worldName().isEmpty()) {
            String pretty = prettyWorld(PveStatsState.worldName());
            ctx.drawText(fr, Text.literal(pretty), padX + stripW + stripGap, rowY + 2, SUBHEADER, true);
            rowY += rowH;
        }

        if (sections.contains("hunter")) {
            for (MiningRow r : hunterRows()) {
                ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, r.accent);
                int textX = padX + stripW + stripGap;
                int textY = rowY + 2;
                int valW = fr.getWidth(r.value);
                ctx.drawText(fr, Text.literal(r.value), w - padX - valW, textY, VALUE_COLOR, true);
                ctx.drawText(fr, Text.literal(r.label), textX, textY,
                        (r.accent & 0x00FFFFFF) | 0xFF000000, true);
                rowY += rowH;
            }
        }

        if (sections.contains("mining") && MiningStatsState.isLive()) {
            for (MiningRow r : miningRows()) {
                ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, r.accent);
                int textX = padX + stripW + stripGap;
                int textY = rowY + 2;
                int valW = fr.getWidth(r.value);
                ctx.drawText(fr, Text.literal(r.value), w - padX - valW, textY, VALUE_COLOR, true);
                ctx.drawText(fr, Text.literal(r.label), textX, textY,
                        (r.accent & 0x00FFFFFF) | 0xFF000000, true);
                rowY += rowH;
            }
        }

        if (sections.contains("session") && MiningSessionState.isLive()) {
            List<MiningRow> sr = sessionRows();
            if (!sr.isEmpty()) {
                ctx.drawText(fr, Text.literal(sessionHeader()), padX + stripW + stripGap, rowY + 2, SUBHEADER, true);
                rowY += rowH;
                for (MiningRow r : sr) {
                    ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, r.accent);
                    int textX = padX + stripW + stripGap;
                    int textY = rowY + 2;
                    int valW = fr.getWidth(r.value);
                    ctx.drawText(fr, Text.literal(r.value), w - padX - valW, textY, VALUE_COLOR, true);
                    ctx.drawText(fr, Text.literal(r.label), textX, textY,
                            (r.accent & 0x00FFFFFF) | 0xFF000000, true);
                    rowY += rowH;
                }
            }
        }

        if (sections.contains("blocks") && MiningBlocksState.isLive()) {
            List<MiningRow> br = blockRows();
            if (!br.isEmpty()) {
                ctx.drawText(fr, Text.literal(blocksHeader()), padX + stripW + stripGap, rowY + 2, SUBHEADER, true);
                rowY += rowH;
                for (MiningRow r : br) {
                    ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, r.accent);
                    int textX = padX + stripW + stripGap;
                    int textY = rowY + 2;
                    int valW = fr.getWidth(r.value);
                    ctx.drawText(fr, Text.literal(r.value), w - padX - valW, textY, VALUE_COLOR, true);
                    ctx.drawText(fr, Text.literal(r.label), textX, textY,
                            (r.accent & 0x00FFFFFF) | 0xFF000000, true);
                    rowY += rowH;
                }
            }
        }

        if (sections.contains("kills")) {
            for (Row r : killRows()) {
                int accent = killColorFor(r.key);
                ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, accent);
                int textX = padX + stripW + stripGap;
                int textY = rowY + 2;
                String val = String.valueOf(r.value);
                int valW = fr.getWidth(val);
                ctx.drawText(fr, Text.literal(val), w - padX - valW, textY, VALUE_COLOR, true);
                ctx.drawText(fr, Text.literal(r.label), textX, textY,
                        (accent & 0x00FFFFFF) | 0xFF000000, true);
                rowY += rowH;
            }
        }

        if (sections.contains("drops") && !dropRows().isEmpty()) {
            for (Row r : dropRows()) {
                int accent = dropColorFor(r.key);
                ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, accent);
                int textX = padX + stripW + stripGap;
                int textY = rowY + 2;
                String val = String.valueOf(r.value);
                int valW = fr.getWidth(val);
                ctx.drawText(fr, Text.literal(val), w - padX - valW, textY, VALUE_COLOR, true);
                ctx.drawText(fr, Text.literal(r.label), textX, textY, 0xFFE6E8EE, true);
                rowY += rowH;
            }
        }
    }

    /** Live Hunter XP rows: rolling XP/h (while farming) + the session total. */
    private List<MiningRow> hunterRows() {
        List<MiningRow> out = new ArrayList<>(2);
        long rate = PveStatsState.hunterXpPerHour();
        long total = PveStatsState.sessionHunterXp();
        if (rate > 0)  out.add(new MiningRow("Hunter XP/h", formatCompact(rate),  HUNTER_ACCENT));
        if (total > 0) out.add(new MiningRow("Hunter XP",   formatCompact(total), HUNTER_ACCENT));
        return out;
    }

    private List<MiningRow> miningRows() {
        // Only show rates that are actually moving — a zero rate at this point
        // means the player isn't doing anything that produces that resource
        // (e.g. mining contraband ore yields XP but no money), so the row
        // would just clutter the widget.
        List<MiningRow> out = new ArrayList<>(4);
        long xp = MiningStatsState.xpPerHour();
        long energy = MiningStatsState.energyPerHour();
        long money = MiningStatsState.moneyPerHour();
        long blocks = MiningStatsState.blocksPerHour();
        if (xp > 0)     out.add(new MiningRow("XP/h",      formatCompact(xp),    0xFF8AE08A));
        if (energy > 0) out.add(new MiningRow("Energy/h",  formatCompact(energy),0xFF8AC2FF));
        if (money > 0)  out.add(new MiningRow("$/h",       "$" + formatCompact(money), 0xFFE6B05A));
        // Prestige-weighted blocks/h (emerald 1.25, calcite 2, debris 1.5) — reads
        // as progress-toward-prestige per hour, not a raw block tally.
        if (blocks > 0) out.add(new MiningRow("Blocks/h",  formatCompact(blocks), 0xFFC8C0B0));
        return out;
    }

    /** "Session (running) · 12m 30s" / "Session (paused) · 12m 30s". */
    private String sessionHeader() {
        String state = MiningSessionState.isRunning() ? "running" : "paused";
        return "Session (" + state + ") · " + formatDuration(MiningSessionState.elapsedMs());
    }

    /**
     * Mining-session rows: one per metric with a non-zero total. The value column
     * shows the running total, then optionally the session-average per-hour rate
     * ({@code total ÷ elapsed}) and/or the live rolling rate — each gated by its
     * own settings toggle, defaulting to "average on, live off".
     */
    private List<MiningRow> sessionRows() {
        List<MiningRow> out = new ArrayList<>(4);
        if (!MiningSessionState.isLive()) return out;
        boolean showAvg = sessionShowAvg();
        boolean showLive = sessionShowLive();
        addSessionRow(out, "XP",     MiningSessionState.totalXp(),     MiningStatsState.xpPerHour(),     showAvg, showLive, SESSION_ACCENT_XP,     false);
        addSessionRow(out, "Energy", MiningSessionState.totalEnergy(), MiningStatsState.energyPerHour(), showAvg, showLive, SESSION_ACCENT_ENERGY, false);
        addSessionRow(out, "$",      MiningSessionState.totalMoney(),  MiningStatsState.moneyPerHour(),  showAvg, showLive, SESSION_ACCENT_MONEY,  true);
        addSessionRow(out, "Blocks", MiningSessionState.totalBlocks(), MiningStatsState.blocksPerHour(), showAvg, showLive, SESSION_ACCENT_BLOCKS, false);
        return out;
    }

    private void addSessionRow(List<MiningRow> out, String label, long total, long liveRate,
                               boolean showAvg, boolean showLive, int accent, boolean money) {
        if (total <= 0) return;
        String p = money ? "$" : "";
        StringBuilder val = new StringBuilder(p).append(formatCompact(total));
        if (showAvg) {
            val.append("  ").append(p).append(formatCompact(MiningSessionState.perHour(total))).append("/h");
        }
        if (showLive && liveRate > 0) {
            // Distinguish the live rolling figure from the session average with a
            // trailing marker so the two /h numbers don't read as one.
            val.append("  ").append(p).append(formatCompact(liveRate)).append("/h·live");
        }
        out.add(new MiningRow(label, val.toString(), accent));
    }

    /** "1h 02m", "12m 30s", "45s" — mirrors the /miningtrack chat formatter. */
    private static String formatDuration(long ms) {
        long totalSec = Math.max(0L, ms) / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) return h + "h " + String.format(Locale.US, "%02dm", m);
        if (m > 0) return m + "m " + String.format(Locale.US, "%02ds", s);
        return s + "s";
    }

    private String blocksHeader() {
        return blocksSessionMode() ? "Blocks (session)" : "Blocks";
    }

    /** Per-ore rows for the blocks section: only the player's chosen ores that
     *  have a non-zero count in the current mode. Wire order is preserved. */
    private List<MiningRow> blockRows() {
        List<MiningRow> out = new ArrayList<>();
        if (!MiningBlocksState.isLive()) return out;
        Set<String> whitelist = visibleBlocks();
        boolean session = blocksSessionMode();
        for (com.aleks.prisonsmod.net.payload.MiningBlocksPayload.Row r : MiningBlocksState.rows()) {
            if (!whitelist.contains(r.oreId())) continue;
            long v = session ? r.session() : r.lifetime();
            if (v <= 0) continue;
            out.add(new MiningRow(prettyOre(r.oreId()), formatCompact(v), oreColor(r.oreId())));
        }
        return out;
    }

    /** "DIAMOND_ORE" → "Diamond", "ANCIENT_DEBRIS" → "Ancient Debris". */
    public static String prettyOre(String oreId) {
        if (oreId == null || oreId.isEmpty()) return "?";
        String s = oreId;
        if (s.endsWith("_ORE")) s = s.substring(0, s.length() - 4);
        return titleCase(s);
    }

    private static int oreColor(String oreId) {
        return switch (oreId) {
            case "DIAMOND_ORE"      -> 0xFF6BE0D6;
            case "EMERALD_ORE"      -> 0xFF49C96A;
            case "GOLD_ORE", "NETHER_GOLD_ORE" -> 0xFFE6C04A;
            case "IRON_ORE"         -> 0xFFD8C2B0;
            case "COPPER_ORE"       -> 0xFFE08A5A;
            case "REDSTONE_ORE"     -> 0xFFE05A5A;
            case "LAPIS_ORE"        -> 0xFF4A78E6;
            case "COAL_ORE"         -> 0xFF8A8A8A;
            case "NETHER_QUARTZ_ORE"-> 0xFFEAE0D6;
            case "ANCIENT_DEBRIS"   -> 0xFF9A6A4A;
            default                 -> 0xFFFFFFFF;
        };
    }

    /** Format compact numbers like the action bar does: 12345 → "12.3K". */
    static String formatCompact(long n) {
        if (n < 1_000L) return String.valueOf(n);
        if (n < 1_000_000L)         return trimZero(n / 1_000.0)         + "K";
        if (n < 1_000_000_000L)     return trimZero(n / 1_000_000.0)     + "M";
        if (n < 1_000_000_000_000L) return trimZero(n / 1_000_000_000.0) + "B";
        return trimZero(n / 1_000_000_000_000.0) + "T";
    }

    private static String trimZero(double v) {
        // 12.3 / 1.2 / 999 — but drop the trailing ".0" for whole-number values
        // to keep the column tight.
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private List<Row> killRows() {
        Map<String, Integer> all = PveStatsState.kills();
        Set<String> whitelist = visibleKills();
        List<Row> out = new ArrayList<>();

        // Defined order first.
        for (String key : KILL_ORDER) {
            Integer v = all.get(key);
            if (v == null || v <= 0) continue;
            if (!whitelist.isEmpty() && !whitelist.contains(key)) continue;
            out.add(new Row(key, killDisplayName(key), v));
        }
        // Any keys we don't recognise — show in arrival order so new mob types
        // appear without a code update.
        for (Map.Entry<String, Integer> e : all.entrySet()) {
            if (KILL_ORDER.contains(e.getKey())) continue;
            if (e.getValue() == null || e.getValue() <= 0) continue;
            if (!whitelist.isEmpty() && !whitelist.contains(e.getKey())) continue;
            out.add(new Row(e.getKey(), killDisplayName(e.getKey()), e.getValue()));
        }
        return out;
    }

    /**
     * Drops grouped by rarity tier. The server sends one entry per rarity
     * ("common".."mythic"); we render them rarest-first. Any non-rarity key
     * (e.g. an older server still sending per-item keys mid-rollout) falls
     * through to its own row so nothing is silently hidden.
     */
    private List<Row> dropRows() {
        Map<String, Integer> all = PveStatsState.drops();
        Map<String, Integer> buckets = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : all.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            buckets.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        List<Row> out = new ArrayList<>(buckets.size());
        // Known rarity tiers first, rarest → most common.
        for (String tier : RARITY_ORDER) {
            Integer v = buckets.remove(tier);
            if (v == null || v <= 0) continue;
            out.add(new Row(tier, titleCase(tier), v));
        }
        // Leftover non-rarity keys in arrival order.
        for (Map.Entry<String, Integer> e : buckets.entrySet()) {
            out.add(new Row(e.getKey(), dropDisplayName(e.getKey()), e.getValue()));
        }
        return out;
    }

    public static String killDisplayName(String key) {
        return switch (key) {
            case "shade"            -> "Shade";
            case "wraith"           -> "Wraith";
            case "pit_guard"        -> "Erebus Pit Guard";
            case "caravan"          -> "Caravan";
            case "warlord_caravan"  -> "Warlord Caravan";
            case "caravan_escort"   -> "Caravan Escort";
            case "echidna"          -> "Echidna";
            case "lamia"            -> "Lamia";
            case "hephaestus"       -> "Hephaestus";
            case "thanatos"         -> "Thanatos";
            default -> titleCase(key);
        };
    }

    private static String dropDisplayName(String key) {
        // Subtyped keys (e.g. "lootbox:rare_booster_box") drop the prefix and
        // show just the subtype ("Rare Booster Box") so the category isn't
        // repeated. Bare keys title-case as before.
        int colon = key.indexOf(':');
        if (colon > 0 && colon < key.length() - 1) {
            return titleCase(key.substring(colon + 1));
        }
        return titleCase(key);
    }

    private static String titleCase(String key) {
        String[] parts = key.split("_");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) out.append(p.substring(1).toLowerCase());
        }
        return out.toString();
    }

    private static int killColorFor(String key) {
        return switch (key) {
            case "shade"            -> 0xFFB0B0C8;
            case "wraith"           -> 0xFFD37CFF;
            case "pit_guard"        -> 0xFFCF5B5B;
            case "caravan"          -> 0xFFE6B05A;
            case "warlord_caravan"  -> 0xFFFF8A66;
            case "caravan_escort"   -> 0xFFE6E68A;
            case "echidna"          -> 0xFF8AE08A;
            case "lamia"            -> 0xFFC6A0FF;
            case "hephaestus"       -> 0xFFFFA070;
            case "thanatos"         -> 0xFF888888;
            default                 -> 0xFFFFFFFF;
        };
    }

    /** Per-rarity strip colour; unknown keys use the neutral drop blue. */
    private static int dropColorFor(String key) {
        return switch (key) {
            case "common"    -> 0xFFB0B0B8;
            case "uncommon"  -> 0xFF8AE08A;
            case "rare"      -> 0xFF8AC2FF;
            case "epic"      -> 0xFFC6A0FF;
            case "legendary" -> 0xFFE6B05A;
            case "mythic"    -> 0xFFFF7CC8;
            default          -> DROP_DEFAULT_ACCENT;
        };
    }

    private static String prettyWorld(String raw) {
        if (raw == null || raw.isEmpty()) return "—";
        String[] parts = raw.split("[_\\s-]+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) out.append(p.substring(1).toLowerCase());
        }
        return out.toString();
    }

    private static TextRenderer textRenderer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null ? mc.textRenderer : null;
    }

    private record Row(String key, String label, int value) {}
    private record MiningRow(String label, String value, int accent) {}
}
