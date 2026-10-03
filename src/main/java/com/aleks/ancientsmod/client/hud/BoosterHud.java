package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.BoosterUpdatePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-panel widget showing every currently active booster — global, personal,
 * comp, chat games — as a stack of rows. Reads from {@link BoosterState} every
 * frame, so countdowns animate smoothly between server heartbeats.
 *
 * <p>Layout per row: {@code [accent strip] label  xmult        time}. The 1px
 * strip color identifies the source (global / personal / comp / chatgame); the
 * label is body text, the multiplier a Flame value and the time Ash, right-aligned
 * to the panel edge.
 */
public final class BoosterHud extends HudElement {

    public static final BoosterHud INSTANCE = new BoosterHud();

    private static final int MIN_WIDTH    = 158;

    /** Setting key: collapse N matching kinds of the same source into one "All" row. Default ON. */
    public static final String KEY_COLLAPSE = "collapse";

    private static final double MULT_EPSILON = 1e-9;

    private BoosterHud() {}

    @Override public String id() { return "boosters"; }
    @Override public String displayName() { return "Boosters"; }

    @Override
    public Screen openSettings(Screen parent) {
        return new BoosterHudSettingsScreen(parent, this);
    }

    private boolean collapseEnabled() {
        return HudSettings.getBoolean(id(), KEY_COLLAPSE, true);
    }

    @Override
    public boolean isVisible() {
        return FeatureToggles.isBoosterHudEnabled() && !BoosterState.entries().isEmpty();
    }

    @Override
    public String editorPlaceholder() {
        return "Boosters";
    }

    @Override
    public int width() {
        TextRenderer fr = textRenderer();
        if (fr == null) return MIN_WIDTH;
        int padX = HudStyle.padX(id());
        int colGap = HudStyle.columnGap(id());
        int leftPad = padX + HudStyle.stripW() + HudStyle.stripGap(id());
        int widest = fr.getWidth("BOOSTERS");
        for (Row r : computeRows()) {
            int labelW = fr.getWidth(r.label);
            int multW  = fr.getWidth(formatMultForSource(r.source, r.multiplier));
            int timeW  = fr.getWidth(formatDuration(r.secondsRemaining));
            int pausedW = r.paused ? colGap + fr.getWidth("II") : 0;
            int rowW = labelW + colGap + multW + colGap + timeW + pausedW;
            if (rowW > widest) widest = rowW;
        }
        return Math.max(MIN_WIDTH, leftPad + widest + padX);
    }

    @Override
    public int height() {
        int rows = computeRows().size();
        int contentRows = Math.max(rows, 1);
        int padY = HudStyle.padY(id());
        return HudStyle.effectiveHeaderH(id()) + padY + HudStyle.rowH(id()) * contentRows + padY;
    }

    @Override public int defaultX(int screenWidth)  { return 10; }
    @Override public int defaultY(int screenHeight) { return 10; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        List<Row> rows = computeRows();
        int w = width();
        int h = height();

        int rowY = HudStyle.drawChrome(ctx, fr, id(), w, h, "BOOSTERS");

        int padX = HudStyle.padX(id());
        int stripW = HudStyle.stripW();
        int stripGap = HudStyle.stripGap(id());
        int rowH = HudStyle.rowH(id());
        int colGap = HudStyle.columnGap(id());

        for (Row r : rows) {
            GlassRender.accentStrip(ctx, padX, rowY, rowH - 2, r.accent);
            int paused = GlassTheme.textMuted();

            int textX = padX + stripW + stripGap;
            int textY = rowY + 2;

            String mult = formatMultForSource(r.source, r.multiplier);
            String time = formatDuration(r.secondsRemaining);

            int timeW = fr.getWidth(time);
            int timeX = w - padX - timeW;
            ctx.drawText(fr, Text.literal(time), timeX, textY, r.paused ? paused : HudStyle.TIME_COLOR, true);

            int multW = fr.getWidth(mult);
            int multX = timeX - colGap - multW;
            ctx.drawText(fr, Text.literal(mult), multX, textY, r.paused ? paused : GlassTheme.VALUE, true);

            ctx.drawText(fr, Text.literal(r.label), textX, textY,
                    r.paused ? paused : GlassTheme.textDim(), true);

            if (r.paused) {
                int labelW = fr.getWidth(r.label);
                ctx.drawText(fr, Text.literal("II"), textX + labelW + 4, textY, paused, true);
            }

            rowY += rowH;
        }
    }

