package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.pv.PvClient;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod-side overview of every accessible PV with item previews. Opens when the
 * player runs {@code /pv} (no args) and the server replies with a
 * {@link PvBundlePayload}. Clicking a card sends {@code /pv N} to the server
 * and the vanilla chest GUI opens normally.
 *
 * <p>Cards are arranged in a 4-wide grid. When the player has more rows of
 * cards than fit on screen, a scrollbar appears on the right and the
 * mouse-wheel scrolls the viewport.
 */
public final class PvOverviewScreen extends Screen {

    private static final int CARD_W = 144;
    private static final int CARD_H = 124;
    private static final int CARD_GAP = 8;
    private static final int CARDS_PER_ROW = 4;

    private static final int SLOT_PX = 14;
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 6;

    private static final int TITLE_BAR_H = 24;
    private static final int FOOTER_H = 28;

    private static final int PANEL_PADDING = 8;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_GAP = 4;

    /** Minimum + maximum visible card-rows. The viewport sizes between these
     *  based on the window height. */
    private static final int MIN_VIEW_ROWS = 2;
    private static final int MAX_VIEW_ROWS = 4;

    private PvBundlePayload bundle;

    private int hoveredVault = 0;
    private Text hoverTooltip = null;

    /** Vertical scroll in pixels. 0 = top. */
    private double scrollY = 0;

    public PvOverviewScreen(PvBundlePayload bundle) {
        super(Text.literal("Personal Vaults"));
        this.bundle = bundle;
    }

    public void onBundleUpdated(PvBundlePayload payload) {
        this.bundle = payload;
        scrollY = Math.max(0, Math.min(scrollY, maxScroll()));
    }

    @Override
    protected void init() {
        int panelW = panelWidth();
        int panelH = panelHeight();
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;
        int btnW = 90;
        int btnY = panelY + panelH - FOOTER_H + 4;
        ButtonWidget sortBtn = ButtonWidget.builder(Text.literal("Sort"), b -> NetworkHandler.sendPvSortRequest())
                .dimensions(panelX + panelW - btnW - 10, btnY, btnW, 20)
                .build();
        this.addDrawableChild(sortBtn);
    }

    /** How many cards to render: every accessible vault, plus the next locked
     *  one as a "next up" preview (so the player can see what they're working
     *  toward). When every vault is accessible, all are shown. */
    private int displayCount() {
        if (bundle == null || bundle.vaults.isEmpty()) return 0;
        int lastAccessible = -1;
        for (int i = 0; i < bundle.vaults.size(); i++) {
            if (bundle.vaults.get(i).isAccessible()) lastAccessible = i;
        }
        if (lastAccessible < 0) return Math.min(1, bundle.vaults.size());
        int withPreview = lastAccessible + 2; // include one locked tease
        return Math.min(withPreview, bundle.vaults.size());
    }

    private int displayedRows() {
        int n = displayCount();
        return Math.max(1, (n + CARDS_PER_ROW - 1) / CARDS_PER_ROW);
    }

    private int rowStep() { return CARD_H + CARD_GAP; }

    private int contentHeight() {
        int rows = displayedRows();
        return rows * CARD_H + Math.max(0, rows - 1) * CARD_GAP;
    }

    private int viewRowsForWindow() {
        int avail = this.height - TITLE_BAR_H - FOOTER_H - PANEL_PADDING * 2 - 40;
        int fits = Math.max(MIN_VIEW_ROWS, (avail + CARD_GAP) / rowStep());
        return Math.min(MAX_VIEW_ROWS, fits);
    }

    private int viewportHeight() {
        int rows = Math.min(displayedRows(), viewRowsForWindow());
        return rows * CARD_H + Math.max(0, rows - 1) * CARD_GAP;
    }

    private int gridContentWidth() {
        return CARDS_PER_ROW * CARD_W + (CARDS_PER_ROW - 1) * CARD_GAP;
    }

    private int panelWidth() {
        return gridContentWidth() + PANEL_PADDING * 2 + SCROLLBAR_W + SCROLLBAR_GAP;
    }

