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
 * renders kill / drop tallies plus the live Hunter XP rate. The current world
 * is shown as a sub-header so the widget auto-contexts to where the player
 * actually is.
 *
 * <p>Drops are grouped by <b>rarity</b> (the server classifies each drop with
 * the same tiers as the chat drop announcements) rather than per-item, so the
 * panel stays compact during a long farm.
 */
public final class StatsHud extends HudElement {

    public static final StatsHud INSTANCE = new StatsHud();

    /** Setting key: comma-separated list of section keys to show. */
    public static final String KEY_SECTIONS = "sections";
    /** Setting key: comma-separated list of kill kinds the player wants visible (empty = all). */
    public static final String KEY_VISIBLE_KILLS = "visible_kills";

    /**
     * Drop rarity tiers, rarest → most common. The server tallies each drop
     * into one of these buckets (mirroring the plugin's {@code LootRarity}); the
     * HUD renders one row per non-empty tier in this order.
     */
    private static final List<String> RARITY_ORDER =
            List.of("mythic", "legendary", "epic", "rare", "uncommon", "common");

    public static final List<String> ALL_SECTIONS = List.of("world", "hunter", "mining", "kills", "drops");
    public static final Set<String> DEFAULT_SECTIONS = new LinkedHashSet<>(ALL_SECTIONS);

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
        // no rows. Kills/drops/hunter are session-only, mining is live-only,
        // so the HUD naturally disappears once they're all quiet.
        Set<String> sections = enabledSections();
        boolean miningLive = sections.contains("mining") && MiningStatsState.isLive();
        return miningLive
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
                rowY = drawValueRow(ctx, fr, r.label, r.value, r.accent, padX, stripW, stripGap, rowH, rowY, w);
            }
        }

        if (sections.contains("mining") && MiningStatsState.isLive()) {
            for (MiningRow r : miningRows()) {
                rowY = drawValueRow(ctx, fr, r.label, r.value, r.accent, padX, stripW, stripGap, rowH, rowY, w);
            }
        }

        if (sections.contains("kills")) {
            for (Row r : killRows()) {
                int accent = killColorFor(r.key);
                rowY = drawValueRow(ctx, fr, r.label, String.valueOf(r.value), accent, padX, stripW, stripGap, rowH, rowY, w);
            }
        }

        if (sections.contains("drops") && !dropRows().isEmpty()) {
            for (Row r : dropRows()) {
                int accent = dropColorFor(r.key);
                int textX = padX + stripW + stripGap;
                int textY = rowY + 2;
                String val = String.valueOf(r.value);
                int valW = fr.getWidth(val);
                ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, accent);
                ctx.drawText(fr, Text.literal(val), w - padX - valW, textY, VALUE_COLOR, true);
                ctx.drawText(fr, Text.literal(r.label), textX, textY, 0xFFE6E8EE, true);
                rowY += rowH;
            }
        }
    }

    /** Draw one "label … value" row with a coloured left strip; returns the next rowY. */
    private int drawValueRow(DrawContext ctx, TextRenderer fr, String label, String value, int accent,
                             int padX, int stripW, int stripGap, int rowH, int rowY, int w) {
        ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, accent);
        int textX = padX + stripW + stripGap;
        int textY = rowY + 2;
        int valW = fr.getWidth(value);
        ctx.drawText(fr, Text.literal(value), w - padX - valW, textY, VALUE_COLOR, true);
        ctx.drawText(fr, Text.literal(label), textX, textY, (accent & 0x00FFFFFF) | 0xFF000000, true);
        return rowY + rowH;
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
