package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.net.payload.OutpostStatePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Moveable widget showing your gang's owned outposts. The server only sends
 * outposts that are fully owned by the player's gang, so this HUD collapses
 * when you hold no outposts.
 *
 * <p>Layout per row:
 * {@code [accent strip] OutpostName  GangName  HELD}
 * Status is "HELD" while secure; switches to a red "XX%" if the outpost is
 * being neutralized (percentDecreasing) so you can see it's under attack.
 */
public final class OutpostHud extends HudElement {

    public static final OutpostHud INSTANCE = new OutpostHud();

    private static final int MIN_WIDTH = 150;

    // Colors
    private static final int COLOR_HELD    = 0xFF57E89C; // green-teal — fully held and secure
    private static final int COLOR_DANGER  = 0xFFFF5C5C; // red — being neutralized (under attack)
    private static final int COLOR_GANG    = 0xFFE6E8EE;

    public static final String KEY_VISIBLE = "visible";
    private static final Set<String> ALL_IDS = Set.of("chain", "gold", "iron", "diamond", "netherite");
    private static final Set<String> DEFAULT_VISIBLE = ALL_IDS;

    private OutpostHud() {}

    @Override public String id()          { return "outpost"; }
    @Override public String displayName() { return "Outpost"; }

    @Override
    public Screen openSettings(Screen parent) {
        return new OutpostHudSettingsScreen(parent, this);
    }

    @Override
    public boolean isVisible() {
        return (FeatureToggles.isOutpostHudEnabled() && !OutpostState.isEmpty())
                || HudStyle.isAlwaysShown(id());
    }

    @Override public String editorPlaceholder() { return "Outpost"; }

    @Override
    public int width() {
        TextRenderer fr = textRenderer();
        if (fr == null) return MIN_WIDTH;
        int padX    = HudStyle.padX(id());
        int colGap  = HudStyle.columnGap(id());
        int stripOff = HudStyle.stripW() + HudStyle.stripGap(id());
        int widest  = fr.getWidth("OUTPOST");
        for (Row r : computeRows()) {
            int nameW  = fr.getWidth(r.label);
            int gangW  = r.gangName.isEmpty() ? 0 : (fr.getWidth(r.gangName) + colGap);
            int pctW   = fr.getWidth(r.pctLabel);
            widest = Math.max(widest, nameW + gangW + colGap + pctW);
        }
        return Math.max(MIN_WIDTH, padX + stripOff + widest + padX);
    }

    @Override
    public int height() {
        int rows = Math.max(1, computeRows().size());
        int padY = HudStyle.padY(id());
        return HudStyle.effectiveHeaderH(id()) + padY + HudStyle.rowH(id()) * rows + padY;
    }

    @Override public int defaultX(int screenWidth)  { return 10; }
    @Override public int defaultY(int screenHeight) { return 80; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        List<Row> rows = computeRows();
        int w = width();
        int h = height();

        int rowY    = HudStyle.drawChrome(ctx, fr, id(), w, h, "OUTPOST");
        int padX    = HudStyle.padX(id());
        int stripW  = HudStyle.stripW();
        int stripGap = HudStyle.stripGap(id());
        int rowH    = HudStyle.rowH(id());
        int colGap  = HudStyle.columnGap(id());

        for (Row r : rows) {
            // accent strip
            ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, r.accent);

            int textX = padX + stripW + stripGap;
            int textY = rowY + 2;

            // right-aligned percent/status label
            int pctW  = fr.getWidth(r.pctLabel);
            int pctX  = w - padX - pctW;
            ctx.drawText(fr, Text.literal(r.pctLabel), pctX, textY, r.pctColor, true);

            // gang name, right of the outpost label
            if (!r.gangName.isEmpty()) {
                int gangW  = fr.getWidth(r.gangName);
                int gangX  = pctX - colGap - gangW;
                ctx.drawText(fr, Text.literal(r.gangName), gangX, textY, COLOR_GANG, true);
            }

            // outpost name (left side)
            ctx.drawText(fr, Text.literal(r.label), textX, textY, r.accent, true);

            rowY += rowH;
        }
    }

    private List<Row> computeRows() {
        Set<String> visible = HudSettings.getStringSet(id(), KEY_VISIBLE, DEFAULT_VISIBLE);
        List<OutpostStatePayload.Entry> all = OutpostState.entries();
        List<Row> out = new ArrayList<>(all.size());
        for (OutpostStatePayload.Entry e : all) {
            if (!visible.contains(e.id())) continue;
            String label    = capitalize(e.id());
            String gangName = e.gangName();
            String pctLabel;
            int pctColor;
            int accent;
            // All server-sent entries are owned by the player's gang.
            // Show "HELD" when secure; show % in red if actively being neutralized.
            if (e.percentDecreasing() && e.percent() < 100) {
                pctLabel = e.percent() + "%";
                pctColor = COLOR_DANGER;
                accent   = COLOR_DANGER;
            } else {
                pctLabel = "HELD";
                pctColor = COLOR_HELD;
                accent   = COLOR_HELD;
            }
            out.add(new Row(label, gangName, pctLabel, pctColor, accent));
        }
        return out;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private record Row(String label, String gangName, String pctLabel, int pctColor, int accent) {}

    private static TextRenderer textRenderer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null ? mc.textRenderer : null;
    }
}
