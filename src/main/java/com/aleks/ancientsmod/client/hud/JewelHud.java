package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.net.payload.JewelSlotsPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Jewel sockets rendered as extra hotbar-style slots — the client-side mirror
 * of the server's three global jewel slots.
 *
 * <p>Slots are drawn from the vanilla hotbar's own sprites so they sit beside
 * it as if they belonged there, and restyle with whatever pack is loaded. A
 * socketed jewel shows its gem icon (the same per-rarity
 * {@code minecraft:jewel_<rarity>} model the server puts on the item, so the
 * pack art matches exactly); an unlocked empty slot is just the frame, like an
 * empty hotbar cell; a locked slot shows a padlock plus the prestige it wants.
 *
 * <p>Purely a display of server state — nothing here can equip or move a jewel.
 * Sockets are mutated with {@code /jewels} and validated server-side.
 */
public final class JewelHud extends HudElement {

    public static final JewelHud INSTANCE = new JewelHud();

    /** Draw the slots stacked vertically instead of in a row. */
    public static final String KEY_VERTICAL = "jewel_vertical";
    /** Show the "Prestige N" caption under locked slots. */
    public static final String KEY_SHOW_REQUIREMENT = "jewel_show_requirement";
    /** Show the stat lines of socketed jewels next to the slots. */
    public static final String KEY_SHOW_STATS = "jewel_show_stats";
    /** Keep rendering when every slot is empty. */
    public static final String KEY_SHOW_WHEN_EMPTY = "jewel_show_when_empty";

    /**
     * Vanilla hotbar chrome, so the sockets read as part of the hotbar instead
     * of a bolted-on overlay — and so they restyle with the player's pack.
     *
     * <p>Measured off the sprite rather than assumed: {@code hud/hotbar} is
     * 182x22, laid out as a 1px dark outline then nine 20px cells (a 19px cell
     * body plus the 1px outline that its neighbour shares), with one extra
     * outline doubled onto the right end.
     *
     * <p>Horizontally the widget is drawn as a CONTINUATION of the hotbar: the
     * cell run plus the bar's real right cap, sat flush against the hotbar's
     * right edge. Its left edge is deliberately left open because the hotbar's
     * own right cap supplies that border. Every standalone-bar variant fought
     * itself — with both end caps the widget carries more chrome than its cells
     * warrant, and with neither the ends read as sliced off. As an extension
     * there is only one cap to place and the cells stay on vanilla's 20px beat.
     */
    private static final Identifier HOTBAR_TEXTURE = Identifier.ofVanilla("hud/hotbar");
    private static final int HOTBAR_TEX_W = 182, HOTBAR_TEX_H = 22;
    /** The 1px outline that opens the bar and repeats between cells. */
    private static final int EDGE = 1;
    /** One cell, its shared closing outline included. */
    private static final int CELL = 20;
    /** Full bar height; the top/bottom frame runs the whole bar, so it stays. */
    private static final int SLOT = 22;
    /** Vanilla puts hotbar items at bar +3,+3, i.e. +2,+3 inside a cell. */
    private static final int ITEM_INSET_X = 2;
    private static final int ITEM_INSET_Y = 3;

    private static final int LOCK_COLOR     = 0xFF7C838F;
    private static final int CAPTION_COLOR  = 0xFFBFC4CC;
    private static final int STAT_COLOR     = 0xFF9BE59B;

    private static final String[] RARITY_IDS = {
            "simple", "uncommon", "elite", "ultimate",
            "legendary", "godly", "divine", "exceptional",
    };

    /** One cached stack per rarity — building these every frame would churn. */
    private static final ItemStack[] GEM_CACHE = new ItemStack[RARITY_IDS.length];

    private JewelHud() {}

    @Override public String id() { return "jewels"; }
    @Override public String displayName() { return "Jewel Slots"; }

    @Override
    public boolean isVisible() {
        if (!FeatureToggles.isJewelHudEnabled()) return false;
        if (JewelState.isEmpty()) return false;
        if (showWhenEmpty()) return true;
        // Otherwise hide the widget entirely until there's something to show:
        // a socketed jewel, or a locked slot worth advertising.
        for (JewelSlotsPayload.Slot slot : JewelState.slots()) {
            if (slot.isFilled() || slot.isLocked()) return true;
        }
        return false;
    }

    @Override public String editorPlaceholder() { return "Jewel Slots"; }

