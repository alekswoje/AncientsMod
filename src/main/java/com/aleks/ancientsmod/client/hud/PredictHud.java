package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.render.MinePredictRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Moveable Mine Prediction HUD — live counters from {@link MinePredictRenderer}
 * so a player can tell whether mining "feels laggy" because predicted breaks are
 * being rolled back, or because confirmations are arriving late, or neither.
 *
 * <p>Three rows: predicted swaps vs. server-confirmed vs. rolled back; the
 * confirm delay (how long after the local break the server's block update
 * landed); and the last rollback's reason and age. Diagnostic — off by default.
 * Same numbers as {@code /ancientsmod predict}.
 */
public final class PredictHud extends HudElement {

    public static final PredictHud INSTANCE = new PredictHud();

    private static final int MIN_WIDTH = 96;
    private static final int OK_COLOR   = 0xFF9BE39B;
    private static final int BAD_COLOR  = 0xFFF08A8A;
    private static final int TEXT_COLOR = 0xFFE6E8EE;
    private static final int DIM_COLOR  = 0xFFBFC4CC;

    private PredictHud() {}

    @Override public String id() { return "predict"; }
    @Override public String displayName() { return "Mine Prediction"; }

    @Override
    public boolean isVisible() { return FeatureToggles.isPredictHudEnabled(); }

    @Override
    public String editorPlaceholder() { return "Mine prediction"; }

    @Override
    public Screen openSettings(Screen parent) {
        return new PredictHudSettingsScreen(parent, this);
    }

    @Override
    public int width() {
        TextRenderer fr = textRenderer();
        if (fr == null) return MIN_WIDTH;
        int padX = HudStyle.padX(id());
        int leftPad = padX + HudStyle.stripW() + HudStyle.stripGap(id());
        int widest = fr.getWidth("PREDICT");
        widest = Math.max(widest, fr.getWidth(swapsLine()));
        widest = Math.max(widest, fr.getWidth(latencyLine()));
        widest = Math.max(widest, fr.getWidth(lastLine()));
        return Math.max(MIN_WIDTH, leftPad + widest + padX);
    }

    @Override
    public int height() {
        int padY = HudStyle.padY(id());
        return HudStyle.effectiveHeaderH(id()) + padY + HudStyle.rowH(id()) * 3 + padY;
    }

    @Override public int defaultX(int screenWidth)  { return screenWidth - 130; }
    @Override public int defaultY(int screenHeight) { return 40; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        int w = width();
        int h = height();
        int rowY = HudStyle.drawChrome(ctx, fr, id(), w, h, "PREDICT");

        int padX = HudStyle.padX(id());
        int stripW = HudStyle.stripW();
        int stripGap = HudStyle.stripGap(id());
        int rowH = HudStyle.rowH(id());
        int textX = padX + stripW + stripGap;

        MinePredictRenderer.Stats s = MinePredictRenderer.stats();
        int strip = s.rollbacks() == 0 ? OK_COLOR : BAD_COLOR;
        ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, strip);
        ctx.drawText(fr, Text.literal(swapsLine()), textX, rowY + 2, TEXT_COLOR, true);
        rowY += rowH;
        ctx.drawText(fr, Text.literal(latencyLine()), textX, rowY + 2, DIM_COLOR, true);
        rowY += rowH;
        ctx.drawText(fr, Text.literal(lastLine()), textX, rowY + 2,
                s.rollbacks() == 0 ? DIM_COLOR : BAD_COLOR, true);
    }

    /** e.g. {@code 142 broke · 140 ok · 2 back}. */
    private static String swapsLine() {
        MinePredictRenderer.Stats s = MinePredictRenderer.stats();
        return String.format(Locale.US, "%d broke · %d ok · %d back", s.swaps, s.confirms, s.rollbacks());
    }

    /** e.g. {@code confirm 96ms avg · 240 max}. */
    private static String latencyLine() {
        MinePredictRenderer.Stats s = MinePredictRenderer.stats();
        if (s.confirms == 0) return "confirm —";
        return String.format(Locale.US, "confirm %dms avg · %d max", s.confirmLatencyAvgMs(), s.confirmLatencyMaxMs);
    }

    /** e.g. {@code last back: server-moved-on 12s ago}, or the late-start count when clean. */
    private static String lastLine() {
        MinePredictRenderer.Stats s = MinePredictRenderer.stats();
        if (s.rollbacks() == 0) {
            return String.format(Locale.US, "no rollbacks · %d late starts", s.lateStartAdopted);
        }
        long ago = Math.max(0L, System.currentTimeMillis() - s.lastRollbackMs) / 1000L;
        return String.format(Locale.US, "last back: %s %ds ago", s.lastRollbackReason, ago);
    }

    private static TextRenderer textRenderer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null ? mc.textRenderer : null;
    }
}
