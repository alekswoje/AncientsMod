package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.pv.PvClient;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mod-side overview of every accessible PV with item previews. Opens when the
 * player runs {@code /pv} (no args) and the server replies with a
 * {@link PvBundlePayload}. Left-click a card to open it, right-click to edit
 * affinities, or drag a card onto another to swap their contents.
 *
 * <p>Cards are arranged in a 4-wide grid with a vertical scroll viewport.
 * Hover state, drag-ghost rendering, drop-target highlights, and post-swap
 * flashes are all animated via per-tile state lerped each frame.
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
    private static final int SEARCH_BAR_H = 26;
    private static final int FOOTER_H = 28;

    private static final int PANEL_PADDING = 8;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_GAP = 4;

    private static final int MIN_VIEW_ROWS = 2;
    private static final int MAX_VIEW_ROWS = 4;

    /** Px the cursor must travel from press point before we treat it as a drag
     *  rather than a click. */
    private static final double DRAG_THRESHOLD_PX = 4.0;

    /** Hover anim speed: fraction of remaining distance closed per millisecond
     *  (so a value of 0.012 ≈ ~95% lerp in 250ms). */
    private static final float HOVER_LERP_PER_MS = 0.012f;

    /** Swap flash visual duration. */
    private static final long SWAP_FLASH_MS = 450L;

    private PvBundlePayload bundle;

    private Text hoverTooltip = null;
    private double scrollY = 0;

    private TextFieldWidget searchField;
    private String searchQuery = "";
    /** Recomputed every search-change / bundle update. Rows re-pack so PVs with
     *  zero matches don't leave gaps. */
    private final List<PvBundlePayload.Vault> visibleVaults = new ArrayList<>();

    // --- Press/drag state ---
    /** Vault number that has been left-pressed but not yet released. 0 = no press. */
    private int pressedVault = 0;
    private double pressStartX, pressStartY;
    private double currentMouseX, currentMouseY;
    private boolean dragging = false;

    // --- Animation state ---
    /** vault number → 0..1 hover progress. */
    private final Map<Integer, Float> hoverAnim = new HashMap<>();
    /** vault number → start time of the post-swap flash. */
    private final Map<Integer, Long> swapFlashAt = new HashMap<>();
    private long lastFrameMs = System.currentTimeMillis();

    public PvOverviewScreen(PvBundlePayload bundle) {
        super(Text.literal("Personal Vaults"));
        this.bundle = bundle;
    }

    public void onBundleUpdated(PvBundlePayload payload) {
        this.bundle = payload;
        recomputeVisibleVaults();
        scrollY = Math.max(0, Math.min(scrollY, maxScroll()));
    }

    @Override
    protected void init() {
        int panelW = panelWidth();
        int panelH = panelHeight();
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        int searchW = panelW - PANEL_PADDING * 2 - SCROLLBAR_W - SCROLLBAR_GAP;
        int searchX = panelX + PANEL_PADDING;
        int searchY = panelY + TITLE_BAR_H + 4;
        this.searchField = new TextFieldWidget(this.textRenderer, searchX, searchY, searchW, 18,
                Text.literal("Search items…"));
        this.searchField.setMaxLength(64);
        this.searchField.setPlaceholder(Text.literal("§7Search items, lore, or material id…"));
        this.searchField.setText(searchQuery);
        this.searchField.setChangedListener(s -> {
            searchQuery = s == null ? "" : s;
            recomputeVisibleVaults();
            scrollY = Math.max(0, Math.min(scrollY, maxScroll()));
        });
        this.addDrawableChild(this.searchField);

        int btnW = 90;
        int btnY = panelY + panelH - FOOTER_H + 4;
        ButtonWidget sortBtn = ButtonWidget.builder(Text.literal("Sort"), b -> NetworkHandler.sendPvSortRequest())
                .dimensions(panelX + panelW - btnW - 10, btnY, btnW, 20)
                .build();
        this.addDrawableChild(sortBtn);

        recomputeVisibleVaults();
    }

    private void recomputeVisibleVaults() {
        visibleVaults.clear();
        if (bundle == null || bundle.vaults.isEmpty()) return;
        String q = normalizedQuery();
        if (q.isEmpty()) {
            // Default mode: show every accessible vault plus a one-card preview
            // of the next locked vault (upsell). Mirrors the old displayCount().
            int lastAccessible = -1;
            for (int i = 0; i < bundle.vaults.size(); i++) {
                if (bundle.vaults.get(i).isAccessible()) lastAccessible = i;
            }
            if (lastAccessible < 0) {
                visibleVaults.add(bundle.vaults.get(0));
            } else {
                int withPreview = Math.min(lastAccessible + 2, bundle.vaults.size());
                for (int i = 0; i < withPreview; i++) visibleVaults.add(bundle.vaults.get(i));
            }
            return;
        }
        // Search mode: only accessible vaults that contain at least one match.
        // Locked-vault upsell is suppressed during search.
        for (PvBundlePayload.Vault vault : bundle.vaults) {
            if (!vault.isAccessible()) continue;
            for (PvBundlePayload.Slot slot : vault.slots) {
                if (slotMatches(slot, q)) {
                    visibleVaults.add(vault);
                    break;
                }
            }
        }
    }

    private String normalizedQuery() {
        if (searchQuery == null) return "";
        return searchQuery.trim().toLowerCase(Locale.ROOT);
    }

    private boolean slotMatches(PvBundlePayload.Slot slot, String q) {
        if (q.isEmpty()) return true;
        if (slot.displayName != null && !slot.displayName.isEmpty()
                && stripColor(slot.displayName).toLowerCase(Locale.ROOT).contains(q)) return true;
        if (slot.materialKey != null && slot.materialKey.toLowerCase(Locale.ROOT).contains(q)) return true;
        if (slot.lore != null && !slot.lore.isEmpty()
                && stripColor(slot.lore).toLowerCase(Locale.ROOT).contains(q)) return true;
        // Vanilla items have no custom displayName but should still be findable
        // by their localized name (e.g. "Diamond Sword"). resolveStack is cheap
        // — registry lookup + tiny stack alloc — and only runs while filtering.
        ItemStack stack = resolveStack(slot);
        if (!stack.isEmpty()) {
            String name = stack.getName().getString();
            if (name != null && name.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    /** Remove Bukkit/Minecraft §-prefixed colour codes so the user's search
     *  matches the human-readable string. */
    private static String stripColor(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            out.append(c);
        }
        return out.toString();
    }

    private int displayCount() {
        return visibleVaults.size();
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
        int avail = this.height - TITLE_BAR_H - SEARCH_BAR_H - FOOTER_H - PANEL_PADDING * 2 - 40;
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
        return TITLE_BAR_H + SEARCH_BAR_H + PANEL_PADDING + viewportHeight() + PANEL_PADDING + FOOTER_H;
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
        return panelY + TITLE_BAR_H + SEARCH_BAR_H + PANEL_PADDING;
    }

    /** Returns the vault number under (mx, my), or 0 if none. Accounts for
     *  scroll and viewport clipping. Both accessible and locked vaults count
     *  for hit-testing (caller decides whether to act on them). */
    private int vaultUnderCursor(double mx, double my) {
        int vpX = viewportX();
        int vpY = viewportY();
        int vpW = gridContentWidth();
        int vpH = viewportHeight();
        if (mx < vpX || mx >= vpX + vpW || my < vpY || my >= vpY + vpH) return 0;
        int limit = displayCount();
        for (int idx = 0; idx < limit; idx++) {
            int col = idx % CARDS_PER_ROW;
            int row = idx / CARDS_PER_ROW;
            int cx = vpX + col * (CARD_W + CARD_GAP);
            int cy = vpY + row * (CARD_H + CARD_GAP) - (int) scrollY;
            if (cy + CARD_H <= vpY || cy >= vpY + vpH) continue;
            if (mx < cx || mx >= cx + CARD_W) continue;
            if (my < cy || my >= cy + CARD_H) continue;
            return visibleVaults.get(idx).vaultNumber;
        }
        return 0;
    }

    private PvBundlePayload.Vault vaultByNumber(int vaultNumber) {
        if (bundle == null) return null;
        for (PvBundlePayload.Vault v : bundle.vaults) {
            if (v.vaultNumber == vaultNumber) return v;
        }
        return null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        int button = click.button();
        double mouseX = click.x();
        double mouseY = click.y();
        currentMouseX = mouseX;
        currentMouseY = mouseY;

        if (button == 1) {
            // Right-click → open affinity picker immediately (no drag for RMB).
            int v = vaultUnderCursor(mouseX, mouseY);
            if (v > 0) {
                PvBundlePayload.Vault vault = vaultByNumber(v);
                if (vault != null && vault.isAccessible()) {
                    defocusSearch();
                    PvClient.openAffinityPicker(v);
                    return true;
                }
            }
            return super.mouseClicked(click, doubleClick);
        }

        if (button == 0) {
            // Left-press starts a potential click or drag. Don't open the
            // vault yet — wait for mouseReleased to disambiguate.
            int v = vaultUnderCursor(mouseX, mouseY);
            if (v > 0) {
                PvBundlePayload.Vault vault = vaultByNumber(v);
                if (vault != null && vault.isAccessible()) {
                    defocusSearch();
                    pressedVault = v;
                    pressStartX = mouseX;
                    pressStartY = mouseY;
                    dragging = false;
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    private void defocusSearch() {
        if (searchField != null && searchField.isFocused()) {
            searchField.setFocused(false);
            this.setFocused(null);
        }
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (pressedVault > 0 && click.button() == 0) {
            currentMouseX = click.x();
            currentMouseY = click.y();
            if (!dragging) {
                double dx = currentMouseX - pressStartX;
                double dy = currentMouseY - pressStartY;
                if (dx * dx + dy * dy >= DRAG_THRESHOLD_PX * DRAG_THRESHOLD_PX) {
                    dragging = true;
                }
            }
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (pressedVault > 0 && click.button() == 0) {
            double mouseX = click.x();
            double mouseY = click.y();
            int from = pressedVault;
            boolean wasDragging = dragging;
            pressedVault = 0;
            dragging = false;

            if (wasDragging) {
                int target = vaultUnderCursor(mouseX, mouseY);
                if (target > 0 && target != from) {
                    PvBundlePayload.Vault t = vaultByNumber(target);
                    if (t != null && t.isAccessible()) {
                        NetworkHandler.sendPvSwapRequest(from, target);
                        long now = System.currentTimeMillis();
                        swapFlashAt.put(from, now);
                        swapFlashAt.put(target, now);
                        return true;
                    }
                }
                // Drag released over invalid target — just cancel silently.
                return true;
            }

            // Not dragging → treat as click → open the vault.
            PvClient.openVault(from);
            return true;
        }
        return super.mouseReleased(click);
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
        currentMouseX = mouseX;
        currentMouseY = mouseY;

        long now = System.currentTimeMillis();
        long frameDeltaMs = Math.max(1L, now - lastFrameMs);
        lastFrameMs = now;

        int vpX = viewportX();
        int vpY = viewportY();
        int vpW = gridContentWidth();
        int vpH = viewportHeight();
        int hoveredVault = (!dragging && pressedVault == 0) ? vaultUnderCursor(mouseX, mouseY) : 0;
        int dropTarget = dragging ? vaultUnderCursor(mouseX, mouseY) : 0;

        ctx.enableScissor(vpX, vpY, vpX + vpW, vpY + vpH);
        try {
            int limit = displayCount();
            if (limit == 0 && !normalizedQuery().isEmpty()) {
                String msg = "§7No items match §f\"" + searchQuery + "\"";
                int msgW = this.textRenderer.getWidth(msg);
                ctx.drawText(this.textRenderer, Text.literal(msg),
                        vpX + (vpW - msgW) / 2, vpY + vpH / 2 - 4, 0xFFAAAAAA, false);
            }
            for (int idx = 0; idx < limit; idx++) {
                PvBundlePayload.Vault vault = visibleVaults.get(idx);
                int col = idx % CARDS_PER_ROW;
                int row = idx / CARDS_PER_ROW;
                int cx = vpX + col * (CARD_W + CARD_GAP);
                int cy = vpY + row * (CARD_H + CARD_GAP) - (int) scrollY;
                if (cy + CARD_H <= vpY || cy >= vpY + vpH) continue;

                boolean isHover = vault.vaultNumber == hoveredVault;
                float hoverP = advanceHoverAnim(vault.vaultNumber, isHover, frameDeltaMs);
                boolean isDragSource = dragging && vault.vaultNumber == pressedVault;
                boolean isDropTarget = dropTarget > 0
                        && vault.vaultNumber == dropTarget
                        && vault.vaultNumber != pressedVault
                        && vault.isAccessible();
                float flashP = swapFlashProgress(vault.vaultNumber, now);

                renderCard(ctx, vault, cx, cy, hoverP, isDragSource, isDropTarget, flashP,
                        mouseX, mouseY);
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
            ctx.fill(sbX, thumbY, sbX + SCROLLBAR_W, thumbY + thumbH, 0xFFAAAAAA);
        }

        // Render drag ghost on top of everything (outside scissor so it follows
        // the cursor anywhere on screen).
        if (dragging && pressedVault > 0) {
            PvBundlePayload.Vault src = vaultByNumber(pressedVault);
            if (src != null) {
                int ghostX = (int) (mouseX - CARD_W / 2.0);
                int ghostY = (int) (mouseY - CARD_H / 2.0);
                renderGhostCard(ctx, src, ghostX, ghostY);
            }
        }

        if (hoverTooltip != null && !dragging) {
            ctx.drawTooltip(this.textRenderer, hoverTooltip, mouseX, mouseY);
        }
    }

    private float advanceHoverAnim(int vaultNumber, boolean isHovered, long frameDeltaMs) {
        float current = hoverAnim.getOrDefault(vaultNumber, 0f);
        float target = isHovered ? 1f : 0f;
        float step = Math.min(1f, HOVER_LERP_PER_MS * frameDeltaMs);
        float next = current + (target - current) * step;
        if (Math.abs(next - target) < 0.002f) next = target;
        hoverAnim.put(vaultNumber, next);
        return next;
    }

    private float swapFlashProgress(int vaultNumber, long now) {
        Long start = swapFlashAt.get(vaultNumber);
        if (start == null) return 0f;
        long elapsed = now - start;
        if (elapsed >= SWAP_FLASH_MS) {
            swapFlashAt.remove(vaultNumber);
            return 0f;
        }
        float t = elapsed / (float) SWAP_FLASH_MS;
        // 1 → 0 ease-out for the highlight intensity.
        return 1f - (t * t);
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
        String hint = "§7LMB §8open  §7RMB §8affinities  §7Drag §8swap  §7ESC §8close";
        int hintW = this.textRenderer.getWidth(hint);
        ctx.drawText(this.textRenderer, Text.literal(hint),
                panelX + panelW - hintW - 10, panelY + 8, 0xFFAAAAAA, false);
    }

    private void renderCard(DrawContext ctx, PvBundlePayload.Vault vault, int x, int y,
                            float hoverP, boolean isDragSource, boolean isDropTarget,
                            float flashP, int mouseX, int mouseY) {
        // Background color lerp: base → hover bg via hoverP. Drag source dims
        // toward dark. Drop target pulses toward yellow.
        int baseBg = vault.isAccessible() ? 0xFF1E1E1E : 0xFF181010;
        int hoverBg = 0xFF2A2A2A;
        int bg = vault.isAccessible() ? lerpColor(baseBg, hoverBg, hoverP) : baseBg;
        if (isDragSource) bg = 0xFF0E0E0E;

        // Border color: base → hover gold via hoverP. Drop target overrides to
        // a pulsing yellow. Flash overlays a bright yellow that fades out.
        int baseBorder = vault.isAccessible() ? 0xFF444444 : 0xFF552222;
        int hoverBorder = 0xFFFFCC33;
        int border = vault.isAccessible() ? lerpColor(baseBorder, hoverBorder, hoverP) : baseBorder;
        if (isDropTarget) {
            float pulse = 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 110.0);
            border = lerpColor(0xFFFFCC33, 0xFFFFFF66, pulse);
        }
        if (flashP > 0f) {
            border = lerpColor(border, 0xFFFFEE88, flashP);
        }

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

        renderCardBody(ctx, vault, x, y, mouseX, mouseY, isDragSource);
    }

    private void renderCardBody(DrawContext ctx, PvBundlePayload.Vault vault, int x, int y,
                                int mouseX, int mouseY, boolean dimmed) {
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

        String q = normalizedQuery();
        boolean filtering = !q.isEmpty();

        for (PvBundlePayload.Slot slot : vault.slots) {
            int gridSlot = slot.slotIndex;
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

            boolean dimNonMatch = filtering && !slotMatches(slot, q);
            if (dimNonMatch) {
                // Heavy semi-transparent overlay over the slot to read as
                // "filtered out" while still showing the icon underneath.
                ctx.fill(sx - 1, sy - 1, sx + SLOT_PX - 1, sy + SLOT_PX - 1, 0xC8101010);
            }

            if (!dimmed
                    && mouseX >= sx && mouseX < sx + SLOT_PX
                    && mouseY >= sy && mouseY < sy + SLOT_PX) {
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

        if (dimmed) {
            // Dim the source tile while dragging — half-opacity black overlay.
            ctx.fill(x + 1, y + 1, x + CARD_W - 1, y + CARD_H - 1, 0x88000000);
        }
    }

    private void renderGhostCard(DrawContext ctx, PvBundlePayload.Vault vault, int x, int y) {
        // Translucent panel + dashed yellow border.
        ctx.fill(x, y, x + CARD_W, y + CARD_H, 0xC0181818);
        int border = 0xFFFFCC33;
        ctx.fill(x, y, x + CARD_W, y + 1, border);
        ctx.fill(x, y + CARD_H - 1, x + CARD_W, y + CARD_H, border);
        ctx.fill(x, y, x + 1, y + CARD_H, border);
        ctx.fill(x + CARD_W - 1, y, x + CARD_W, y + CARD_H, border);

        renderCardBody(ctx, vault, x, y, -1, -1, false);
    }

    private static int lerpColor(int colorA, int colorB, float t) {
        if (t <= 0f) return colorA;
        if (t >= 1f) return colorB;
        int aA = (colorA >>> 24) & 0xFF;
        int rA = (colorA >>> 16) & 0xFF;
        int gA = (colorA >>> 8) & 0xFF;
        int bA = colorA & 0xFF;
        int aB = (colorB >>> 24) & 0xFF;
        int rB = (colorB >>> 16) & 0xFF;
        int gB = (colorB >>> 8) & 0xFF;
        int bB = colorB & 0xFF;
        int a = (int) (aA + (aB - aA) * t);
        int r = (int) (rA + (rB - rA) * t);
        int g = (int) (gA + (gB - gA) * t);
        int b = (int) (bA + (bB - bA) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
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
