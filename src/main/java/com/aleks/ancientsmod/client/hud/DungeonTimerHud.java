package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.net.payload.DungeonTimerPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Moveable dungeon run timer. Appears when the server starts streaming the
 * run clock (the START-room "GO!" moment), ticks live between 1 Hz
 * heartbeats, and freezes green/red for a few seconds on completion/wipe
 * before fading with the wire.
 */
public final class DungeonTimerHud extends HudElement {

    public static final DungeonTimerHud INSTANCE = new DungeonTimerHud();

    private static final int MIN_WIDTH = 96;
    private static final int ACCENT_RUNNING  = 0xFFC6A0FF;
    private static final int ACCENT_COMPLETE = 0xFF7FE07F;
    private static final int ACCENT_WIPED    = 0xFFFF7070;

    private DungeonTimerHud() {}

    @Override public String id() { return "dungeon_timer"; }
    @Override public String displayName() { return "Dungeon Timer"; }

    @Override
    public boolean isVisible() {
        return FeatureToggles.isDungeonTimerHudEnabled() && DungeonTimerState.isLive();
    }

    @Override
    public String editorPlaceholder() { return "Dungeon Timer"; }

    @Override
    public int width() {
        TextRenderer fr = textRenderer();
        if (fr == null) return MIN_WIDTH;
        int padX = HudStyle.padX(id());
        int leftPad = padX + HudStyle.stripW() + HudStyle.stripGap(id());
        int rowW = fr.getWidth(label()) + HudStyle.columnGap(id()) + fr.getWidth(clockText());
        int widest = Math.max(fr.getWidth("DUNGEON"), rowW);
        return Math.max(MIN_WIDTH, leftPad + widest + padX);
    }

    @Override
    public int height() {
        int padY = HudStyle.padY(id());
        return HudStyle.effectiveHeaderH(id()) + padY + HudStyle.rowH(id()) + padY;
    }

    @Override public int defaultX(int screenWidth)  { return 10; }
    @Override public int defaultY(int screenHeight) { return 40; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        int w = width();
        int h = height();
        int rowY = HudStyle.drawChrome(ctx, fr, id(), w, h, "DUNGEON");

        int padX = HudStyle.padX(id());
        int stripW = HudStyle.stripW();
        int stripGap = HudStyle.stripGap(id());
        int rowH = HudStyle.rowH(id());
        int accent = accent();

        ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, accent);

        int textX = padX + stripW + stripGap;
        int textY = rowY + 2;

        String clock = clockText();
        int rightX = w - padX - fr.getWidth(clock);
        ctx.drawText(fr, Text.literal(clock), rightX, textY, HudStyle.TIME_COLOR, true);
        ctx.drawText(fr, Text.literal(label()), textX, textY,
                (accent & 0x00FFFFFF) | 0xFF000000, true);
    }

    private String label() {
        return switch (DungeonTimerState.state()) {
            case DungeonTimerPayload.STATE_COMPLETE -> "Complete";
            case DungeonTimerPayload.STATE_WIPED -> "Wiped";
            default -> DungeonTimerState.tier() > 0
                    ? "Tier " + DungeonTimerState.tier() : "Run";
        };
    }

    private int accent() {
        return switch (DungeonTimerState.state()) {
            case DungeonTimerPayload.STATE_COMPLETE -> ACCENT_COMPLETE;
            case DungeonTimerPayload.STATE_WIPED -> ACCENT_WIPED;
            default -> ACCENT_RUNNING;
        };
    }

    private String clockText() {
        long totalSec = DungeonTimerState.liveElapsedMs() / 1000L;
        long m = totalSec / 60;
        long s = totalSec % 60;
        if (m < 60) return String.format(Locale.US, "%d:%02d", m, s);
        long hrs = m / 60;
        long rm = m % 60;
        return String.format(Locale.US, "%d:%02d:%02d", hrs, rm, s);
    }

    private static TextRenderer textRenderer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null ? mc.textRenderer : null;
    }
}
