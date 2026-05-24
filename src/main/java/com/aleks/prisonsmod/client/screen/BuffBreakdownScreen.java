package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.buffs.BuffSnapshotState;
import com.aleks.prisonsmod.client.buffs.BuffStacker;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.payload.BuffSnapshotPayload;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interactive /pickbuffs breakdown — shows every contributing buff layer in
 * the snapshot, lets the player toggle layers on/off to see how the final
 * multiplier changes (sandbox mode).
 *
 * <p>Layout: left rail of channel tabs (Mining XP, Energy, Money, …), main
 * panel showing the active channel's layers with checkboxes + header showing
 * current vs sandbox final, and a bottom row with Refresh + Done buttons.
 */
public final class BuffBreakdownScreen extends Screen {

    // Theme — matches BoosterHud's dark-gradient + accent-strip aesthetic.
    private static final int BG_TOP        = 0xE6111319;
    private static final int BG_BOT        = 0xE61A1D26;
    private static final int PANEL_BG_TOP  = 0xCC1F2330;
    private static final int PANEL_BG_BOT  = 0xCC15171F;
    private static final int BORDER        = 0x55FFFFFF;
    private static final int BORDER_INNER  = 0x11FFFFFF;
    private static final int HEADER_BG     = 0x22FFC857;
    private static final int HEADER_RULE   = 0x44FFFFFF;
    private static final int HEADER_TEXT   = 0xFFFFD68A;
    private static final int LABEL_COLOR   = 0xFFE6E8EE;
    private static final int DIM_COLOR     = 0xFF8A8F9A;
    private static final int VALUE_COLOR   = 0xFFFFFFFF;
    private static final int DELTA_UP      = 0xFF8AE08A;
    private static final int DELTA_DOWN    = 0xFFFF8A8A;
    private static final int ROW_ALT       = 0x10FFFFFF;
    private static final int ROW_HOVER     = 0x22FFFFFF;
    private static final int TAB_ACTIVE_BG = 0x33FFC857;
    private static final int TAB_TEXT      = 0xFFE6E8EE;

    private static final int PADDING = 8;
    private static final int TAB_W   = 180;
    private static final int TAB_H   = 22;
    private static final int ROW_H   = 14;
    private static final int CHECKBOX_W = 12;
    private static final int HEADER_H = 16;

    private final Screen parent;
    private BuffSnapshotPayload snapshot;
    /** Active channel id; defaults to the first present. */
    private byte activeChannel;
    /** When true the right panel shows the per-ore yields table instead of the active channel. */
    private boolean viewingOreYields;
    /** Index of the per-ore row currently expanded in the yields panel, or -1 if collapsed. */
    private int expandedOreIndex = -1;
    /** Per-channel sandbox toggle state. BitSet bit i = layer i enabled. */
    private final Map<Byte, BitSet> channelToggles = new HashMap<>();

    /** Scroll offset for the active-channel layer list. */
    private double scrollOffset;
    /** Refresh-button cooldown so users don't spam — server also rate-limits. */
    private long lastRefreshAtMs;

    private @Nullable ButtonWidget refreshBtn;
    private @Nullable ButtonWidget doneBtn;

    public BuffBreakdownScreen(Screen parent) {
        super(Text.literal("Pickaxe Buffs"));
        this.parent = parent;
        this.snapshot = BuffSnapshotState.latest();
        if (snapshot != null && !snapshot.channels.isEmpty()) {
            this.activeChannel = snapshot.channels.keySet().iterator().next();
        }
    }

    /** Called by NetworkHandler when a new snapshot arrives — refreshes if open. */
    public void onSnapshotUpdated() {
        this.snapshot = BuffSnapshotState.latest();
        if (snapshot != null && !snapshot.channels.containsKey(activeChannel) && !snapshot.channels.isEmpty()) {
            activeChannel = snapshot.channels.keySet().iterator().next();
        }
        // Rebuild widgets so the header values pick up the fresh numbers.
        if (this.client != null) {
            this.clearChildren();
            this.init();
        }
    }

