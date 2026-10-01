package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.client.glass.GlassLink;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassSlider;
import com.aleks.ancientsmod.client.glass.GlassTextField;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.client.glass.GlassScrollbar;
import com.aleks.ancientsmod.client.glass.GlassToggle;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Settings screen base shared by the F9 menu, the advanced menu and every per-HUD popup.
 *
 * <p>Two layouts, one row API:
 * <ul>
 *   <li><b>Sidebar</b> ({@link #useSidebar()} true): the sections become a category list on
 *   the left and the right pane shows one category at a time. Typing in the search box shows
 *   matching rows from every category, grouped under their section names.</li>
 *   <li><b>Single pane</b> (default): one scrolling list with section headings, for the short
 *   per-HUD popups.</li>
 * </ul>
 *
 * <p>Subclasses override {@link #addRows()} and call {@link #addSection(String)},
 * {@link #addToggle}, {@link #addAction} and {@link #addSlider}. Row widgets are rendered
 * inside a scissor so a half-scrolled row never draws over the header or footer.
 */
public abstract class WidgetSettingsScreen extends Screen {

    private static final int BUTTON_W = 240;
    private static final int ROW_H = 18;
    private static final int WIDGET_H = 16;
    private static final int HEADER_ROW_H = 20;
    private static final int SIDEBAR_W = 104;
    private static final int CAT_H = 15;
    private static final int PAD = 10;
    private static final int FOOTER_H = 26;

    /** Last category picked per screen class, so reopening F9 lands where the player left it. */
    private static final Map<String, Integer> LAST_CATEGORY = new HashMap<>();

    private final Screen parent;
    private final Text subtitle;

    private final List<Row> rows = new ArrayList<>();
    private final List<String> sections = new ArrayList<>();
    private final GlassScrollbar scrollbar = new GlassScrollbar();
    private GlassTextField searchField;
    private GlassButton doneButton;
    private int scrollY;
    private int totalContentHeight;
    private int category;

    // Geometry, recomputed in init().
    private int px, py, pw, ph;
    private int paneX, paneW, viewTop, viewBottom;

    /** Bind to a HUD element: title and subtitle are derived from it. */
    protected WidgetSettingsScreen(Screen parent, HudElement element) {
        this(parent, Text.literal(element.displayName()), Text.literal("HUD settings"));
    }

    /** Plain-title constructor for non-widget settings screens (e.g. the F9 menu). */
    protected WidgetSettingsScreen(Screen parent, Text title, Text subtitle) {
        super(title);
        this.parent = parent;
        this.subtitle = subtitle;
    }

    /** Subclasses register their toggles / sliders / section headers here. */
    protected abstract void addRows();

    /**
     * Width of the row column. The sidebar layout adds the sidebar on top of this; the single
     * pane uses it directly. Clamped against the screen width in {@link #init()}.
     */
    protected int buttonWidth() {
        return BUTTON_W;
    }

    /** True to show sections as a category sidebar instead of one long list. */
    protected boolean useSidebar() {
        return false;
    }

    @Override
    protected final void init() {
        rows.clear();
        sections.clear();
        scrollY = 0;

        boolean sidebar = useSidebar();
        int rowW = buttonWidth();
        pw = Math.min(this.width - 16, rowW + 2 * PAD + 6 + (sidebar ? SIDEBAR_W : 0));
        ph = Math.min(this.height - 12, sidebar ? 300 : 280);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;

        if (sidebar) {
            paneX = px + SIDEBAR_W + PAD;
            searchField = new GlassTextField(this.textRenderer, px + 8, py + 24, SIDEBAR_W - 16, 14,
                    Text.literal("Search settings"));
            viewTop = py + 28;
        } else {
            paneX = px + PAD;
            searchField = new GlassTextField(this.textRenderer, paneX, py + 34, pw - 2 * PAD - 6, 14,
                    Text.literal("Search settings"));
            viewTop = py + 54;
        }
        paneW = px + pw - PAD - 6 - paneX;
        viewBottom = py + ph - FOOTER_H - 2;

        searchField.setPlaceholder(Text.literal("Search"));
        searchField.setChangedListener(s -> { scrollY = 0; relayout(); });
        addDrawableChild(searchField);

        addRows();
        if (sections.isEmpty()) sections.add("General");

        category = Math.max(0, Math.min(sections.size() - 1, LAST_CATEGORY.getOrDefault(getClass().getName(), 0)));

        doneButton = new GlassButton(px + pw - PAD - 52, py + ph - 20, 52, 14,
                Text.translatable("gui.done"), this::close).primary();
        addDrawableChild(doneButton);

        relayout();
    }

    // ── Public row-building API ─────────────────────────────────────────────

    /** Start a new section. In the sidebar layout this is a category; otherwise a heading. */
    protected final void addSection(String label) {
        sections.add(label);
        rows.add(new HeaderRow(label, sections.size() - 1));
    }

    /** Labeled ON/OFF toggle wired to a getter/setter pair. */
    protected final void addToggle(String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        GlassToggle btn = new GlassToggle(0, 0, paneW, WIDGET_H, label, getter.getAsBoolean(), setter);
        addSelectableChild(btn);
        rows.add(new WidgetRow(label, btn, currentSection()));
    }

    /** Action row (e.g. "Edit HUD positions"): candle label with a chevron, opens or runs something. */
    protected final void addAction(String label, Runnable action) {
        GlassLink btn = new GlassLink(0, 0, paneW, WIDGET_H, Text.literal(stripEllipsis(label)), action);
        addSelectableChild(btn);
        rows.add(new WidgetRow(label, btn, currentSection()));
    }

    /** Integer slider (min..max) with a labeled message. */
    protected final void addSlider(String label, int min, int max, IntSupplier getter, IntConsumer setter) {
        GlassSlider slider = new GlassSlider(0, 0, paneW, WIDGET_H, label, min, max, getter.getAsInt(), setter);
        addSelectableChild(slider);
        rows.add(new WidgetRow(label, slider, currentSection()));
    }

    private int currentSection() {
        if (sections.isEmpty()) {
            sections.add("General");
            rows.add(new HeaderRow("General", 0));
        }
        return sections.size() - 1;
    }

    private static String stripEllipsis(String s) {
        String t = s.trim();
        while (t.endsWith(".") || t.endsWith("…")) t = t.substring(0, t.length() - 1);
        return t.trim();
    }

    // ── Layout + scroll + filter ────────────────────────────────────────────

    private String filterQuery() {
        if (searchField == null) return "";
        String s = searchField.getText();
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private boolean searching() {
        return !filterQuery().isEmpty();
    }

    /** Whether a row is part of the current view (category or search results). */
    private boolean rowShown(Row r) {
        String q = filterQuery();
        if (useSidebar() && q.isEmpty()) {
            return !(r instanceof HeaderRow) && r.section() == category;
        }
        if (q.isEmpty()) return true;
        if (r instanceof HeaderRow) return sectionHasMatch(r.section(), q);
        return r.label().toLowerCase(Locale.ROOT).contains(q);
    }

    private boolean sectionHasMatch(int section, String q) {
        for (Row r : rows) {
            if (!(r instanceof HeaderRow) && r.section() == section && r.label().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    private void relayout() {
        int contentHeight = 0;
        for (Row r : rows) if (rowShown(r)) contentHeight += r.height();
        totalContentHeight = contentHeight;

        int maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        scrollY = Math.max(0, Math.min(maxScroll, scrollY));

        int y = viewTop - scrollY;
        for (Row r : rows) {
            if (!rowShown(r)) {
                r.setOnScreen(false);
                continue;
            }
            r.setOnScreen(y + r.height() > viewTop && y < viewBottom);
            r.place(paneX, y, paneW);
            y += r.height();
        }
    }

    private int countIn(int section) {
        int n = 0;
        for (Row r : rows) if (!(r instanceof HeaderRow) && r.section() == section) n++;
        return n;
    }

    private int matchCount() {
        int n = 0;
        for (Row r : rows) if (!(r instanceof HeaderRow) && rowShown(r)) n++;
        return n;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizDelta, double vertDelta) {
        scrollY -= (int) Math.round(vertDelta * ROW_H * 2);
        relayout();
        return true;
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        GlassRender.menuBackdrop(ctx, this.width, this.height);
        GlassRender.panel(ctx, px, py, pw, ph);

        boolean sidebar = useSidebar();
        if (sidebar) renderSidebar(ctx, mouseX, mouseY);
        renderPaneHeader(ctx, sidebar);

        // Footer: rule, hint, Done (Done is a drawable child).
        int footY = py + ph - FOOTER_H;
        GlassRender.rule(ctx, sidebar ? px + SIDEBAR_W + 1 : px + 1, px + pw - 1, footY);
        String hint = searching() && matchCount() == 0 ? "No settings match \"" + searchField.getText().trim() + "\""
                : "Changes save instantly";
        int hintMax = doneButton.getX() - 8 - paneX;
        ctx.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(hint, hintMax)),
                paneX, footY + 9, GlassTheme.textMuted(), false);

        // Rows, clipped to the viewport.
        ctx.enableScissor(paneX - 4, viewTop, px + pw - 2, viewBottom);
        int y = viewTop - scrollY;
        for (Row r : rows) {
            if (!rowShown(r)) continue;
            if (y + r.height() > viewTop && y < viewBottom) {
                if (r instanceof HeaderRow h) {
                    GlassRender.sectionDivider(ctx, this.textRenderer, paneX + paneW / 2, y, r.height(), paneW, h.text);
                } else if (r instanceof WidgetRow w) {
                    w.widget.render(ctx, mouseX, mouseY, delta);
                }
            }
            y += r.height();
        }
        ctx.disableScissor();

        scrollbar.render(ctx, px + pw - PAD + 2, viewTop, viewBottom, totalContentHeight, scrollY);

        // Search field + Done.
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderSidebar(DrawContext ctx, int mouseX, int mouseY) {
        ctx.fill(px + 1, py + 1, px + SIDEBAR_W, py + ph - 1, GlassTheme.isLight() ? 0x1A24130A : 0x33000000);
        GlassRender.vrule(ctx, px + SIDEBAR_W, py + 1, py + ph - 1);

        String brand = this.textRenderer.trimToWidth(this.title.getString(), SIDEBAR_W - 18);
        ctx.drawText(this.textRenderer, Text.literal(brand), px + 9, py + 10, GlassTheme.ACCENT, true);

        boolean searching = searching();
        int y = py + 48;
        int maxY = py + ph - 8;
        for (int i = 0; i < sections.size() && y + CAT_H <= maxY; i++) {
            int x1 = px + 5, x2 = px + SIDEBAR_W - 5;
            boolean active = !searching && i == category;
            boolean hover = mouseX >= x1 && mouseX < x2 && mouseY >= y && mouseY < y + CAT_H - 1;
            if (active) GlassRender.selected(ctx, x1, y, x2, y + CAT_H - 1);
            else if (hover) GlassRender.row(ctx, x1, y, x2, y + CAT_H - 1, true);
            int color = active ? GlassTheme.text() : hover ? GlassTheme.textDim() : GlassTheme.textMuted();
            String name = this.textRenderer.trimToWidth(sections.get(i), x2 - x1 - 12);
            ctx.drawText(this.textRenderer, Text.literal(name), x1 + 6, y + 3, color, active);
            y += CAT_H;
        }
    }

    private void renderPaneHeader(DrawContext ctx, boolean sidebar) {
        String heading;
        String meta;
        if (searching()) {
            heading = "Search";
            int n = matchCount();
            meta = n == 1 ? "1 result" : n + " results";
        } else if (sidebar) {
            heading = sections.get(category);
            int n = countIn(category);
            meta = n == 1 ? "1 setting" : n + " settings";
        } else {
            heading = this.title.getString();
            meta = null;
        }
        int hx = paneX;
        int hy = py + 10;
        if (sidebar) {
            String h = this.textRenderer.trimToWidth(heading, paneW - 60);
            ctx.drawText(this.textRenderer, Text.literal(h), hx, hy, GlassTheme.text(), true);
            if (meta != null) {
                ctx.drawText(this.textRenderer, Text.literal(meta), hx + this.textRenderer.getWidth(h) + 6, hy,
                        GlassTheme.textMuted(), false);
            }
            GlassRender.rule(ctx, paneX, paneX + paneW, py + 22);
        } else {
            ctx.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(heading, paneW)), hx, hy,
                    GlassTheme.ACCENT, true);
            if (subtitle != null) {
                ctx.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(subtitle.getString(), paneW)),
                        hx, hy + 12, GlassTheme.textMuted(), false);
            }
        }
    }

    // ── Input ───────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        if (click.button() == 0 && scrollbar.mousePressed(mx, my)) {
            scrollY = scrollbar.scrollFor(my, totalContentHeight);
            relayout();
            return true;
        }
        if (click.button() == 0 && useSidebar() && mx >= px + 5 && mx < px + SIDEBAR_W - 5) {
            int y = py + 48;
            for (int i = 0; i < sections.size(); i++) {
                if (my >= y && my < y + CAT_H - 1) {
                    selectCategory(i);
                    return true;
                }
                y += CAT_H;
            }
        }
        // Rows that are scrolled half out of view must not take clicks outside the viewport.
        if (my < viewTop || my >= viewBottom) {
            List<ClickableWidget> parked = new ArrayList<>();
            for (Row r : rows) {
                if (r instanceof WidgetRow w && w.widget.active) {
                    w.widget.active = false;
                    parked.add(w.widget);
                }
            }
            try {
                return super.mouseClicked(click, doubled);
            } finally {
                for (ClickableWidget w : parked) w.active = true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void selectCategory(int i) {
        category = i;
        LAST_CATEGORY.put(getClass().getName(), i);
        scrollY = 0;
        if (searching()) searchField.setText("");
        relayout();
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (scrollbar.isDragging()) {
            scrollY = scrollbar.scrollFor(click.y(), totalContentHeight);
            relayout();
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (scrollbar.isDragging()) {
            scrollbar.release();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    // ── Row types ───────────────────────────────────────────────────────────

    private interface Row {
        String label();
        int section();
        int height();
        /** Position at this pane x / top y, sized to {@code width}. */
        void place(int x, int topY, int width);
        /** Hidden rows don't render or take clicks. */
        void setOnScreen(boolean on);
    }

    private static final class HeaderRow implements Row {
        final String text;
        final int section;
        HeaderRow(String text, int section) { this.text = text; this.section = section; }
        @Override public String label() { return text; }
        @Override public int section() { return section; }
        @Override public int height() { return HEADER_ROW_H; }
        @Override public void place(int x, int topY, int width) {}
        @Override public void setOnScreen(boolean on) {}
    }

    private static final class WidgetRow implements Row {
        final String label;
        final ClickableWidget widget;
        final int section;
        WidgetRow(String label, ClickableWidget widget, int section) {
            this.label = label;
            this.widget = widget;
            this.section = section;
        }
        @Override public String label() { return label; }
        @Override public int section() { return section; }
        @Override public int height() { return ROW_H; }
        @Override public void place(int x, int topY, int width) {
            widget.setWidth(width);
            widget.setX(x);
            widget.setY(topY + 1);
        }
        @Override public void setOnScreen(boolean on) {
            widget.visible = on;
            widget.active = on;
        }
    }
}
