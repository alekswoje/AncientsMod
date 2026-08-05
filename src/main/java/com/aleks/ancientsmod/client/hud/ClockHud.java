package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Moveable Clock HUD — the player's own wall-clock time, read from the OS
 * timezone. Handy for anyone who plays fullscreen and can't see the system
 * clock, and for lining up a real-world time against an event countdown.
 *
 * <p>Purely local: {@link ZoneId#systemDefault()} plus {@link LocalDateTime},
 * no server packet involved. (The mod separately reports this zone to the
 * server on join via {@code PKT_CLIENT_TIMEZONE} so server-rendered timestamps
 * agree with what this widget shows.)
 *
 * <p>Off by default (new HUD clutter); flip it on in Settings → HUDs.
 */
public final class ClockHud extends HudElement {

    public static final ClockHud INSTANCE = new ClockHud();

    /** 24-hour clock (default) vs 12-hour with AM/PM. */
    public static final String KEY_24H = "clock_24h";
    /** Append seconds. */
    public static final String KEY_SECONDS = "clock_seconds";
    /** Append the timezone's short name (e.g. "CEST"). */
    public static final String KEY_SHOW_ZONE = "clock_show_zone";
    /** Show the date on a second row. */
    public static final String KEY_SHOW_DATE = "clock_show_date";

    private static final int MIN_WIDTH  = 62;
    private static final int TIME_COLOR = 0xFFE6E8EE;
    private static final int DATE_COLOR = 0xFFBFC4CC;

    private ClockHud() {}

    @Override public String id() { return "clock"; }
    @Override public String displayName() { return "Clock"; }

    @Override
    public boolean isVisible() { return FeatureToggles.isClockHudEnabled(); }

    @Override
    public String editorPlaceholder() { return "Clock"; }

    @Override
    public Screen openSettings(Screen parent) {
        return new ClockHudSettingsScreen(parent, this);
    }

    public boolean use24Hour()  { return HudSettings.getBoolean(id(), KEY_24H, true); }
    public boolean showSeconds() { return HudSettings.getBoolean(id(), KEY_SECONDS, false); }
    public boolean showZone()    { return HudSettings.getBoolean(id(), KEY_SHOW_ZONE, false); }
    public boolean showDate()    { return HudSettings.getBoolean(id(), KEY_SHOW_DATE, false); }

    @Override
    public int width() {
        TextRenderer fr = textRenderer();
        if (fr == null) return MIN_WIDTH;
        int padX = HudStyle.padX(id());
        int leftPad = padX + HudStyle.stripW() + HudStyle.stripGap(id());
        int widest = fr.getWidth("CLOCK");
        widest = Math.max(widest, fr.getWidth(formatTime()));
        if (showDate()) widest = Math.max(widest, fr.getWidth(formatDate()));
        return Math.max(MIN_WIDTH, leftPad + widest + padX);
    }

    @Override
    public int height() {
        int contentRows = showDate() ? 2 : 1;
        int padY = HudStyle.padY(id());
        return HudStyle.effectiveHeaderH(id()) + padY + HudStyle.rowH(id()) * contentRows + padY;
    }

    @Override public int defaultX(int screenWidth)  { return screenWidth - 80; }
    @Override public int defaultY(int screenHeight) { return 10; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        int w = width();
        int h = height();
        int rowY = HudStyle.drawChrome(ctx, fr, id(), w, h, "CLOCK");

        int padX = HudStyle.padX(id());
        int stripW = HudStyle.stripW();
        int stripGap = HudStyle.stripGap(id());
        int rowH = HudStyle.rowH(id());
        int textX = padX + stripW + stripGap;

        ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, TIME_COLOR);
        ctx.drawText(fr, Text.literal(formatTime()), textX, rowY + 2, TIME_COLOR, true);
        rowY += rowH;

        if (showDate()) {
            ctx.drawText(fr, Text.literal(formatDate()), textX, rowY + 2, DATE_COLOR, true);
        }
    }

    /** e.g. {@code 00:31}, {@code 12:31:07 AM}, {@code 00:31 CEST}. */
    private String formatTime() {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        if (use24Hour()) {
            sb.append(String.format(Locale.US, "%02d:%02d", now.getHour(), now.getMinute()));
            if (showSeconds()) sb.append(String.format(Locale.US, ":%02d", now.getSecond()));
        } else {
            int h12 = now.getHour() % 12;
            if (h12 == 0) h12 = 12;
            sb.append(String.format(Locale.US, "%d:%02d", h12, now.getMinute()));
            if (showSeconds()) sb.append(String.format(Locale.US, ":%02d", now.getSecond()));
            sb.append(now.getHour() < 12 ? " AM" : " PM");
        }
        if (showZone()) sb.append(' ').append(zoneShortName());
        return sb.toString();
    }

    private static String formatDate() {
        LocalDateTime now = LocalDateTime.now();
        return String.format(Locale.US, "%s %d %s",
                now.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                now.getDayOfMonth(),
                now.getMonth().getDisplayName(TextStyle.SHORT, Locale.US));
    }

    /** Short zone label for the current instant — "CEST" in summer, "CET" in winter. */
    private static String zoneShortName() {
        try {
            java.util.TimeZone tz = java.util.TimeZone.getTimeZone(ZoneId.systemDefault());
            boolean dst = tz.inDaylightTime(new java.util.Date());
            String name = tz.getDisplayName(dst, java.util.TimeZone.SHORT, Locale.US);
            return name == null ? "" : name;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static TextRenderer textRenderer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null ? mc.textRenderer : null;
    }
}
