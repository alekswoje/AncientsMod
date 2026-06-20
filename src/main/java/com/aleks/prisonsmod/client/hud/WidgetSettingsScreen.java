package com.aleks.prisonsmod.client.hud;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Per-widget settings popup base. Long lists work because the row list:
 * <ul>
 *   <li>Scrolls on the mouse wheel</li>
 *   <li>Filters live as you type in the search box</li>
 *   <li>Shows non-interactive section headers between groups</li>
 * </ul>
 *
 * <p>Subclasses override {@link #addRows()} and call
 * {@link #addSection(String)}, {@link #addToggle}, {@link #addSlider} — the
 * scroll + filter machinery is handled here.
 */
public abstract class WidgetSettingsScreen extends Screen {

    private static final int BUTTON_W = 240;
    private static final int BUTTON_H = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int HEADER_HEIGHT = 18;
    private static final int VIEWPORT_TOP = 84;
    private static final int VIEWPORT_BOTTOM_PAD = 36;

    private final Screen parent;
    private final Text subtitle;

    private final List<Row> rows = new ArrayList<>();
    private TextFieldWidget searchField;
    private int scrollY;
    private int totalContentHeight;

    /** Bind to a HUD element — title and subtitle are derived from it. */
    protected WidgetSettingsScreen(Screen parent, HudElement element) {
        this(parent, Text.literal(element.displayName() + " Settings"),
                Text.literal(element.displayName() + " — element id: " + element.id()));
    }

    /** Plain-title constructor for non-widget settings screens (e.g. the F9 menu). */
    protected WidgetSettingsScreen(Screen parent, Text title, Text subtitle) {
        super(title);
        this.parent = parent;
        this.subtitle = subtitle;
    }

    @Override
    protected final void init() {
        rows.clear();
        scrollY = 0;

        // Search box at top — narrower than the row area so the "x to clear" gap reads.
        int rowW = buttonWidth();
        searchField = new TextFieldWidget(this.textRenderer,
                this.width / 2 - rowW / 2, VIEWPORT_TOP - 28,
                rowW, BUTTON_H, Text.literal("Search settings"));
        searchField.setPlaceholder(Text.literal("Search…"));
        searchField.setChangedListener(s -> relayout());
        addDrawableChild(searchField);

        addRows();

        // Done button anchored to the bottom.
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> this.close())
                .dimensions(this.width / 2 - 50, this.height - 28, 100, BUTTON_H)
                .build());

        relayout();
    }

    /** Subclasses register their toggles / sliders / section headers here. */
    protected abstract void addRows();

    /**
     * Width of each row / button (and the viewport box) in this screen. Defaults
     * to {@link #BUTTON_W}; the full-mod settings screens override this wider so
     * the long F9 list isn't a thin vertical strip. Per-HUD popups keep the
     * default. Clamped against the screen width by callers' layout maths.
     */
    protected int buttonWidth() {
        return BUTTON_W;
    }

    // ── Public row-building API ─────────────────────────────────────────────

    /** Add a non-interactive section header (visual divider with text). */
    protected final void addSection(String label) {
        rows.add(new HeaderRow(label));
    }

    /** Labeled ON/OFF toggle wired to a getter/setter pair. */
    protected final void addToggle(String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        Text onText  = Text.literal("ON").formatted(Formatting.GREEN, Formatting.BOLD);
        Text offText = Text.literal("OFF").formatted(Formatting.RED,   Formatting.BOLD);
        CyclingButtonWidget<Boolean> btn = CyclingButtonWidget
                .onOffBuilder(onText, offText, getter.getAsBoolean())
                .build(0, 0, buttonWidth(), BUTTON_H, Text.literal(label),
                        (button, value) -> setter.accept(value));
        addDrawableChild(btn);
        rows.add(new WidgetRow(label, btn));
    }

    /** Plain action button (e.g. "Edit HUD positions...") — sits in the same scrollable list as toggles. */
    protected final void addAction(String label, Runnable action) {
        ButtonWidget btn = ButtonWidget.builder(Text.literal(label), b -> action.run())
                .dimensions(0, 0, buttonWidth(), BUTTON_H)
                .build();
        addDrawableChild(btn);
        rows.add(new WidgetRow(label, btn));
    }

    /** Integer slider (min..max) with a labeled message. */
    protected final void addSlider(String label, int min, int max, IntSupplier getter, IntConsumer setter) {
        int initial = Math.max(min, Math.min(max, getter.getAsInt()));
        double initialNorm = max == min ? 0.0 : (double) (initial - min) / (max - min);
        SliderWidget slider = new SliderWidget(0, 0, buttonWidth(), BUTTON_H,
                Text.literal(label + ": " + initial), initialNorm) {
            @Override
            protected void updateMessage() {
                int v = min + (int) Math.round(this.value * (max - min));
                this.setMessage(Text.literal(label + ": " + v));
            }
            @Override
            protected void applyValue() {
                int v = min + (int) Math.round(this.value * (max - min));
                setter.accept(v);
            }
        };
        addDrawableChild(slider);
        rows.add(new WidgetRow(label, slider));
    }

    // ── Layout + scroll + filter ────────────────────────────────────────────

    private int viewportBottom() {
        return this.height - VIEWPORT_BOTTOM_PAD;
    }

    private String filterQuery() {
        if (searchField == null) return "";
        String s = searchField.getText();
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private boolean rowMatches(Row r, String query) {
        if (query.isEmpty()) return true;
        // Section headers always show — they orient the user during a search.
        if (r instanceof HeaderRow) return true;
        return r.label().toLowerCase(Locale.ROOT).contains(query);
    }

    private void relayout() {
        String query = filterQuery();
        int top = VIEWPORT_TOP;
        int bottom = viewportBottom();
        int centerX = this.width / 2;

        // First pass: total content height (matching rows only).
        int contentHeight = 0;
        for (Row r : rows) {
            if (!rowMatches(r, query)) continue;
            contentHeight += r.height();
        }
        totalContentHeight = contentHeight;

        // Clamp scroll so we can't scroll past the end / before the start.
        int viewportH = bottom - top;
        int maxScroll = Math.max(0, contentHeight - viewportH);
        if (scrollY < 0) scrollY = 0;
        if (scrollY > maxScroll) scrollY = maxScroll;

        // Second pass: position each row and cull off-screen ones.
        int rowW = buttonWidth();
        int y = top - scrollY;
        for (Row r : rows) {
            boolean visible = rowMatches(r, query);
            if (!visible) {
                r.setOnScreen(false);
                continue;
            }
            int rowTop = y;
            int rowBottom = y + r.height();
            boolean onScreen = rowBottom > top && rowTop < bottom;
            r.setOnScreen(onScreen);
            r.setY(centerX, rowTop, rowW);
            y += r.height();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizDelta, double vertDelta) {
        // 1 wheel-tick ≈ 24 px (one row).
        scrollY -= (int) Math.round(vertDelta * ROW_HEIGHT);
        relayout();
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int rowW = buttonWidth();
        // Dim the world behind. The viewport gets a softer fill so rows pop.
        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        ctx.fill(this.width / 2 - rowW / 2 - 4, VIEWPORT_TOP - 2,
                 this.width / 2 + rowW / 2 + 4, viewportBottom() + 2, 0x33000000);

        super.render(ctx, mouseX, mouseY, delta);

        // Header titles.
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);
        if (subtitle != null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, subtitle, this.width / 2, 26, 0x888888);
        }

        // Section header text (non-widget rows) inside the viewport.
        String query = filterQuery();
        int top = VIEWPORT_TOP;
        int bottom = viewportBottom();
        int centerX = this.width / 2;
        int y = top - scrollY;
        ctx.enableScissor(0, top, this.width, bottom);
        for (Row r : rows) {
            if (!rowMatches(r, query)) continue;
            int rowTop = y;
            int rowBottom = y + r.height();
            if (rowBottom > top && rowTop < bottom && r instanceof HeaderRow h) {
                int hy = rowTop + (r.height() - this.textRenderer.fontHeight) / 2;
                // Divider line behind the label so the section reads at a glance.
                ctx.fill(centerX - rowW / 2, rowTop + r.height() / 2,
                         centerX + rowW / 2, rowTop + r.height() / 2 + 1, 0x33FFFFFF);
                int labelW = this.textRenderer.getWidth(h.text);
                ctx.fill(centerX - labelW / 2 - 4, hy - 1,
                         centerX + labelW / 2 + 4, hy + this.textRenderer.fontHeight + 1, 0xAA000000);
                ctx.drawText(this.textRenderer, h.text,
                        centerX - labelW / 2, hy, 0xFFFFC857, false);
            }
            y += r.height();
        }
        ctx.disableScissor();

        // Scrollbar indicator on the right edge of the viewport (only when content overflows).
        int viewportH = bottom - top;
        if (totalContentHeight > viewportH) {
            int barX = this.width / 2 + rowW / 2 + 6;
            int barH = Math.max(20, (int) ((double) viewportH * viewportH / totalContentHeight));
            int barY = top + (int) ((double) scrollY * (viewportH - barH) / Math.max(1, totalContentHeight - viewportH));
            ctx.fill(barX, top, barX + 3, bottom, 0x44FFFFFF);
            ctx.fill(barX, barY, barX + 3, barY + barH, 0xFFFFC857);
        }
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    // ── Row types ───────────────────────────────────────────────────────────

    private interface Row {
        String label();
        int height();
        /** Reposition for display at this center-x and top-y, sized to {@code width}. */
        void setY(int centerX, int topY, int width);
        /** Allow the widget (if any) to be off-screen — hidden = doesn't render or take clicks. */
        void setOnScreen(boolean on);
    }

    private static final class HeaderRow implements Row {
        final String text;
        HeaderRow(String text) { this.text = text; }
        @Override public String label() { return text; }
        @Override public int height() { return HEADER_HEIGHT; }
        @Override public void setY(int centerX, int topY, int width) {}
        @Override public void setOnScreen(boolean on) {}
    }

    private static final class WidgetRow implements Row {
        final String label;
        final ClickableWidget widget;
        WidgetRow(String label, ClickableWidget widget) {
            this.label = label;
            this.widget = widget;
        }
        @Override public String label() { return label; }
        @Override public int height() { return ROW_HEIGHT; }
        @Override public void setY(int centerX, int topY, int width) {
            widget.setWidth(width);
            widget.setX(centerX - width / 2);
            widget.setY(topY + 2);
        }
        @Override public void setOnScreen(boolean on) {
            widget.visible = on;
            widget.active = on;
        }
    }
}
