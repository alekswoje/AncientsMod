package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTextField;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.client.loot.LootClient;
import com.aleks.ancientsmod.client.loot.LootRarityVisual;
import com.aleks.ancientsmod.net.payload.LootSnapshotPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Landing screen for the mod loot browser. With an empty search box it lists
 * every loot table grouped under its category (click a table → its drop list).
 * Typing in the search box switches to a search view split into two sections:
 * <ul>
 *   <li><b>Loot Tables</b> — tables whose name matches the query (e.g. typing a
 *       boss name like "Lamia" surfaces the Lamia table; click → its drops).</li>
 *   <li><b>Items</b> — a global reverse-lookup of items whose name matches: each
 *       row shows the item, the table it drops from, and the chance, so the same
 *       item across several tables reads as multiple rows. Click → that table.</li>
 * </ul>
 *
 * <p>Drawn in the flat Hearth glass: ember title with a muted table count, a bronze rule under
 * the header, category headings as ember labels trailed by a hairline, transparent rows that
 * tint on hover, and a footer with the hint on the left and Done on the right.
 */
public final class LootTablesScreen extends Screen {

    private static final int PANEL_W = 380;
    private static final int TITLE_BAR_H = 22;
    private static final int SEARCH_BAR_H = 26;
    private static final int ROW_H = 22;
    private static final int ROWS_VISIBLE = 12;
    private static final int FOOTER_H = 26;
    private static final int PADDING = 10;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_GAP = 4;
    private static final int ICON = 16;
    private static final int NAME_COL_W = 150;
    private static final int PREVIEW_COUNT = 3;

    private LootSnapshotPayload snapshot;

    /** Browse mode: each row is either a {@code String} (category header) or a {@link LootSnapshotPayload.Table}. */
    private final List<Object> browseRows = new ArrayList<>();
    /**
     * Search mode: heterogeneous rows — a {@code String} section header
     * ("Loot Tables" / "Items"), a {@link LootSnapshotPayload.Table} (table-name
     * match), or a {@link Hit} (item-name match).
     */
    private final List<Object> searchRows = new ArrayList<>();
    private boolean searchMode = false;

    private int scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragGrab = 0;
    /** Remembered across detail navigation so Back returns to the same spot. */
    private static int savedScroll = 0;
    private static String savedQuery = "";
    private GlassTextField searchField;
    private String searchQuery = "";
    private List<Text> hoverTooltip = null;

    public LootTablesScreen(LootSnapshotPayload snapshot) {
        super(Text.literal("Loot Tables"));
        this.snapshot = snapshot;
    }

    public void onSnapshotUpdated(LootSnapshotPayload payload) {
        this.snapshot = payload;
        recompute();
        clampScroll();
    }

