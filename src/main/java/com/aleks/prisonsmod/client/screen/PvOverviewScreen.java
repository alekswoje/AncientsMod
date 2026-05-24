package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.pv.PvClient;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
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
 * Mod-side overview of all 7 PVs with item previews. Opens when the player
 * runs {@code /pv} (no args) and the server replies with a
 * {@link PvBundlePayload}. Clicking a card sends {@code /pv N} to the server
 * and the vanilla chest GUI opens normally.
 *
 * <p>Items are rendered as vanilla icons resolved from the server-sent
 * material id. Custom display names and PDC are not transferred — they show
 * up in tooltip form (display name) but the icon is the plain Material.
 */
public final class PvOverviewScreen extends Screen {

    private static final int CARD_W = 144;
    private static final int CARD_H = 124;
    private static final int CARD_GAP = 8;
    private static final int CARDS_PER_ROW = 4;
    private static final int CARD_ROWS = 2;

    private static final int SLOT_PX = 14;
    private static final int GRID_COLS = 9;
    private static final int GRID_ROWS = 6;

    private static final int TITLE_BAR_H = 24;
    private static final int FOOTER_H = 18;

    private final PvBundlePayload bundle;

    /** Hovered vault number (1..7) or 0 if none. Used to highlight the card. */
    private int hoveredVault = 0;

    /** Hovered slot info — cached per frame so the tooltip survives the
     *  drawable child render pass. */
    private Text hoverTooltip = null;

    public PvOverviewScreen(PvBundlePayload bundle) {
        super(Text.literal("Personal Vaults"));
        this.bundle = bundle;
    }