    @Override
    public Screen openSettings(Screen parent) {
        return new JewelHudSettingsScreen(parent, this);
    }

    public boolean vertical()        { return HudSettings.getBoolean(id(), KEY_VERTICAL, false); }
    public boolean showRequirement() { return HudSettings.getBoolean(id(), KEY_SHOW_REQUIREMENT, true); }
    public boolean showStats()       { return HudSettings.getBoolean(id(), KEY_SHOW_STATS, false); }
    public boolean showWhenEmpty()   { return HudSettings.getBoolean(id(), KEY_SHOW_WHEN_EMPTY, false); }

    private static List<JewelSlotsPayload.Slot> slots() {
        return JewelState.slots();
    }

    private int slotsWidth() {
        int n = Math.max(1, slots().size());
        return vertical() ? EDGE + CELL + EDGE : n * CELL + EDGE;
    }

    private int slotsHeight() {
        int n = Math.max(1, slots().size());
        return vertical() ? n * SLOT : SLOT;
    }

    @Override
    public int width() {
        int w = slotsWidth();
        TextRenderer fr = textRenderer();
        if (fr == null) return w;
        for (String line : captionLines()) {
            w = Math.max(w, fr.getWidth(line));
        }
        return Math.max(SLOT, w);
    }

    @Override
    public int height() {
        int h = slotsHeight();
        int captions = captionLines().size();
        if (captions > 0) h += 2 + captions * 9;
        return h;
    }

    /** Requirement + stat lines drawn under the slot strip. */
    private List<String> captionLines() {
        List<String> out = new java.util.ArrayList<>(4);
        List<JewelSlotsPayload.Slot> slots = slots();
        for (int i = 0; i < slots.size(); i++) {
            JewelSlotsPayload.Slot slot = slots.get(i);
            if (slot.isLocked() && showRequirement()) {
                out.add("Slot " + (i + 1) + ": Prestige " + slot.requiredPrestige());
            } else if (slot.isFilled() && showStats()) {
                for (String line : slot.statLines()) out.add(line);
            }
        }
        return out;
    }

    /** Flush with the hotbar's right edge (vanilla draws it at centre - 91). */
    @Override public int defaultX(int screenWidth)  { return screenWidth / 2 + 91; }
    /** Sits on the hotbar's own baseline: vanilla draws it 22px off the bottom. */
    @Override public int defaultY(int screenHeight) { return screenHeight - 22; }

    /** One-shot geometry dump — see {@link #logGeometryOnce}. */
    private static boolean geometryLogged;

    /**
     * Logs, once per session, what this widget draws next to what vanilla draws
     * for the hotbar. Eyeballing a compressed screenshot kept giving the wrong
     * answer about which is bigger; these are the numbers that settle it.
     */
    private void logGeometryOnce(DrawContext ctx, int n) {
        if (geometryLogged) return;
        geometryLogged = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();
        double guiScale = mc != null && mc.getWindow() != null ? mc.getWindow().getScaleFactor() : -1;
        com.aleks.ancientsmod.AncientsMod.LOGGER.info(
                "[JewelHud] scaledWindow={}x{} guiScale={} | jewel: x={} y={} w={} h={} cells={} pitch={} "
                        + "| vanilla hotbar: x={} y={} w=182 h=22 pitch=20 | widgetScale={}",
                sw, sh, guiScale,
                HudPositions.getX(this, sw), HudPositions.getY(this, sh), slotsWidth(), slotsHeight(),
                n, CELL,
                sw / 2 - 91, sh - 22,
                HudPositions.getScale(this));
    }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        List<JewelSlotsPayload.Slot> slots = slots();
        boolean vertical = vertical();

        int n = slots.size();
        logGeometryOnce(ctx, n);
        // Chrome first, contents second. Horizontally the cells come out of the
        // sprite as one contiguous run, so the dividers land exactly where
        // vanilla puts them and no seam appears at the joins.
        if (vertical) {
            for (int i = 0; i < n; i++) drawStandaloneCell(ctx, 0, i * SLOT);
        } else {
            drawExtension(ctx, 0, 0, n);
        }
        for (int i = 0; i < n; i++) {
            // Vertical cells carry a left cap of their own; the horizontal run
            // borrows the hotbar's, so its cells start at the widget edge.
            int x = (vertical ? EDGE : i * CELL) + ITEM_INSET_X;
            int y = (vertical ? i * SLOT : 0) + ITEM_INSET_Y;
            drawContents(ctx, fr, slots.get(i), x, y);
        }

