package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.glass.GlassRender;
import com.aleks.prisonsmod.client.glass.GlassScrollbar;
import com.aleks.prisonsmod.client.glass.GlassTextField;
import com.aleks.prisonsmod.client.glass.GlassTheme;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared ME-terminal style item screen: a flat aggregated grid of every
 * non-empty stack across a set of item "groups" (PVs for the PV terminal,
 * cell containers for the cell terminal), with a search bar, sort button,
 * scrollbar, and a manually-drawn player-inventory strip for deposits.
 *
 * <p>Mechanically extracted from the original {@code PvTerminalScreen} so the
 * PV terminal and the cell-vault terminal share one copy of the complex
 * machinery: entry aggregation by item identity, optimistic amount overrides,
 * cursor-stack prediction, frozen-order-while-shift, shift-drag deposit, and
 * legacy-§ text rendering. Subclasses supply the bundle-specific bits via the
 * abstract hooks at the bottom: slot iteration, labels/strings, sort-mode
 * persistence, and the transport ops (which C2S packets to send).
 *
 * <h2>Interactions (identical across subclasses)</h2>
 * <ul>
 *   <li>L-click tile → extract 1 ({@link Protocol#PV_EXTRACT_ONE}).</li>
 *   <li>R-click tile → extract half ({@link Protocol#PV_EXTRACT_HALF}).</li>
 *   <li>Shift+L-click tile → extract a full stack summed across all of the tile's
 *       source slots/vaults into the inventory ({@link Protocol#PV_EXTRACT_STACK});
 *       repeat to pull the next stack.</li>
 *   <li>L-press inventory slot, drag onto grid, release → deposit that slot
 *       via {@link #sendDeposit(int)} (server picks the destination).</li>
 * </ul>
 *
 * <p>The screen is bundle-driven: after every extract / deposit the server
 * pushes a fresh bundle which the subclass routes to its
 * {@code onBundleUpdated} for in-place re-render (server state is
 * authoritative — all optimistic overrides are discarded when it lands).
 */
public abstract class ItemTerminalScreen extends Screen {

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

    /** Sort-mode button on the right of the search row (cycles Qty / A-Z / Type). */
    private static final int SORT_BTN_W = 46;
    private static final int SORT_BTN_H = 18;
    private static final int SORT_BTN_GAP = 4;


    private final List<Entry> entries = new ArrayList<>();
    private int scrollRowOffset = 0;
    private List<Text> hoverTooltip = null;

    /** Draggable right-edge scrollbar. The screen owns {@link #scrollRowOffset}
     *  (in rows); this helper works in pixels, so we feed it row*SLOT_PX units
     *  and divide the result back to a row offset. */
    private final GlassScrollbar scrollbar = new GlassScrollbar();

    /** While Shift is held, tile order is frozen to this snapshot of item keys so
     *  repeated shift-click extracts don't reshuffle tiles under the cursor (a
     *  tile whose count drops would otherwise re-sort away mid-pull). Null = not
     *  frozen (sort by the active mode). Captured on shift press, cleared on
     *  release (and on a sort-mode change). */
    private java.util.List<String> frozenOrder = null;
    private boolean prevShiftDown = false;

    /** Optimistic local override of slot amounts — keyed by (group &lt;&lt; 16) | slot.
     *  Set on extract click so the tile updates instantly without waiting for the
     *  server's bundle refresh. Cleared whenever a fresh bundle arrives (server
     *  state is authoritative once it lands). 0 means "this slot is now empty". */
    private final java.util.Map<Long, Integer> optimisticAmounts = new java.util.HashMap<>();

    private GlassTextField searchField;
    private String searchQuery = "";

    /** When true, the player is shift+left-dragging across inventory slots —
     *  each new slot the cursor enters fires a deposit. */
    private boolean shiftDragging = false;
    /** Slots already shift-dragged-over this drag, so we don't re-fire while
     *  the cursor still sits on the same slot. Reset on mouseRelease. */
    private final java.util.Set<Integer> shiftDragDeposited = new java.util.HashSet<>();

    /** Visible flash on a group after the bundle reflects an op there.
     *  Tracked by start-time ms; expires after FLASH_MS. */
    private final java.util.Map<Integer, Long> recentGroupFlash = new java.util.HashMap<>();
    private static final long FLASH_MS = 380L;

    /** Until-timestamp (ms) for the notice shown after a blocked take/put
     *  attempt while the screen is view-only. */
    private long blockedFlashUntilMs = 0L;

    protected ItemTerminalScreen(Text title) {
        super(title);
    }

    /**
     * Common bundle-refresh tail, called by the subclass's
     * {@code onBundleUpdated} after it swapped in the new bundle: server state
     * is authoritative, so discard all optimistic overrides, rebuild the grid
     * (keeping scroll/search where possible), and flash the changed groups.
     */
    protected final void applyBundleRefresh(java.util.Set<Integer> changedGroups) {
        optimisticAmounts.clear();
        recomputeEntries();
        int maxOffset = Math.max(0, totalRows() - GRID_ROWS);
        if (scrollRowOffset > maxOffset) scrollRowOffset = maxOffset;
        long now = System.currentTimeMillis();
        for (Integer g : changedGroups) recentGroupFlash.put(g, now);
    }

    @Override
    protected void init() {
        int panelW = panelWidth();
        int panelH = panelHeight();
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;

        int searchW = panelW - PANEL_PADDING * 2 - SCROLLBAR_W - SCROLLBAR_GAP - SORT_BTN_W - SORT_BTN_GAP;
        int searchX = panelX + PANEL_PADDING;
        int searchY = panelY + TITLE_BAR_H + 4;
        this.searchField = new GlassTextField(this.textRenderer, searchX, searchY, searchW, 18,
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
        if (autoFocusSearch()) {
            this.setFocused(this.searchField);
            this.searchField.setFocused(true);
        }

        recomputeEntries();
    }

    protected final void recomputeEntries() {
        entries.clear();
        String q = normalizedQuery();
        // Group every matching stackable slot by visible item identity so the
        // same item shows as ONE tile summed across all its source slots; each
        // non-stackable item (gear, pickaxes — maxCount 1) stays its own tile.
        java.util.Map<String, Acc> groups = new java.util.LinkedHashMap<>();
        java.util.List<Acc> accs = new java.util.ArrayList<>();
        forEachVisibleSlot((groupId, s) -> {
            if (!q.isEmpty() && !slotMatches(s, q)) return;
            int displayed = effectiveAmount(groupId, s);
            if (displayed <= 0) return; // optimistically removed
            ItemStack icon = resolveStack(s, 1);
            if (icon.isEmpty()) return; // unresolvable material — skip rather than blank tile
            boolean stackable = icon.getMaxCount() > 1;
            if (stackable) {
                String key = identityKey(s);
                Acc a = groups.get(key);
                if (a == null) { a = new Acc(s, icon); groups.put(key, a); accs.add(a); }
                a.total += displayed;
                a.sources.add(new Source(groupId, s.slotIndex, displayed));
            } else {
                Acc a = new Acc(s, icon);
                a.total += displayed;
                a.sources.add(new Source(groupId, s.slotIndex, displayed));
                accs.add(a);
            }
        });
        for (Acc a : accs) {
            String name = (a.rep.displayName != null && !a.rep.displayName.isEmpty())
                    ? stripColor(a.rep.displayName)
                    : (a.icon.isEmpty() ? a.rep.materialKey : a.icon.getName().getString());
            String category;
            if (a.icon.isEmpty()) {
                category = "~";
            } else {
                Identifier id = Registries.ITEM.getId(a.icon.getItem());
                category = id == null ? "~" : id.toString();
            }
            entries.add(new Entry(a.rep, a.total, a.sources, a.icon,
                    name == null ? "" : name, category));
        }
        sortEntries();
    }

    /** Identity for display-merging: same material + name + lore → one tile.
     *  Mirrors what the server merges (it uses isSimilar; this is the closest
     *  the bundle's no-NBT view can get). */
    private static String identityKey(PvBundlePayload.Slot s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.materialKey == null ? "" : s.materialKey).append('\u0001');
        sb.append(s.displayName == null ? "" : s.displayName).append('\u0001');
        if (s.lore != null) for (String line : s.lore) sb.append(line).append('\u0001');
        return sb.toString();
    }

    /** Sort the aggregated tiles by the persisted sort mode (Quantity / A-Z /
     *  Category). Quantity is the default; all three cycle via the sort button.
     *  While {@link #frozenOrder} is set (Shift held), tiles keep that frozen
     *  position instead, so a shift-click pull never moves the tile. */
    private void sortEntries() {
        java.util.Comparator<Entry> byName =
                java.util.Comparator.comparing((Entry e) -> e.sortName, String.CASE_INSENSITIVE_ORDER);
        if (frozenOrder != null) {
            java.util.Map<String, Integer> idx = new java.util.HashMap<>();
            for (int i = 0; i < frozenOrder.size(); i++) idx.putIfAbsent(frozenOrder.get(i), i);
            // Frozen tiles keep their snapshot position; anything new (e.g. just
            // deposited) falls to the end in name order.
            entries.sort(java.util.Comparator
                    .comparingInt((Entry e) -> idx.getOrDefault(entryKey(e), Integer.MAX_VALUE))
                    .thenComparing(byName));
            return;
        }
        int mode = getSortMode();
        java.util.Comparator<Entry> cmp = switch (mode) {
            case 1 -> byName;
            case 2 -> java.util.Comparator.comparing((Entry e) -> e.category).thenComparing(byName);
            default -> java.util.Comparator.comparingInt((Entry e) -> -e.total).thenComparing(byName);
        };
        entries.sort(cmp);
    }

    /** Stable per-item key for freeze ordering — same identity used for merging. */
    private static String entryKey(Entry e) {
        return identityKey(e.rep);
    }

    /** Snapshot the current on-screen tile order (by item key) for the freeze. */
    private java.util.List<String> captureOrder() {
        java.util.List<String> order = new java.util.ArrayList<>(entries.size());
        for (Entry e : entries) order.add(entryKey(e));
        return order;
    }

    private int effectiveAmount(int groupId, PvBundlePayload.Slot s) {
        long key = (((long) groupId) << 16) | (s.slotIndex & 0xFFFFL);
        Integer override = optimisticAmounts.get(key);
        return override != null ? override : s.amount;
    }

    /** Optimistically remove {@code taken} items from an aggregated tile by
     *  decrementing its source slots in order, so the tile updates instantly
     *  before the server's bundle refresh lands. */
    private void applyOptimisticGroupExtract(Entry e, int taken) {
        int remaining = taken;
        for (Source src : e.sources) {
            if (remaining <= 0) break;
            long key = (((long) src.group) << 16) | (src.slotIndex & 0xFFFFL);
            int current = optimisticAmounts.getOrDefault(key, src.amount);
            int dec = Math.min(current, remaining);
            optimisticAmounts.put(key, current - dec);
            remaining -= dec;
        }
        recomputeEntries();
    }

    /** Abbreviate a stack total for the tile overlay, kept to ≤4 chars so it fits
     *  a slot: 576 → "576", 1234 → "1.2k", 12000 → "12k", 1_200_000 → "1.2M". */
    private static String abbreviate(int n) {
        if (n < 1000) return Integer.toString(n);                          // 0..999
        if (n < 10_000) return trimOneDecimal(n / 1000.0) + "k";          // 1.2k..9.9k
        if (n < 1_000_000) return (n / 1000) + "k";                       // 10k..999k
        if (n < 10_000_000) return trimOneDecimal(n / 1_000_000.0) + "M"; // 1.2M..9.9M
        return (n / 1_000_000) + "M";                                     // 10M+
    }

    private static String trimOneDecimal(double v) {
        long whole = (long) v;
        int dec = (int) Math.floor((v - whole) * 10 + 0.5);
        if (dec >= 10) { whole += 1; dec = 0; }
        return dec == 0 ? Long.toString(whole) : (whole + "." + dec);
    }

    /** Flash the blocked-action notice after a blocked take/put attempt. */
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

    protected static String stripColor(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Render a legacy section-coded string (what the server bundles for an item
     * name / lore line) as a styled {@link Text}, including the BungeeCord
     * §x§r§r§g§g§b§b hex format. The vanilla parser doesn't understand §x, so it
     * collapses a hex run to the last nibble's colour — a #FF0000 name renders
     * black — which is why coloured names looked wrong. Here we expand the run to
     * a true RGB {@link TextColor}. Defensive: a dangling / partial § at the end
     * (e.g. a truncation artifact) is dropped rather than mis-coloured.
     */
    protected static Text legacyText(String s) {
        if (s == null || s.isEmpty()) return Text.empty();
        MutableText root = Text.empty();
        StringBuilder run = new StringBuilder();
        Style style = Style.EMPTY;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < n) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                if (code == 'x' && i + 13 < n) {
                    String hex = readHexRun(s, i);
                    if (hex != null) {
                        flushRun(root, run, style);
                        style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
                        i += 14;
                        continue;
                    }
                }
                Formatting fmt = Formatting.byCode(code);
                if (fmt != null) {
                    flushRun(root, run, style);
                    style = applyCode(style, fmt);
                }
                i += 2; // consume the token (unknown codes are dropped, like vanilla)
                continue;
            }
            run.append(c);
            i++;
        }
        flushRun(root, run, style);
        return root;
    }

    /** Parse a §x§r§r§g§g§b§b run starting at {@code i} into 6 hex digits, or null
     *  if the 14-char shape isn't intact (e.g. a truncated tail). */
    private static String readHexRun(String s, int i) {
        StringBuilder hex = new StringBuilder(6);
        for (int k = 0; k < 6; k++) {
            int p = i + 2 + k * 2;
            if (p + 1 >= s.length() || s.charAt(p) != '§') return null;
            char h = s.charAt(p + 1);
            if (Character.digit(h, 16) < 0) return null;
            hex.append(h);
        }
        return hex.toString();
    }

    private static Style applyCode(Style style, Formatting fmt) {
        if (fmt == Formatting.RESET) return Style.EMPTY;
        if (fmt.isColor()) return Style.EMPTY.withColor(fmt); // a colour resets modifiers (vanilla)
        return switch (fmt) {
            case BOLD -> style.withBold(true);
            case ITALIC -> style.withItalic(true);
            case UNDERLINE -> style.withUnderline(true);
            case STRIKETHROUGH -> style.withStrikethrough(true);
            case OBFUSCATED -> style.withObfuscated(true);
            default -> style;
        };
    }

    private static void flushRun(MutableText root, StringBuilder run, Style style) {
        if (run.length() == 0) return;
        root.append(Text.literal(run.toString()).setStyle(style));
        run.setLength(0);
    }

    private int totalRows() {
        return Math.max(1, (entries.size() + GRID_COLS - 1) / GRID_COLS);
    }

    private int gridContentWidth() { return GRID_COLS * SLOT_PX; }
    private int gridContentHeight() { return GRID_ROWS * SLOT_PX; }

    /** Total scrollable content height in the same pixel units as the scrollbar
     *  viewport ({@code gridContentHeight()}): one row per SLOT_PX. The helper
     *  no-ops when this is &le; the viewport. */
    private int scrollbarContentHeight() { return totalRows() * SLOT_PX; }

    /** Convert the scrollbar helper's pixel scrollY into a clamped row offset and
     *  apply it. The helper already clamps to [0, maxScroll]; we round to the
     *  nearest row and clamp again (belt-and-suspenders). */
    private void applyScrollbarPixels(int pixelScrollY) {
        int maxOffset = Math.max(0, totalRows() - GRID_ROWS);
        int row = (int) Math.round((double) pixelScrollY / SLOT_PX);
        if (row < 0) row = 0;
        if (row > maxOffset) row = maxOffset;
        scrollRowOffset = row;
    }

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

    /** Left X of the sort button — right-aligned with the grid's right edge,
     *  above the scrollbar gap, on the search row. */
    private int sortBtnX() {
        int panelX = (this.width - panelWidth()) / 2;
        return panelX + panelWidth() - PANEL_PADDING - SCROLLBAR_W - SCROLLBAR_GAP - SORT_BTN_W;
    }

    private int sortBtnY() {
        int panelY = (this.height - panelHeight()) / 2;
        return panelY + TITLE_BAR_H + 4;
    }

    private boolean overSortButton(double mx, double my) {
        int bx = sortBtnX();
        int by = sortBtnY();
        return mx >= bx && mx < bx + SORT_BTN_W && my >= by && my < by + SORT_BTN_H;
    }

    private static String sortLabel(int mode) {
        return switch (mode) {
            case 1 -> "A-Z";
            case 2 -> "Type";
            default -> "Qty";
        };
    }

    private static String sortLabelFull(int mode) {
        return switch (mode) {
            case 1 -> "Alphabetical (A-Z)";
            case 2 -> "Category (by type)";
            default -> "Quantity (most first)";
        };
    }

    /** Draw the sort button + set its hover tooltip. Drawn each frame in render(). */
    private void renderSortButton(DrawContext ctx, int mouseX, int mouseY) {
        int bx = sortBtnX();
        int by = sortBtnY();
        boolean hover = overSortButton(mouseX, mouseY);
        GlassRender.button(ctx, bx, by, bx + SORT_BTN_W, by + SORT_BTN_H, hover, true, false);
        int mode = getSortMode();
        String label = sortLabel(mode);
        int tw = this.textRenderer.getWidth(label);
        ctx.drawText(this.textRenderer, Text.literal(label),
                bx + (SORT_BTN_W - tw) / 2,
                by + (SORT_BTN_H - this.textRenderer.fontHeight) / 2 + 1,
                GlassTheme.text(), false);
        if (hover) {
            hoverTooltip = java.util.List.of(
                    Text.literal("§7Sort: §f" + sortLabelFull(mode)),
                    Text.literal("§8Click to change"));
        }
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

        // First shift-click of a hold: freeze the order from BEFORE this click's
        // extract (render's edge-detect may not have run yet if the click landed
        // in the same frame as the keypress) so even the first pull doesn't move.
        if (isShiftDown() && frozenOrder == null) {
            frozenOrder = captureOrder();
        }

        // ── Draggable scrollbar — grab the thumb (drag) or click the track (jump).
        // Checked before any grid/slot/sort hit-testing; the helper's hit zone is
        // confined to the bar's x, so a grid click never lands here. Left-click only.
        if (button == 0 && scrollbar.mousePressed(mx, my)) {
            defocusSearch();
            applyScrollbarPixels(scrollbar.scrollFor(my, scrollbarContentHeight()));
            return true;
        }

        boolean holdingCursor = !cursorStack().isEmpty();
        int invSlot = invSlotUnderCursor(mx, my);
        int entryIdx = entryUnderCursor(mx, my);

        // ── Sort button (cycles Quantity → A-Z → Category) ──
        if (button == 0 && overSortButton(mx, my)) {
            defocusSearch();
            cycleSortMode();
            frozenOrder = null; // a manual sort change always re-sorts, even if Shift is down
            recomputeEntries();
            scrollRowOffset = 0;
            return true;
        }

        // ── Inventory slot clicks (vanilla-like) ──
        if (invSlot >= 0) {
            defocusSearch();
            // Shift+L → deposit (bulk), and arm a shift-drag so dragging
            // across more slots deposits them too. Armed even when the starting
            // slot is empty (vanilla shift-drag begins on any slot).
            if (button == 0 && isShiftDown() && !holdingCursor) {
                // View-only: don't deposit (and don't arm the shift-drag),
                // just flash the notice. Server blocks it too.
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
                    sendDeposit(invSlot);
                }
                return true;
            }
            // Bare L → vanilla slot click: pick up if cursor empty, place/
            // swap/merge if holding. Predicted locally, server reconciles.
            if (button == 0) {
                predictInvSlotClick(invSlot);
                sendCursorPlaceInv(invSlot);
                return true;
            }
            return super.mouseClicked(click, doubleClick);
        }

        // ── Terminal tile clicks ──
        if (entryIdx >= 0) {
            defocusSearch();
            if (holdingCursor) {
                // Holding a stack → click a tile to stash it back.
                // Predict the cursor clearing; the server routes the stack to
                // its return destination.
                setClientCursor(ItemStack.EMPTY);
                sendCursorReturn();
                return true;
            }
            Entry e = entries.get(entryIdx);
            // View-only: block all extract modes (the holding-cursor return
            // path above is harmless and stays). Server rejects + re-syncs
            // too, but blocking here avoids the tile flicker.
            if (!canModify()) {
                flashBlocked();
                return true;
            }
            // A tile may aggregate the same item across several source slots/vaults.
            // The cursor pulls (L / R) act on ONE source stack (the first), so their
            // optimistic amount is exact (we know that stack's size) with no overshoot
            // when the server's view differs (e.g. unique-id boosters). Shift+L instead
            // pulls a full stack summed across ALL sources in one atomic server op, so
            // a tile spread over multiple vaults extracts the whole stack — not just
            // the first source — the way a vanilla shift-click would.
            Source ref = e.sources.get(0);
            int srcAmt = ref.amount;
            int maxStack = Math.max(1, e.icon.getMaxCount());
            if (button == 1) {
                // Right-click → half of that source stack onto the cursor.
                int take = Math.min(maxStack, Math.max(1, (srcAmt + 1) / 2));
                applyOptimisticGroupExtract(e, take);
                predictPickupToCursor(e.rep, take);
                sendExtract(ref, Protocol.PV_EXTRACT_HALF, Protocol.PV_TARGET_CURSOR);
            } else if (button == 0 && isShiftDown()) {
                // Shift+left → a full stack of this item summed across EVERY source
                // slot/vault, into the inventory (repeat to pull the next stack). The
                // aggregated server op drains matching stacks in order, so a tile
                // split over multiple vaults comes out whole instead of yielding only
                // the first source stack.
                int take = Math.min(maxStack, e.total);
                applyOptimisticGroupExtract(e, take);
                sendExtractItem(ref, Protocol.PV_EXTRACT_STACK, Protocol.PV_TARGET_INV);
            } else if (button == 0) {
                // Left-click → one onto the cursor.
                applyOptimisticGroupExtract(e, 1);
                predictPickupToCursor(e.rep, 1);
                sendExtract(ref, Protocol.PV_EXTRACT_ONE, Protocol.PV_TARGET_CURSOR);
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
    protected static ItemStack cursorStack() {
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
        // Scrollbar drag wins over everything else while the thumb is grabbed.
        if (scrollbar.isDragging()) {
            applyScrollbarPixels(scrollbar.scrollFor(click.y(), scrollbarContentHeight()));
            return true;
        }
        // Shift+drag across inventory slots → deposit each new slot.
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
                        sendDeposit(invSlot);
                    }
                }
                return true;
            }
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (scrollbar.isDragging()) {
            scrollbar.release();
            return true;
        }
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

        GlassRender.menuBackdrop(ctx, this.width, this.height);
        GlassRender.panel(ctx, panelX, panelY, panelW, panelH);

        // Violet title-bar wash (inset by the panel radius so it sits inside the rim).
        int r = GlassRender.RADIUS;
        ctx.fill(panelX + r, panelY + r, panelX + panelW - r, panelY + TITLE_BAR_H,
                GlassTheme.withAlpha(GlassTheme.ACCENT, 0x2E));
        int totalOccupied = occupiedSlots();
        int totalCapacity = capacitySlots();
        int pct = totalCapacity > 0 ? (totalOccupied * 100 / totalCapacity) : 0;
        String pctColor = pct >= 90 ? "§c" : pct >= 75 ? "§e" : "§7";
        ctx.drawText(this.textRenderer,
                titleText(" §8· §7" + entries.size() + " items §8· " + pctColor + pct + "%"),
                panelX + 10, panelY + 8, 0xFFFFFFFF, true);
        if (!canModify()) {
            // Persistent view-only indicator — browsing/searching always works,
            // taking/depositing is gated.
            String warn = viewOnlyBadge();
            int warnW = this.textRenderer.getWidth(warn);
            ctx.drawText(this.textRenderer, Text.literal(warn),
                    panelX + panelW - warnW - 10, panelY + 8, GlassTheme.WARN, false);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        hoverTooltip = null;
        long now = System.currentTimeMillis();

        // Freeze tile order while Shift is held so repeated shift-click extracts
        // don't reshuffle the grid under the cursor; restore the active sort the
        // moment Shift is released.
        boolean shiftNow = isShiftDown();
        if (shiftNow && !prevShiftDown) {
            if (frozenOrder == null) frozenOrder = captureOrder();
        } else if (!shiftNow && prevShiftDown) {
            frozenOrder = null;
            recomputeEntries();
        }
        prevShiftDown = shiftNow;

        int gx = gridX();
        int gy = gridY();
        int gridW = gridContentWidth();
        int gridH = gridContentHeight();

        // Grid background — frosted glass slots.
        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                int sx = gx + c * SLOT_PX;
                int sy = gy + r * SLOT_PX;
                GlassRender.slot(ctx, sx + 1, sy + 1, sx + SLOT_PX - 1, sy + SLOT_PX - 1);
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

            ItemStack stack = e.icon;
            if (stack.isEmpty()) continue;

            int io = (SLOT_PX - 16) / 2; // centre the 16px icon in the 22px cell
            ctx.drawItem(stack, sx + io, sy + io);
            // Custom count overlay: the aggregated total, abbreviated, so merged
            // tiles read "576" / "12k" instead of a single stack's count.
            if (e.total > 1) {
                drawCountOverlay(ctx, e.total, sx + io, sy + io);
            }

            // Post-op flash if any of this tile's source groups just changed.
            Long flashAt = null;
            for (Source src : e.sources) {
                Long f = recentGroupFlash.get(src.group);
                if (f != null) { flashAt = f; break; }
            }
            if (flashAt != null && now - flashAt < FLASH_MS) {
                float t = 1f - ((now - flashAt) / (float) FLASH_MS);
                int alpha = (int) (Math.max(0, Math.min(1, t)) * 100);
                int color = GlassTheme.withAlpha(GlassTheme.ACCENT, alpha);
                ctx.fill(sx + 1, sy + 1, sx + SLOT_PX - 1, sy + SLOT_PX - 1, color);
            }

            // Hover highlight + tooltip (item name + bundled lore + aggregate info)
            if (mouseX >= sx && mouseX < sx + SLOT_PX && mouseY >= sy && mouseY < sy + SLOT_PX) {
                ctx.fill(sx + 1, sy + 1, sx + SLOT_PX - 1, sy + SLOT_PX - 1, 0x66FFFFFF);
                hoverTooltip = buildItemTooltip(e, stack);
            }
        }

        // Empty / no-match message
        if (entries.isEmpty()) {
            String msg = normalizedQuery().isEmpty()
                    ? emptyMessage()
                    : "§7No items match §f\"" + searchQuery + "\"";
            int msgW = this.textRenderer.getWidth(msg);
            ctx.drawText(this.textRenderer, Text.literal(msg),
                    gx + (gridW - msgW) / 2, gy + gridH / 2 - 4, GlassTheme.textMuted(), false);
        }

        // Scrollbar (draggable — see scrollbarContentHeight()). Content/scroll are
        // expressed in pixels (rows * SLOT_PX) so the pixel-based helper matches the
        // grid's row-based scrolling; it no-ops when everything fits.
        int sbX = gx + gridW + SCROLLBAR_GAP;
        int sbY = gy;
        int sbH = gridH;
        scrollbar.render(ctx, sbX, sbY, sbY + sbH, scrollbarContentHeight(), scrollRowOffset * SLOT_PX);

        // Blocked-action notice — shown briefly after a take/put attempt while
        // view-only. Drawn over the grid, under the cursor.
        if (now < blockedFlashUntilMs) {
            String msg = blockedMessage();
            int msgW = this.textRenderer.getWidth(msg);
            int bx = gx + (gridW - msgW) / 2;
            int by = gy + gridH / 2 - 4;
            GlassRender.roundedRect(ctx, bx - 6, by - 5, bx + msgW + 6, by + 13, 5,
                    GlassTheme.withAlpha(GlassTheme.WARN, 0xC0));
            GlassRender.roundedBorder(ctx, bx - 6, by - 5, bx + msgW + 6, by + 13, 5,
                    GlassTheme.withAlpha(GlassTheme.WARN, 0xFF));
            ctx.drawText(this.textRenderer, Text.literal(msg), bx, by, GlassTheme.text(), true);
        }

        // Sort-mode button (search row, right side).
        renderSortButton(ctx, mouseX, mouseY);

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
     *  display-name as the title, then each lore line, then an aggregate footer
     *  (total + stack breakdown + which sources it spans). */
    private List<Text> buildItemTooltip(Entry e, ItemStack stack) {
        List<Text> lines = new ArrayList<>();
        String name = (e.rep.displayName != null && !e.rep.displayName.isEmpty())
                ? e.rep.displayName
                : stack.getName().getString();
        lines.add(legacyText(name));
        if (e.rep.lore != null) {
            for (String loreLine : e.rep.lore) lines.add(legacyText(loreLine));
        }
        int max = Math.max(1, stack.getMaxCount());
        if (e.total > max) {
            int full = e.total / max;
            int rem = e.total % max;
            String breakdown = rem > 0 ? (full + "x" + max + " + " + rem) : (full + "x" + max);
            lines.add(Text.literal("§7" + e.total + " total §8(" + breakdown + ")"));
        } else {
            lines.add(Text.literal("§8x" + e.total));
        }
        lines.add(Text.literal("§8" + sourcesLabel(e)));
        return lines;
    }

    /** Draw the abbreviated aggregated total at the bottom-right of a 16px icon
     *  at (iconX, iconY), right-aligned with a drop shadow — mirrors the vanilla
     *  count position but supports totals far beyond one stack. */
    private void drawCountOverlay(DrawContext ctx, int total, int iconX, int iconY) {
        String label = abbreviate(total);
        int w = this.textRenderer.getWidth(label);
        int tx = iconX + 16 - w;
        int ty = iconY + 16 - this.textRenderer.fontHeight + 1;
        ctx.drawText(this.textRenderer, Text.literal(label), tx, ty, 0xFFFFFFFF, true);
    }

    private void renderPlayerInventory(DrawContext ctx, int mouseX, int mouseY) {
        int ix = invX();
        int iy = invY();
        int panelX = (this.width - panelWidth()) / 2;

        ctx.drawText(this.textRenderer, Text.literal("Your inventory:"),
                panelX + 10, iy - 9, GlassTheme.textMuted(), false);

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
        GlassRender.slot(ctx, sx, sy, sx + INV_SLOT_PX, sy + INV_SLOT_PX);

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
                        ? Text.literal(depositHintModifiable())
                        : Text.literal(depositHintViewOnly()));
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
        // back to its return destination. ALWAYS first, before the close-out
        // packet, so the server still has the session to return into.
        if (!cursorStack().isEmpty()) {
            sendCursorReturn();
        }
        onClosed();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ── Abstract hooks — supplied by PvTerminalScreen / CellTerminalScreen ───

    /** Receives one (groupId, slot) pair per visible non-empty source slot. */
    @FunctionalInterface
    protected interface SlotVisitor {
        void accept(int groupId, PvBundlePayload.Slot slot);
    }

    /** Feed every accessible non-empty slot of the current bundle to the
     *  visitor, tagged with its group id (PV number / cell container id). */
    protected abstract void forEachVisibleSlot(SlotVisitor visitor);

    /** Whether the player may currently take/put (server-authoritative; the
     *  server enforces the same gate regardless of what the client sends). */
    protected abstract boolean canModify();

    /** Total non-empty slots across all accessible groups (title-bar % fill). */
    protected abstract int occupiedSlots();

    /** Total slot capacity across all accessible groups (title-bar % fill). */
    protected abstract int capacitySlots();

    /** The title-bar text. {@code statsSuffix} is the pre-built
     *  " · N items · pct%" tail (legacy § coded) to append. */
    protected abstract Text titleText(String statsSuffix);

    /** Persistent top-right view-only badge (legacy § coded). */
    protected abstract String viewOnlyBadge();

    /** Flash notice after a blocked take/put attempt (legacy § coded). */
    protected abstract String blockedMessage();

    /** Centered grid message when there are no entries and no query. */
    protected abstract String emptyMessage();

    /** Inventory-slot tooltip hint while deposits are allowed. */
    protected abstract String depositHintModifiable();

    /** Inventory-slot tooltip hint while view-only. */
    protected abstract String depositHintViewOnly();

    /** Tooltip footer naming the groups a tile's stacks live in
     *  (PV: "PVs 1, 3" — cellterm: "Vault, Chest 2"). */
    protected abstract String sourcesLabel(Entry e);

    /** Current persisted sort mode (0 = Qty, 1 = A-Z, 2 = Type). */
    protected abstract int getSortMode();

    /** Advance to the next sort mode (persisted) and return it. */
    protected abstract int cycleSortMode();

    /** Whether the search box should grab focus when the screen opens. */
    protected abstract boolean autoFocusSearch();

    /** Transport: extract from the ref's (group, slot) with PV-shared
     *  mode/target bytes ({@link Protocol#PV_EXTRACT_ONE} etc.). */
    protected abstract void sendExtract(Source ref, byte mode, byte target);

    /** Transport: aggregated extract-this-item-everywhere; the ref only names
     *  which item to match. Reserved for STACK-mode pulls — not yet wired to a
     *  click in the shared layout (kept so both transports stay in lock-step). */
    protected abstract void sendExtractItem(Source ref, byte mode, byte target);

    /** Transport: deposit the given player-inventory slot (Bukkit 0..35). */
    protected abstract void sendDeposit(int playerInvSlot);

    /** Transport: vanilla-semantics cursor ↔ player-inv slot click. */
    protected abstract void sendCursorPlaceInv(int playerInvSlot);

    /** Transport: return the cursor stack to its origin store. */
    protected abstract void sendCursorReturn();

    /** Close-out hook, invoked by {@link #close()} AFTER the cursor-return (if
     *  any) and BEFORE {@code super.close()}: send the session close-out
     *  packet(s) and notify the owning client state machine. */
    protected abstract void onClosed();

    // ── Shared data shapes ───────────────────────────────────────────────────

    /** One source stack feeding an aggregated tile: which group + slot it
     *  lives in and how much of it there is. */
    protected static final class Source {
        public final int group;
        public final int slotIndex;
        public final int amount;

        Source(int group, int slotIndex, int amount) {
            this.group = group;
            this.slotIndex = slotIndex;
            this.amount = amount;
        }
    }

    /** Mutable accumulator used while grouping bundle slots into tiles. */
    private static final class Acc {
        final PvBundlePayload.Slot rep;        // representative slot (icon / name / lore)
        final ItemStack icon;                  // resolved once (count 1) for render + sort
        int total = 0;                         // summed displayed amount across sources
        final java.util.List<Source> sources = new java.util.ArrayList<>();

        Acc(PvBundlePayload.Slot rep, ItemStack icon) {
            this.rep = rep;
            this.icon = icon;
        }
    }

    /** A single visible tile: one item, aggregated across every source slot
     *  that holds it. {@code total} is the summed amount (may exceed a vanilla
     *  stack); {@code sources} are the backing slots, ascending, used to drive
     *  extraction and the optimistic decrement. */
    protected static final class Entry {
        public final PvBundlePayload.Slot rep;
        public final int total;
        public final java.util.List<Source> sources;
        public final ItemStack icon;       // pre-resolved icon stack (count 1)
        public final String sortName;      // display name for A-Z / tiebreak sort
        public final String category;      // registry id for category sort

        Entry(PvBundlePayload.Slot rep, int total, java.util.List<Source> sources,
              ItemStack icon, String sortName, String category) {
            this.rep = rep;
            this.total = total;
            this.sources = sources;
            this.icon = icon;
            this.sortName = sortName;
            this.category = category;
        }
    }
}
