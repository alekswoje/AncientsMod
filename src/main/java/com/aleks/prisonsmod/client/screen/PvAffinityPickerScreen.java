package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.pv.PvCategory;
import com.aleks.prisonsmod.client.pv.PvClient;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.EnumMap;
import java.util.Map;

/**
 * Client-side affinity picker. Replaces the server's chest GUI for editing
 * which categories route to a particular vault.
 *
 * <p>Grid of 4×4 cells, one per {@link PvCategory}. Each cell colors its
 * border by bind state:
 * <ul>
 *   <li>Green — bound to this vault</li>
 *   <li>Gold — bound to a different vault (cell shows "On PV N", click moves)</li>
 *   <li>Gray — unbound</li>
 * </ul>
 *
 * <p>Click toggles the binding (server enforces exclusivity). Server replies
 * with a fresh {@link PvBundlePayload} which {@link PvClient#onBundle}
 * routes back to this screen via {@link #onBundleUpdated} so the cells
 * re-render in place.
 */
public final class PvAffinityPickerScreen extends Screen {

    private static final int PANEL_W = 380;
    private static final int PANEL_H = 230;
    private static final int TITLE_BAR_H = 24;
    private static final int FOOTER_H = 28;
    private static final int CELL_W = 84;
    private static final int CELL_H = 36;
    private static final int CELL_GAP = 4;
    private static final int COLS = 4;
    private static final int ROWS = 4;

    private final int vaultNumber;
    private PvBundlePayload bundle;
    private ButtonWidget clearButton;
    private ButtonWidget presetsButton;
    private ButtonWidget backButton;

    public PvAffinityPickerScreen(int vaultNumber, PvBundlePayload bundle) {
        super(Text.literal("PV " + vaultNumber + " Affinities"));
        this.vaultNumber = vaultNumber;
        this.bundle = bundle;
    }

    /** Called by {@link PvClient#onBundle} when the server pushes an updated
     *  bundle after a toggle/clear. Replaces our snapshot in place. */
    public void onBundleUpdated(PvBundlePayload payload) {
        this.bundle = payload;
    }

    public int vaultNumber() { return vaultNumber; }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int btnW = 110;
        int btnY = panelY + PANEL_H - FOOTER_H + 4;

