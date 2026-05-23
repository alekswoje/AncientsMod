package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.client.FeatureToggles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    public static final List<String> ALL_SECTIONS = List.of("world", "kills", "drops");
    public static final Set<String> DEFAULT_SECTIONS = new LinkedHashSet<>(ALL_SECTIONS);

    /** Display order for kill kinds — anything not listed renders after these, in arrival order. */
    public static final List<String> KILL_ORDER = List.of(
            "shade", "wraith",
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
        // Hide when there's literally nothing to show. The widget still
        // claims space in the editor through the placeholder.
        return !PveStatsState.kills().isEmpty()
            || !PveStatsState.drops().isEmpty()
            || !PveStatsState.worldName().isEmpty();
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
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : all.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            out.add(new Row(e.getKey(), dropDisplayName(e.getKey()), e.getValue()));
        }
        return out;
    }

    public static String killDisplayName(String key) {
        return switch (key) {
            case "shade"            -> "Shade";
            case "wraith"           -> "Wraith";
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
}