    @Override
    protected void init() {
        searchQuery = savedQuery;
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelHeight()) / 2;
        int searchW = PANEL_W - PADDING * 2;
        this.searchField = new GlassTextField(this.textRenderer,
                panelX + PADDING, panelY + TITLE_BAR_H + 4, searchW, 18,
                Text.literal("Search any item…"));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(Text.literal("Search any item across all tables…")
                .withColor(GlassTheme.textMuted()));
        this.searchField.setText(searchQuery);
        this.searchField.setChangedListener(s -> {
            searchQuery = s == null ? "" : s;
            recompute();
            clampScroll();
        });
        this.addDrawableChild(this.searchField);
        this.addDrawableChild(new GlassButton(panelX + PANEL_W - PADDING - 52,
                panelY + panelHeight() - 20, 52, 14, Text.translatable("gui.done"), this::close).primary());
        recompute();
        scrollOffset = savedScroll;
        clampScroll();
    }

    private void recompute() {
        browseRows.clear();
        searchRows.clear();
        if (snapshot == null) return;
        String q = searchQuery.trim().toLowerCase(Locale.ROOT);
        searchMode = !q.isEmpty();

        if (searchMode) {
            // Section 1 — loot tables whose name matches (boss/table name lookup).
            List<LootSnapshotPayload.Table> tableMatches = new ArrayList<>();
            for (LootSnapshotPayload.Table t : snapshot.tables) {
                if (t.name.toLowerCase(Locale.ROOT).contains(q)) tableMatches.add(t);
            }
            tableMatches.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

            // Section 2 — items whose own name matches, across every table.
            List<Hit> itemMatches = new ArrayList<>();
            for (LootSnapshotPayload.Table t : snapshot.tables) {
                for (LootSnapshotPayload.Entry e : t.entries) {
                    if (e.masked || e.name == null) continue;
                    if (e.name.toLowerCase(Locale.ROOT).contains(q)) {
                        itemMatches.add(new Hit(t, e));
                    }
                }
            }
            // Group by item name (reverse-lookup feel), strongest drop first.
            itemMatches.sort((a, b) -> {
                int c = a.entry.name.compareToIgnoreCase(b.entry.name);
                if (c != 0) return c;
                return Double.compare(b.entry.chancePct(), a.entry.chancePct());
            });

            if (!tableMatches.isEmpty()) {
                searchRows.add("Loot Tables");
                searchRows.addAll(tableMatches);
            }
            if (!itemMatches.isEmpty()) {
                searchRows.add("Items");
                searchRows.addAll(itemMatches);
            }
        } else {
            // Group tables under their category, in category order.
            for (int ci = 0; ci < snapshot.categories.size(); ci++) {
                List<LootSnapshotPayload.Table> group = new ArrayList<>();
                for (LootSnapshotPayload.Table t : snapshot.tables) {
                    if (t.categoryIndex == ci) group.add(t);
                }
                if (group.isEmpty()) continue;
                browseRows.add(snapshot.categories.get(ci).label);
                browseRows.addAll(group);
            }
            // Tables with no resolved category (shouldn't normally happen).
            List<LootSnapshotPayload.Table> orphans = new ArrayList<>();
            for (LootSnapshotPayload.Table t : snapshot.tables) {
                if (t.categoryIndex < 0) orphans.add(t);
            }
            if (!orphans.isEmpty()) {
                browseRows.add("Other");
                browseRows.addAll(orphans);
            }
        }
    }

    private int rowCount() {
        return (searchMode ? searchRows : browseRows).size();
    }

    /** The row object at an absolute index in the active mode's list, or {@code null}. */
    private Object rowAt(int idx) {
        List<Object> rows = searchMode ? searchRows : browseRows;
        return (idx >= 0 && idx < rows.size()) ? rows.get(idx) : null;
    }

    private void clampScroll() {
        int maxOffset = Math.max(0, rowCount() - ROWS_VISIBLE);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    private int panelHeight() {
        return TITLE_BAR_H + SEARCH_BAR_H + ROWS_VISIBLE * ROW_H + FOOTER_H;
    }

    private int listX() { return (this.width - PANEL_W) / 2 + PADDING; }
    private int listY() { return (this.height - panelHeight()) / 2 + TITLE_BAR_H + SEARCH_BAR_H; }
    private int listW() { return PANEL_W - PADDING * 2 - SCROLLBAR_W - SCROLLBAR_GAP; }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxOffset = Math.max(0, rowCount() - ROWS_VISIBLE);
        if (maxOffset <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollOffset -= (int) Math.signum(verticalAmount);
        clampScroll();
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0) {
            if (overScrollbar(click.x(), click.y())) {
                beginScrollDrag(click.y());
                return true;
            }
            int idx = rowUnderCursor(click.x(), click.y());
            if (idx >= 0) {
                Object row = rowAt(idx);
                if (row instanceof LootSnapshotPayload.Table t) {
                    openDetail(t.tableId);
                    return true;
                }
                if (row instanceof Hit h) {
                    openDetail(h.table.tableId);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    private void openDetail(String tableId) {
        // Level-banded rows open the server's level-picker menu (the Polis loot menu), where
        // every level's drops can be browsed: /loot tear and /loot shades.
        String serverMenu = "tear_crate".equals(tableId) ? "loot tear"
                : "shade".equals(tableId) || "wraith".equals(tableId) ? "loot shades" : null;
        if (serverMenu != null) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getNetworkHandler() != null) {
                LootClient.onScreenClosed();
                mc.setScreen(null);
                mc.getNetworkHandler().sendChatCommand(serverMenu);
                return;
            }
        }
        savedScroll = scrollOffset;
        savedQuery = searchQuery;
        MinecraftClient.getInstance().setScreen(new LootTableDetailScreen(snapshot, tableId));
    }

    /** Absolute row index under the cursor (already offset by scroll), or -1. */
    private int rowUnderCursor(double mx, double my) {
        int lx = listX();
        int ly = listY();
        int lw = listW();
        if (mx < lx || mx >= lx + lw) return -1;
        if (my < ly || my >= ly + ROWS_VISIBLE * ROW_H) return -1;
        int rel = (int) ((my - ly) / ROW_H);
        int idx = scrollOffset + rel;
        if (idx < 0 || idx >= rowCount()) return -1;
        return idx;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        GlassRender.menuBackdrop(ctx, this.width, this.height);
        int panelW = PANEL_W;
        int panelH = panelHeight();
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        GlassRender.panel(ctx, panelX, panelY, panelW, panelH);

        // Header: ember title, muted count beside it, bronze rule underneath.
        int tableCount = snapshot != null ? snapshot.tables.size() : 0;
        ctx.drawText(this.textRenderer, Text.literal("Loot Tables"),
                panelX + PADDING, panelY + 8, GlassTheme.ACCENT, true);
        int titleW = this.textRenderer.getWidth("Loot Tables");
        ctx.drawText(this.textRenderer, Text.literal(tableCount == 1 ? "1 table" : tableCount + " tables"),
                panelX + PADDING + titleW + 6, panelY + 8, GlassTheme.textMuted(), false);
        GlassRender.rule(ctx, panelX + PADDING, panelX + panelW - PADDING, panelY + TITLE_BAR_H);

        // Footer rule (hint + Done are drawn in render()).
        GlassRender.rule(ctx, panelX + 1, panelX + panelW - 1, panelY + panelH - FOOTER_H);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        hoverTooltip = null;

        int lx = listX();
        int ly = listY();
        int lw = listW();
        int listH = ROWS_VISIBLE * ROW_H;

        int first = scrollOffset;
        int last = Math.min(rowCount(), first + ROWS_VISIBLE);
        for (int i = first; i < last; i++) {
            int rel = i - first;
            int ry = ly + rel * ROW_H;
            Object row = rowAt(i);
            if (row instanceof Hit h) {
                renderHitRow(ctx, h, lx, ry, lw, mouseX, mouseY);
            } else {
                renderBrowseRow(ctx, row, lx, ry, lw, mouseX, mouseY);
            }
        }

        if (rowCount() == 0) {
            String msg = searchMode
                    ? "Nothing matches \"" + searchQuery + "\""
                    : "No loot tables available.";
            ctx.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(msg, lw - 8)),
                    lx + 4, ly + 6, GlassTheme.textMuted(), false);
        }

        // Scrollbar.
        int maxOffset = Math.max(0, rowCount() - ROWS_VISIBLE);
        if (maxOffset > 0) {
            int sbX = lx + lw + SCROLLBAR_GAP;
            double ratio = (double) ROWS_VISIBLE / rowCount();
            int thumbH = Math.max(20, (int) (listH * ratio));
            int range = listH - thumbH;
            int thumbY = ly + (int) (range * ((double) scrollOffset / maxOffset));
            GlassRender.scrollbar(ctx, sbX + (SCROLLBAR_W - 3) / 2, ly, ly + listH, thumbY, thumbH);
        }

        // Footer hint.
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelHeight()) / 2;
        String hint = searchMode ? "Click a result to open its table" : "Click a table to view its drops";
        ctx.drawText(this.textRenderer, Text.literal(hint),
                panelX + PADDING, panelY + panelHeight() - FOOTER_H + 9, GlassTheme.textMuted(), false);

        if (hoverTooltip != null) {
            ctx.drawTooltip(this.textRenderer, hoverTooltip, mouseX, mouseY);
        }
    }

    private void renderBrowseRow(DrawContext ctx, Object row, int lx, int ry, int lw, int mouseX, int mouseY) {
        if (row instanceof String header) {
            renderSectionHeader(ctx, header, lx, ry, lw);
            return;
        }
        LootSnapshotPayload.Table t = (LootSnapshotPayload.Table) row;
        boolean hovered = mouseX >= lx && mouseX < lx + lw && mouseY >= ry && mouseY < ry + ROW_H;
        GlassRender.row(ctx, lx, ry, lx + lw, ry + ROW_H - 2, hovered);

        int iconY = ry + (ROW_H - 2 - ICON) / 2;
        ctx.drawItem(resolveIcon(t.iconKey), lx + 2, iconY);

        int nameX = lx + ICON + 6;
        String name = this.textRenderer.trimToWidth(t.name, NAME_COL_W);
        ctx.drawText(this.textRenderer, Text.literal(name), nameX, ry + 6, GlassTheme.text(), false);

        // Preview icons of the first few drops (masked drops render as paper).
        int px = nameX + NAME_COL_W + 4;
        int shown = 0;
        for (LootSnapshotPayload.Entry e : t.entries) {
            if (shown >= PREVIEW_COUNT) break;
            ItemStack icon = e.masked ? new ItemStack(Items.PAPER) : resolveIcon(e.iconKey);
            if (icon.isEmpty()) continue;
            ctx.drawItem(icon, px, iconY);
            px += 18;
            shown++;
        }

        String drops = t.entries.size() + " drops";
        int dropsW = this.textRenderer.getWidth(drops);
        ctx.drawText(this.textRenderer, Text.literal(drops), lx + lw - dropsW - 2, ry + 6, GlassTheme.textMuted(), false);

        if (hovered) hoverTooltip = buildTableTooltip(t);
    }

    /** Category / search-section heading: ember label with a bronze hairline to the row's end. */
    private void renderSectionHeader(DrawContext ctx, String label, int lx, int ry, int lw) {
        int ty = ry + ROW_H - this.textRenderer.fontHeight - 4;
        ctx.drawText(this.textRenderer, Text.literal(label), lx + 2, ty, GlassTheme.ACCENT, false);
        int ruleX = lx + 2 + this.textRenderer.getWidth(label) + 6;
        if (ruleX < lx + lw) GlassRender.rule(ctx, ruleX, lx + lw, ty + this.textRenderer.fontHeight / 2);
    }

    /** Hover preview for a table row: name, section, roll count, and the top drops. */
    private List<Text> buildTableTooltip(LootSnapshotPayload.Table t) {
        List<Text> tip = new ArrayList<>();
        tip.add(Text.literal(t.name).withColor(GlassTheme.ACCENT));
        if (t.categoryIndex >= 0 && t.categoryIndex < snapshot.categories.size()) {
            tip.add(Text.literal("§7Section: §f" + snapshot.categories.get(t.categoryIndex).label));
        }
        tip.add(Text.literal("§7" + t.entries.size() + " drops, rolls/trigger " + t.rollsText()));
        if (!t.entries.isEmpty()) {
            tip.add(Text.literal("§7Top drops:"));
            int n = Math.min(5, t.entries.size());
            for (int i = 0; i < n; i++) {
                LootSnapshotPayload.Entry e = t.entries.get(i);
                String nm = e.masked
                        ? "§8???"
                        : (LootRarityVisual.has(e.rarity) ? LootRarityVisual.code(e.rarity) : "§f")
                          + (e.name == null ? "?" : e.name);
                tip.add(Text.literal("  ").append(Text.literal(e.chanceText()).withColor(GlassTheme.VALUE))
                        .append(Text.literal(" " + nm)));
            }
            if (t.entries.size() > n) {
                tip.add(Text.literal("  §8…and " + (t.entries.size() - n) + " more"));
            }
        }
        tip.add(Text.literal("§8Click to view all drops"));
        return tip;
    }

    private void renderHitRow(DrawContext ctx, Hit hit, int lx, int ry, int lw, int mouseX, int mouseY) {
        LootSnapshotPayload.Entry e = hit.entry;
        boolean hovered = mouseX >= lx && mouseX < lx + lw && mouseY >= ry && mouseY < ry + ROW_H;
        GlassRender.row(ctx, lx, ry, lx + lw, ry + ROW_H - 2, hovered);

        int iconY = ry + (ROW_H - 2 - ICON) / 2;
        ctx.drawItem(resolveIcon(e.iconKey), lx + 2, iconY);

        int nameX = lx + ICON + 6;
        String nameCode = LootRarityVisual.has(e.rarity) ? LootRarityVisual.code(e.rarity) : "§f";
        String chance = e.chanceText();
        int chanceW = this.textRenderer.getWidth(chance);
        // Item name in its rarity colour, then "in <table>" in muted text, trimmed together.
        int avail = Math.max(20, lw - (nameX - lx) - chanceW - 8);
        String itemName = this.textRenderer.trimToWidth(nameCode + (e.name == null ? "?" : e.name), avail);
        ctx.drawText(this.textRenderer, Text.literal(itemName), nameX, ry + 6, GlassTheme.text(), false);
        int inX = nameX + this.textRenderer.getWidth(itemName) + 4;
        int inW = nameX + avail - inX;
        if (inW > 12) {
            String where = this.textRenderer.trimToWidth("in " + hit.table.name, inW);
            ctx.drawText(this.textRenderer, Text.literal(where), inX, ry + 6, GlassTheme.textMuted(), false);
        }
        ctx.drawText(this.textRenderer, Text.literal(chance), lx + lw - chanceW - 2, ry + 6, GlassTheme.VALUE, false);

        if (hovered) {
            List<Text> tip = new ArrayList<>();
            tip.add(Text.literal(nameCode + (e.name == null ? "?" : e.name)));
            tip.add(Text.literal("§7Table: §f" + hit.table.name));
            tip.add(Text.literal("§7Drop chance: §f" + e.chanceText()));
            if (e.amountText != null && !e.amountText.isEmpty()) {
                tip.add(Text.literal("§7Amount: §f" + e.amountText));
            }
            if (LootRarityVisual.has(e.rarity)) {
                tip.add(Text.literal("§7Rarity: " + LootRarityVisual.code(e.rarity) + LootRarityVisual.name(e.rarity)));
            }
            tip.add(Text.literal("§8Click to open this table"));
            hoverTooltip = tip;
        }
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingScroll && click.button() == 0) {
            updateScrollDrag(click.y());
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingScroll && click.button() == 0) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    private boolean overScrollbar(double mx, double my) {
        if (Math.max(0, rowCount() - ROWS_VISIBLE) <= 0) return false;
        int sbX = listX() + listW() + SCROLLBAR_GAP;
        int sbY = listY();
        int sbH = ROWS_VISIBLE * ROW_H;
        return mx >= sbX && mx < sbX + SCROLLBAR_W && my >= sbY && my < sbY + sbH;
    }

    private void beginScrollDrag(double my) {
        int count = rowCount();
        int maxOffset = Math.max(0, count - ROWS_VISIBLE);
        if (maxOffset <= 0) return;
        int sbY = listY();
        int listH = ROWS_VISIBLE * ROW_H;
        int thumbH = Math.max(20, (int) (listH * ((double) ROWS_VISIBLE / count)));
        int range = listH - thumbH;
        int thumbY = sbY + (range <= 0 ? 0 : (int) Math.round(range * ((double) scrollOffset / maxOffset)));
        if (my >= thumbY && my < thumbY + thumbH) {
            dragGrab = my - thumbY;
        } else {
            dragGrab = thumbH / 2.0;
            draggingScroll = true;
            updateScrollDrag(my);
            return;
        }
        draggingScroll = true;
    }

    private void updateScrollDrag(double my) {
        int count = rowCount();
        int maxOffset = Math.max(0, count - ROWS_VISIBLE);
        if (maxOffset <= 0) return;
        int sbY = listY();
        int listH = ROWS_VISIBLE * ROW_H;
        int thumbH = Math.max(20, (int) (listH * ((double) ROWS_VISIBLE / count)));
        int range = listH - thumbH;
        if (range <= 0) { scrollOffset = 0; return; }
        double thumbTop = my - dragGrab;
        double frac = (thumbTop - sbY) / range;
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        scrollOffset = (int) Math.round(frac * maxOffset);
        clampScroll();
    }

    private static ItemStack resolveIcon(String key) {
        return com.aleks.ancientsmod.client.IconResolver.resolve(key, Items.PAPER, 1);
    }

    @Override
    public void close() {
        savedScroll = 0;
        savedQuery = "";
        LootClient.onScreenClosed();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static final class Hit {
        final LootSnapshotPayload.Table table;
        final LootSnapshotPayload.Entry entry;

        Hit(LootSnapshotPayload.Table table, LootSnapshotPayload.Entry entry) {
            this.table = table;
            this.entry = entry;
        }
    }
}