        clearButton = ButtonWidget.builder(Text.literal("Clear All"),
                        b -> NetworkHandler.sendPvAffinityClear(vaultNumber))
                .dimensions(panelX + 12, btnY, btnW, 20)
                .build();
        presetsButton = ButtonWidget.builder(Text.literal("Presets"),
                        b -> PvClient.openPresetsFromPicker(vaultNumber))
                .dimensions(panelX + (PANEL_W - btnW) / 2, btnY, btnW, 20)
                .build();
        backButton = ButtonWidget.builder(Text.literal("Back to /pv"), b -> PvClient.openOverviewFromPicker())
                .dimensions(panelX + PANEL_W - btnW - 12, btnY, btnW, 20)
                .build();
        this.addDrawableChild(clearButton);
        this.addDrawableChild(presetsButton);
        this.addDrawableChild(backButton);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        // Check the cell grid BEFORE delegating to super — otherwise the
        // parent dispatcher may consume the click (focus-related quirks) and
        // we never reach the toggle.
        if (click.button() == 0) {
            int gridX = (this.width - gridWidth()) / 2;
            int gridY = (this.height - PANEL_H) / 2 + TITLE_BAR_H + 6;
            double mx = click.x();
            double my = click.y();
            PvCategory[] all = PvCategory.values();
            for (int i = 0; i < all.length; i++) {
                int col = i % COLS;
                int row = i / COLS;
                int cx = gridX + col * (CELL_W + CELL_GAP);
                int cy = gridY + row * (CELL_H + CELL_GAP);
                if (mx < cx || mx >= cx + CELL_W) continue;
                if (my < cy || my >= cy + CELL_H) continue;
                com.aleks.prisonsmod.PrisonsMod.LOGGER.info(
                        "[PvPicker] toggle vault={} key={}", vaultNumber, all[i].storageKey());
                NetworkHandler.sendPvAffinityToggle(vaultNumber, all[i].storageKey());
                return true;
            }
        }
        // Footer buttons (Clear All, Back) and any future widgets.
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;

        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xF0101010);
        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, 0xFF555555);
        ctx.fill(panelX, panelY + PANEL_H - 1, panelX + PANEL_W, panelY + PANEL_H, 0xFF555555);
        ctx.fill(panelX, panelY, panelX + 1, panelY + PANEL_H, 0xFF555555);
        ctx.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFF555555);

        ctx.fill(panelX, panelY, panelX + PANEL_W, panelY + TITLE_BAR_H, 0xFF1A1A1A);
        ctx.drawText(this.textRenderer, Text.literal("§ePV " + vaultNumber + " Affinities"),
                panelX + 10, panelY + 8, 0xFFFFFFFF, true);
        String hint = "§7Click a category to bind/move/unbind";
        int hintW = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint),
                panelX + PANEL_W - hintW - 10, panelY + 8, 0xFFAAAAAA, false);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        Map<PvCategory, Integer> owner = ownerByCategory();
        int gridX = (this.width - gridWidth()) / 2;
        int gridY = (this.height - PANEL_H) / 2 + TITLE_BAR_H + 6;

        PvCategory[] all = PvCategory.values();
        for (int i = 0; i < all.length; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int cx = gridX + col * (CELL_W + CELL_GAP);
            int cy = gridY + row * (CELL_H + CELL_GAP);
            boolean hover = mouseX >= cx && mouseX < cx + CELL_W
                    && mouseY >= cy && mouseY < cy + CELL_H;
            renderCell(ctx, all[i], cx, cy, hover, owner);
        }
    }

    private void renderCell(DrawContext ctx, PvCategory cat, int x, int y, boolean hover,
                            Map<PvCategory, Integer> owner) {
        int boundVault = owner.getOrDefault(cat, 0);
        boolean here = boundVault == vaultNumber;
        boolean elsewhere = boundVault > 0 && boundVault != vaultNumber;

        int bg;
        int border;
        if (here) {
            bg = hover ? 0xFF1E3A1E : 0xFF152915;
            border = hover ? 0xFF55FF55 : 0xFF338833;
        } else if (elsewhere) {
            bg = hover ? 0xFF3A2E15 : 0xFF2A2210;
            border = hover ? 0xFFFFCC33 : 0xFF886633;
        } else {
            bg = hover ? 0xFF2A2A2A : 0xFF1E1E1E;
            border = hover ? 0xFFCCCCCC : 0xFF444444;
        }
        ctx.fill(x, y, x + CELL_W, y + CELL_H, bg);
        ctx.fill(x, y, x + CELL_W, y + 1, border);
        ctx.fill(x, y + CELL_H - 1, x + CELL_W, y + CELL_H, border);
        ctx.fill(x, y, x + 1, y + CELL_H, border);
        ctx.fill(x + CELL_W - 1, y, x + CELL_W, y + CELL_H, border);

        // Icon — vertically centered on the left.
        ItemStack icon = new ItemStack(cat.icon());
        int iconX = x + 4;
        int iconY = y + (CELL_H - 16) / 2;
        ctx.drawItem(icon, iconX, iconY);

        // Name + status text on the right.
        int textX = x + 24;
        String name = (here ? "§a" : (elsewhere ? "§6" : "§f")) + cat.displayName();
        // Trim name to fit in the cell, leaving space for status badge.
        int maxNameW = CELL_W - 24 - 4;
        name = trimWithEllipsis(name, maxNameW);
        ctx.drawText(this.textRenderer, Text.literal(name),
                textX, y + 6, 0xFFFFFFFF, false);

        String status;
        int statusColor;
        if (here) {
            status = "§a✔ bound";
            statusColor = 0xFFAAFFAA;
        } else if (elsewhere) {
            status = "§eon PV " + boundVault;
            statusColor = 0xFFFFCC55;
        } else {
            status = "§8unbound";
            statusColor = 0xFF888888;
        }
        ctx.drawText(this.textRenderer, Text.literal(status),
                textX, y + 18, statusColor, false);
    }

    private Map<PvCategory, Integer> ownerByCategory() {
        EnumMap<PvCategory, Integer> map = new EnumMap<>(PvCategory.class);
        if (bundle == null) return map;
        for (PvBundlePayload.Vault v : bundle.vaults) {
            if (v.affinityCsv == null || v.affinityCsv.isEmpty()) continue;
            for (String token : v.affinityCsv.split(",")) {
                PvCategory cat = PvCategory.fromStorageKey(token.trim());
                if (cat == null) continue;
                map.putIfAbsent(cat, v.vaultNumber);
            }
        }
        return map;
    }

    private String trimWithEllipsis(String text, int maxWidth) {
        if (this.textRenderer.getWidth(text) <= maxWidth) return text;
        // Strip color codes for measuring purposes, then trim. Color is reapplied
        // since the prefix is preserved.
        String colorPrefix = "";
        if (text.length() >= 2 && text.charAt(0) == '§') {
            colorPrefix = text.substring(0, 2);
            text = text.substring(2);
        }
        String trimmed = this.textRenderer.trimToWidth(text, maxWidth - this.textRenderer.getWidth("…"));
        return colorPrefix + trimmed + "…";
    }

    private int gridWidth() {
        return COLS * CELL_W + (COLS - 1) * CELL_GAP;
    }

    @Override
    public void close() {
        PvClient.onPickerClosed();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
