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
 * Read-only drop list for a single loot table. Each row shows the item icon,
 * its rarity-coloured name, the drop chance, and (second line) the amount range
 * + rarity. Undiscovered in-scope entries render as masked "???" with rarity +
 * chance still visible — matching the server chest GUI.
 *
 * <p>ESC returns to {@link LootTablesScreen} (via {@link LootClient#openTableList()}).
 */
public final class LootTableDetailScreen extends Screen {

    private static final int PANEL_W = 380;
    private static final int TITLE_BAR_H = 22;
    private static final int SUBHEADER_H = 14;
    private static final int SEARCH_BAR_H = 26;
    private static final int ROW_H = 24;
    private static final int ROWS_VISIBLE = 10;
    private static final int FOOTER_H = 16;
    private static final int PADDING = 8;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_GAP = 4;
    private static final int ICON = 16;

    private LootSnapshotPayload snapshot;
    private final String tableId;
    private LootSnapshotPayload.Table table;

    private final List<LootSnapshotPayload.Entry> filtered = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean draggingScroll = false;
    private double dragGrab = 0;
    private TextFieldWidget searchField;
    private String searchQuery = "";
    private List<Text> hoverTooltip = null;

    public LootTableDetailScreen(LootSnapshotPayload snapshot, String tableId) {
        super(Text.literal("Loot: " + tableId));
        this.snapshot = snapshot;
        this.tableId = tableId;
        this.table = findTable(snapshot, tableId);
    }

    public void onSnapshotUpdated(LootSnapshotPayload payload) {
        this.snapshot = payload;
        LootSnapshotPayload.Table t = findTable(payload, tableId);
        if (t != null) this.table = t;
        recompute();
        clampScroll();
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
        this.searchField = new TextFieldWidget(this.textRenderer,
                panelX + PADDING, panelY + TITLE_BAR_H + SUBHEADER_H + 4, searchW, 18,
                Text.literal("Search this table…"));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(Text.literal("§7Search this table…"));
        this.searchField.setText(searchQuery);
        this.searchField.setChangedListener(s -> {
            searchQuery = s == null ? "" : s;
            recompute();
            clampScroll();
        });
        this.addDrawableChild(this.searchField);
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

        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0101010);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF555555);
        ctx.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF555555);
        ctx.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF555555);
        ctx.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF555555);

        ctx.fill(panelX, panelY, panelX + panelW, panelY + TITLE_BAR_H, 0xFF1A1A1A);
        String name = table != null ? table.name : tableId;
        int dropCount = table != null ? table.entries.size() : 0;
        ctx.drawText(this.textRenderer, Text.literal("§e§l" + name + " §8· §7" + dropCount + " drops"),
                panelX + 10, panelY + 7, 0xFFFFFFFF, true);

        // Subheader: rolls + luck.
        String rolls = table != null ? table.rollsText() : "?";
        String luck = (table != null && table.luck) ? "§aLuck affects this table" : "§8Luck does not affect this table";
        ctx.drawText(this.textRenderer,
                Text.literal("§7Rolls/trigger: §f" + rolls + " §8· " + luck),
                panelX + 10, panelY + TITLE_BAR_H + 3, 0xFFAAAAAA, false);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        hoverTooltip = null;
        renderBackButton(ctx, mouseX, mouseY);

        int lx = listX();
        int ly = listY();
        int lw = listW();
        int listH = ROWS_VISIBLE * ROW_H;

        if (table == null) {
            ctx.drawText(this.textRenderer, Text.literal("§7This table is no longer available."),
                    lx, ly + 8, 0xFFAAAAAA, false);
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
            ctx.fill(lx, ry, lx + lw, ry + ROW_H - 2, hovered ? 0x33FFFFFF : 0xFF0E0E0E);

            int iconY = ry + (ROW_H - 2 - ICON) / 2;
            ItemStack icon = e.masked ? new ItemStack(Items.PAPER) : resolveIcon(e.iconKey);
            ctx.drawItem(icon, lx + 2, iconY);

            int rarityColor = LootRarityVisual.argb(e.rarity);
            String nameCode = e.masked
                    ? LootRarityVisual.code(e.rarity)
                    : (LootRarityVisual.has(e.rarity) ? LootRarityVisual.code(e.rarity) : "§f");
            String displayName = e.masked ? "???" : (e.name == null || e.name.isEmpty() ? "?" : e.name);

            int nameX = lx + ICON + 6;
            int chanceW = this.textRenderer.getWidth(e.chanceText());
            int nameMaxW = lw - (nameX - lx) - chanceW - 8;
            String trimmedName = this.textRenderer.trimToWidth(nameCode + displayName, Math.max(20, nameMaxW));
            ctx.drawText(this.textRenderer, Text.literal(trimmedName), nameX, ry + 3, 0xFFFFFFFF, false);

            // Right-aligned chance.
            ctx.drawText(this.textRenderer, Text.literal("§6" + e.chanceText()),
                    lx + lw - chanceW - 2, ry + 3, 0xFFFFFFFF, false);

            // Second line: amount + rarity (or "Undiscovered").
            String second;
            if (e.masked) {
                second = "§8Undiscovered — be the first to drop this!";
            } else {
                StringBuilder sb = new StringBuilder("§8");
                if (e.amountText != null && !e.amountText.isEmpty()) sb.append("x").append(e.amountText);
                if (LootRarityVisual.has(e.rarity)) {
                    if (sb.length() > 2) sb.append(" §8· ");
                    sb.append(LootRarityVisual.code(e.rarity)).append(LootRarityVisual.name(e.rarity));
                }
                second = sb.toString();
            }
            ctx.drawText(this.textRenderer, Text.literal(second), nameX, ry + 13, 0xFF888888, false);

            if (hovered) hoverTooltip = buildTooltip(e, displayName);
        }

        // Empty / no-match.
        if (filtered.isEmpty()) {
            String msg = searchQuery.trim().isEmpty()
                    ? "§7This table has no drops."
                    : "§7No drops match §f\"" + searchQuery + "\"";
            ctx.drawText(this.textRenderer, Text.literal(msg), lx + 4, ly + listH / 2 - 4, 0xFFAAAAAA, false);
        }

        // Scrollbar.
        int maxOffset = Math.max(0, filtered.size() - ROWS_VISIBLE);
        if (maxOffset > 0) {
            int sbX = lx + lw + SCROLLBAR_GAP;
            ctx.fill(sbX, ly, sbX + SCROLLBAR_W, ly + listH, 0x80000000);
            double ratio = (double) ROWS_VISIBLE / filtered.size();
            int thumbH = Math.max(20, (int) (listH * ratio));
            int range = listH - thumbH;
            int thumbY = ly + (int) (range * ((double) scrollOffset / maxOffset));
            ctx.fill(sbX, thumbY, sbX + SCROLLBAR_W, thumbY + thumbH, 0xFFAAAAAA);
        }

        renderFooter(ctx);

        if (hoverTooltip != null) {
            ctx.drawTooltip(this.textRenderer, hoverTooltip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0) {
            int[] b = backButtonBounds();
            if (click.x() >= b[0] && click.x() < b[0] + b[2]
                    && click.y() >= b[1] && click.y() < b[1] + b[3]) {
                LootClient.openTableList();
                return true;
            }
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

    private int[] backButtonBounds() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelHeight()) / 2;
        int w = 48;
        int h = 14;
        return new int[]{ panelX + PANEL_W - w - 8, panelY + 4, w, h };
    }

    private void renderBackButton(DrawContext ctx, int mouseX, int mouseY) {
        int[] b = backButtonBounds();
        boolean hov = mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
        ctx.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], hov ? 0xFF3A3A3A : 0xFF262626);
        ctx.fill(b[0], b[1], b[0] + b[2], b[1] + 1, 0xFF777777);
        ctx.drawText(this.textRenderer, Text.literal("§f‹ Back"), b[0] + 6, b[1] + 3, 0xFFFFFFFF, false);
    }

    private void renderFooter(DrawContext ctx) {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelHeight()) / 2;
        ctx.drawText(this.textRenderer, Text.literal("§8ESC → back to tables"),
                panelX + 10, panelY + panelHeight() - 12, 0xFF777777, false);
    }

    private List<Text> buildTooltip(LootSnapshotPayload.Entry e, String displayName) {
        List<Text> lines = new ArrayList<>();
        String nameCode = LootRarityVisual.has(e.rarity) ? LootRarityVisual.code(e.rarity) : "§f";
        lines.add(Text.literal(nameCode + displayName));
        lines.add(Text.literal("§7Drop chance: §f" + e.chanceText()));
        if (!e.masked && e.amountText != null && !e.amountText.isEmpty()) {
            lines.add(Text.literal("§7Amount: §f" + e.amountText));
        }
        if (LootRarityVisual.has(e.rarity)) {
            lines.add(Text.literal("§7Rarity: " + LootRarityVisual.code(e.rarity) + LootRarityVisual.name(e.rarity)));
        }
        if (e.masked) {
            lines.add(Text.literal("§8Undiscovered — be the first to drop this!"));
        }
        return lines;
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
        // ESC / close → back to the table list rather than fully exiting.
        LootClient.openTableList();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