        List<String> captions = captionLines();
        if (captions.isEmpty()) return;
        int y = slotsHeight() + 2;
        for (String line : captions) {
            // Requirement captions are ours and plain; stat lines are the
            // server's lore, so they keep their own colours (tier badge
            // included) and STAT_COLOR is only the fallback.
            boolean requirement = line.startsWith("Slot ");
            ctx.drawText(fr, requirement ? Text.literal(line)
                            : com.aleks.ancientsmod.client.LegacyText.parse(line),
                    0, y, requirement ? CAPTION_COLOR : STAT_COLOR, true);
            y += 9;
        }
    }

    /** {@code n} cells then the bar's right cap; the hotbar closes the left side. */
    private static void drawExtension(DrawContext ctx, int x, int y, int n) {
        int cells = n * CELL;
        ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, HOTBAR_TEXTURE,
                HOTBAR_TEX_W, HOTBAR_TEX_H, EDGE, 0, x, y, cells, SLOT);
        ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, HOTBAR_TEXTURE,
                HOTBAR_TEX_W, HOTBAR_TEX_H, HOTBAR_TEX_W - EDGE, 0, x + cells, y, EDGE, SLOT);
    }

    /** A cell capped on both sides — the vertical layout has no bar to lean on. */
    private static void drawStandaloneCell(DrawContext ctx, int x, int y) {
        ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, HOTBAR_TEXTURE,
                HOTBAR_TEX_W, HOTBAR_TEX_H, 0, 0, x, y, EDGE + CELL, SLOT);
        ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, HOTBAR_TEXTURE,
                HOTBAR_TEX_W, HOTBAR_TEX_H, HOTBAR_TEX_W - EDGE, 0, x + EDGE + CELL, y, EDGE, SLOT);
    }

    /** Whatever goes inside a cell, drawn at its 16x16 content origin. */
    private void drawContents(DrawContext ctx, TextRenderer fr,
                              JewelSlotsPayload.Slot slot, int cx, int cy) {
        if (slot.isLocked()) {
            ctx.fill(cx, cy, cx + 16, cy + 16, 0x99101014);
            drawPadlock(ctx, cx + 5, cy + 4);
            if (slot.requiredPrestige() > 0) {
                String tag = "P" + slot.requiredPrestige();
                ctx.drawText(fr, Text.literal(tag),
                        cx + 16 - fr.getWidth(tag), cy + 8, LOCK_COLOR, true);
            }
            return;
        }
        // An empty cell is just the frame, like an empty hotbar slot. A filled
        // one is only the gem: the rarity is already in the icon's colour, and
        // a ring around it read as "this slot is selected".
        if (slot.isFilled()) ctx.drawItem(gemFor(slot.rarityOrdinal()), cx, cy);
    }

    /** Tiny 7x9 padlock drawn from rectangles — no font glyph or texture needed. */
    private static void drawPadlock(DrawContext ctx, int x, int y) {
        // Shackle: two uprights + a top bar.
        ctx.fill(x + 1, y, x + 6, y + 1, LOCK_COLOR);
        ctx.fill(x + 1, y + 1, x + 2, y + 3, LOCK_COLOR);
        ctx.fill(x + 5, y + 1, x + 6, y + 3, LOCK_COLOR);
        // Body.
        ctx.fill(x, y + 3, x + 7, y + 9, LOCK_COLOR);
        // Keyhole.
        ctx.fill(x + 3, y + 5, x + 4, y + 7, 0xFF20242C);
    }

    /**
     * An amethyst shard wearing the server's per-rarity jewel item-model, so
     * the HUD shows the exact pack art. Without the pack it degrades to a plain
     * amethyst shard rather than a missing-texture square.
     */
    private static ItemStack gemFor(int ordinal) {
        int idx = (ordinal < 0 || ordinal >= RARITY_IDS.length) ? 0 : ordinal;
        ItemStack cached = GEM_CACHE[idx];
        if (cached != null) return cached;
        ItemStack stack = new ItemStack(Items.AMETHYST_SHARD);
        stack.set(DataComponentTypes.ITEM_MODEL, Identifier.of("minecraft", "jewel_" + RARITY_IDS[idx]));
        GEM_CACHE[idx] = stack;
        return stack;
    }

    private static TextRenderer textRenderer() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        return mc != null ? mc.textRenderer : null;
    }
}
