package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.glass.GlassTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tier-specific top five plus a pinned personal rank; all scores are server supplied. */
public final class MiningCompHud extends HudElement {
    public static final MiningCompHud INSTANCE = new MiningCompHud();
    private MiningCompHud() {}
    @Override public String id() { return "mining_comp"; }
    @Override public String displayName() { return "Mining Competition"; }
    public boolean enabled() { return HudSettings.getBoolean(id(), "enabled", true); }
    @Override public boolean isVisible() { return enabled() && MiningCompState.current() != null; }
    @Override public String editorPlaceholder() { return "Mining Competition - Top 5"; }
    @Override public int defaultX(int width) { return 10; }
    @Override public int defaultY(int height) { return Math.max(10, height / 2 - 50); }
    @Override public Screen openSettings(Screen parent) {
        return new WidgetSettingsScreen(parent, this) {
            @Override protected void addRows() {
                addToggle("Show this HUD", MiningCompHud.this::enabled,
                        value -> HudSettings.setBoolean(id(), "enabled", value));
                BoosterHudSettingsScreen.addLookRows(this, MiningCompHud.this);
            }
        };
    }
    private record Row(String left, String right, boolean own) {}
    private static String number(int n) { return String.format(Locale.US, "%,d", n); }
    private List<Row> rows() {
        var state = MiningCompState.current();
        if (state == null) return List.of(new Row("No active competition", "", false));
        List<Row> rows = new ArrayList<>();
        int seconds = MiningCompState.seconds();
        rows.add(new Row(state.tier() + " Mine", String.format(Locale.ROOT, "%d:%02d left", seconds / 60, seconds % 60), false));
        for (int i = 0; i < state.entries().size(); i++) {
            var entry = state.entries().get(i);
            boolean own = state.rank() == i + 1;
            int gap = entry.blocks() - state.blocks();
            String delta = own ? "You" : gap == 0 ? "tied" : number(Math.abs(gap)) + (gap > 0 ? " ahead" : " behind");
            rows.add(new Row("#" + (i + 1) + " " + entry.name(), number(entry.blocks()) + "  |  " + delta, own));
        }
        if (state.rank() == 0 || state.rank() > 5) {
            rows.add(new Row(state.rank() == 0 ? "You - unranked" : "#" + state.rank() + " You", number(state.blocks()) + " blocks", true));
        }
        if (state.rank() == 0) rows.add(new Row("Mine your tier to join", "", false));
        return rows;
    }
    @Override public int width() {
        TextRenderer fr = MinecraftClient.getInstance().textRenderer;
        int content = 210;
        if (fr != null) for (Row row : rows())
            content = Math.max(content, fr.getWidth(row.left()) + HudStyle.columnGap(id()) + fr.getWidth(row.right()));
        return content + 2 * HudStyle.padX(id());
    }
    @Override public int height() {
        return HudStyle.effectiveHeaderH(id()) + 2 * HudStyle.padY(id()) + HudStyle.rowH(id()) * rows().size();
    }
    @Override public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        int w = width();
        int y = HudStyle.drawChrome(ctx, fr, id(), w, height(), "MINING COMP - TOP 5");
        int pad = HudStyle.padX(id());
        for (Row row : rows()) {
            int color = row.own() ? GlassTheme.VALUE : GlassTheme.textDim();
            if (row.own()) ctx.fill(pad - 2, y, w - pad + 2, y + HudStyle.rowH(id()) - 1, 0x303FAE9B);
            ctx.drawText(fr, row.left(), pad, y + 2, color, true);
            ctx.drawText(fr, row.right(), w - pad - fr.getWidth(row.right()), y + 2, color, true);
            y += HudStyle.rowH(id());
        }
    }
}
