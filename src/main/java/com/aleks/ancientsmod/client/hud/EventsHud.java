package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.EventTimersPayload;
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
 * Moveable Events HUD. Renders next-fire timers (or "LIVE"/"OFF" status) for
 * the cluster events the player has opted in to. Visible event list is
 * configurable per widget via {@link EventsHudSettingsScreen}.
 */
public final class EventsHud extends HudElement {

    public static final EventsHud INSTANCE = new EventsHud();

    public static final String KEY_ENABLED = "enabled";

    public static final List<String> ALL_KEYS = List.of(
            "koth", "bah", "meteor", "mining_comp",
            "meteorite", "meteorite_shower", "mining_rush", "hot_zone",
            "oracle", "outpost"
    );

    public static final Set<String> DEFAULT_ENABLED = new LinkedHashSet<>(List.of(
            "koth", "bah", "meteor", "mining_comp", "outpost"
    ));

    private static final int MIN_WIDTH    = 158;
    private static final int STATE_LIVE   = 0xFF7FE07F;
    private static final int STATE_OFF    = 0xFF888888;
    private static final int LABEL_FALLBACK = 0xFFE6E8EE;

    private EventsHud() {}

    @Override public String id() { return "events"; }
    @Override public String displayName() { return "Events"; }

    @Override
    public boolean isVisible() {
        return FeatureToggles.isEventsHudEnabled() && !visibleEntries().isEmpty();
    }

    @Override
    public String editorPlaceholder() { return "Events"; }

    @Override
    public Screen openSettings(Screen parent) {
        return new EventsHudSettingsScreen(parent, this);
    }

    public Set<String> enabledKeys() {
        return HudSettings.getStringSet(id(), KEY_ENABLED, DEFAULT_ENABLED);
    }

    @Override
    public int width() {
        TextRenderer fr = textRenderer();
        if (fr == null) return MIN_WIDTH;
        int padX = HudStyle.padX(id());
        int colGap = HudStyle.columnGap(id());
        int leftPad = padX + HudStyle.stripW() + HudStyle.stripGap(id());
        int widest = fr.getWidth("EVENTS");
        for (Row r : computeRows()) {
            int labelW = fr.getWidth(r.label);
            int rightW = fr.getWidth(r.rightText);
            int rowW = labelW + colGap + rightW;
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
    @Override public int defaultY(int screenHeight) { return 80; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        List<Row> rows = computeRows();
        int w = width();
        int h = height();

        int rowY = HudStyle.drawChrome(ctx, fr, id(), w, h, "EVENTS");

        int padX = HudStyle.padX(id());
        int stripW = HudStyle.stripW();
        int stripGap = HudStyle.stripGap(id());
        int rowH = HudStyle.rowH(id());

        for (Row r : rows) {
            ctx.fill(padX, rowY, padX + stripW, rowY + rowH - 2, r.accent);

            int textX = padX + stripW + stripGap;
            int textY = rowY + 2;

            int rightW = fr.getWidth(r.rightText);
            int rightX = w - padX - rightW;
            ctx.drawText(fr, Text.literal(r.rightText), rightX, textY, r.rightColor, true);

            ctx.drawText(fr, Text.literal(r.label), textX, textY,
                    (r.accent & 0x00FFFFFF) | 0xFF000000, true);

            rowY += rowH;
        }
    }

    private List<Row> computeRows() {
        return visibleEntries();
    }

    private List<Row> visibleEntries() {
        Set<String> enabled = enabledKeys();
        if (enabled.isEmpty()) return List.of();

        Map<String, EventTimersPayload.Entry> byKey = new LinkedHashMap<>();
        for (EventTimersPayload.Entry e : EventState.entries()) {
            String key = keyForId(e.eventId());
            if (key != null) byKey.put(key, e);
        }

        List<Row> out = new ArrayList<>();
        for (String key : ALL_KEYS) {
            if (!enabled.contains(key)) continue;
            EventTimersPayload.Entry e = byKey.get(key);
            if (e == null) continue;
            if (e.state() == Protocol.EVENT_STATE_UNKNOWN) continue;
            if (e.state() == Protocol.EVENT_STATE_DISABLED) {
                out.add(new Row(displayNameFor(key), "OFF", STATE_OFF, accentFor(key)));
                continue;
            }
            if (e.state() == Protocol.EVENT_STATE_ACTIVE) {
                out.add(new Row(displayNameFor(key), "LIVE", STATE_LIVE, accentFor(key)));
                continue;
            }
            int seconds = EventState.liveSecondsUntilFire(e);
            out.add(new Row(displayNameFor(key), formatDuration(seconds), HudStyle.TIME_COLOR, accentFor(key)));
        }
        return out;
    }

    private static String keyForId(byte id) {
        if (id == Protocol.EVENT_KOTH)              return "koth";
        if (id == Protocol.EVENT_BAH)               return "bah";
        if (id == Protocol.EVENT_METEOR)            return "meteor";
        if (id == Protocol.EVENT_MINING_COMP)       return "mining_comp";
        if (id == Protocol.EVENT_METEORITE)         return "meteorite";
        if (id == Protocol.EVENT_MINING_RUSH)       return "mining_rush";
        if (id == Protocol.EVENT_HOT_ZONE)          return "hot_zone";
        if (id == Protocol.EVENT_ORACLE)            return "oracle";
        if (id == Protocol.EVENT_OUTPOST)           return "outpost";
        if (id == Protocol.EVENT_METEORITE_SHOWER)  return "meteorite_shower";
        return null;
    }

    public static String displayNameFor(String key) {
        return switch (key) {
            case "koth"              -> "KOTH";
            case "bah"               -> "BAH";
            case "meteor"            -> "Meteor";
            case "mining_comp"       -> "Mining Comp";
            case "meteorite"         -> "Meteorite";
            case "meteorite_shower"  -> "Meteorite Shower";
            case "mining_rush"       -> "Mining Rush";
            case "hot_zone"          -> "Hot Zone";
            case "oracle"            -> "Oracle";
            case "outpost"           -> "Outpost rotation";
            case "chat_games"        -> "Chat Games";
            default -> key;
        };
    }

    private static int accentFor(String key) {
        return switch (key) {
            case "koth"              -> 0xFFFF8A66;
            case "bah"               -> 0xFF6FE8C9;
            case "meteor"            -> 0xFFFFC857;
            case "mining_comp"       -> 0xFF8AE08A;
            case "meteorite"         -> 0xFFE6B05A;
            case "meteorite_shower"  -> 0xFFFFB070;
            case "mining_rush"       -> 0xFFC8E08A;
            case "hot_zone"          -> 0xFFFF7070;
            case "oracle"            -> 0xFFC6A0FF;
            case "outpost"           -> 0xFF87BFFF;
            default                  -> LABEL_FALLBACK;
        };
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

    private record Row(String label, String rightText, int rightColor, int accent) {}
}
