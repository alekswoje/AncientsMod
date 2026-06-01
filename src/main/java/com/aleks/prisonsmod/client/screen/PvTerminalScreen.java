package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.pv.PvClient;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ME-terminal style PV view. Renders every non-empty stack across every
 * unlocked PV as a single flat grid, with a search bar and a hotbar strip at
 * the bottom for drag-deposits.
 *
 * <p>Gated behind the {@code pvTerminal} feature toggle (off by default). When
 * the toggle is off, {@link PvClient} opens the existing
 * {@link PvOverviewScreen} instead — the card view stays untouched.
 *
 * <h2>Interactions</h2>
 * <ul>
 *   <li>L-click tile → extract 1 ({@link Protocol#PV_EXTRACT_ONE}).</li>
 *   <li>R-click tile → extract half ({@link Protocol#PV_EXTRACT_HALF}).</li>
 *   <li>Shift+L-click tile → extract entire stack
 *       ({@link Protocol#PV_EXTRACT_ALL}).</li>
 *   <li>L-press hotbar slot, drag onto grid, release → deposit that hotbar
 *       slot via {@link NetworkHandler#sendPvShiftClick(int)} (server routes
 *       by affinity).</li>
 * </ul>
 *
 * <p>The screen is bundle-driven: after every extract / deposit the server
 * pushes a fresh {@link PvBundlePayload} which {@link PvClient#onBundle}
 * routes to {@link #onBundleUpdated} for in-place re-render.
 */
public final class PvTerminalScreen extends Screen {

    /** Terminal-grid tile size (bigger than vanilla 18 so item names are easier to read). */
    private static final int SLOT_PX = 22;
    private static final int GRID_COLS = 11;
    private static final int GRID_ROWS = 8;

    /** Player-inventory slots use vanilla 18px so they look familiar / aligned
     *  with the rest of the game. */
    private static final int INV_SLOT_PX = 18;
    private static final int INV_COLS = 9;
    /** 3 rows of main inv (slots 9..35) above a 1-row hotbar (slots 0..8). */
    private static final int INV_MAIN_ROWS = 3;
    private static final int INV_HOTBAR_GAP = 4;
    private static final int INV_HEIGHT =
            INV_MAIN_ROWS * INV_SLOT_PX + INV_HOTBAR_GAP + INV_SLOT_PX;

    private static final int TITLE_BAR_H = 24;
    private static final int SEARCH_BAR_H = 26;
    private static final int INV_TOP_GAP = 14;
    /** Bottom padding below the inventory (footer text removed). */
    private static final int FOOTER_H = 8;

    private static final int PANEL_PADDING = 8;
    private static final int SCROLLBAR_W = 6;
    private static final int SCROLLBAR_GAP = 4;


    private PvBundlePayload bundle;
    private final List<Entry> entries = new ArrayList<>();
    private int scrollRowOffset = 0;
    private List<Text> hoverTooltip = null;

    /** Optimistic local override of slot amounts — keyed by (vault &lt;&lt; 16) | slot.
     *  Set on extract click so the tile updates instantly without waiting for the
     *  server's bundle refresh. Cleared whenever a fresh bundle arrives (server
     *  state is authoritative once it lands). 0 means "this slot is now empty". */
    private final java.util.Map<Long, Integer> optimisticAmounts = new java.util.HashMap<>();

    private TextFieldWidget searchField;
    private String searchQuery = "";

    /** When true, the player is shift+left-dragging across inventory slots —
     *  each new slot the cursor enters fires a deposit. */
    private boolean shiftDragging = false;
    /** Slots already shift-dragged-over this drag, so we don't re-fire while
     *  the cursor still sits on the same slot. Reset on mouseRelease. */
    private final java.util.Set<Integer> shiftDragDeposited = new java.util.HashSet<>();

    /** Visible flash on a vault number after the bundle reflects an op there.
     *  Tracked by start-time ms; expires after FLASH_MS. */
    private final java.util.Map<Integer, Long> recentVaultFlash = new java.util.HashMap<>();
    private static final long FLASH_MS = 380L;

    /** Until-timestamp (ms) for the "safe zone only" notice shown after a
     *  blocked take/put attempt while the bundle reports view-only. */
    private long blockedFlashUntilMs = 0L;

    public PvTerminalScreen(PvBundlePayload bundle) {
        super(Text.literal("PV Terminal"));
        this.bundle = bundle;
    }

    public void onBundleUpdated(PvBundlePayload payload) {
        // Note vaults whose visible item count changed — used for the post-op
        // tile flash. Only flash on a delta to avoid the cosmetic firing on
        // every periodic refresh.
        java.util.Set<Integer> changed = changedVaults(this.bundle, payload);
        this.bundle = payload;
        // Server state is authoritative — discard any optimistic overrides.
        optimisticAmounts.clear();
        recomputeEntries();
        int maxOffset = Math.max(0, totalRows() - GRID_ROWS);
        if (scrollRowOffset > maxOffset) scrollRowOffset = maxOffset;
        long now = System.currentTimeMillis();
        for (Integer v : changed) recentVaultFlash.put(v, now);
    }

    private static java.util.Set<Integer> changedVaults(PvBundlePayload before, PvBundlePayload after) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (before == null || after == null) return out;
        java.util.Map<Integer, Integer> beforeCounts = new java.util.HashMap<>();
        for (PvBundlePayload.Vault v : before.vaults) beforeCounts.put(v.vaultNumber, v.slots.size());
        for (PvBundlePayload.Vault v : after.vaults) {
            Integer prev = beforeCounts.get(v.vaultNumber);
            if (prev == null || prev != v.slots.size()) out.add(v.vaultNumber);
        }
        return out;
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
        this.searchField.setPlaceholder(Text.literal("§7Search items or material id…"));
        this.searchField.setText(searchQuery);
        this.searchField.setChangedListener(s -> {
            searchQuery = s == null ? "" : s;
            recomputeEntries();
            int maxOffset = Math.max(0, totalRows() - GRID_ROWS);
            if (scrollRowOffset > maxOffset) scrollRowOffset = maxOffset;
        });
        this.addDrawableChild(this.searchField);

        // Optionally grab keyboard focus so the player can type a query the
        // instant the terminal opens (mirrors defocusSearch's focus toggling).
        // The inventory-key close guard in keyPressed already lets 'e' type into
        // a focused search field instead of closing the screen.
        if (FeatureToggles.isPvTerminalAutoFocusSearchEnabled()) {
            this.setFocused(this.searchField);
            this.searchField.setFocused(true);
        }

        recomputeEntries();
    }

    private void recomputeEntries() {
        entries.clear();
        if (bundle == null) return;
        String q = normalizedQuery();
        for (PvBundlePayload.Vault v : bundle.vaults) {
            if (!v.isAccessible()) continue;
            for (PvBundlePayload.Slot s : v.slots) {
                if (!q.isEmpty() && !slotMatches(s, q)) continue;
                int displayed = effectiveAmount(v.vaultNumber, s);
                if (displayed <= 0) continue; // optimistically removed
                entries.add(new Entry(v.vaultNumber, s, displayed));
            }
        }
    }

    private int effectiveAmount(int vaultNumber, PvBundlePayload.Slot s) {
        long key = (((long) vaultNumber) << 16) | (s.slotIndex & 0xFFFFL);
        Integer override = optimisticAmounts.get(key);
        return override != null ? override : s.amount;
    }

    /** Apply an extracted amount locally so the tile updates immediately. */
    private void applyOptimisticExtract(int vaultNumber, PvBundlePayload.Slot s, int taken) {
        long key = (((long) vaultNumber) << 16) | (s.slotIndex & 0xFFFFL);
        int current = optimisticAmounts.getOrDefault(key, s.amount);
        int next = Math.max(0, current - taken);
        optimisticAmounts.put(key, next);
        recomputeEntries();
    }

    /** Whether the player may currently take/put. Driven by the bundle's
     *  safe-zone flag (server-authoritative). When false, extract + deposit are
     *  blocked client-side and the screen renders a view-only notice. The
     *  server enforces the same gate regardless of what the client sends. */
    private boolean canModify() {
        return bundle == null || bundle.safe;
    }

    /** Flash the "safe zone only" notice after a blocked take/put attempt. */
    private void flashBlocked() {
        blockedFlashUntilMs = System.currentTimeMillis() + 1600L;
    }

    private String normalizedQuery() {
        return searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
    }

    private boolean slotMatches(PvBundlePayload.Slot slot, String q) {
        if (q.isEmpty()) return true;
        if (slot.displayName != null && !slot.displayName.isEmpty()
                && stripColor(slot.displayName).toLowerCase(Locale.ROOT).contains(q)) return true;
        if (slot.materialKey != null && slot.materialKey.toLowerCase(Locale.ROOT).contains(q)) return true;
        ItemStack stack = resolveStack(slot, slot.amount);
        if (!stack.isEmpty()) {
            String name = stack.getName().getString();
            if (name != null && name.toLowerCase(Locale.ROOT).contains(q)) return true;
            // Also search vanilla lore (some items put info in lore that's not in displayName).
            for (String loreLine : slot.lore) {
                if (stripColor(loreLine).toLowerCase(Locale.ROOT).contains(q)) return true;
            }
        }
        return false;
    }

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

    private int totalRows() {
        return Math.max(1, (entries.size() + GRID_COLS - 1) / GRID_COLS);
    }

    private int gridContentWidth() { return GRID_COLS * SLOT_PX; }
    private int gridContentHeight() { return GRID_ROWS * SLOT_PX; }

    private int panelWidth() {
        return gridContentWidth() + PANEL_PADDING * 2 + SCROLLBAR_W + SCROLLBAR_GAP;
    }

    private int panelHeight() {
        return TITLE_BAR_H + SEARCH_BAR_H + PANEL_PADDING + gridContentHeight()
                + INV_TOP_GAP + INV_HEIGHT + FOOTER_H;
    }

    private int gridX() {
        return (this.width - panelWidth()) / 2 + PANEL_PADDING;
    }

    private int gridY() {
        return (this.height - panelHeight()) / 2 + TITLE_BAR_H + SEARCH_BAR_H + PANEL_PADDING;
    }

    /** Top-left X of the player-inventory grid (main inv row 1). Centered
     *  under the terminal grid. */
    private int invX() {
        return (this.width - INV_COLS * INV_SLOT_PX) / 2;
    }

    /** Top-left Y of the player-inventory grid (first row of main inv). */
    private int invY() {
        int panelY = (this.height - panelHeight()) / 2;
        return panelY + panelHeight() - FOOTER_H - INV_HEIGHT;
    }

    /** Returns the entry index under (mx, my), or -1 if none. */
    private int entryUnderCursor(double mx, double my) {
        int gx = gridX();
        int gy = gridY();
        if (mx < gx || mx >= gx + gridContentWidth()) return -1;
        if (my < gy || my >= gy + gridContentHeight()) return -1;
        int col = (int) ((mx - gx) / SLOT_PX);
        int row = (int) ((my - gy) / SLOT_PX);
        int index = (scrollRowOffset + row) * GRID_COLS + col;
        if (index < 0 || index >= entries.size()) return -1;
        return index;
    }

    /** Returns the player-inventory slot index under cursor in Bukkit ordering
     *  (0..8 hotbar, 9..35 main inv), or -1 if none. */
    private int invSlotUnderCursor(double mx, double my) {
        int ix = invX();
        int iy = invY();
        if (mx < ix || mx >= ix + INV_COLS * INV_SLOT_PX) return -1;
        if (my < iy || my >= iy + INV_HEIGHT) return -1;

        int relCol = (int) ((mx - ix) / INV_SLOT_PX);
        if (relCol < 0 || relCol >= INV_COLS) return -1;

        // Three main inv rows at the top, then a gap, then the hotbar row.
        int mainBottom = iy + INV_MAIN_ROWS * INV_SLOT_PX;
        int hotbarTop = mainBottom + INV_HOTBAR_GAP;
        if (my < mainBottom) {
            int row = (int) ((my - iy) / INV_SLOT_PX);
            // Bukkit slot ordering: row 0 (top) = 9..17, row 1 = 18..26, row 2 = 27..35.
            return 9 + row * INV_COLS + relCol;
        } else if (my >= hotbarTop && my < hotbarTop + INV_SLOT_PX) {
            return relCol; // hotbar 0..8
        }
        // Gap between main inv and hotbar — not a slot.
        return -1;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        int button = click.button();
        double mx = click.x();
        double my = click.y();

        boolean holdingCursor = !cursorStack().isEmpty();
        int invSlot = invSlotUnderCursor(mx, my);
        int entryIdx = entryUnderCursor(mx, my);

        // ── Inventory slot clicks (vanilla-like) ──
        if (invSlot >= 0) {
            defocusSearch();
            // Shift+L → deposit to PV (bulk), and arm a shift-drag so dragging
            // across more slots deposits them too. Armed even when the starting
            // slot is empty (vanilla shift-drag begins on any slot).
            if (button == 0 && isShiftDown() && !holdingCursor) {
                // View-only outside a safe zone: don't deposit (and don't arm
                // the shift-drag), just flash the notice. Server blocks it too.
                if (!canModify()) {
                    flashBlocked();
                    return true;
                }
                shiftDragging = true;
                shiftDragDeposited.clear();
                shiftDragDeposited.add(invSlot);
                ItemStack inSlot = playerInvStack(invSlot);
                if (inSlot != null && !inSlot.isEmpty()) {
                    setClientInvSlot(invSlot, ItemStack.EMPTY); // optimistic
                    NetworkHandler.sendPvShiftClick(invSlot);
                }
                return true;
            }
            // Bare L → vanilla slot click: pick up if cursor empty, place/
            // swap/merge if holding. Predicted locally, server reconciles.
            if (button == 0) {
                predictInvSlotClick(invSlot);
                NetworkHandler.sendPvCursorPlaceInv(invSlot);
                return true;
            }
            return super.mouseClicked(click, doubleClick);
        }

        // ── Terminal tile clicks ──
        if (entryIdx >= 0) {
            defocusSearch();
            if (holdingCursor) {
                // Holding a stack → click a tile to stash it back into a PV.
                // Predict the cursor clearing immediately.
                setClientCursor(ItemStack.EMPTY);
                NetworkHandler.sendPvCursorReturn();
                return true;
            }
            Entry e = entries.get(entryIdx);
            int available = e.displayedAmount;
            // View-only outside a safe zone: block all extract modes (the
            // holding-cursor return-to-inventory path above is harmless and
            // stays). Server rejects + re-syncs too, but blocking here avoids
            // the optimistic tile flicker.
            if (!canModify()) {
                flashBlocked();
                return true;
            }
            if (button == 1) {
                // Right-click → half onto cursor.
                int take = Math.max(1, (available + 1) / 2);
                applyOptimisticExtract(e.vaultNumber, e.slot, take);
                predictPickupToCursor(e.slot, take);
                NetworkHandler.sendPvExtract(e.vaultNumber, e.slot.slotIndex,
                        Protocol.PV_EXTRACT_HALF, Protocol.PV_TARGET_CURSOR);
            } else if (button == 0 && isShiftDown()) {
                // Shift+left → whole stack straight into the inventory (bulk).
                // Inventory placement is hard to predict precisely, so only the
                // tile decrement is optimistic; the server fills the inventory.
                applyOptimisticExtract(e.vaultNumber, e.slot, available);
                NetworkHandler.sendPvExtract(e.vaultNumber, e.slot.slotIndex,
                        Protocol.PV_EXTRACT_ALL, Protocol.PV_TARGET_INV);
            } else if (button == 0) {
                // Left-click → one onto cursor.
                applyOptimisticExtract(e.vaultNumber, e.slot, 1);
                predictPickupToCursor(e.slot, 1);
                NetworkHandler.sendPvExtract(e.vaultNumber, e.slot.slotIndex,
                        Protocol.PV_EXTRACT_ONE, Protocol.PV_TARGET_CURSOR);
            } else {
                return super.mouseClicked(click, doubleClick);
            }
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    /** The player's current cursor (carried) stack — server-authoritative,
     *  synced into the active screen handler. Empty when nothing is held. We
     *  also write to it for optimistic prediction; the server reconciles. */
    private static ItemStack cursorStack() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.player.currentScreenHandler == null) return ItemStack.EMPTY;
        return mc.player.currentScreenHandler.getCursorStack();
    }

    private static void setClientCursor(ItemStack stack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.player.currentScreenHandler == null) return;
        mc.player.currentScreenHandler.setCursorStack(stack);
    }

    private static void setClientInvSlot(int slot, ItemStack stack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (slot < 0 || slot >= 36) return;
        mc.player.getInventory().setStack(slot, stack);
    }

    /** Optimistically reflect a tile→cursor pickup so it feels instant. The
     *  predicted stack uses the bundle's material id (no NBT), so an enchanted
     *  item may briefly render plain until the server's authoritative cursor
     *  sync arrives and corrects it. */
    private void predictPickupToCursor(PvBundlePayload.Slot slot, int take) {
        ItemStack cursor = cursorStack();
        if (cursor.isEmpty()) {
            setClientCursor(resolveStack(slot, take));
        } else {
            ItemStack next = cursor.copy();
            next.setCount(cursor.getCount() + take);
            setClientCursor(next);
        }
    }

    /** Optimistic vanilla left-click-on-slot: mirrors the server's
     *  pickup/place/merge/swap so the move shows instantly. */
    private void predictInvSlotClick(int slot) {
        ItemStack cursor = cursorStack();
        ItemStack inSlot = playerInvStack(slot);
        boolean cursorEmpty = cursor.isEmpty();
        boolean slotEmpty = (inSlot == null || inSlot.isEmpty());
        if (cursorEmpty && slotEmpty) return;

        if (cursorEmpty) {
            setClientCursor(inSlot.copy());
            setClientInvSlot(slot, ItemStack.EMPTY);
        } else if (slotEmpty) {
            setClientInvSlot(slot, cursor.copy());
            setClientCursor(ItemStack.EMPTY);
        } else if (ItemStack.areItemsAndComponentsEqual(inSlot, cursor)) {
            int space = inSlot.getMaxCount() - inSlot.getCount();
            int move = Math.min(space, cursor.getCount());
            if (move <= 0) return;
            ItemStack newSlot = inSlot.copy();
            newSlot.setCount(inSlot.getCount() + move);
            setClientInvSlot(slot, newSlot);
            int leftover = cursor.getCount() - move;
            if (leftover <= 0) {
                setClientCursor(ItemStack.EMPTY);
            } else {
                ItemStack c = cursor.copy();
                c.setCount(leftover);
                setClientCursor(c);
            }
        } else {
            setClientInvSlot(slot, cursor.copy());
            setClientCursor(inSlot.copy());
        }
    }

    private void defocusSearch() {
        if (searchField != null && searchField.isFocused()) {
            searchField.setFocused(false);
            this.setFocused(null);
        }
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        // Shift+drag across inventory slots → deposit each new slot to PV.
        if (shiftDragging && click.button() == 0) {
            if (!isShiftDown()) {
                // Player released shift mid-drag — stop the mass-deposit.
                shiftDragging = false;
                shiftDragDeposited.clear();
            } else {
                int invSlot = invSlotUnderCursor(click.x(), click.y());
                if (invSlot >= 0 && !shiftDragDeposited.contains(invSlot)) {
                    ItemStack stack = playerInvStack(invSlot);
                    if (stack != null && !stack.isEmpty()) {
                        shiftDragDeposited.add(invSlot);
                        setClientInvSlot(invSlot, ItemStack.EMPTY); // optimistic
                        NetworkHandler.sendPvShiftClick(invSlot);
                    }
                }
                return true;
            }
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (shiftDragging && click.button() == 0) {
            shiftDragging = false;
            shiftDragDeposited.clear();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maxOffset = Math.max(0, totalRows() - GRID_ROWS);
        if (maxOffset <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollRowOffset -= (int) Math.signum(verticalAmount);
        if (scrollRowOffset < 0) scrollRowOffset = 0;
        if (scrollRowOffset > maxOffset) scrollRowOffset = maxOffset;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        // Close on the inventory key (E by default) — but only when the search
        // field doesn't have keyboard focus, so typing 'e' into the search box
        // still works.
        if (searchField == null || !searchField.isFocused()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.options != null && mc.options.inventoryKey != null
                    && mc.options.inventoryKey.matchesKey(input)) {
                this.close();
                return true;
            }
        }
        return super.keyPressed(input);
    }

    private static boolean isShiftDown() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return false;
        Window window = mc.getWindow();
        if (window == null) return false;
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
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
        int totalOccupied = 0, totalCapacity = 0;
        if (bundle != null) {
            for (PvBundlePayload.Vault v : bundle.vaults) {
                if (v.isAccessible()) { totalOccupied += v.slots.size(); totalCapacity += v.slotCount; }
            }
        }
        int pct = totalCapacity > 0 ? (totalOccupied * 100 / totalCapacity) : 0;
        String pctColor = pct >= 90 ? "§c" : pct >= 75 ? "§e" : "§7";
        ctx.drawText(this.textRenderer,
                Text.literal("§ePV Terminal §8· §7" + entries.size() + " items §8· " + pctColor + pct + "%"),
                panelX + 10, panelY + 8, 0xFFFFFFFF, true);
        if (!canModify()) {
            // Persistent view-only indicator — you can browse/search your PVs
            // anywhere, but taking/depositing is safe-zone only.
            String warn = "§c⚠ View only · safe zone";
            int warnW = this.textRenderer.getWidth(warn);
            ctx.drawText(this.textRenderer, Text.literal(warn),
                    panelX + panelW - warnW - 10, panelY + 8, 0xFFFF5555, true);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        hoverTooltip = null;
        long now = System.currentTimeMillis();

        int gx = gridX();
        int gy = gridY();
        int gridW = gridContentWidth();
        int gridH = gridContentHeight();

        // Grid background — dark slots.
        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                int sx = gx + c * SLOT_PX;
                int sy = gy + r * SLOT_PX;
                ctx.fill(sx + 1, sy + 1, sx + SLOT_PX - 1, sy + SLOT_PX - 1, 0xFF0E0E0E);
            }
        }

        // Render visible entries
        int firstIdx = scrollRowOffset * GRID_COLS;
        int lastIdx = Math.min(entries.size(), firstIdx + GRID_ROWS * GRID_COLS);
        for (int i = firstIdx; i < lastIdx; i++) {
            Entry e = entries.get(i);
            int rel = i - firstIdx;
            int col = rel % GRID_COLS;
            int row = rel / GRID_COLS;
            int sx = gx + col * SLOT_PX;
            int sy = gy + row * SLOT_PX;

            ItemStack stack = resolveStack(e.slot, e.displayedAmount);
            if (stack.isEmpty()) continue;

            int io = (SLOT_PX - 16) / 2; // centre the 16px icon in the 22px vault cell
            ctx.drawItem(stack, sx + io, sy + io);
            if (e.displayedAmount > 1) {
                ctx.drawStackOverlay(this.textRenderer, stack, sx + io, sy + io);
            }

            // Post-op flash for the vault this entry came from
            Long flashAt = recentVaultFlash.get(e.vaultNumber);
            if (flashAt != null) {
                long elapsed = now - flashAt;
                if (elapsed < FLASH_MS) {
                    float t = 1f - (elapsed / (float) FLASH_MS);
                    int alpha = (int) (Math.max(0, Math.min(1, t)) * 100);
                    int color = (alpha << 24) | 0xFFCC33;
                    ctx.fill(sx + 1, sy + 1, sx + SLOT_PX - 1, sy + SLOT_PX - 1, color);
                } else {
                    recentVaultFlash.remove(e.vaultNumber);
                }
            }

            // Hover highlight + tooltip (vanilla item name + bundled lore + PV hint)
            if (mouseX >= sx && mouseX < sx + SLOT_PX && mouseY >= sy && mouseY < sy + SLOT_PX) {
                ctx.fill(sx + 1, sy + 1, sx + SLOT_PX - 1, sy + SLOT_PX - 1, 0x66FFFFFF);
                hoverTooltip = buildItemTooltip(e, stack);
            }
        }

        // Empty / no-match message
        if (entries.isEmpty()) {
            String msg = normalizedQuery().isEmpty()
                    ? "§7Your vaults are empty."
                    : "§7No items match §f\"" + searchQuery + "\"";
            int msgW = this.textRenderer.getWidth(msg);
            ctx.drawText(this.textRenderer, Text.literal(msg),
                    gx + (gridW - msgW) / 2, gy + gridH / 2 - 4, 0xFFAAAAAA, false);
        }

        // Scrollbar
        int maxOffset = Math.max(0, totalRows() - GRID_ROWS);
        if (maxOffset > 0) {
            int sbX = gx + gridW + SCROLLBAR_GAP;
            int sbY = gy;
            int sbH = gridH;
            ctx.fill(sbX, sbY, sbX + SCROLLBAR_W, sbY + sbH, 0x80000000);
            double trackRatio = (double) GRID_ROWS / totalRows();
            int thumbH = Math.max(20, (int) (sbH * trackRatio));
            int range = sbH - thumbH;
            int thumbY = sbY + (int) (range * ((double) scrollRowOffset / maxOffset));
            ctx.fill(sbX, thumbY, sbX + SCROLLBAR_W, thumbY + thumbH, 0xFFAAAAAA);
        }

        // Blocked-action notice — shown briefly after a take/put attempt while
        // view-only (outside a safe zone). Drawn over the grid, under the cursor.
        if (now < blockedFlashUntilMs) {
            String msg = "§cYou can only use your vault in a safe zone!";
            int msgW = this.textRenderer.getWidth(msg);
            int bx = gx + (gridW - msgW) / 2;
            int by = gy + gridH / 2 - 4;
            ctx.fill(bx - 6, by - 5, bx + msgW + 6, by + 13, 0xD0200000);
            ctx.drawText(this.textRenderer, Text.literal(msg), bx, by, 0xFFFF6666, true);
        }

        // Player inventory strip (3 main rows + hotbar)
        renderPlayerInventory(ctx, mouseX, mouseY);

        // Cursor stack — the item picked up from a tile, floating with the mouse.
        // (Vanilla only renders this inside HandledScreens; our screen isn't one,
        // so we draw it ourselves.)
        ItemStack cursor = cursorStack();
        boolean holding = !cursor.isEmpty();
        if (holding) {
            ctx.drawItem(cursor, (int) (mouseX - 8), (int) (mouseY - 8));
            if (cursor.getCount() > 1) {
                ctx.drawStackOverlay(this.textRenderer, cursor, (int) (mouseX - 8), (int) (mouseY - 8));
            }
        }

        // Suppress hover tooltips while holding a stack — matches vanilla.
        if (hoverTooltip != null && !holding) {
            ctx.drawTooltip(this.textRenderer, hoverTooltip, mouseX, mouseY);
        }
    }

    /** Build a vanilla-style multi-line tooltip from a terminal tile: bundled
     *  display-name as the title, then each lore line, then a footer with
     *  count + source PV. */
    private List<Text> buildItemTooltip(Entry e, ItemStack stack) {
        List<Text> lines = new ArrayList<>();
        String name = (e.slot.displayName != null && !e.slot.displayName.isEmpty())
                ? e.slot.displayName
                : stack.getName().getString();
        lines.add(Text.literal(name));
        if (e.slot.lore != null) {
            for (String loreLine : e.slot.lore) lines.add(Text.literal(loreLine));
        }
        lines.add(Text.literal("§8x" + e.displayedAmount + "  §7· §8PV " + e.vaultNumber));
        return lines;
    }

    private void renderPlayerInventory(DrawContext ctx, int mouseX, int mouseY) {
        int ix = invX();
        int iy = invY();
        int panelX = (this.width - panelWidth()) / 2;

        ctx.drawText(this.textRenderer, Text.literal("§7Your inventory:"),
                panelX + 10, iy - 9, 0xFF888888, false);

        // Main inventory rows (slots 9..35) — top to bottom.
        for (int row = 0; row < INV_MAIN_ROWS; row++) {
            for (int col = 0; col < INV_COLS; col++) {
                int slot = 9 + row * INV_COLS + col;
                int sx = ix + col * INV_SLOT_PX;
                int sy = iy + row * INV_SLOT_PX;
                renderInvSlot(ctx, slot, sx, sy, mouseX, mouseY);
            }
        }

        // Hotbar row (slots 0..8) below the main inv with a small gap.
        int hbY = iy + INV_MAIN_ROWS * INV_SLOT_PX + INV_HOTBAR_GAP;
        for (int col = 0; col < INV_COLS; col++) {
            int sx = ix + col * INV_SLOT_PX;
            renderInvSlot(ctx, col, sx, hbY, mouseX, mouseY);
        }
    }

    private void renderInvSlot(DrawContext ctx, int slotIndex, int sx, int sy,
                               int mouseX, int mouseY) {
        ctx.fill(sx, sy, sx + INV_SLOT_PX, sy + INV_SLOT_PX, 0xFF2A2A2A);
        ctx.fill(sx + 1, sy + 1, sx + INV_SLOT_PX - 1, sy + INV_SLOT_PX - 1, 0xFF101010);

        ItemStack stack = playerInvStack(slotIndex);
        if (stack != null && !stack.isEmpty()) {
            ctx.drawItem(stack, sx + 1, sy + 1);
            if (stack.getCount() > 1) {
                ctx.drawStackOverlay(this.textRenderer, stack, sx + 1, sy + 1);
            }
        }

        if (mouseX >= sx && mouseX < sx + INV_SLOT_PX
                && mouseY >= sy && mouseY < sy + INV_SLOT_PX) {
            ctx.fill(sx + 1, sy + 1, sx + INV_SLOT_PX - 1, sy + INV_SLOT_PX - 1, 0x44FFFFFF);
            if (stack != null && !stack.isEmpty()) {
                // Vanilla item tooltip (name + lore + enchants + durability),
                // plus a hint about the shift-click deposit shortcut.
                List<Text> vanilla = Screen.getTooltipFromItem(MinecraftClient.getInstance(), stack);
                List<Text> tip = new ArrayList<>(vanilla.size() + 1);
                tip.addAll(vanilla);
                tip.add(canModify()
                        ? Text.literal("§8Shift-click → deposit to PV")
                        : Text.literal("§cView only — deposit in a safe zone"));
                hoverTooltip = tip;
            }
        }
    }

    /** Read a player inventory slot — uses live client inventory state.
     *  Bukkit ordering: 0..8 hotbar, 9..35 main inv. */
    private static ItemStack playerInvStack(int slot) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return ItemStack.EMPTY;
        PlayerInventory inv = mc.player.getInventory();
        if (slot < 0 || slot >= 36) return ItemStack.EMPTY;
        return inv.getStack(slot);
    }

    private ItemStack resolveStack(PvBundlePayload.Slot slot, int displayedAmount) {
        if (slot.materialKey == null || slot.materialKey.isEmpty()) return ItemStack.EMPTY;
        return com.aleks.prisonsmod.client.IconResolver.resolve(slot.materialKey, Items.BARRIER, displayedAmount);
    }

    @Override
    public void close() {
        // Never leave a picked-up stack dangling — ask the server to route it
        // back into a PV (or the player's inventory).
        if (!cursorStack().isEmpty()) {
            NetworkHandler.sendPvCursorReturn();
        }
        PvClient.onScreenClosed();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** A single visible tile: the source vault + slot stub from the bundle.
     *  {@code displayedAmount} can differ from {@code slot.amount} during a
     *  pending optimistic-extract before the server bundle refreshes. */
    private static final class Entry {
        final int vaultNumber;
        final PvBundlePayload.Slot slot;
        final int displayedAmount;

        Entry(int vaultNumber, PvBundlePayload.Slot slot, int displayedAmount) {
            this.vaultNumber = vaultNumber;
            this.slot = slot;
            this.displayedAmount = displayedAmount;
        }
    }
}