    @Override
    protected void init() {
        int gridX = (this.width - gridWidth()) / 2;
        int gridY = (this.height - gridHeight()) / 2 + TITLE_BAR_H;

        // Invisible click targets per card so we can route clicks via the
        // standard widget pipeline (keyboard accessibility comes for free).
        for (int idx = 0; idx < bundle.vaults.size(); idx++) {
            PvBundlePayload.Vault vault = bundle.vaults.get(idx);
            int col = idx % CARDS_PER_ROW;
            int row = idx / CARDS_PER_ROW;
            if (row >= CARD_ROWS) break;
            int cx = gridX + col * (CARD_W + CARD_GAP);
            int cy = gridY + row * (CARD_H + CARD_GAP);

            if (!vault.isAccessible()) continue; // locked vault — not clickable

            int finalVaultNumber = vault.vaultNumber;
            ButtonWidget btn = ButtonWidget.builder(Text.empty(), b -> PvClient.openVault(finalVaultNumber))
                    .dimensions(cx, cy, CARD_W, CARD_H)
                    .build();
            // No tooltip — it covers the item grid. The gold hover border
            // already signals the card is clickable, and item-hover tooltips
            // (rendered by us in render()) wouldn't show through a button
            // tooltip anyway.
            btn.visible = true;
            this.addDrawableChild(btn);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        hoverTooltip = null;

        int gridX = (this.width - gridWidth()) / 2;
        int gridY = (this.height - gridHeight()) / 2 + TITLE_BAR_H;

        hoveredVault = 0;
        for (int idx = 0; idx < bundle.vaults.size(); idx++) {
            PvBundlePayload.Vault vault = bundle.vaults.get(idx);
            int col = idx % CARDS_PER_ROW;
            int row = idx / CARDS_PER_ROW;
            if (row >= CARD_ROWS) break;
            int cx = gridX + col * (CARD_W + CARD_GAP);
            int cy = gridY + row * (CARD_H + CARD_GAP);
            boolean hover = vault.isAccessible()
                    && mouseX >= cx && mouseX < cx + CARD_W
                    && mouseY >= cy && mouseY < cy + CARD_H;
            if (hover) hoveredVault = vault.vaultNumber;
            renderCard(ctx, vault, cx, cy, hover, mouseX, mouseY);
        }

        if (hoverTooltip != null) {
            ctx.drawTooltip(this.textRenderer, hoverTooltip, mouseX, mouseY);
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);

        int totalW = gridWidth();
        int totalH = gridHeight();
        int panelX = (this.width - totalW) / 2 - 8;
        int panelY = (this.height - totalH) / 2 - 8;
        int panelW = totalW + 16;
        int panelH = totalH + 16;

        // Panel
        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0101010);
        ctx.fill(panelX, panelY, panelX + panelW, panelY + 1, 0xFF555555);
        ctx.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, 0xFF555555);
        ctx.fill(panelX, panelY, panelX + 1, panelY + panelH, 0xFF555555);
        ctx.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, 0xFF555555);

        // Title bar
        ctx.fill(panelX, panelY, panelX + panelW, panelY + TITLE_BAR_H, 0xFF1A1A1A);
        ctx.drawText(this.textRenderer, Text.literal("§ePersonal Vaults"),
                panelX + 10, panelY + 8, 0xFFFFFFFF, true);
        String hint = "§8ESC to close · click a vault to open";
        int hintW = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint),
                panelX + panelW - hintW - 10, panelY + 8, 0xFFAAAAAA, false);
    }

    private void renderCard(DrawContext ctx, PvBundlePayload.Vault vault, int x, int y,
                            boolean hover, int mouseX, int mouseY) {
        int bg = vault.isAccessible() ? (hover ? 0xFF2A2A2A : 0xFF1E1E1E) : 0xFF181010;
        int border = vault.isAccessible() ? (hover ? 0xFFFFCC33 : 0xFF444444) : 0xFF552222;

        // Card background
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
            return;
        }

        // Title: "PV N — used/total"
        int totalSlots = vault.slotCount;
        int usedSlots = vault.slots.size();
        ctx.drawText(this.textRenderer, Text.literal("§ePV " + vault.vaultNumber),
                x + 6, y + 5, 0xFFFFCC33, false);
        String counts = "§7" + usedSlots + "§8/§7" + totalSlots;
        int countsW = this.textRenderer.getWidth(counts);
        ctx.drawText(this.textRenderer, Text.literal(counts),
                x + CARD_W - countsW - 6, y + 5, 0xFFAAAAAA, false);

        // Item grid
        int gridStartX = x + (CARD_W - GRID_COLS * SLOT_PX) / 2;
        int gridStartY = y + 18;

        // Slot background grid (dim cells so empty slots are visible)
        for (int gx = 0; gx < GRID_COLS; gx++) {
            for (int gy = 0; gy < GRID_ROWS; gy++) {
                int sx = gridStartX + gx * SLOT_PX;
                int sy = gridStartY + gy * SLOT_PX;
                ctx.fill(sx, sy, sx + SLOT_PX - 1, sy + SLOT_PX - 1, 0xFF0E0E0E);
            }
        }

        // Render items at their actual slot indices.
        for (PvBundlePayload.Slot slot : vault.slots) {
            int displayIndex = slot.slotIndex;
            int gridSlot = displayIndex; // first 54 slots map 1:1; beyond that we clamp.
            if (gridSlot >= GRID_COLS * GRID_ROWS) continue;
            int gx = gridSlot % GRID_COLS;
            int gy = gridSlot / GRID_COLS;
            int sx = gridStartX + gx * SLOT_PX;
            int sy = gridStartY + gy * SLOT_PX;

            ItemStack stack = resolveStack(slot);
            if (stack.isEmpty()) continue;

            // Vanilla items are rendered at 16x16 — scale down to SLOT_PX by
            // letting the matrix push push the items into a tighter grid.
            // DrawContext.drawItem renders at fixed 16x16, so we accept a 1px
            // overlap with the grid border to keep them readable.
            ctx.drawItem(stack, sx - 1, sy - 1);
            if (slot.amount > 1) {
                ctx.drawStackOverlay(this.textRenderer, stack, sx - 1, sy - 1);
            }

            // Hover tooltip — use the captured display name if present, else
            // the vanilla item name. Append amount.
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

        // Affinities row
        int affY = y + CARD_H - 14;
        String affText = formatAffinity(vault.affinityCsv);
        if (affText.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal("§8No affinities"),
                    x + 6, affY, 0xFF777777, false);
        } else {
            ctx.drawText(this.textRenderer, Text.literal("§b" + affText),
                    x + 6, affY, 0xFF88EEFF, false);
        }
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
        // enchant_dust → Enchant Dust. Keep it simple — server may add new
        // categories that the client doesn't know yet.
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

    private int gridWidth() {
        return CARDS_PER_ROW * CARD_W + (CARDS_PER_ROW - 1) * CARD_GAP;
    }

    private int gridHeight() {
        return CARD_ROWS * CARD_H + (CARD_ROWS - 1) * CARD_GAP + TITLE_BAR_H + FOOTER_H;
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