    private List<Row> computeRows() {
        List<BoosterUpdatePayload.Entry> entries = BoosterState.entries();
        if (entries.isEmpty()) return List.of();
        if (!collapseEnabled()) {
            List<Row> out = new ArrayList<>(entries.size());
            for (BoosterUpdatePayload.Entry e : entries) out.add(rowFromEntry(e));
            return out;
        }

        Map<Byte, List<BoosterUpdatePayload.Entry>> bySource = new HashMap<>();
        List<Byte> orderedSources = new ArrayList<>();
        List<Row> standalone = new ArrayList<>();
        for (BoosterUpdatePayload.Entry e : entries) {
            // Hunter has no global/comp variant, so it never belongs to an "All" row.
            if (e.kind() == Protocol.BOOSTER_KIND_HUNTER) {
                standalone.add(rowFromEntry(e));
                continue;
            }
            if (!bySource.containsKey(e.source())) orderedSources.add(e.source());
            bySource.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e);
        }

        List<Row> out = new ArrayList<>();
        for (Byte source : orderedSources) {
            List<BoosterUpdatePayload.Entry> group = bySource.get(source);
            int expectedKinds = (source == Protocol.BOOSTER_SRC_CHATGAME) ? 2 : 4;

            if (group.size() == expectedKinds && allMatching(group)) {
                BoosterUpdatePayload.Entry first = group.get(0);
                // Show the SHORTEST remaining time, not the longest. A collapsed
                // row stands for "all N kinds at this multiplier"; that claim
                // stops being true the moment the first one expires, so the row
                // must count down to the soonest expiry. (Once it does expire the
                // group no longer has all N kinds and the row splits back out
                // into per-kind rows automatically.)
                int shortest = Integer.MAX_VALUE;
                for (BoosterUpdatePayload.Entry e : group) shortest = Math.min(shortest, BoosterState.liveSecondsRemaining(e));
                if (shortest == Integer.MAX_VALUE) shortest = 0;
                out.add(new Row(
                        collapsedLabelFor(source),
                        first.multiplier(),
                        shortest,
                        first.paused(),
                        collapsedColorFor(source),
                        source
                ));
            } else {
                for (BoosterUpdatePayload.Entry e : group) out.add(rowFromEntry(e));
            }
        }
        out.addAll(standalone);
        return out;
    }

    private static boolean allMatching(List<BoosterUpdatePayload.Entry> group) {
        BoosterUpdatePayload.Entry first = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            BoosterUpdatePayload.Entry e = group.get(i);
            if (Math.abs(e.multiplier() - first.multiplier()) > MULT_EPSILON) return false;
            if (e.paused() != first.paused()) return false;
        }
        return true;
    }

    private static Row rowFromEntry(BoosterUpdatePayload.Entry e) {
        return new Row(
                formatLabel(e),
                e.multiplier(),
                BoosterState.liveSecondsRemaining(e),
                e.paused(),
                colorFor(e),
                e.source()
        );
    }

    private static String collapsedLabelFor(byte source) {
        if (source == Protocol.BOOSTER_SRC_GLOBAL)   return "Global All";
        if (source == Protocol.BOOSTER_SRC_PERSONAL) return "Personal All";
        if (source == Protocol.BOOSTER_SRC_COMP)     return "Comp All";
        if (source == Protocol.BOOSTER_SRC_CHATGAME) return "Chat All";
        return "All";
    }

    private static int collapsedColorFor(byte source) {
        if (source == Protocol.BOOSTER_SRC_GLOBAL)   return 0xFFFFC857;
        if (source == Protocol.BOOSTER_SRC_PERSONAL) return GlassTheme.BRONZE;
        if (source == Protocol.BOOSTER_SRC_COMP)     return GlassTheme.ACCENT;
        if (source == Protocol.BOOSTER_SRC_CHATGAME) return 0xFF6FE8C9;
        return GlassTheme.BRONZE;
    }

    private record Row(String label, double multiplier, int secondsRemaining, boolean paused, int accent, byte source) {}

    private static String formatLabel(BoosterUpdatePayload.Entry e) {
        String src = sourcePrefix(e.source());
        String kind = kindLabel(e.kind());
        return src.isEmpty() ? kind : src + " " + kind;
    }

    private static String sourcePrefix(byte source) {
        if (source == Protocol.BOOSTER_SRC_GLOBAL)   return "Global";
        if (source == Protocol.BOOSTER_SRC_PERSONAL) return "";
        if (source == Protocol.BOOSTER_SRC_COMP)     return "Comp";
        if (source == Protocol.BOOSTER_SRC_CHATGAME) return "Chat";
        return "?";
    }

    private static String kindLabel(byte kind) {
        if (kind == Protocol.BOOSTER_KIND_XP)     return "XP";
        if (kind == Protocol.BOOSTER_KIND_ENERGY) return "Energy";
        if (kind == Protocol.BOOSTER_KIND_ORE)    return "Ore";
        if (kind == Protocol.BOOSTER_KIND_SHARD)  return "Shard";
        if (kind == Protocol.BOOSTER_KIND_HUNTER) return "Hunter XP";
        return "?";
    }

    private static int colorFor(BoosterUpdatePayload.Entry e) {
        if (e.source() == Protocol.BOOSTER_SRC_GLOBAL)   return 0xFFFFC857;
        if (e.source() == Protocol.BOOSTER_SRC_COMP)     return GlassTheme.ACCENT;
        if (e.source() == Protocol.BOOSTER_SRC_CHATGAME) return 0xFF6FE8C9;
        if (e.kind() == Protocol.BOOSTER_KIND_XP)     return 0xFF8AE08A;
        if (e.kind() == Protocol.BOOSTER_KIND_ENERGY) return 0xFF8AC2FF;
        if (e.kind() == Protocol.BOOSTER_KIND_ORE)    return 0xFFE6B05A;
        if (e.kind() == Protocol.BOOSTER_KIND_SHARD)  return 0xFFE68AE0;
        if (e.kind() == Protocol.BOOSTER_KIND_HUNTER) return 0xFFB57BFF;
        return GlassTheme.BRONZE;
    }

    private static String formatMult(double mult) {
        if (mult == Math.floor(mult)) return "x" + (int) mult;
        String s = String.format(Locale.US, "%.2f", mult);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return "x" + s;
    }

    // Comp boosters are an additive-pool boost (server-side comp layer), not their own 1.#x multiplier
    private static String formatMultForSource(byte source, double mult) {
        if (source == Protocol.BOOSTER_SRC_COMP) {
            long pct = Math.round((mult - 1.0) * 100);
            return pct + "% increased";
        }
        return formatMult(mult);
    }

    private static String formatDuration(int seconds) {
        if (seconds < 60) return seconds + "s";
        int m = seconds / 60;
        int s = seconds % 60;
        if (m < 60) return String.format(Locale.US, "%d:%02d", m, s);
        int h = m / 60;
        int rm = m % 60;
        return String.format(Locale.US, "%d:%02d:%02d", h, rm, s);
    }

    private static TextRenderer textRenderer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null ? mc.textRenderer : null;
    }
}
