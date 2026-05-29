package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.loot.LootClient;
import com.aleks.prisonsmod.client.loot.LootRarityVisual;
import com.aleks.prisonsmod.net.payload.LootSnapshotPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
 * Typing in the search box switches to a global item search across all tables:
 * each match shows the item, the table it drops from, and the chance — so the
 * same item appearing in several tables reads as a reverse-lookup. Clicking a
 * result opens that table's drops.
 */
public final class LootTablesScreen extends Screen {

    private static final int PANEL_W = 380;
    private static final int TITLE_BAR_H = 22;
    private static final int SEARCH_BAR_H = 26;
    private static final int ROW_H = 22;
    private static final int ROWS_VISIBLE = 12;
    private static final int FOOTER_H = 16;
    private static final int PADDING = 8;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_GAP = 4;
    private static final int ICON = 16;
    private static final int NAME_COL_W = 150;
    private static final int PREVIEW_COUNT = 3;

    private LootSnapshotPayload snapshot;

    /** Browse mode: each row is either a {@code String} (category header) or a {@link LootSnapshotPayload.Table}. */
    private final List<Object> browseRows = new ArrayList<>();
    /** Search mode: flat list of (table, entry) matches. */
    private final List<Hit> searchHits = new ArrayList<>();
    private boolean searchMode = false;

