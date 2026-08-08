package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.client.hud.JewelState;
import com.aleks.ancientsmod.mixin.client.HandledScreenAccessor;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.JewelSlotsPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The three jewel sockets drawn beside the survival inventory, behaving like
 * vanilla equipment slots: pick a jewel up onto the cursor, click a socket to
 * put it in; click a filled socket to take it back out.
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

    private static final ItemStack[] GEM_CACHE = new ItemStack[RARITY_IDS.length];

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
     * Column origin: the LEFT of the inventory panel by default. The right side
     * is where the screen stacks its status-effect widgets, so parking there
     * means the sockets sit under a Dolphin's Grace icon the moment the player
     * drinks anything. Falls back to the right edge only if the left would run
     * off-screen. Every caller goes through this, so hit-testing and rendering
     * can't disagree about which side it's on.
     */
    private static int columnX(int panelX, int panelWidth) {
        int left = panelX - PANEL_GAP - CELL - FRAME;
        if (left >= FRAME) return left;
        int right = panelX + panelWidth + PANEL_GAP + FRAME;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getWindow() != null
                && right + CELL + FRAME > mc.getWindow().getScaledWidth()) {
            return Math.max(FRAME, left);
        }
        return right;
    }

    private static int columnY(int panelY) {
        return panelY + 17;
    }

    private static int slotY(int panelY, int index) {
        return columnY(panelY) + index * (CELL + SLOT_GAP);
    }

    /** True when the pointer is anywhere on the widget, frame border included. */
    public static boolean contains(int panelX, int panelY, int panelWidth, double mouseX, double mouseY) {
        if (!enabled()) return false;
        int x = columnX(panelX, panelWidth);
        int y = columnY(panelY);
        int h = slotsHeight(JewelState.slots().size());
        return mouseX >= x - FRAME && mouseX < x + CELL + FRAME
                && mouseY >= y - FRAME && mouseY < y + h + FRAME;
    }

    /** Slot index under the mouse, or -1. */
    public static int slotAt(int panelX, int panelY, int panelWidth, double mouseX, double mouseY) {
        if (!enabled()) return -1;
        int x = columnX(panelX, panelWidth);
        if (mouseX < x || mouseX >= x + CELL) return -1;
        List<JewelSlotsPayload.Slot> slots = JewelState.slots();
        for (int i = 0; i < slots.size(); i++) {
            int y = slotY(panelY, i);
            if (mouseY >= y && mouseY < y + CELL) return i;
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
    public static void render(DrawContext ctx, int panelX, int panelY, int panelWidth,
                              int mouseX, int mouseY) {
        if (!enabled()) return;
        List<JewelSlotsPayload.Slot> slots = JewelState.slots();
        int x = columnX(panelX, panelWidth);
        drawFrame(ctx, x, columnY(panelY), CELL, slotsHeight(slots.size()));

        for (int i = 0; i < slots.size(); i++) {
            JewelSlotsPayload.Slot slot = slots.get(i);
            int y = slotY(panelY, i);

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
                ctx.drawItem(gemFor(slot.rarityOrdinal()), cx, cy);
            }

            // Vanilla-style hover highlight.
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                ctx.fill(cx, cy, cx + 16, cy + 16, 0x80FFFFFF);
            }
        }
    }

    /** Tooltip for the hovered socket. Call after render, before the screen's own tooltip. */
    public static void renderTooltip(DrawContext ctx, int panelX, int panelY, int panelWidth,
                                     int mouseX, int mouseY) {
        int index = slotAt(panelX, panelY, panelWidth, mouseX, mouseY);
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
            int ord = clampOrdinal(slot.rarityOrdinal());
            String name = RARITY_NAMES[ord] + " " + slot.familyName() + " Jewel";
            lines.add(Text.literal(name).withColor(RARITY_COLORS[ord]));
            // Server-authored lore, colours intact — the tier badge is
            // colour-coded, so repainting the line one flat green loses it.
            for (String stat : slot.statLines()) {
                lines.add(LegacyText.parse(stat));
            }
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
    public static boolean onClick(int panelX, int panelY, int panelWidth,
                                  double mouseX, double mouseY, boolean shift) {
        if (!contains(panelX, panelY, panelWidth, mouseX, mouseY)) return false;
        pressSwallowed = true;

        int index = slotAt(panelX, panelY, panelWidth, mouseX, mouseY);
        if (index < 0) return true;                        // on the frame, not a cell
        List<JewelSlotsPayload.Slot> slots = JewelState.slots();
        if (index >= slots.size()) return true;
        if (slots.get(index).isLocked()) return true;      // server would refuse anyway

        // Plain click = vanilla slot semantics (take to cursor / put in / swap),
        // resolved server-side — it re-reads the cursor and ignores anything
        // that isn't a jewel. Shift-click sends it straight to the inventory.
        NetworkHandler.sendJewelSocketRequest(
                shift ? Protocol.JEWEL_OP_UNSOCKET : Protocol.JEWEL_OP_SOCKET_CURSOR, index);
        return true;
    }

    /**
     * Shift-clicking a jewel in the bag sends it to the first free socket
     * rather than shuffling it between the hotbar and the inventory, which is
     * never what anyone wanted that jewel to do.
     *
     * <p>Bails out to vanilla whenever the quick-socket can't apply — no free
     * socket, not a jewel, not a bag slot — so shift-click keeps working
     * normally on everything else. Returns true when we took the click.
     */
    public static boolean onInventoryShiftClick(HandledScreenAccessor screen,
                                                double mouseX, double mouseY) {
        if (!enabled() || !hasFreeSocket()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return false;

        Slot slot = screen.ancientsmod$getSlotAt(mouseX, mouseY);
        if (slot == null || slot.inventory != mc.player.getInventory()) return false;
        // Bag + hotbar only: armour, offhand and the crafting grid are backed
        // by the same inventory but aren't indices the server can read as one.
        int index = slot.getIndex();
        if (index < 0 || index >= PlayerInventory.MAIN_SIZE) return false;
        if (!looksLikeJewel(slot.getStack())) return false;

        pressSwallowed = true;
        NetworkHandler.sendJewelSocketRequest(Protocol.JEWEL_OP_QUICK_SOCKET, index);
        return true;
    }

    private static boolean hasFreeSocket() {
        for (JewelSlotsPayload.Slot slot : JewelState.slots()) {
            if (!slot.isLocked() && !slot.isFilled()) return true;
        }
        return false;
    }

    /**
     * Best-effort check on the item art, only ever used to decide whether to
     * intercept — the server re-reads the item and refuses anything that isn't
     * really a jewel. Mystery jewels are excluded on purpose: those have to be
     * opened before there's anything to socket.
     */
    private static boolean looksLikeJewel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier model = stack.get(DataComponentTypes.ITEM_MODEL);
        if (model == null) return false;
        String path = model.getPath();
        return path.startsWith("jewel_") && !path.startsWith("jewel_mystery_");
    }

    /**
     * Whether the screen should be kept out of this release. Pairs with the
     * swallowed press wherever the pointer ended up — dragging off the widget
     * mid-click must not turn into a drop either.
     */
    public static boolean onRelease(int panelX, int panelY, int panelWidth,
                                    double mouseX, double mouseY) {
        if (pressSwallowed) {
            pressSwallowed = false;
            return true;
        }
        return contains(panelX, panelY, panelWidth, mouseX, mouseY);
    }

    /** Drags belonging to a swallowed press — keeps quick-craft from starting. */
    public static boolean onDrag() {
        return pressSwallowed;
    }

    private static int clampOrdinal(int ordinal) {
        return (ordinal < 0 || ordinal >= RARITY_IDS.length) ? 0 : ordinal;
    }

    private static ItemStack gemFor(int ordinal) {
        int idx = clampOrdinal(ordinal);
        ItemStack cached = GEM_CACHE[idx];
        if (cached != null) return cached;
        ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
        stack.set(DataComponentTypes.ITEM_MODEL, Identifier.of("minecraft", "jewel_" + RARITY_IDS[idx]));
        GEM_CACHE[idx] = stack;
        return stack;
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
