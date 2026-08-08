package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.client.hud.JewelState;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.Protocol;
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

    /** Gap between the inventory panel's right edge and the socket column. */
    private static final int PANEL_GAP = 4;
    private static final int SLOT_GAP = 2;
    /** Inset from the cell's top-left to its 16x16 content area. */
    private static final int CONTENT_INSET = 1;

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

    private JewelSockets() {}

    public static boolean enabled() {
        return ServerAllowlist.isAllowed()
                && FeatureToggles.isJewelSocketsEnabled()
                && !JewelState.isEmpty();
    }

    /**
     * Column origin: hugging the right edge of the inventory panel, flipping to
     * the left edge when the right would run off-screen (small window / large
     * GUI scale). Every caller goes through this so hit-testing and rendering
     * can't disagree about which side it's on.
     */
    private static int columnX(int panelX, int panelWidth) {
        int right = panelX + panelWidth + PANEL_GAP;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getWindow() != null
                && right + CELL > mc.getWindow().getScaledWidth()) {
            return Math.max(0, panelX - PANEL_GAP - CELL);
        }
        return right;
    }

    private static int columnY(int panelY) {
        return panelY + 17;
    }

    private static int slotY(int panelY, int index) {
        return columnY(panelY) + index * (CELL + SLOT_GAP);
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

    /** Draws the socket column. Call at the tail of the inventory screen's render. */
    public static void render(DrawContext ctx, int panelX, int panelY, int panelWidth,
                              int mouseX, int mouseY) {
        if (!enabled()) return;
        List<JewelSlotsPayload.Slot> slots = JewelState.slots();
        int x = columnX(panelX, panelWidth);

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
            for (String stat : slot.statLines()) {
                lines.add(Text.literal(stat).formatted(Formatting.GREEN));
            }
            lines.add(Text.literal("Click to unsocket").formatted(Formatting.YELLOW));
        } else {
            lines.add(Text.literal("Empty Jewel Socket").formatted(Formatting.WHITE));
            lines.add(Text.literal("Hold a jewel and click to socket it")
                    .formatted(Formatting.GRAY));
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ctx.drawTooltip(mc.textRenderer, lines, mouseX, mouseY);
    }

    /**
     * Handles a click in the socket column. Returns true when the click was
     * ours (so the screen shouldn't also treat it as a normal click).
     */
    public static boolean onClick(int panelX, int panelY, int panelWidth,
                                  double mouseX, double mouseY) {
        int index = slotAt(panelX, panelY, panelWidth, mouseX, mouseY);
        if (index < 0) return false;
        List<JewelSlotsPayload.Slot> slots = JewelState.slots();
        if (index >= slots.size()) return false;
        JewelSlotsPayload.Slot slot = slots.get(index);
        if (slot.isLocked()) return true; // eat the click, server would refuse anyway

        NetworkHandler.sendJewelSocketRequest(
                slot.isFilled() ? Protocol.JEWEL_OP_UNSOCKET : Protocol.JEWEL_OP_SOCKET_CURSOR,
                index);
        return true;
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
