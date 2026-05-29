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
 * renders kill / drop tallies. The current world is shown as a sub-header so
 * the widget auto-contexts to where the player actually is.
 */
public final class StatsHud extends HudElement {

    public static final StatsHud INSTANCE = new StatsHud();

    /** Setting key: comma-separated list of section keys to show. */
    public static final String KEY_SECTIONS = "sections";
    /** Setting key: comma-separated list of kill kinds the player wants visible (empty = all). */
    public static final String KEY_VISIBLE_KILLS = "visible_kills";
    /**
     * Setting key: when true, lootbox / lockbox / seasonal_crate drops show
     * one row per subtype (e.g. "Rare Booster Box") instead of being lumped
     * under a single "Lootbox" row. The server always sends the full
     * `lootbox:<subtype>` key — the mod aggregates locally based on this flag.
     */
    public static final String KEY_SPLIT_LOOTBOX_SUBTYPES = "split_lootbox_subtypes";

    /** Drop-key prefixes that the mod can aggregate or split. Matches the
     *  server-side allowlist in {@code LootTableRegistry.rollAndDropKillerOnly}. */
    private static final Set<String> SPLITTABLE_DROP_PREFIXES = Set.of("lootbox", "lockbox", "seasonal_crate");

    public static final List<String> ALL_SECTIONS = List.of("world", "mining", "kills", "drops");
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

    private StatsHud() {}

    @Override public String id() { return "stats"; }
    @Override public String displayName() { return "Stats"; }

    @Override
    public boolean isVisible() {
        if (!FeatureToggles.isStatsHudEnabled()) return false;
        if (HudStyle.isAlwaysShown(id())) return true;
        // Only show when the player has something to look at — bare world
        // name on its own meant the widget hung around in spawn/hub with
        // no rows. Kills/drops are session-only, mining is live-only, so
        // the HUD naturally disappears once both are quiet.
        Set<String> sections = enabledSections();
        boolean miningLive = sections.contains("mining") && MiningStatsState.isLive();
        return miningLive
            || !PveStatsState.kills().isEmpty()
            || !PveStatsState.drops().isEmpty();
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

    /** When true, lootbox/lockbox/crate rows split by subtype instead of aggregating. */
    public boolean splitLootboxSubtypes() {
        return HudSettings.getBoolean(id(), KEY_SPLIT_LOOTBOX_SUBTYPES, false);
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
                int accent = 0xFF8AC2FF;
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

    private List<MiningRow> miningRows() {
        // Only show rates that are actually moving — a zero rate at this point
        // means the player isn't doing anything that produces that resource
        // (e.g. mining contraband ore yields XP but no money), so the row
        // would just clutter the widget.
        List<MiningRow> out = new ArrayList<>(3);
        long xp = MiningStatsState.xpPerHour();
        long energy = MiningStatsState.energyPerHour();
        long money = MiningStatsState.moneyPerHour();
        if (xp > 0)     out.add(new MiningRow("XP/h",      formatCompact(xp),    0xFF8AE08A));
        if (energy > 0) out.add(new MiningRow("Energy/h",  formatCompact(energy),0xFF8AC2FF));
        if (money > 0)  out.add(new MiningRow("$/h",       "$" + formatCompact(money), 0xFFE6B05A));
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

    private List<Row> dropRows() {
        Map<String, Integer> all = PveStatsState.drops();
        boolean split = splitLootboxSubtypes();
        // First pass: aggregate any splittable-prefix keys into the bare
        // prefix when split is off. Stable iteration order via LinkedHashMap
        // matches the server's first-seen order.
        Map<String, Integer> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : all.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            String key = e.getKey();
            if (!split) {
                int colon = key.indexOf(':');
                if (colon > 0) {
                    String prefix = key.substring(0, colon);
                    if (SPLITTABLE_DROP_PREFIXES.contains(prefix)) {
                        grouped.merge(prefix, e.getValue(), Integer::sum);
                        continue;
                    }
                }
            }
            grouped.merge(key, e.getValue(), Integer::sum);
        }
        List<Row> out = new ArrayList<>(grouped.size());
        for (Map.Entry<String, Integer> e : grouped.entrySet()) {
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
