package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.client.hud.JewelLoadoutState;
import com.aleks.ancientsmod.client.hud.JewelState;
import com.aleks.ancientsmod.mixin.client.HandledScreenAccessor;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.JewelLoadoutsPayload;
import com.aleks.ancientsmod.net.payload.JewelSlotsPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The three jewel sockets drawn beside the survival inventory, behaving like
 * vanilla equipment slots: pick a jewel up onto the cursor, click a socket to
 * put it in; click a filled socket to take it back out. Dragging a jewel onto
 * a socket and letting go there is the same request as a click on it.
 *
 * <p>Slot chrome is blitted straight out of the pack's own
 * {@code gui/container/inventory.png} (the first storage cell at 7,83), so the
 * sockets restyle themselves with whatever resource pack is loaded instead of
 * being hand-drawn rectangles that only match vanilla.
 *
 * <p>Purely a view + an intent: every click is a request the server re-validates
 * (it reads the cursor itself and re-checks the combat/zone locks), so nothing
 * here can socket a jewel the player isn't really holding.
 */
public final class JewelSockets {

    private static final Identifier INVENTORY_TEXTURE =
            Identifier.ofVanilla("textures/gui/container/inventory.png");

    /** Source rect of one empty storage cell (with its bevel) in inventory.png. */
    private static final int SRC_U = 7, SRC_V = 83, CELL = 18;

    /** Gap between the inventory panel and the socket frame. */
    private static final int PANEL_GAP = 3;
    /** Cells sit flush like the vanilla armour column — a gap reads as floating. */
    private static final int SLOT_GAP = 0;
    /** Panel border drawn around the cell column. */
    private static final int FRAME = 4;
    /** Height of one loadout tab. Shorter than a cell — a tab is a label, not
     *  a slot, and three of them should not out-measure the sockets. */
    private static final int TAB_H = 12;
    /** Breathing room between the tab stack and the sockets below it. */
    private static final int TAB_DIVIDER = 3;
    /** Inset from the cell's top-left to its 16x16 content area. */
    private static final int CONTENT_INSET = 1;

    // Vanilla GUI panel palette — the same three tones the inventory frame uses.
    private static final int PANEL_FILL   = 0xFFC6C6C6;
    private static final int PANEL_LIGHT  = 0xFFFFFFFF;
    private static final int PANEL_SHADOW = 0xFF555555;

    private static final int[] RARITY_COLORS = {
            0xAAAAAA, 0x55FF55, 0x5555FF, 0xFFFF55,
            0xFFAA00, 0xFF5555, 0x55FFFF, 0xFFFFFF,
    };
    private static final String[] RARITY_NAMES = {
            "Simple", "Uncommon", "Elite", "Ultimate",
            "Legendary", "Godly", "Divine", "Exceptional",
    };
    private static final String[] RARITY_IDS = {
            "simple", "uncommon", "elite", "ultimate",
            "legendary", "godly", "divine", "exceptional",
    };

    // Tab palette. The active tab borrows the vanilla "selected" look — a
    // bright fill against the panel — so which loadout is live is readable at
    // a glance rather than needing the tooltip.
    private static final int TAB_ACTIVE_FILL   = 0xFF3C6E47;
    private static final int TAB_ACTIVE_TEXT   = 0xFFFFFFFF;
    private static final int TAB_IDLE_FILL     = 0xFF3A3A3A;
    private static final int TAB_IDLE_TEXT     = 0xFFBBBBBB;
    private static final int TAB_LOCKED_FILL   = 0xFF262626;
    private static final int TAB_LOCKED_TEXT   = 0xFF6A6A6A;
    private static final int TAB_EDGE          = 0xFF1A1A1A;

    private static final java.util.Map<String, ItemStack> GEM_CACHE = new java.util.HashMap<>();

    /**
     * True while a press we swallowed is still waiting for its release.
     *
     * <p>HandledScreen puts the "clicked outside the inventory panel" drop in
     * {@code mouseReleased}, not {@code mouseClicked} — so eating only the
     * press still lets the release throw the cursor stack on the floor, and
     * the resulting {@code slot -999} desync makes the client replay the
     * held-item equip animation (which reads as the hand swinging). The
     * gesture has to be swallowed end to end.
     */
    private static boolean pressSwallowed;

    private JewelSockets() {}

