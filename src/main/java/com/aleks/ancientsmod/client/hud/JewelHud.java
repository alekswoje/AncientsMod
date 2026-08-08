package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.net.payload.JewelSlotsPayload;
import net.minecraft.client.font.TextRenderer;
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
 * <p>Each slot draws like a vanilla inventory cell: a socketed jewel shows its
 * gem icon (the same per-rarity {@code minecraft:jewel_<rarity>} model the
 * server puts on the item, so the pack art matches exactly), an unlocked but
 * empty slot shows a faint outline, and a locked slot shows a padlock plus the
 * prestige it wants.
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

    private static final int SLOT = 20;
    private static final int GAP = 2;

    private static final int SLOT_BG        = 0xB0101014;
    private static final int SLOT_BG_LOCKED = 0xB01A0F12;
    private static final int SLOT_BORDER    = 0xFF3A3F4B;
    private static final int LOCK_COLOR     = 0xFF7C838F;
    private static final int CAPTION_COLOR  = 0xFFBFC4CC;
    private static final int STAT_COLOR     = 0xFF9BE59B;

    /** ShardRarity ordinal → chat colour, matching the server's rarity ladder. */
    private static final int[] RARITY_COLORS = {
            0xFFAAAAAA, // Simple      gray
            0xFF55FF55, // Uncommon    green
            0xFF5555FF, // Elite       blue
            0xFFFFFF55, // Ultimate    yellow
            0xFFFFAA00, // Legendary   gold
            0xFFFF5555, // Godly       red
            0xFF55FFFF, // Divine      aqua
            0xFFFFFFFF, // Exceptional white
    };

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
        return vertical() ? SLOT : n * SLOT + (n - 1) * GAP;
    }

    private int slotsHeight() {
        int n = Math.max(1, slots().size());
        return vertical() ? n * SLOT + (n - 1) * GAP : SLOT;
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

    @Override public int defaultX(int screenWidth)  { return screenWidth / 2 + 95; }
    @Override public int defaultY(int screenHeight) { return screenHeight - 23; }

    @Override
    public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        List<JewelSlotsPayload.Slot> slots = slots();
        boolean vertical = vertical();

        for (int i = 0; i < slots.size(); i++) {
            int x = vertical ? 0 : i * (SLOT + GAP);
            int y = vertical ? i * (SLOT + GAP) : 0;
            drawSlot(ctx, fr, slots.get(i), x, y);
        }

        List<String> captions = captionLines();
        if (captions.isEmpty()) return;
        int y = slotsHeight() + 2;
        for (String line : captions) {
            boolean requirement = line.startsWith("Slot ");
            ctx.drawText(fr, Text.literal(line), 0, y, requirement ? CAPTION_COLOR : STAT_COLOR, true);
            y += 9;
        }
    }

    private void drawSlot(DrawContext ctx, TextRenderer fr, JewelSlotsPayload.Slot slot, int x, int y) {
        boolean locked = slot.isLocked();
        ctx.fill(x, y, x + SLOT, y + SLOT, locked ? SLOT_BG_LOCKED : SLOT_BG);

        // 1px border, tinted to the socketed jewel's rarity when filled.
        int border = slot.isFilled() ? rarityColor(slot.rarityOrdinal()) : SLOT_BORDER;
        ctx.fill(x, y, x + SLOT, y + 1, border);
        ctx.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, border);
        ctx.fill(x, y, x + 1, y + SLOT, border);
        ctx.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, border);

        if (locked) {
            drawPadlock(ctx, x + SLOT / 2 - 3, y + SLOT / 2 - 4);
            if (slot.requiredPrestige() > 0) {
                String tag = "P" + slot.requiredPrestige();
                ctx.drawText(fr, Text.literal(tag),
                        x + SLOT - 1 - fr.getWidth(tag), y + SLOT - 8, LOCK_COLOR, true);
            }
            return;
        }
        if (!slot.isFilled()) {
            // Empty but unlocked — a faint centre dot reads as "socket here".
            ctx.fill(x + SLOT / 2 - 1, y + SLOT / 2 - 1, x + SLOT / 2 + 1, y + SLOT / 2 + 1, 0x40FFFFFF);
            return;
        }
        ctx.drawItem(gemFor(slot.rarityOrdinal()), x + 2, y + 2);
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

    private static int rarityColor(int ordinal) {
        if (ordinal < 0 || ordinal >= RARITY_COLORS.length) return SLOT_BORDER;
        return RARITY_COLORS[ordinal];
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