    private int scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragGrab = 0;
    /** Remembered across detail navigation so Back returns to the same spot. */
    private static int savedScroll = 0;
    private static String savedQuery = "";
    private TextFieldWidget searchField;
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
        this.searchField = new TextFieldWidget(this.textRenderer,
                panelX + PADDING, panelY + TITLE_BAR_H + 4, searchW, 18,
                Text.literal("Search any item…"));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(Text.literal("§7Search any item across all tables…"));
        this.searchField.setText(searchQuery);
        this.searchField.setChangedListener(s -> {
            searchQuery = s == null ? "" : s;
            recompute();
            clampScroll();
        });
        this.addDrawableChild(this.searchField);
        recompute();
        scrollOffset = savedScroll;
        clampScroll();
    }

    private void recompute() {
        browseRows.clear();
        searchHits.clear();
        if (snapshot == null) return;
        String q = searchQuery.trim().toLowerCase(Locale.ROOT);
        searchMode = !q.isEmpty();

        if (searchMode) {
            for (LootSnapshotPayload.Table t : snapshot.tables) {
                for (LootSnapshotPayload.Entry e : t.entries) {
                    if (e.masked || e.name == null) continue;
                    if (e.name.toLowerCase(Locale.ROOT).contains(q)) {
                        searchHits.add(new Hit(t, e));
                    }
                }
            }
            // Group by item name (reverse-lookup feel), strongest drop first.
            searchHits.sort((a, b) -> {
                int c = a.entry.name.compareToIgnoreCase(b.entry.name);
                if (c != 0) return c;
                return Double.compare(b.entry.chancePct(), a.entry.chancePct());
            });
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
        return searchMode ? searchHits.size() : browseRows.size();
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
            int[] c = closeButtonBounds();
            if (click.x() >= c[0] && click.x() < c[0] + c[2]
                    && click.y() >= c[1] && click.y() < c[1] + c[3]) {
                this.close();
                return true;
            }
            if (overScrollbar(click.x(), click.y())) {
                beginScrollDrag(click.y());
                return true;
            }
            int idx = rowUnderCursor(click.x(), click.y());
            if (idx >= 0) {
                if (searchMode) {
                    if (idx < searchHits.size()) {
                        openDetail(searchHits.get(idx).table.tableId);
                        return true;
                    }
                } else if (idx < browseRows.size()) {
                    Object row = browseRows.get(idx);
                    if (row instanceof LootSnapshotPayload.Table t) {
                        openDetail(t.tableId);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    private void openDetail(String tableId) {
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
        int panelW = PANEL_W;
        int panelH = panelHeight();
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0101010);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF555555);
        ctx.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF555555);
        ctx.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF555555);
        ctx.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF555555);

        ctx.fill(panelX, panelY, panelX + panelW, panelY + TITLE_BAR_H, 0xFF1A1A1A);
        int tableCount = snapshot != null ? snapshot.tables.size() : 0;
        ctx.drawText(this.textRenderer, Text.literal("§e§lLoot Tables §8· §7" + tableCount + " tables"),
                panelX + 10, panelY + 7, 0xFFFFFFFF, true);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        hoverTooltip = null;
        renderCloseButton(ctx, mouseX, mouseY);

        int lx = listX();
        int ly = listY();
        int lw = listW();
        int listH = ROWS_VISIBLE * ROW_H;

        int first = scrollOffset;
        int last = Math.min(rowCount(), first + ROWS_VISIBLE);
        for (int i = first; i < last; i++) {
            int rel = i - first;
            int ry = ly + rel * ROW_H;
            if (searchMode) {
                renderHitRow(ctx, searchHits.get(i), lx, ry, lw, mouseX, mouseY);
            } else {
                renderBrowseRow(ctx, browseRows.get(i), lx, ry, lw, mouseX, mouseY);
            }
        }

        if (rowCount() == 0) {
            String msg = searchMode
                    ? "§7No items match §f\"" + searchQuery + "\""
                    : "§7No loot tables available.";
            ctx.drawText(this.textRenderer, Text.literal(msg), lx + 4, ly + listH / 2 - 4, 0xFFAAAAAA, false);
        }

        // Scrollbar.
        int maxOffset = Math.max(0, rowCount() - ROWS_VISIBLE);
        if (maxOffset > 0) {
            int sbX = lx + lw + SCROLLBAR_GAP;
            ctx.fill(sbX, ly, sbX + SCROLLBAR_W, ly + listH, 0x80000000);
            double ratio = (double) ROWS_VISIBLE / rowCount();
            int thumbH = Math.max(20, (int) (listH * ratio));
            int range = listH - thumbH;
            int thumbY = ly + (int) (range * ((double) scrollOffset / maxOffset));
            ctx.fill(sbX, thumbY, sbX + SCROLLBAR_W, thumbY + thumbH, 0xFFAAAAAA);
        }

        // Footer hint.
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelHeight()) / 2;
        String hint = searchMode ? "§8Click a result → open its table" : "§8Click a table to view its drops";
        ctx.drawText(this.textRenderer, Text.literal(hint),
                panelX + 10, panelY + panelHeight() - 12, 0xFF777777, false);

        if (hoverTooltip != null) {
            ctx.drawTooltip(this.textRenderer, hoverTooltip, mouseX, mouseY);
        }
    }

    private void renderBrowseRow(DrawContext ctx, Object row, int lx, int ry, int lw, int mouseX, int mouseY) {
        if (row instanceof String header) {
            ctx.fill(lx, ry, lx + lw, ry + ROW_H - 2, 0xFF222630);
            ctx.drawText(this.textRenderer, Text.literal("§b§l" + header),
                    lx + 4, ry + 6, 0xFFFFFFFF, false);
            return;
        }
        LootSnapshotPayload.Table t = (LootSnapshotPayload.Table) row;
        boolean hovered = mouseX >= lx && mouseX < lx + lw && mouseY >= ry && mouseY < ry + ROW_H;
        ctx.fill(lx, ry, lx + lw, ry + ROW_H - 2, hovered ? 0x33FFFFFF : 0xFF0E0E0E);

        int iconY = ry + (ROW_H - 2 - ICON) / 2;
        ctx.drawItem(resolveIcon(t.iconKey), lx + 2, iconY);

        int nameX = lx + ICON + 6;
        String name = this.textRenderer.trimToWidth("§f" + t.name, NAME_COL_W);
        ctx.drawText(this.textRenderer, Text.literal(name), nameX, ry + 6, 0xFFFFFFFF, false);

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

        String drops = "§8" + t.entries.size() + " drops";
        int dropsW = this.textRenderer.getWidth(drops);
        ctx.drawText(this.textRenderer, Text.literal(drops), lx + lw - dropsW - 2, ry + 6, 0xFF888888, false);

        if (hovered) hoverTooltip = buildTableTooltip(t);
    }

    /** Hover preview for a table row: name, section, roll count, and the top drops. */
    private List<Text> buildTableTooltip(LootSnapshotPayload.Table t) {
        List<Text> tip = new ArrayList<>();
        tip.add(Text.literal("§b§l" + t.name));
        if (t.categoryIndex >= 0 && t.categoryIndex < snapshot.categories.size()) {
            tip.add(Text.literal("§7Section: §f" + snapshot.categories.get(t.categoryIndex).label));
        }
        tip.add(Text.literal("§7" + t.entries.size() + " drops §8· §7rolls/trigger " + t.rollsText()));
        if (!t.entries.isEmpty()) {
            tip.add(Text.literal("§7Top drops:"));
            int n = Math.min(5, t.entries.size());
            for (int i = 0; i < n; i++) {
                LootSnapshotPayload.Entry e = t.entries.get(i);
                String nm = e.masked
                        ? "§8???"
                        : (LootRarityVisual.has(e.rarity) ? LootRarityVisual.code(e.rarity) : "§f")
                          + (e.name == null ? "?" : e.name);
                tip.add(Text.literal("  §6" + e.chanceText() + " §r" + nm));
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
        ctx.fill(lx, ry, lx + lw, ry + ROW_H - 2, hovered ? 0x33FFFFFF : 0xFF0E0E0E);

        int iconY = ry + (ROW_H - 2 - ICON) / 2;
        ctx.drawItem(resolveIcon(e.iconKey), lx + 2, iconY);

        int nameX = lx + ICON + 6;
        String nameCode = LootRarityVisual.has(e.rarity) ? LootRarityVisual.code(e.rarity) : "§f";
        String chance = "§6" + e.chanceText();
        int chanceW = this.textRenderer.getWidth(chance);
        String line = nameCode + (e.name == null ? "?" : e.name) + " §8in " + hit.table.name;
        String trimmed = this.textRenderer.trimToWidth(line, Math.max(20, lw - (nameX - lx) - chanceW - 8));
        ctx.drawText(this.textRenderer, Text.literal(trimmed), nameX, ry + 6, 0xFFFFFFFF, false);
        ctx.drawText(this.textRenderer, Text.literal(chance), lx + lw - chanceW - 2, ry + 6, 0xFFFFFFFF, false);

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
            tip.add(Text.literal("§8Click → open this table"));
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

    private int[] closeButtonBounds() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelHeight()) / 2;
        int w = 16;
        int h = 14;
        return new int[]{ panelX + PANEL_W - w - 6, panelY + 4, w, h };
    }

    private void renderCloseButton(DrawContext ctx, int mouseX, int mouseY) {
        int[] b = closeButtonBounds();
        boolean hov = mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
        ctx.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], hov ? 0xFFAA3030 : 0xFF402020);
        ctx.fill(b[0], b[1], b[0] + b[2], b[1] + 1, 0xFF777777);
        ctx.drawText(this.textRenderer, Text.literal("§f✕"), b[0] + 5, b[1] + 3, 0xFFFFFFFF, false);
    }

    private static ItemStack resolveIcon(String key) {
        if (key == null || key.isEmpty()) return new ItemStack(Items.PAPER);
        Identifier id = Identifier.tryParse(key);
        if (id == null) return new ItemStack(Items.PAPER);
        Item item = Registries.ITEM.get(id);
        return item == Items.AIR ? new ItemStack(Items.PAPER) : new ItemStack(item);
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