    private int panelHeight() {
        return TITLE_BAR_H + PANEL_PADDING + viewportHeight() + PANEL_PADDING + FOOTER_H;
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - viewportHeight());
    }

    private int viewportX() {
        int panelX = (this.width - panelWidth()) / 2;
        return panelX + PANEL_PADDING;
    }

    private int viewportY() {
        int panelY = (this.height - panelHeight()) / 2;
        return panelY + TITLE_BAR_H + PANEL_PADDING;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        int button = click.button();
        if (button != 0 && button != 1) {
            return super.mouseClicked(click, doubleClick);
        }
        double mouseX = click.x();
        double mouseY = click.y();

        int vpX = viewportX();
        int vpY = viewportY();
        int vpW = gridContentWidth();
        int vpH = viewportHeight();
        if (mouseX < vpX || mouseX >= vpX + vpW || mouseY < vpY || mouseY >= vpY + vpH) {
            return super.mouseClicked(click, doubleClick);
        }

        int limit = displayCount();
        for (int idx = 0; idx < limit; idx++) {
            PvBundlePayload.Vault vault = bundle.vaults.get(idx);
            int col = idx % CARDS_PER_ROW;
            int row = idx / CARDS_PER_ROW;
            int cx = vpX + col * (CARD_W + CARD_GAP);
            int cy = vpY + row * (CARD_H + CARD_GAP) - (int) scrollY;
            if (cy + CARD_H <= vpY || cy >= vpY + vpH) continue;
            if (!vault.isAccessible()) continue;
            if (mouseX < cx || mouseX >= cx + CARD_W) continue;
            if (mouseY < cy || mouseY >= cy + CARD_H) continue;
            if (button == 1) {
                PvClient.openAffinityPicker(vault.vaultNumber);
            } else {
                PvClient.openVault(vault.vaultNumber);
            }
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maxScroll() <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollY -= verticalAmount * rowStep();
        scrollY = Math.max(0, Math.min(scrollY, maxScroll()));
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        hoverTooltip = null;
        hoveredVault = 0;

        int vpX = viewportX();
        int vpY = viewportY();
        int vpW = gridContentWidth();
        int vpH = viewportHeight();

        ctx.enableScissor(vpX, vpY, vpX + vpW, vpY + vpH);
        try {
            int limit = displayCount();
            for (int idx = 0; idx < limit; idx++) {
                PvBundlePayload.Vault vault = bundle.vaults.get(idx);
                int col = idx % CARDS_PER_ROW;
                int row = idx / CARDS_PER_ROW;
                int cx = vpX + col * (CARD_W + CARD_GAP);
                int cy = vpY + row * (CARD_H + CARD_GAP) - (int) scrollY;
                if (cy + CARD_H <= vpY || cy >= vpY + vpH) continue;
                boolean hover = vault.isAccessible()
                        && mouseX >= cx && mouseX < cx + CARD_W
                        && mouseY >= cy && mouseY < cy + CARD_H
                        && mouseY >= vpY && mouseY < vpY + vpH;
                if (hover) hoveredVault = vault.vaultNumber;
                renderCard(ctx, vault, cx, cy, hover, mouseX, mouseY);
            }
        } finally {
            ctx.disableScissor();
        }

        if (maxScroll() > 0) {
            int sbX = vpX + vpW + SCROLLBAR_GAP;
            int sbY = vpY;
            int sbH = vpH;
            ctx.fill(sbX, sbY, sbX + SCROLLBAR_W, sbY + sbH, 0x80000000);
            double trackRatio = (double) vpH / contentHeight();
            int thumbH = Math.max(20, (int) (sbH * trackRatio));
            int range = sbH - thumbH;
            int thumbY = sbY + (int) (range * (scrollY / maxScroll()));
            ctx.fill(sbX, thumbY, sbX + SCROLLBAR_W, thumbY + thumbH, 0xFF888888);
        }

        if (hoverTooltip != null) {
            ctx.drawTooltip(this.textRenderer, hoverTooltip, mouseX, mouseY);
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);

        int panelW = panelWidth();
        int panelH = panelHeight();
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0101010);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF555555);
        ctx.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF555555);
        ctx.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF555555);
        ctx.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF555555);

        ctx.fill(panelX, panelY, panelX + panelW, panelY + TITLE_BAR_H, 0xFF1A1A1A);
        ctx.drawText(this.textRenderer, Text.literal("§ePersonal Vaults"),
                panelX + 10, panelY + 8, 0xFFFFFFFF, true);
        String hint = "§7Left-click §8open  §7Right-click §8affinities  §7ESC §8close";
        int hintW = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint),
                panelX + panelW - hintW - 10, panelY + 8, 0xFFAAAAAA, false);
    }

    private void renderCard(DrawContext ctx, PvBundlePayload.Vault vault, int x, int y,
                            boolean hover, int mouseX, int mouseY) {
        int bg = vault.isAccessible() ? (hover ? 0xFF2A2A2A : 0xFF1E1E1E) : 0xFF181010;
        int border = vault.isAccessible() ? (hover ? 0xFFFFCC33 : 0xFF444444) : 0xFF552222;

        ctx.fill(x, y, x + CARD_W, y + CARD_H, bg);
        ctx.fill(x, y, x + CARD_W, y + 1, border);
        ctx.fill(x, y + CARD_H - 1, x + CARD_W, y + CARD_H, border);
        ctx.fill(x, y, x + 1, y + CARD_H, border);
        ctx.fill(x + CARD_W - 1, y, x + CARD_W, y + CARD_H, border);

        if (!vault.isAccessible()) {
            ctx.drawText(this.textRenderer, Text.literal("§cPV " + vault.vaultNumber),
                    x + 6, y + 6, 0xFFFF6666, false);
            ctx.drawText(this.textRenderer, Text.literal("§8Locked"),
                    x + 6, y + 18, 0xFF888888, false);
            ctx.drawText(this.textRenderer, Text.literal("§7Use a PV"),
                    x + 6, y + 36, 0xFF999999, false);
            ctx.drawText(this.textRenderer, Text.literal("§7Expansion to"),
                    x + 6, y + 46, 0xFF999999, false);
            ctx.drawText(this.textRenderer, Text.literal("§7unlock."),
                    x + 6, y + 56, 0xFF999999, false);
            return;
        }

        int totalSlots = vault.slotCount;
        int usedSlots = vault.slots.size();
        ctx.drawText(this.textRenderer, Text.literal("§ePV " + vault.vaultNumber),
                x + 6, y + 5, 0xFFFFCC33, false);
        String counts = "§7" + usedSlots + "§8/§7" + totalSlots;
        int countsW = this.textRenderer.getWidth(counts);
        ctx.drawText(this.textRenderer, Text.literal(counts),
                x + CARD_W - countsW - 6, y + 5, 0xFFAAAAAA, false);

        int gridStartX = x + (CARD_W - GRID_COLS * SLOT_PX) / 2;
        int gridStartY = y + 18;

        for (int gx = 0; gx < GRID_COLS; gx++) {
            for (int gy = 0; gy < GRID_ROWS; gy++) {
                int sx = gridStartX + gx * SLOT_PX;
                int sy = gridStartY + gy * SLOT_PX;
                ctx.fill(sx, sy, sx + SLOT_PX - 1, sy + SLOT_PX - 1, 0xFF0E0E0E);
            }
        }

        for (PvBundlePayload.Slot slot : vault.slots) {
            int displayIndex = slot.slotIndex;
            int gridSlot = displayIndex;
            if (gridSlot >= GRID_COLS * GRID_ROWS) continue;
            int gx = gridSlot % GRID_COLS;
            int gy = gridSlot / GRID_COLS;
            int sx = gridStartX + gx * SLOT_PX;
            int sy = gridStartY + gy * SLOT_PX;

            ItemStack stack = resolveStack(slot);
            if (stack.isEmpty()) continue;

            ctx.drawItem(stack, sx - 1, sy - 1);
            if (slot.amount > 1) {
                ctx.drawStackOverlay(this.textRenderer, stack, sx - 1, sy - 1);
            }

            if (mouseX >= sx && mouseX < sx + SLOT_PX && mouseY >= sy && mouseY < sy + SLOT_PX) {
                Text label;
                if (slot.displayName != null && !slot.displayName.isEmpty()) {
                    label = Text.literal(slot.displayName + " §7x" + slot.amount);
                } else {
                    label = Text.literal(stack.getName().getString() + " §7x" + slot.amount);
                }
                hoverTooltip = label;
            }
        }

        int affY = y + CARD_H - 14;
        int maxAffW = CARD_W - 12;
        String affText = formatAffinity(vault.affinityCsv);
        if (affText.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal("§8No affinities · §oRMB to edit"),
                    x + 6, affY, 0xFF777777, false);
        } else {
            String trimmed = trimAffinityToWidth(affText, maxAffW);
            ctx.drawText(this.textRenderer, Text.literal("§b" + trimmed),
                    x + 6, affY, 0xFF88EEFF, false);
        }
    }

    private String trimAffinityToWidth(String text, int maxWidth) {
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        String ellipsis = "…";
        int ellipsisW = this.textRenderer.getWidth(ellipsis);
        String trimmed = this.textRenderer.trimToWidth(text, Math.max(0, maxWidth - ellipsisW));
        return trimmed + ellipsis;
    }

    private ItemStack resolveStack(PvBundlePayload.Slot slot) {
        if (slot.materialKey == null || slot.materialKey.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Identifier id = Identifier.tryParse(slot.materialKey);
        if (id == null) return new ItemStack(Items.BARRIER, slot.amount);
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) return new ItemStack(Items.BARRIER, slot.amount);
        return new ItemStack(item, Math.max(1, Math.min(slot.amount, 99)));
    }

    private String formatAffinity(String csv) {
        if (csv == null || csv.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            parts.add(prettifyKey(trimmed));
        }
        return String.join(", ", parts);
    }

    private static String prettifyKey(String storageKey) {
        StringBuilder out = new StringBuilder(storageKey.length());
        boolean upperNext = true;
        for (int i = 0; i < storageKey.length(); i++) {
            char c = storageKey.charAt(i);
            if (c == '_') {
                out.append(' ');
                upperNext = true;
            } else if (upperNext) {
                out.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        PvClient.onScreenClosed();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