    /** Drops any half-finished gesture. Called whenever a screen opens. */
    public static void resetGesture() {
        pressSwallowed = false;
    }

    public static boolean enabled() {
        return ServerAllowlist.isAllowed()
                && FeatureToggles.isJewelSocketsEnabled()
                && !JewelState.isEmpty();
    }

    /**
     * Column origin: the RIGHT of the inventory panel, matching the side the
     * in-game HUD row sits on. Having the sockets jump sides between the HUD
     * and the inventory made them read as two unrelated things.
     *
     * <p>The cost is that the screen stacks its status-effect widgets down the
     * right too, so with several effects running an icon can reach the column.
     * The column is bottom-aligned and effects stack from the top, which keeps
     * them apart for the first few. Falls back to the left only when the right
     * would run off-screen. Every caller goes through this, so hit-testing and
     * rendering can't disagree about which side it's on.
     */
    private static int columnX(HandledScreenAccessor panel) {
        int panelX = panel.ancientsmod$panelX();
        int right = panelX + panel.ancientsmod$panelWidth() + PANEL_GAP + FRAME;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null
                || right + CELL + FRAME <= mc.getWindow().getScaledWidth()) {
            return right;
        }
        return Math.max(FRAME, panelX - PANEL_GAP - CELL - FRAME);
    }

    /** Loadout tabs currently drawn. Zero on a server without the feature. */
    private static List<JewelLoadoutsPayload.Page> tabs() {
        if (!ServerAllowlist.isAllowed() || !FeatureToggles.isJewelSocketsEnabled()) return List.of();
        return JewelLoadoutState.pages();
    }

    private static int tabsHeight(int count) {
        return count <= 0 ? 0 : count * TAB_H + TAB_DIVIDER;
    }

    /** Height of everything inside the frame: the tab stack over the sockets. */
    private static int contentHeight(HandledScreenAccessor panel) {
        return tabsHeight(tabs().size()) + slotsHeight(JewelState.slots().size());
    }

    /**
     * Bottom-aligned with the inventory panel: the frame's lower edge lines up
     * with the panel's, so the column reads as anchored to the screen rather
     * than floating alongside its top. The tab stack grows UPWARD from there,
     * which keeps the sockets themselves in the same place whether or not a
     * player has loadouts.
     */
    private static int contentY(HandledScreenAccessor panel) {
        int bottom = panel.ancientsmod$panelY() + panel.ancientsmod$panelHeight();
        return bottom - FRAME - contentHeight(panel);
    }

    /** Top of the socket cells — below the tab stack, if there is one. */
    private static int columnY(HandledScreenAccessor panel) {
        return contentY(panel) + tabsHeight(tabs().size());
    }

    private static int slotY(HandledScreenAccessor panel, int index) {
        return columnY(panel) + index * (CELL + SLOT_GAP);
    }

    private static int tabY(HandledScreenAccessor panel, int index) {
        return contentY(panel) + index * TAB_H;
    }

    /** True when the pointer is anywhere on the widget, frame border included. */
    public static boolean contains(HandledScreenAccessor panel, double mouseX, double mouseY) {
        if (!enabled()) return false;
        int x = columnX(panel);
        int y = contentY(panel);
        int h = contentHeight(panel);
        return mouseX >= x - FRAME && mouseX < x + CELL + FRAME
                && mouseY >= y - FRAME && mouseY < y + h + FRAME;
    }

    /** Slot index under the mouse, or -1. */
    public static int slotAt(HandledScreenAccessor panel, double mouseX, double mouseY) {
        if (!enabled()) return -1;
        int x = columnX(panel);
        if (mouseX < x || mouseX >= x + CELL) return -1;
        List<JewelSlotsPayload.Slot> slots = JewelState.slots();
        for (int i = 0; i < slots.size(); i++) {
            int y = slotY(panel, i);
            if (mouseY >= y && mouseY < y + CELL) return i;
        }
        return -1;
    }

    /** Loadout tab index under the mouse, or -1. */
    public static int tabAt(HandledScreenAccessor panel, double mouseX, double mouseY) {
        if (!enabled()) return -1;
        int x = columnX(panel);
        if (mouseX < x || mouseX >= x + CELL) return -1;
        List<JewelLoadoutsPayload.Page> pages = tabs();
        for (int i = 0; i < pages.size(); i++) {
            int y = tabY(panel, i);
            if (mouseY >= y && mouseY < y + TAB_H) return i;
        }
        return -1;
    }

    /**
     * Draws the socket column. Hooked to {@code afterBackground}, NOT
     * {@code afterRender}: the screen paints the cursor stack at the very end
     * of its own render, so drawing after that puts the sockets on top of a
     * dragged item. Nothing else draws in this strip, so being early costs
     * nothing and the held item correctly floats above.
     */
    public static void render(DrawContext ctx, HandledScreenAccessor panel,
                              int mouseX, int mouseY) {
        if (!enabled()) return;
        List<JewelSlotsPayload.Slot> slots = JewelState.slots();
        int x = columnX(panel);
        drawFrame(ctx, x, contentY(panel), CELL, contentHeight(panel));
        drawTabs(ctx, panel, x, mouseX, mouseY);

        for (int i = 0; i < slots.size(); i++) {
            JewelSlotsPayload.Slot slot = slots.get(i);
            int y = slotY(panel, i);

            // Pack-accurate empty cell, sampled from the loaded inventory texture.
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, INVENTORY_TEXTURE,
                    x, y, SRC_U, SRC_V, CELL, CELL, 256, 256);

            int cx = x + CONTENT_INSET;
            int cy = y + CONTENT_INSET;
            if (slot.isLocked()) {
                // Dim the cell so it reads as unavailable, then stamp a padlock.
                ctx.fill(cx, cy, cx + 16, cy + 16, 0x99101014);
                drawPadlock(ctx, cx + 5, cy + 4);
            } else if (slot.isFilled()) {
                ctx.drawItem(gemFor(slot), cx, cy);
            }

            // Vanilla-style hover highlight.
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                ctx.fill(cx, cy, cx + 16, cy + 16, 0x80FFFFFF);
            }
        }
    }

    /** Tooltip for the hovered socket. Call after render, before the screen's own tooltip. */
    public static void renderTooltip(DrawContext ctx, HandledScreenAccessor panel,
                                     int mouseX, int mouseY) {
        int tab = tabAt(panel, mouseX, mouseY);
        if (tab >= 0) {
            renderTabTooltip(ctx, tab, mouseX, mouseY);
            return;
        }
        int index = slotAt(panel, mouseX, mouseY);
        if (index < 0) return;
        List<JewelSlotsPayload.Slot> slots = JewelState.slots();
        if (index >= slots.size()) return;
        JewelSlotsPayload.Slot slot = slots.get(index);

        List<Text> lines = new ArrayList<>();
        if (slot.isLocked()) {
            lines.add(Text.literal("Locked Jewel Socket").formatted(Formatting.RED));
            lines.add(Text.literal("Unlocks at Prestige " + slot.requiredPrestige())
                    .formatted(Formatting.GRAY));
        } else if (slot.isFilled()) {
            // Server-authored name. Rebuilding it here from rarity + type named
            // a unique "Divine Aetheric Jewel"; the fallback stays only for an
            // older server that sends no name.
            if (!slot.displayName().isEmpty()) {
                lines.add(LegacyText.parse(slot.displayName()));
            } else {
                int ord = clampOrdinal(slot.rarityOrdinal());
                lines.add(Text.literal(RARITY_NAMES[ord] + " " + slot.familyName() + " Jewel")
                        .withColor(RARITY_COLORS[ord]));
            }
            // Server-authored lore, colours intact — the tier badge is
            // colour-coded, so repainting the line one flat green loses it.
            for (String stat : slot.statLines()) {
                lines.add(LegacyText.parse(stat));
            }
            if (!slot.loreLines().isEmpty()) {
                lines.add(Text.empty());
                for (String lore : slot.loreLines()) {
                    lines.add(LegacyText.parse(lore));
                }
            }
            lines.add(Text.empty());
            lines.add(Text.literal("Click to unsocket").formatted(Formatting.YELLOW));
        } else {
            lines.add(Text.literal("Empty Jewel Socket").formatted(Formatting.WHITE));
            lines.add(Text.literal("Hold a jewel and click to socket it")
                    .formatted(Formatting.GRAY));
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return;
        // drawTooltip() DEFERS to a pass the screen already flushed by the time
        // afterRender fires, so it silently never appears. Drawing outside a
        // screen's own render has to go through drawTooltipImmediately — same
        // route InteractiveItemTooltip uses.
        List<net.minecraft.client.gui.tooltip.TooltipComponent> comps = new ArrayList<>(lines.size());
        for (Text line : lines) {
            comps.add(net.minecraft.client.gui.tooltip.TooltipComponent.of(line.asOrderedText()));
        }
        ctx.drawTooltipImmediately(mc.textRenderer, comps, mouseX, mouseY,
                net.minecraft.client.gui.tooltip.HoveredTooltipPositioner.INSTANCE, null);
    }

    /**
     * Handles a press in the socket column. Returns true when the press was
     * ours (so the screen shouldn't also treat it as a normal click).
     *
     * <p>The whole widget is claimed, frame border included and whatever the
     * cursor happens to be holding: to the screen underneath this strip is
     * "outside the inventory", so any press that gets through is a request to
     * bin the cursor stack. A click that can't do anything has to end up doing
     * nothing rather than falling through.
     */
    public static boolean onClick(HandledScreenAccessor panel,
                                  double mouseX, double mouseY, boolean shift) {
        if (!contains(panel, mouseX, mouseY)) return false;
        pressSwallowed = true;

        // A tab press swaps the whole set. Locked pages are refused here so a
        // click that can do nothing costs no packet; the server checks anyway.
        int tab = tabAt(panel, mouseX, mouseY);
        if (tab >= 0) {
            List<JewelLoadoutsPayload.Page> pages = tabs();
            if (tab < pages.size() && pages.get(tab).unlocked()
                    && tab != JewelLoadoutState.activePage()) {
                NetworkHandler.sendJewelLoadoutSwitch(tab);
            }
            return true;
        }

        int index = usableSlotAt(panel, mouseX, mouseY);
        if (index < 0) return true;   // on the frame, or a locked cell the server would refuse

        // Plain click = vanilla slot semantics (take to cursor / put in / swap),
        // resolved server-side — it re-reads the cursor and refuses anything
        // that isn't a jewel. Shift-click sends it straight to the inventory.
        NetworkHandler.sendJewelSocketRequest(
                shift ? Protocol.JEWEL_OP_UNSOCKET : Protocol.JEWEL_OP_SOCKET_CURSOR, index);
        return true;
    }

    /**
     * Whether the screen should be kept out of this release. Pairs with the
     * swallowed press wherever the pointer ended up — dragging off the widget
     * mid-click must not turn into a drop either.
     *
     * <p>A release that LANDS here after starting somewhere else is a drag and
     * drop onto the socket, and is answered with the same request a click
     * sends: the press already lifted the stack onto the cursor, which is what
     * the server reads. Swallowing it silently instead made a dropped jewel
     * look like it had been eaten.
     */
    public static boolean onRelease(HandledScreenAccessor panel,
                                    double mouseX, double mouseY) {
        boolean ourPress = pressSwallowed;
        pressSwallowed = false;
        if (!ourPress && !contains(panel, mouseX, mouseY)) return false;

        // HandledScreen#mouseReleased is the ONLY place the quick-craft drag it
        // starts on press gets cleared, and a swallowed release never reaches
        // it. Left set, cursorDragging swallows every later click and the stale
        // cursorDragSlots get distributed into on the next release — the item
        // scatters into slots it was only dragged ACROSS, which reads as loss.
        panel.ancientsmod$setCursorDragging(false);
        panel.ancientsmod$cursorDragSlots().clear();

        if (!ourPress) dropOnSocket(panel, mouseX, mouseY);
        return true;
    }

    /** Socket whatever the cursor is carrying into the cell under the pointer. */
    private static void dropOnSocket(HandledScreenAccessor panel, double mouseX, double mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null
                || mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            return;   // empty-handed release: nothing to put in
        }
        int index = usableSlotAt(panel, mouseX, mouseY);
        if (index >= 0) {
            NetworkHandler.sendJewelSocketRequest(Protocol.JEWEL_OP_SOCKET_CURSOR, index);
        }
    }

    /** Index of the cell under the pointer that can actually take a click, or -1. */
    private static int usableSlotAt(HandledScreenAccessor panel, double mouseX, double mouseY) {
        if (tabAt(panel, mouseX, mouseY) >= 0) return -1;   // that is a tab, not a cell
        int index = slotAt(panel, mouseX, mouseY);
        if (index < 0) return -1;
        List<JewelSlotsPayload.Slot> slots = JewelState.slots();
        if (index >= slots.size() || slots.get(index).isLocked()) return -1;
        return index;
    }

    /** Drags belonging to a swallowed press — keeps quick-craft from starting. */
    public static boolean onDrag() {
        return pressSwallowed;
    }

    private static int clampOrdinal(int ordinal) {
        return (ordinal < 0 || ordinal >= RARITY_IDS.length) ? 0 : ordinal;
    }

    /**
     * The gem icon for a socket. The server sends the item-model path, so this
     * is the same texture the real item wears.
     *
     * <p>Deriving it here from rarity + type is the fallback, not the rule: that
     * only works for the two rolled types, and it silently gave every unique the
     * generic type-less gem, because "Aetheric" matches neither branch. Falling
     * back keeps an older server rendering something sensible.
     */
    public static ItemStack gemFor(JewelSlotsPayload.Slot slot) {
        return gemFor(slot.rarityOrdinal(), slot.familyName(), slot.modelPath());
    }

    private static ItemStack gemFor(int ordinal, String typeName, String modelPath) {
        String model = modelPath == null ? "" : modelPath.trim();
        if (model.isEmpty()) {
            int idx = clampOrdinal(ordinal);
            String type = typeName == null ? "" : typeName.trim().toLowerCase(java.util.Locale.ROOT);
            model = (type.equals("gaian") || type.equals("therian"))
                    ? "jewel_" + type + "_" + RARITY_IDS[idx]
                    : "jewel_" + RARITY_IDS[idx];
        }
        ItemStack cached = GEM_CACHE.get(model);
        if (cached != null) return cached;
        Identifier id = Identifier.tryParse(model.contains(":") ? model : "minecraft:" + model);
        ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
        // tryParse returns null on anything malformed; leaving ITEM_MODEL unset
        // renders a plain amethyst shard, which beats throwing mid-draw.
        if (id != null) stack.set(DataComponentTypes.ITEM_MODEL, id);
        GEM_CACHE.put(model, stack);
        return stack;
    }

    /**
     * The loadout tab stack. Each tab is a flat label carrying the loadout's
     * number, with the active one filled bright — the tabs exist so a set swap
     * is one click without opening a menu, so which one is live has to be
     * readable without hovering.
     *
     * <p>Drawn top-down above the sockets. Locked pages are still drawn (dim)
     * rather than hidden, so the upgrade path is visible from where it matters.
     */
    private static void drawTabs(DrawContext ctx, HandledScreenAccessor panel,
                                 int x, int mouseX, int mouseY) {
        List<JewelLoadoutsPayload.Page> pages = tabs();
        if (pages.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return;
        int active = JewelLoadoutState.activePage();

        for (int i = 0; i < pages.size(); i++) {
            JewelLoadoutsPayload.Page page = pages.get(i);
            int y = tabY(panel, i);
            boolean isActive = i == active;
            int fill = isActive ? TAB_ACTIVE_FILL
                    : (page.unlocked() ? TAB_IDLE_FILL : TAB_LOCKED_FILL);
            int text = isActive ? TAB_ACTIVE_TEXT
                    : (page.unlocked() ? TAB_IDLE_TEXT : TAB_LOCKED_TEXT);

            ctx.fill(x, y, x + CELL, y + TAB_H, TAB_EDGE);
            ctx.fill(x + 1, y + 1, x + CELL - 1, y + TAB_H - 1, fill);
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + TAB_H) {
                ctx.fill(x + 1, y + 1, x + CELL - 1, y + TAB_H - 1, 0x40FFFFFF);
            }

            // The number, not the name: a name does not fit in 16px and the
            // tooltip carries it. Centred by measured width so double digits
            // stay centred too.
            String label = String.valueOf(i + 1);
            int labelWidth = mc.textRenderer.getWidth(label);
            ctx.drawText(mc.textRenderer, label,
                    x + (CELL - labelWidth) / 2, y + 2, text, false);

            // A dot per filled socket, so a glance tells you which loadouts
            // actually hold something without opening anything.
            int filled = page.filled();
            for (int d = 0; d < filled; d++) {
                int dx = x + 3 + d * 4;
                ctx.fill(dx, y + TAB_H - 3, dx + 2, y + TAB_H - 2, text);
            }
        }
    }

    /** Tooltip for a hovered loadout tab: its name and what it holds. */
    private static void renderTabTooltip(DrawContext ctx, int index, int mouseX, int mouseY) {
        List<JewelLoadoutsPayload.Page> pages = tabs();
        if (index >= pages.size()) return;
        JewelLoadoutsPayload.Page page = pages.get(index);
        boolean isActive = index == JewelLoadoutState.activePage();

        List<Text> lines = new ArrayList<>();
        String name = page.name() == null || page.name().isEmpty()
                ? "Loadout " + (index + 1) : page.name();
        lines.add(Text.literal(name).formatted(
                isActive ? Formatting.GREEN : (page.unlocked() ? Formatting.WHITE : Formatting.RED)));

        if (!page.unlocked()) {
            lines.add(Text.literal("Locked").formatted(Formatting.GRAY));
            lines.add(Text.literal("Season Pass L21, Ascendant rank,")
                    .formatted(Formatting.DARK_GRAY));
            lines.add(Text.literal("or a Jewel Loadout Expander.")
                    .formatted(Formatting.DARK_GRAY));
        } else {
            lines.add(Text.empty());
            for (int slot = 0; slot < page.jewelNames().size(); slot++) {
                String jewelName = page.jewelNames().get(slot);
                if (jewelName == null || jewelName.isEmpty()) {
                    lines.add(Text.literal("- empty -").formatted(Formatting.DARK_GRAY));
                } else {
                    lines.add(LegacyText.parse(jewelName));
                }
            }
            lines.add(Text.empty());
            if (isActive) {
                lines.add(Text.literal("Equipped").formatted(Formatting.GREEN));
            } else {
                lines.add(Text.literal("Click to equip this loadout").formatted(Formatting.YELLOW));
                lines.add(Text.literal("Safe zones only").formatted(Formatting.DARK_GRAY));
            }
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null) return;
        // drawTooltipImmediately for the same reason the socket tooltip uses it:
        // the deferred pass has already been flushed by the time we draw.
        List<net.minecraft.client.gui.tooltip.TooltipComponent> comps = new ArrayList<>(lines.size());
        for (Text line : lines) {
            comps.add(net.minecraft.client.gui.tooltip.TooltipComponent.of(line.asOrderedText()));
        }
        ctx.drawTooltipImmediately(mc.textRenderer, comps, mouseX, mouseY,
                net.minecraft.client.gui.tooltip.HoveredTooltipPositioner.INSTANCE, null);
    }

    private static int slotsHeight(int count) {
        int n = Math.max(1, count);
        return n * CELL + (n - 1) * SLOT_GAP;
    }

    /**
     * Vanilla-style GUI panel behind the cells: flat fill, light top-left edge,
     * dark bottom-right edge. Without it the cells float in space and read as
     * an overlay rather than part of the screen.
     */
    private static void drawFrame(DrawContext ctx, int x, int y, int w, int h) {
        int x0 = x - FRAME, y0 = y - FRAME;
        int x1 = x + w + FRAME, y1 = y + h + FRAME;
        ctx.fill(x0, y0, x1, y1, PANEL_FILL);
        ctx.fill(x0, y0, x1, y0 + 1, PANEL_LIGHT);          // top
        ctx.fill(x0, y0, x0 + 1, y1, PANEL_LIGHT);          // left
        ctx.fill(x0, y1 - 1, x1, y1, PANEL_SHADOW);         // bottom
        ctx.fill(x1 - 1, y0, x1, y1, PANEL_SHADOW);         // right
    }

    /** 7x9 padlock from primitive fills — matches the item-lock badge style. */
    private static void drawPadlock(DrawContext ctx, int x, int y) {
        final int outline = 0xFF1A1A1A;
        final int body = 0xFF8A919D;
        ctx.fill(x + 1, y, x + 5, y + 1, outline);
        ctx.fill(x, y + 1, x + 1, y + 3, outline);
        ctx.fill(x + 5, y + 1, x + 6, y + 3, outline);
        ctx.fill(x, y + 3, x + 6, y + 9, outline);
        ctx.fill(x + 1, y + 4, x + 5, y + 8, body);
        ctx.fill(x + 2, y + 5, x + 4, y + 7, outline);
    }
}
