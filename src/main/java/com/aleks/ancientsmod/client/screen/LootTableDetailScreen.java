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
 * Read-only drop list for a single loot table. Each row shows the item icon,
 * its rarity-coloured name, the drop chance, and (second line) the amount range
 * + rarity. Undiscovered in-scope entries render as masked "???" with rarity +
 * chance still visible — matching the server chest GUI.
 *
 * <p>ESC returns to {@link LootTablesScreen} (via {@link LootClient#openTableList()}).
 *
 * <p>Drawn in the flat Hearth glass: ember table name with a muted drop count, a meta line
 * (rolls, luck), a bronze rule, transparent rows that tint on hover, chances in Flame (with the
 * luck-adjusted figure in Moss), and a footer with the hint on the left and Back on the right.
 */
public final class LootTableDetailScreen extends Screen {

    private static final int PANEL_W = 380;
    private static final int TITLE_BAR_H = 22;
    private static final int SUBHEADER_H = 14;
    private static final int SEARCH_BAR_H = 26;
    private static final int ROW_H = 24;
    private static final int ROWS_VISIBLE = 10;
    private static final int FOOTER_H = 26;
    private static final int PADDING = 10;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_GAP = 4;
    private static final int ICON = 16;
    /** Room for "Lv 103" between the -1 and +1 buttons. */
    private static final int LEVEL_LABEL_W = 44;

    private LootSnapshotPayload snapshot;
    private final String tableId;
    private LootSnapshotPayload.Table table;

    private final List<LootSnapshotPayload.Entry> filtered = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragGrab = 0;
    private GlassTextField searchField;
    private String searchQuery = "";
    private List<Text> hoverTooltip = null;
    /** This row's level picker, or null for an ordinary table. */
    private LootSnapshotPayload.Level levelRange;
    /** The level on show, moved at once on a click while the server rebuilds the catalog. */
    private int shownLevel;

    public LootTableDetailScreen(LootSnapshotPayload snapshot, String tableId) {
        super(Text.literal("Loot: " + tableId));
        this.snapshot = snapshot;
        this.tableId = tableId;
        this.table = findTable(snapshot, tableId);
        readLevel(snapshot);
    }

    public void onSnapshotUpdated(LootSnapshotPayload payload) {
        this.snapshot = payload;
        LootSnapshotPayload.Table t = findTable(payload, tableId);
        if (t != null) this.table = t;
        readLevel(payload);
        recompute();
        clampScroll();
    }

    private void readLevel(LootSnapshotPayload payload) {
        LootSnapshotPayload.Level level = payload == null || payload.levels == null ? null : payload.levels.get(tableId);
        if (level == null) return;
        levelRange = level;
        shownLevel = level.level();
    }

    private void stepLevel(int delta) { setLevel(shownLevel + delta); }

    private void setLevel(int level) {
        if (levelRange == null) return;
        int next = levelRange.clamp(level);
        if (next == shownLevel) return;
        shownLevel = next;
        scrollOffset = 0;
        LootClient.pickLevel(tableId, next);
    }

    private static LootSnapshotPayload.Table findTable(LootSnapshotPayload snap, String id) {
        if (snap == null || id == null) return null;
        for (LootSnapshotPayload.Table t : snap.tables) {
            if (id.equals(t.tableId)) return t;
        }
        return null;
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelHeight()) / 2;
        int searchW = PANEL_W - PADDING * 2;
        this.searchField = new GlassTextField(this.textRenderer,
                panelX + PADDING, panelY + TITLE_BAR_H + SUBHEADER_H + 4, searchW, 18,
                Text.literal("Search this table…"));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(Text.literal("Search this table…").withColor(GlassTheme.textMuted()));
        this.searchField.setText(searchQuery);
        this.searchField.setChangedListener(s -> {
            searchQuery = s == null ? "" : s;
            recompute();
            clampScroll();
        });
        this.addDrawableChild(this.searchField);
        this.addDrawableChild(new GlassButton(panelX + PANEL_W - PADDING - 52,
                panelY + panelHeight() - 20, 52, 14, Text.literal("Back"), LootClient::openTableList).primary());
        if (levelRange != null) {
            // Polis loot menu's controls: -10 -1 [level] +1 +10, and a reset to the row's home level.
            int by = panelY + panelHeight() - 20;
            int bx = panelX + PADDING;
            this.addDrawableChild(new GlassButton(bx, by, 24, 14, Text.literal("-10"), () -> stepLevel(-10)));
            this.addDrawableChild(new GlassButton(bx + 26, by, 20, 14, Text.literal("-1"), () -> stepLevel(-1)));
            this.addDrawableChild(new GlassButton(bx + 48 + LEVEL_LABEL_W, by, 20, 14, Text.literal("+1"), () -> stepLevel(1)));
            this.addDrawableChild(new GlassButton(bx + 70 + LEVEL_LABEL_W, by, 24, 14, Text.literal("+10"), () -> stepLevel(10)));
            String reset = "tear_crate".equals(tableId) ? "Now" : "Mine";
            this.addDrawableChild(new GlassButton(bx + 98 + LEVEL_LABEL_W, by, 32, 14, Text.literal(reset),
                    () -> setLevel(levelRange.home())));
        }
        recompute();
    }

    private void recompute() {
        filtered.clear();
        if (table == null) return;
        String q = searchQuery.trim().toLowerCase(Locale.ROOT);
        for (LootSnapshotPayload.Entry e : table.entries) {
            if (q.isEmpty()) {
                filtered.add(e);
            } else if (!e.masked && e.name != null && e.name.toLowerCase(Locale.ROOT).contains(q)) {
                filtered.add(e);
            }
        }
    }

    private void clampScroll() {
        int maxOffset = Math.max(0, filtered.size() - ROWS_VISIBLE);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    private int panelHeight() {
        return TITLE_BAR_H + SUBHEADER_H + SEARCH_BAR_H + ROWS_VISIBLE * ROW_H + FOOTER_H;
    }

    private int listX() { return (this.width - PANEL_W) / 2 + PADDING; }
    private int listY() {
        return (this.height - panelHeight()) / 2 + TITLE_BAR_H + SUBHEADER_H + SEARCH_BAR_H;
    }
    private int listW() { return PANEL_W - PADDING * 2 - SCROLLBAR_W - SCROLLBAR_GAP; }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxOffset = Math.max(0, filtered.size() - ROWS_VISIBLE);
        if (maxOffset <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollOffset -= (int) Math.signum(verticalAmount);
        clampScroll();
        return true;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        int panelW = PANEL_W;
        int panelH = panelHeight();
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        GlassRender.menuBackdrop(ctx, this.width, this.height);
        GlassRender.panel(ctx, panelX, panelY, panelW, panelH);

        // Header: ember table name, muted drop count beside it.
        int hx = panelX + PADDING;
        String countText = (table != null ? table.entries.size() : 0) + " drops";
        int nameMax = panelW - 2 * PADDING - this.textRenderer.getWidth(countText) - 6;
        String baseName = table != null ? table.name : tableId;
        if (levelRange != null) baseName += " · Level " + shownLevel;
        String name = this.textRenderer.trimToWidth(baseName, nameMax);
        ctx.drawText(this.textRenderer, Text.literal(name), hx, panelY + 8, GlassTheme.ACCENT, true);
        int nameW = this.textRenderer.getWidth(name);
        ctx.drawText(this.textRenderer, Text.literal(countText),
                hx + nameW + 6, panelY + 8, GlassTheme.textMuted(), false);

        // Meta line: rolls (value in Flame) then luck (Moss when it applies), rule underneath.
        String rolls = table != null ? table.rollsText() : "?";
        double luckPct = LootClient.luckPercent();
        int my = panelY + TITLE_BAR_H;
        ctx.drawText(this.textRenderer, Text.literal("Rolls/trigger:"), hx, my, GlassTheme.textMuted(), false);
        int labelW = this.textRenderer.getWidth("Rolls/trigger: ");
        ctx.drawText(this.textRenderer, Text.literal(rolls), hx + labelW, my, GlassTheme.VALUE, false);
        int rollsW = labelW + this.textRenderer.getWidth(rolls);
        String luck;
        int luckColor;
        if (table != null && table.luck) {
            luck = (luckPct > 0)
                    ? String.format(Locale.US, "Luck affects this table (%.1f%% luck)", luckPct)
                    : "Luck affects this table";
            luckColor = GlassTheme.OK;
        } else {
            luck = "Luck does not affect this table";
            luckColor = GlassTheme.textMuted();
        }
        int luckX = hx + rollsW + 12;
        ctx.drawText(this.textRenderer,
                Text.literal(this.textRenderer.trimToWidth(luck, panelX + panelW - PADDING - luckX)),
                luckX, my, luckColor, false);
        GlassRender.rule(ctx, hx, panelX + panelW - PADDING, panelY + TITLE_BAR_H + SUBHEADER_H - 1);

        // Footer rule (hint + Back are drawn in render()).
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

        if (table == null) {
            ctx.drawText(this.textRenderer, Text.literal("This table is no longer available."),
                    lx + 4, ly + 6, GlassTheme.textMuted(), false);
            renderFooter(ctx);
            return;
        }

        int firstIdx = scrollOffset;
        int lastIdx = Math.min(filtered.size(), firstIdx + ROWS_VISIBLE);
        for (int i = firstIdx; i < lastIdx; i++) {
            LootSnapshotPayload.Entry e = filtered.get(i);
            int rel = i - firstIdx;
            int ry = ly + rel * ROW_H;

            // Row background + hover highlight.
            boolean hovered = mouseX >= lx && mouseX < lx + lw && mouseY >= ry && mouseY < ry + ROW_H;
            GlassRender.row(ctx, lx, ry, lx + lw, ry + ROW_H - 2, hovered);

            int iconY = ry + (ROW_H - 2 - ICON) / 2;
            ItemStack icon = e.masked ? new ItemStack(Items.PAPER) : resolveIcon(e.iconKey);
            ctx.drawItem(icon, lx + 2, iconY);

            int rarityColor = LootRarityVisual.argb(e.rarity);
            String nameCode = e.masked
                    ? LootRarityVisual.code(e.rarity)
                    : (LootRarityVisual.has(e.rarity) ? LootRarityVisual.code(e.rarity) : "§f");
            String displayName = e.masked ? "???" : (e.name == null || e.name.isEmpty() ? "?" : e.name);

            // Right-aligned chance: raw in Flame, plus "→ luck-adjusted" in Moss when the
            // viewer has luck and this table is luck-affected (matches the server chest
            // GUI's "With luck" line, inline here).
            double luckPct = LootClient.luckPercent();
            boolean showAdj = table.showsLuck(luckPct);
            String rawChance = e.chanceText();
            String arrow = " → ";
            String adjChance = showAdj
                    ? LootSnapshotPayload.Entry.pctText(table.adjustedChancePct(e, luckPct)) : "";
            int chanceW = this.textRenderer.getWidth(rawChance)
                    + (showAdj ? this.textRenderer.getWidth(arrow) + this.textRenderer.getWidth(adjChance) : 0);

            int nameX = lx + ICON + 6;
            int nameMaxW = lw - (nameX - lx) - chanceW - 8;
            String trimmedName = this.textRenderer.trimToWidth(nameCode + displayName, Math.max(20, nameMaxW));
            ctx.drawText(this.textRenderer, Text.literal(trimmedName), nameX, ry + 3, GlassTheme.text(), false);

            int cx = lx + lw - chanceW - 2;
            ctx.drawText(this.textRenderer, Text.literal(rawChance), cx, ry + 3, GlassTheme.VALUE, false);
            if (showAdj) {
                cx += this.textRenderer.getWidth(rawChance);
                ctx.drawText(this.textRenderer, Text.literal(arrow), cx, ry + 3, GlassTheme.textMuted(), false);
                cx += this.textRenderer.getWidth(arrow);
                ctx.drawText(this.textRenderer, Text.literal(adjChance), cx, ry + 3, GlassTheme.OK, false);
            }

            // Second line: amount + rarity (or "Undiscovered").
            // Uncoded text takes the muted base colour; the rarity word keeps its § code.
            String second;
            if (e.masked) {
                second = "Undiscovered. Be the first to drop this!";
            } else {
                StringBuilder sb = new StringBuilder();
                if (e.amountText != null && !e.amountText.isEmpty()) sb.append("x").append(e.amountText);
                if (LootRarityVisual.has(e.rarity)) {
                    if (sb.length() > 0) sb.append("  ");
                    sb.append(LootRarityVisual.code(e.rarity)).append(LootRarityVisual.name(e.rarity));
                }
                second = sb.toString();
            }
            ctx.drawText(this.textRenderer, Text.literal(second), nameX, ry + 13, GlassTheme.textMuted(), false);

            if (hovered) hoverTooltip = buildTooltip(e, displayName);
        }

        // Empty / no-match.
        if (filtered.isEmpty()) {
            String msg = searchQuery.trim().isEmpty()
                    ? "This table has no drops."
                    : "No drops match \"" + searchQuery + "\"";
            ctx.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(msg, lw - 8)),
                    lx + 4, ly + 6, GlassTheme.textMuted(), false);
        }

        // Scrollbar.
        int maxOffset = Math.max(0, filtered.size() - ROWS_VISIBLE);
        if (maxOffset > 0) {
            int sbX = lx + lw + SCROLLBAR_GAP;
            double ratio = (double) ROWS_VISIBLE / filtered.size();
            int thumbH = Math.max(20, (int) (listH * ratio));
            int range = listH - thumbH;
            int thumbY = ly + (int) (range * ((double) scrollOffset / maxOffset));
            // Centre the 3px glass bar within the 6px hit slot (hit-testing geometry unchanged).
            GlassRender.scrollbar(ctx, sbX + (SCROLLBAR_W - 3) / 2, ly, ly + listH, thumbY, thumbH);
        }

        renderFooter(ctx);

        if (hoverTooltip != null) {
            ctx.drawTooltip(this.textRenderer, hoverTooltip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0) {
            if (overScrollbar(click.x(), click.y())) {
                beginScrollDrag(click.y());
                return true;
            }
        }
        return super.mouseClicked(click, doubleClick);
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
        if (Math.max(0, filtered.size() - ROWS_VISIBLE) <= 0) return false;
        int sbX = listX() + listW() + SCROLLBAR_GAP;
        int sbY = listY();
        int sbH = ROWS_VISIBLE * ROW_H;
        return mx >= sbX && mx < sbX + SCROLLBAR_W && my >= sbY && my < sbY + sbH;
    }

    /** Start a scrollbar drag, recording where within the thumb the cursor
     *  grabbed (so the thumb tracks the cursor 1:1). A click on the track
     *  outside the thumb jumps the thumb to centre on the cursor first. */
    private void beginScrollDrag(double my) {
        int count = filtered.size();
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

    /** Update scroll from the dragged thumb-top (cursor minus grab offset). */
    private void updateScrollDrag(double my) {
        int count = filtered.size();
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

    private void renderFooter(DrawContext ctx) {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelHeight()) / 2;
        if (levelRange != null) {
            String label = "Lv " + shownLevel;
            int cx = panelX + PADDING + 47 + LEVEL_LABEL_W / 2;
            ctx.drawText(this.textRenderer, Text.literal(label), cx - this.textRenderer.getWidth(label) / 2,
                    panelY + panelHeight() - 17, GlassTheme.ACCENT, false);
            return;
        }
        ctx.drawText(this.textRenderer, Text.literal("Esc returns to the table list"),
                panelX + PADDING, panelY + panelHeight() - FOOTER_H + 9, GlassTheme.textMuted(), false);
    }

    private List<Text> buildTooltip(LootSnapshotPayload.Entry e, String displayName) {
        List<Text> lines = new ArrayList<>();
        String nameCode = LootRarityVisual.has(e.rarity) ? LootRarityVisual.code(e.rarity) : "§f";
        lines.add(Text.literal(nameCode + displayName));
        lines.add(Text.literal("§7Drop chance: §f" + e.chanceText()));
        double luckPct = LootClient.luckPercent();
        if (table != null && table.showsLuck(luckPct)) {
            lines.add(Text.literal("§7With luck: §a"
                    + LootSnapshotPayload.Entry.pctText(table.adjustedChancePct(e, luckPct))
                    + " §8(" + String.format(Locale.US, "%.1f", luckPct) + "% luck)"));
        }
        if (!e.masked && e.amountText != null && !e.amountText.isEmpty()) {
            lines.add(Text.literal("§7Amount: §f" + e.amountText));
        }
        if (LootRarityVisual.has(e.rarity)) {
            lines.add(Text.literal("§7Rarity: " + LootRarityVisual.code(e.rarity) + LootRarityVisual.name(e.rarity)));
        }
        if (e.masked) {
            lines.add(Text.literal("§8Undiscovered. Be the first to drop this!"));
        }
        return lines;
    }

    private static ItemStack resolveIcon(String key) {
        return com.aleks.ancientsmod.client.IconResolver.resolve(key, Items.PAPER, 1);
    }

    @Override
    public void close() {
        // ESC / close → back to the table list rather than fully exiting.
        LootClient.openTableList();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