    @Override
    protected void init() {
        // Done button — bottom right.
        doneBtn = ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(width - PADDING - 60, height - PADDING - 20, 60, 20)
                .build();
        addDrawableChild(doneBtn);

        // Refresh button — bottom left.
        refreshBtn = ButtonWidget.builder(Text.literal("Refresh"), b -> requestRefresh())
                .dimensions(PADDING, height - PADDING - 20, 80, 20)
                .build();
        addDrawableChild(refreshBtn);

        // Reset-toggles button — bottom center.
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset toggles"), b -> resetTogglesForChannel())
                .dimensions(width / 2 - 60, height - PADDING - 20, 120, 20)
                .build());
    }

    private void requestRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshAtMs < 1100L) return; // server enforces 1s; pad a touch.
        lastRefreshAtMs = now;
        NetworkHandler.sendBuffRefreshRequest();
    }

    private void resetTogglesForChannel() {
        if (snapshot == null) return;
        BuffSnapshotPayload.Channel ch = snapshot.channels.get(activeChannel);
        if (ch == null) return;
        BitSet bs = togglesFor(ch);
        bs.clear();
        for (int i = 0; i < ch.layers.size(); i++) {
            if (ch.layers.get(i).state == BuffSnapshotPayload.STATE_ACTIVE) bs.set(i);
        }
    }

    private BitSet togglesFor(BuffSnapshotPayload.Channel ch) {
        BitSet bs = channelToggles.get(ch.id);
        if (bs == null) {
            bs = new BitSet(ch.layers.size());
            // Default: ACTIVE layers checked, POTENTIAL unchecked.
            for (int i = 0; i < ch.layers.size(); i++) {
                if (ch.layers.get(i).state == BuffSnapshotPayload.STATE_ACTIVE) bs.set(i);
            }
            channelToggles.put(ch.id, bs);
        }
        return bs;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Full-screen dim background.
        ctx.fillGradient(0, 0, width, height, BG_TOP, BG_BOT);

        // Title.
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Pickaxe Buffs — interactive breakdown").formatted(net.minecraft.util.Formatting.GOLD),
                width / 2, PADDING, HEADER_TEXT);

        if (snapshot == null || snapshot.channels.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("No snapshot yet. Press Refresh."),
                    width / 2, height / 2, DIM_COLOR);
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        // Layout.
        int topY = PADDING + 24;
        int contentBottom = height - PADDING - 30;
        int leftRailX = PADDING;
        int leftRailY = topY;
        int leftRailW = TAB_W;
        int panelX = leftRailX + leftRailW + PADDING;
        int panelY = topY;
        int panelW = width - panelX - PADDING;
        int panelH = contentBottom - topY;

        renderLeftRail(ctx, leftRailX, leftRailY, leftRailW, contentBottom - leftRailY, mouseX, mouseY);
        if (viewingOreYields) {
            renderOreYieldsPanel(ctx, panelX, panelY, panelW, panelH, mouseX, mouseY);
        } else {
            renderPanel(ctx, panelX, panelY, panelW, panelH, mouseX, mouseY);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderLeftRail(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        ctx.fillGradient(x, y, x + w, y + h, PANEL_BG_TOP, PANEL_BG_BOT);
        drawBorder(ctx, x, y, x + w, y + h, BORDER);
        int rowY = y + 4;
        int leftPad = 8;
        int rightPad = 6;
        int gap = 6; // minimum gap between channel name and right-side value
        for (Map.Entry<Byte, BuffSnapshotPayload.Channel> e : snapshot.channels.entrySet()) {
            byte chId = e.getKey();
            BuffSnapshotPayload.Channel ch = e.getValue();
            boolean active = !viewingOreYields && chId == activeChannel;
            boolean hovered = mouseX >= x + 2 && mouseX <= x + w - 2 && mouseY >= rowY && mouseY <= rowY + TAB_H - 2;
            int bg = active ? TAB_ACTIVE_BG : (hovered ? ROW_HOVER : 0);
            if (bg != 0) ctx.fill(x + 2, rowY, x + w - 2, rowY + TAB_H - 2, bg);

            int textY = rowY + (TAB_H - 2 - textRenderer.fontHeight) / 2;

            // Right-side current final, dim. Drawn first so we can trim the label
            // to whatever space is left.
            String mini = formatValueForChannel(chId, ch.serverFinalValue);
            int miniW = textRenderer.getWidth(mini);
            int miniX = x + w - rightPad - miniW;
            ctx.drawText(textRenderer, Text.literal(mini), miniX, textY, DIM_COLOR, true);

            // Channel name, trimmed to whatever fits to the left of the value.
            int nameRoom = Math.max(0, miniX - gap - (x + leftPad));
            String name = BuffSnapshotPayload.channelDisplayName(chId);
            String fitted = textRenderer.trimToWidth(name, nameRoom);
            ctx.drawText(textRenderer, Text.literal(fitted), x + leftPad, textY,
                    active ? HEADER_TEXT : TAB_TEXT, true);

            rowY += TAB_H;
            if (rowY + TAB_H > y + h) break;
        }
        // Per-Ore Yields tab (only when the server sent yield data).
        if (snapshot.oreYields != null && !snapshot.oreYields.isEmpty() && rowY + TAB_H <= y + h) {
            // Visual divider above the synthetic tab so it's clearly separate.
            ctx.fill(x + 6, rowY, x + w - 6, rowY + 1, HEADER_RULE);
            rowY += 3;
            boolean active = viewingOreYields;
            boolean hovered = mouseX >= x + 2 && mouseX <= x + w - 2 && mouseY >= rowY && mouseY <= rowY + TAB_H - 2;
            int bg = active ? TAB_ACTIVE_BG : (hovered ? ROW_HOVER : 0);
            if (bg != 0) ctx.fill(x + 2, rowY, x + w - 2, rowY + TAB_H - 2, bg);
            int textY = rowY + (TAB_H - 2 - textRenderer.fontHeight) / 2;
            int oreTabRoom = Math.max(0, (x + w - rightPad) - (x + leftPad));
            String fitted = textRenderer.trimToWidth("Per-Ore Yields", oreTabRoom);
            ctx.drawText(textRenderer, Text.literal(fitted), x + leftPad, textY,
                    active ? HEADER_TEXT : TAB_TEXT, true);
        }
    }

    private void renderOreYieldsPanel(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        ctx.fillGradient(x, y, x + w, y + h, PANEL_BG_TOP, PANEL_BG_BOT);
        drawBorder(ctx, x, y, x + w, y + h, BORDER);

        // Header.
        ctx.fill(x + 1, y + 1, x + w - 1, y + 1 + HEADER_H, HEADER_BG);
        ctx.fill(x + 1, y + 1 + HEADER_H, x + w - 1, y + 1 + HEADER_H + 1, HEADER_RULE);
        ctx.drawText(textRenderer, Text.literal("Per-Ore Yields"), x + PADDING, y + 5, HEADER_TEXT, true);
        ctx.drawText(textRenderer, Text.literal("click any row for the math"),
                x + PADDING + textRenderer.getWidth("Per-Ore Yields") + 8, y + 5, DIM_COLOR, false);

        // Layout — four right-aligned columns of equal width to the right of
        // the ore name. Cells render "base → result" with the base dim and
        // the result bright so the math is visible.
        int bodyY = y + 1 + HEADER_H + 4;
        int bodyH = h - (1 + HEADER_H + 4) - 4;
        int rightPad = 6;
        int oreColW = 72;
        int dataW = w - PADDING - oreColW - rightPad;
        int colWidth = dataW / 4;
        int oreX = x + PADDING;
        int xpRight     = x + PADDING + oreColW + colWidth * 1 - 4;
        int energyRight = x + PADDING + oreColW + colWidth * 2 - 4;
        int moneyRight  = x + PADDING + oreColW + colWidth * 3 - 4;
        int shardRight  = x + PADDING + oreColW + colWidth * 4 - 4;

        // Column headers.
        ctx.drawText(textRenderer, Text.literal("Ore"), oreX, bodyY, DIM_COLOR, false);
        drawRightAligned(ctx, "XP",     xpRight,     bodyY, DIM_COLOR);
        drawRightAligned(ctx, "Energy", energyRight, bodyY, DIM_COLOR);
        drawRightAligned(ctx, "Money",  moneyRight,  bodyY, DIM_COLOR);
        drawRightAligned(ctx, "Shard%", shardRight,  bodyY, DIM_COLOR);
        ctx.fill(x + 2, bodyY + textRenderer.fontHeight + 2, x + w - 2, bodyY + textRenderer.fontHeight + 3, HEADER_RULE);

        int rowsStartY = bodyY + textRenderer.fontHeight + 5;
        int rowH = ROW_H;

        ctx.enableScissor(x + 2, rowsStartY, x + w - 2, bodyY + bodyH);
        int rowY = rowsStartY - (int) scrollOffset;
        int i = 0;
        for (BuffSnapshotPayload.OreYield y0 : snapshot.oreYields) {
            boolean expanded = (i == expandedOreIndex);
            boolean hovered = mouseY >= rowY && mouseY <= rowY + rowH
                    && mouseX >= x + 2 && mouseX <= x + w - 2;
            if (rowY + rowH >= rowsStartY && rowY < bodyY + bodyH) {
                if (hovered) ctx.fill(x + 2, rowY, x + w - 2, rowY + rowH, ROW_HOVER);
                else if (expanded) ctx.fill(x + 2, rowY, x + w - 2, rowY + rowH, TAB_ACTIVE_BG);
                else if ((i & 1) == 1) ctx.fill(x + 2, rowY, x + w - 2, rowY + rowH, ROW_ALT);
                int textY = rowY + (rowH - textRenderer.fontHeight) / 2;
                // Disclosure triangle in front of the ore name.
                String tri = expanded ? "▼ " : "▶ ";
                ctx.drawText(textRenderer, Text.literal(tri), oreX, textY, DIM_COLOR, false);
                int nameX = oreX + textRenderer.getWidth(tri);
                ctx.drawText(textRenderer, Text.literal(y0.displayName), nameX, textY, LABEL_COLOR, true);
                drawTransformCell(ctx,
                        formatYieldXp(y0.baseXp), formatYieldXp(y0.xpPerOre),
                        xpRight, textY);
                drawTransformCell(ctx,
                        formatYieldEnergy(y0.baseEnergy), formatYieldEnergy(y0.energyPerOre),
                        energyRight, textY);
                drawTransformCell(ctx,
                        formatYieldMoney(y0.baseMoney), formatYieldMoney(y0.moneyPerOre),
                        moneyRight, textY);
                drawTransformCell(ctx,
                        formatYieldShard(y0.baseShard), formatYieldShard(y0.shardChancePerOre),
                        shardRight, textY);
            }
            rowY += rowH;
            if (expanded) {
                rowY += renderOreBreakdownRows(ctx, x, rowY, w, bodyY, bodyH, y0);
            }
            i++;
        }
        ctx.disableScissor();
    }

    /**
     * Render the 4 expanded breakdown lines (one per yield channel) for an
     * ore. Each shows the base value, every active multiplier layer with its
     * label, and the resulting product. Returns the vertical pixels consumed.
     */
    private int renderOreBreakdownRows(DrawContext ctx, int x, int startY, int w,
                                        int bodyY, int bodyH, BuffSnapshotPayload.OreYield ore) {
        int rowH = 11;
        int lineY = startY;
        int indent = PADDING + 14;

        renderBreakdownLine(ctx, "XP",     ore.baseXp,     ore.xpPerOre,
                BuffSnapshotPayload.CH_MINING_XP,     x + indent, lineY, w - indent - PADDING);
        lineY += rowH;
        renderBreakdownLine(ctx, "Energy", ore.baseEnergy, ore.energyPerOre,
                BuffSnapshotPayload.CH_MINING_ENERGY, x + indent, lineY, w - indent - PADDING);
        lineY += rowH;
        renderBreakdownLine(ctx, "Money",  ore.baseMoney,  ore.moneyPerOre,
                BuffSnapshotPayload.CH_MINING_MONEY,  x + indent, lineY, w - indent - PADDING);
        lineY += rowH;
        renderBreakdownLine(ctx, "Shard",  ore.baseShard,  ore.shardChancePerOre,
                BuffSnapshotPayload.CH_MINING_SHARDS, x + indent, lineY, w - indent - PADDING);
        lineY += rowH;

        // Subtle divider beneath the expanded block.
        ctx.fill(x + indent, lineY, x + w - PADDING, lineY + 1, HEADER_RULE);
        lineY += 3;
        return lineY - startY;
    }

    private void renderBreakdownLine(DrawContext ctx, String label, double base, double result,
                                      byte channelId, int x, int y, int maxWidth) {
        BuffSnapshotPayload.Channel ch = snapshot.channels.get(channelId);
        // Build the formula string: `base × layer1(±X%) × layer2(×Yx) ... = result`.
        StringBuilder sb = new StringBuilder();
        sb.append(formatNum(base));
        if (ch != null) {
            for (BuffSnapshotPayload.Layer layer : ch.layers) {
                if (layer.state != BuffSnapshotPayload.STATE_ACTIVE) continue;
                if (layer.kind == BuffSnapshotPayload.KIND_BASE) continue;
                String shortLabel = shortLayerLabel(layer.label);
                if (layer.kind == BuffSnapshotPayload.KIND_ADDITIVE) {
                    if (Math.abs(layer.value) < 0.0001) continue;
                    sb.append("  × ").append(formatSignedPct(layer.value)).append(' ').append(shortLabel);
                } else if (layer.kind == BuffSnapshotPayload.KIND_MULTIPLICATIVE) {
                    if (Math.abs(layer.value - 1.0) < 0.0001) continue;
                    sb.append("  × ").append(formatXShort(layer.value)).append(' ').append(shortLabel);
                } else if (layer.kind == BuffSnapshotPayload.KIND_FLAT_BONUS) {
                    if (Math.abs(layer.value) < 0.0001) continue;
                    sb.append("  + ").append(formatNum(layer.value)).append(' ').append(shortLabel);
                }
            }
        }
        sb.append("  =  ").append(formatNum(result));

        // Render label in header-text gold, body in dim grey. Trim the body
        // (not the label) when there isn't room.
        String labelText = label + ": ";
        int labelW = textRenderer.getWidth(labelText);
        ctx.drawText(textRenderer, Text.literal(labelText), x, y, HEADER_TEXT, false);
        String body = sb.toString();
        String fitted = textRenderer.trimToWidth(body, Math.max(0, maxWidth - labelW));
        ctx.drawText(textRenderer, Text.literal(fitted), x + labelW, y, DIM_COLOR, false);
    }

    private static String shortLayerLabel(String label) {
        // Trim parenthetical suffixes (e.g. " (at cap)") to keep the formula compact.
        int paren = label.indexOf(" (");
        if (paren > 0) label = label.substring(0, paren);
        return label;
    }

    private static String formatSignedPct(double v) {
        double pct = v * 100.0;
        if (Math.abs(pct) < 0.05) return "+0%";
        if (pct == Math.floor(pct)) return String.format(Locale.US, "%+.0f%%", pct);
        return String.format(Locale.US, "%+.1f%%", pct);
    }

    private static String formatXShort(double v) {
        if (v == Math.floor(v)) return String.format(Locale.US, "×%d", (int) v);
        if (v >= 10) return String.format(Locale.US, "×%.1f", v);
        return String.format(Locale.US, "×%.2f", v);
    }

    private static String formatNum(double v) {
        if (v <= 0) return "0";
        if (v >= 100_000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        if (v >= 10) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    /**
     * Walk the per-ore yield list (accounting for expanded rows that occupy
     * more vertical space) and return the index whose row body contains the
     * given {@code relY} (relative to the body top after scroll). Returns
     * -1 if no row was hit. Only the COLLAPSED row strip is clickable — the
     * expanded breakdown lines below it don't toggle.
     */
    private int oreRowAtRelY(int relY) {
        int rowH = ROW_H;
        int expandedExtraRowH = 11;
        int cursor = 0;
        for (int i = 0; i < snapshot.oreYields.size(); i++) {
            int rowTop = cursor;
            cursor += rowH;
            if (relY >= rowTop && relY < cursor) return i;
            if (i == expandedOreIndex) {
                cursor += expandedExtraRowH * 4 + 3; // 4 channel lines + divider gap
            }
        }
        return -1;
    }

    /**
     * Draws "{base} → {result}" right-aligned to {@code rightEdge}. Base + arrow
     * in DIM_COLOR, result in VALUE_COLOR. If the cell would overflow, drops the
     * base portion so the result is always readable.
     */
    private void drawTransformCell(DrawContext ctx, String base, String result, int rightEdge, int y) {
        String arrow = " → ";
        int resultW = textRenderer.getWidth(result);
        int arrowW = textRenderer.getWidth(arrow);
        int baseW = textRenderer.getWidth(base);
        // Result always drawn at rightEdge.
        ctx.drawText(textRenderer, Text.literal(result), rightEdge - resultW, y, VALUE_COLOR, true);
        // Arrow + base only if there's room; otherwise just result.
        int arrowX = rightEdge - resultW - arrowW;
        int baseX = arrowX - baseW;
        // Heuristic: don't show base if it would push past the cell start (4px buffer).
        // Caller's panel layout reserves ~colWidth per cell; we just check baseX is positive.
        if (baseX < 0) return;
        ctx.drawText(textRenderer, Text.literal(arrow), arrowX, y, DIM_COLOR, false);
        ctx.drawText(textRenderer, Text.literal(base), baseX, y, DIM_COLOR, false);
    }

    private void drawRightAligned(DrawContext ctx, String text, int rightEdge, int y, int color) {
        int w = textRenderer.getWidth(text);
        ctx.drawText(textRenderer, Text.literal(text), rightEdge - w, y, color, true);
    }

    private static String formatYieldXp(double v) {
        if (v >= 100_000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        if (v >= 10) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static String formatYieldEnergy(double v) {
        if (v >= 100_000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        if (v >= 10) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static String formatYieldMoney(double v) {
        if (v <= 0) return "—";
        if (v >= 1_000_000) return String.format(Locale.US, "$%.1fM", v / 1_000_000.0);
        if (v >= 10_000) return String.format(Locale.US, "$%.1fK", v / 1000.0);
        if (v >= 10) return String.format(Locale.US, "$%.0f", v);
        return String.format(Locale.US, "$%.2f", v);
    }

    private static String formatYieldShard(double v) {
        if (v <= 0) return "—";
        if (v >= 0.01) return String.format(Locale.US, "%.2f%%", v * 100.0);
        return String.format(Locale.US, "%.3f%%", v * 100.0);
    }

    private void renderPanel(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        ctx.fillGradient(x, y, x + w, y + h, PANEL_BG_TOP, PANEL_BG_BOT);
        drawBorder(ctx, x, y, x + w, y + h, BORDER);

        BuffSnapshotPayload.Channel ch = snapshot.channels.get(activeChannel);
        if (ch == null) return;

        // Header bar with channel name + current vs sandbox final.
        ctx.fill(x + 1, y + 1, x + w - 1, y + 1 + HEADER_H, HEADER_BG);
        ctx.fill(x + 1, y + 1 + HEADER_H, x + w - 1, y + 1 + HEADER_H + 1, HEADER_RULE);

        BitSet bs = togglesFor(ch);
        BuffStacker.Result sandbox = BuffStacker.compute(ch.layers, bs::get);

        String chName = BuffSnapshotPayload.channelDisplayName(ch.id);
        ctx.drawText(textRenderer, Text.literal(chName), x + PADDING, y + 5, HEADER_TEXT, true);

        // Right-aligned: current → sandbox, with delta colour.
        String current = formatValueForChannel(ch.id, ch.serverFinalValue);
        String sand = formatValueForChannel(ch.id, sandbox.finalValue);
        int deltaColor = Math.abs(sandbox.finalValue - ch.serverFinalValue) < 1e-6
                ? VALUE_COLOR
                : (sandbox.finalValue > ch.serverFinalValue ? DELTA_UP : DELTA_DOWN);
        String composite = current + "  →  " + sand;
        int compW = textRenderer.getWidth(composite);
        ctx.drawText(textRenderer, Text.literal(current), x + w - PADDING - compW, y + 5, DIM_COLOR, true);
        int arrowOffset = textRenderer.getWidth(current);
        ctx.drawText(textRenderer, Text.literal("  →  "), x + w - PADDING - compW + arrowOffset, y + 5, DIM_COLOR, true);
        int sandOffset = arrowOffset + textRenderer.getWidth("  →  ");
        ctx.drawText(textRenderer, Text.literal(sand), x + w - PADDING - compW + sandOffset, y + 5, deltaColor, true);

        // Body — scrollable layer list.
        int bodyY = y + 1 + HEADER_H + 4;
        int bodyH = h - (1 + HEADER_H + 4) - 4;

        // Compute visible window.
        int totalRows = ch.layers.size();
        int totalContentH = totalRows * ROW_H;
        int maxScroll = Math.max(0, totalContentH - bodyH);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        ctx.enableScissor(x + 2, bodyY, x + w - 2, bodyY + bodyH);
        int rowY = bodyY - (int) scrollOffset;
        for (int i = 0; i < ch.layers.size(); i++) {
            BuffSnapshotPayload.Layer layer = ch.layers.get(i);
            if (rowY + ROW_H >= bodyY && rowY < bodyY + bodyH) {
                renderRow(ctx, x + 4, rowY, w - 8, layer, i, bs.get(i), mouseX, mouseY);
            }
            rowY += ROW_H;
        }
        ctx.disableScissor();

        // Footer rule with a tiny composition hint.
        int footerY = y + h - 14;
        ctx.fill(x + 1, footerY, x + w - 1, footerY + 1, HEADER_RULE);
        String footer = String.format(Locale.US, "additive pool × %s × multipliers × %s",
                formatX(sandbox.additivePool), formatX(sandbox.multiplicativeProduct));
        ctx.drawText(textRenderer, Text.literal(footer), x + PADDING, footerY + 3, DIM_COLOR, false);
    }

    private void renderRow(DrawContext ctx, int x, int y, int w, BuffSnapshotPayload.Layer layer, int index,
                            boolean checked, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + ROW_H;
        if (hovered) ctx.fill(x, y, x + w, y + ROW_H, ROW_HOVER);
        else if ((index & 1) == 1) ctx.fill(x, y, x + w, y + ROW_H, ROW_ALT);

        // Accent strip — colored by category.
        int accent = BuffSnapshotPayload.categoryColor(layer.category);
        ctx.fill(x + 2, y + 2, x + 4, y + ROW_H - 2, accent);

        // Checkbox (only for non-BASE layers — base can't be toggled off).
        int checkboxX = x + 8;
        int checkboxY = y + (ROW_H - CHECKBOX_W) / 2;
        boolean toggleable = layer.kind != BuffSnapshotPayload.KIND_BASE;
        if (toggleable) {
            ctx.fill(checkboxX, checkboxY, checkboxX + CHECKBOX_W, checkboxY + CHECKBOX_W, 0x44000000);
            drawBorder(ctx, checkboxX, checkboxY, checkboxX + CHECKBOX_W, checkboxY + CHECKBOX_W, BORDER);
            if (checked) {
                ctx.fill(checkboxX + 2, checkboxY + 2, checkboxX + CHECKBOX_W - 2, checkboxY + CHECKBOX_W - 2, accent);
            }
        }

        // Label + detail.
        int labelX = checkboxX + CHECKBOX_W + 6;
        int textY = y + (ROW_H - textRenderer.fontHeight) / 2;
        int labelColor = layer.state == BuffSnapshotPayload.STATE_POTENTIAL ? DIM_COLOR : LABEL_COLOR;
        if (toggleable && !checked) labelColor = DIM_COLOR;
        ctx.drawText(textRenderer, Text.literal(layer.label), labelX, textY, labelColor, true);

        if (!layer.detail.isEmpty()) {
            int labelW = textRenderer.getWidth(layer.label);
            ctx.drawText(textRenderer, Text.literal("  " + layer.detail),
                    labelX + labelW, textY, DIM_COLOR, false);
        }

        // Right-aligned value with kind suffix.
        String valueStr = formatLayerValue(layer);
        int valW = textRenderer.getWidth(valueStr);
        int valColor = layer.state == BuffSnapshotPayload.STATE_POTENTIAL && !checked ? DIM_COLOR : VALUE_COLOR;
        ctx.drawText(textRenderer, Text.literal(valueStr), x + w - 6 - valW, textY, valColor, true);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;
        double mx = click.x();
        double my = click.y();
        int button = click.button();
        if (button != 0 || snapshot == null) return false;

        // Tab click — left rail.
        int topY = PADDING + 24;
        int contentBottom = height - PADDING - 30;
        if (mx >= PADDING && mx <= PADDING + TAB_W) {
            int rowY = topY + 4;
            for (Map.Entry<Byte, BuffSnapshotPayload.Channel> e : snapshot.channels.entrySet()) {
                if (my >= rowY && my <= rowY + TAB_H - 2) {
                    activeChannel = e.getKey();
                    viewingOreYields = false;
                    scrollOffset = 0;
                    return true;
                }
                rowY += TAB_H;
                if (rowY + TAB_H > contentBottom) break;
            }
            // Per-Ore Yields synthetic tab — appears after the divider.
            if (snapshot.oreYields != null && !snapshot.oreYields.isEmpty()) {
                rowY += 3; // matches the divider gap in renderLeftRail
                if (my >= rowY && my <= rowY + TAB_H - 2) {
                    viewingOreYields = true;
                    scrollOffset = 0;
                    return true;
                }
            }
        }

        // Yields panel — click an ore row to toggle its expanded math view.
        if (viewingOreYields) {
            int panelX = PADDING + TAB_W + PADDING;
            int panelY = topY;
            int panelW = width - panelX - PADDING;
            int bodyY = panelY + 1 + HEADER_H + 4 + textRenderer.fontHeight + 5;
            int bodyH = (contentBottom - panelY) - (1 + HEADER_H + 4 + textRenderer.fontHeight + 5) - 4;
            if (mx < panelX || mx > panelX + panelW) return false;
            if (my < bodyY || my > bodyY + bodyH) return false;
            int relY = (int) (my - bodyY + scrollOffset);
            int rowIdx = oreRowAtRelY(relY);
            if (rowIdx < 0) return false;
            expandedOreIndex = (expandedOreIndex == rowIdx) ? -1 : rowIdx;
            return true;
        }

        // Row toggle click — main panel.
        BuffSnapshotPayload.Channel ch = snapshot.channels.get(activeChannel);
        if (ch == null) return false;
        int panelX = PADDING + TAB_W + PADDING;
        int panelY = topY;
        int panelW = width - panelX - PADDING;
        int bodyY = panelY + 1 + HEADER_H + 4;
        int bodyH = (contentBottom - panelY) - (1 + HEADER_H + 4) - 4;
        if (mx < panelX || mx > panelX + panelW) return false;
        if (my < bodyY || my > bodyY + bodyH) return false;

        // Resolve which row was hit using current scroll offset.
        int relY = (int) (my - bodyY + scrollOffset);
        int rowIdx = relY / ROW_H;
        if (rowIdx < 0 || rowIdx >= ch.layers.size()) return false;
        BuffSnapshotPayload.Layer layer = ch.layers.get(rowIdx);
        if (layer.kind == BuffSnapshotPayload.KIND_BASE) return false;
        BitSet bs = togglesFor(ch);
        bs.flip(rowIdx);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontalAmount, double verticalAmount) {
        scrollOffset -= verticalAmount * ROW_H * 2;
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false; // matches SettingsScreen behaviour — keep the world running behind.
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String formatX(double v) {
        if (v == Math.floor(v) && Math.abs(v) < 1000) return String.format(Locale.US, "x%d", (int) v);
        if (Math.abs(v) >= 100) return String.format(Locale.US, "x%.0f", v);
        if (Math.abs(v) >= 10)  return String.format(Locale.US, "x%.1f", v);
        return String.format(Locale.US, "x%.2f", v);
    }

    /** Format a layer's value depending on its kind, with a sign for ADDITIVE. */
    private static String formatLayerValue(BuffSnapshotPayload.Layer layer) {
        switch (layer.kind) {
            case BuffSnapshotPayload.KIND_ADDITIVE -> {
                double pct = layer.value * 100.0;
                if (Math.abs(pct) < 0.005) return "+0%";
                return String.format(Locale.US, "%+.2f%%", pct);
            }
            case BuffSnapshotPayload.KIND_FLAT_BONUS -> {
                return String.format(Locale.US, "+%.1f", layer.value);
            }
            case BuffSnapshotPayload.KIND_BASE -> {
                if (layer.value == 0.0) return "—";
                if (layer.value == 1.0) return "1.00";
                return String.format(Locale.US, "%.2f", layer.value);
            }
            default -> {
                return formatX(layer.value);
            }
        }
    }

    /** Format the final value of a channel in its native unit. */
    private static String formatValueForChannel(byte chId, double v) {
        if (chId == BuffSnapshotPayload.CH_MINING_SHARDS) {
            return String.format(Locale.US, "%.2f%%", v * 100.0);
        }
        if (chId == BuffSnapshotPayload.CH_COMBAT_OUT || chId == BuffSnapshotPayload.CH_BOSS_OUT) {
            return String.format(Locale.US, "%.2f dmg", v);
        }
        if (chId == BuffSnapshotPayload.CH_COMBAT_IN || chId == BuffSnapshotPayload.CH_BOSS_IN) {
            // Lead with the reduction% so players see their total DR at a glance,
            // not the inverse "taken" fraction.
            double reductionPct = (1.0 - v) * 100.0;
            double takenPct = v * 100.0;
            return String.format(Locale.US, "-%.1f%% (%.1f%% taken)", reductionPct, takenPct);
        }
        return formatX(v);
    }

    /** Open the screen from an existing snapshot (or noop if none yet). */
    public static void openNow(@Nullable Screen parent) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.currentScreen instanceof BuffBreakdownScreen open) {
                open.onSnapshotUpdated();
                return;
            }
            mc.setScreen(new BuffBreakdownScreen(parent));
        });
    }

    private static void drawBorder(DrawContext ctx, int x, int y, int x2, int y2, int color) {
        ctx.fill(x, y, x2, y + 1, color);
        ctx.fill(x, y2 - 1, x2, y2, color);
        ctx.fill(x, y, x + 1, y2, color);
        ctx.fill(x2 - 1, y, x2, y2, color);
    }
}
