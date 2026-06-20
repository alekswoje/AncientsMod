package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.client.glass.GlassButton;
import com.aleks.prisonsmod.client.glass.GlassRender;
import com.aleks.prisonsmod.client.glass.GlassSlider;
import com.aleks.prisonsmod.client.glass.GlassTextField;
import com.aleks.prisonsmod.client.glass.GlassTheme;
import com.aleks.prisonsmod.client.glass.GlassToggle;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

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
    private GlassTextField searchField;
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
        searchField = new GlassTextField(this.textRenderer,
                this.width / 2 - rowW / 2, VIEWPORT_TOP - 28,
                rowW, BUTTON_H, Text.literal("Search settings"));
        searchField.setPlaceholder(Text.literal("Search…"));
        searchField.setChangedListener(s -> relayout());
        addDrawableChild(searchField);

        addRows();

        // Done button anchored to the bottom (accent-filled glass).
        addDrawableChild(new GlassButton(this.width / 2 - 50, this.height - 28, 100, BUTTON_H,
                Text.translatable("gui.done"), this::close).primary());

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
        GlassToggle btn = new GlassToggle(0, 0, buttonWidth(), BUTTON_H, label, getter.getAsBoolean(), setter);
        addDrawableChild(btn);
        rows.add(new WidgetRow(label, btn));
    }

    /** Plain action button (e.g. "Edit HUD positions...") — sits in the same scrollable list as toggles. */
    protected final void addAction(String label, Runnable action) {
        GlassButton btn = new GlassButton(0, 0, buttonWidth(), BUTTON_H, Text.literal(label), action);
        addDrawableChild(btn);
        rows.add(new WidgetRow(label, btn));
    }

    /** Integer slider (min..max) with a labeled message. */
    protected final void addSlider(String label, int min, int max, IntSupplier getter, IntConsumer setter) {
        GlassSlider slider = new GlassSlider(0, 0, buttonWidth(), BUTTON_H, label, min, max, getter.getAsInt(), setter);
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
        // Real blurred backdrop, then one frosted glass card behind the whole column.
        GlassRender.menuBackdrop(ctx, this.width, this.height);
        GlassRender.panel(ctx, this.width / 2 - rowW / 2 - 12, 6, rowW + 24, this.height - 12);

        super.render(ctx, mouseX, mouseY, delta);

        // Header titles.
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, GlassTheme.text());
        if (subtitle != null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, subtitle, this.width / 2, 28, GlassTheme.textDim());
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
                GlassRender.sectionDivider(ctx, this.textRenderer, centerX, rowTop, r.height(), rowW, h.text);
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
            GlassRender.scrollbar(ctx, barX, top, bottom, barY, barH);
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
