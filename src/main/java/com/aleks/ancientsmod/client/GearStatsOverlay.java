package com.aleks.ancientsmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws a leveled item's level (small bold, top-right) and — for pickaxes — its
 * prestige (small bold gold, top-left), so you can read both at a glance.
 *
 * <p>PrisonsCore builds the display name as {@code "<base> <level>"} for gear and
 * {@code "<base> <level> P<prestige>"} for pickaxes, and the name always syncs to
 * the client. So the <b>level</b> is the last pure-integer token, and the
 * <b>prestige</b> is the {@code P<n>} token. Gated to PrisonsCore items (which
 * carry a {@code custom_data} marker) so vanilla/renamed items aren't annotated.
 * Pure client render; no server change.
 */
public final class GearStatsOverlay {

    private static final float SCALE = 0.6f;              // smaller than before
    private static final int LEVEL_COLOR = 0xFFFFFFFF;    // white
    private static final int PRESTIGE_COLOR = 0xFFFFC83C; // gold

    private static final Pattern PURE_INT = Pattern.compile("\\d{1,7}");
    private static final Pattern PRESTIGE_TOKEN = Pattern.compile("P(\\d{1,3})");

    /** Bukkit serializes its PersistentDataContainer into custom_data under this compound. */
    private static final String PDC_ROOT = "PublicBukkitValues";
    /** Markers the plugin sets on real leveled gear / pickaxes (NamespacedKey(PrisonsCore, ...)). */
    private static final String PICKAXE_MARKER = "prisonscore:ancient_pickaxe";
    private static final String GEAR_MARKER = "prisonscore:ancient_gear";

    public static void render(DrawContext context, int x, int y, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom == null || custom.isEmpty()) return; // PrisonsCore custom items only

        // Only annotate REAL leveled gear / pickaxes. The display-name parse below
        // treats the trailing integer of the name as a level, so without this gate
        // ANY PrisonsCore item whose name ends in a number gets a bogus badge —
        // e.g. a quest pane named "Quest 7: Reach Level 10" wrongly shows a "10".
        // Bukkit serializes its PDC into custom_data under PublicBukkitValues; real
        // gear carries the ancient_pickaxe / ancient_gear marker the plugin sets.
        // (This also subsumes the old currency-name exclusion: currency items lack
        // the gear marker, so they're filtered out here too.)
        NbtCompound pdc = custom.copyNbt().getCompound(PDC_ROOT).orElse(null);
        if (pdc == null) return;
        if (!pdc.contains(PICKAXE_MARKER) && !pdc.contains(GEAR_MARKER)) return;

        Text name = stack.getName();
        if (name == null) return;
        String[] tokens = name.getString().trim().split("\\s+");

        int level = levelToken(tokens);
        int prestige = prestigeToken(tokens);
        if (level <= 0 && prestige <= 0) return;

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        org.joml.Matrix3x2fStack m = context.getMatrices();

        if (level > 0) {
            Text t = Text.literal(Integer.toString(level)).formatted(Formatting.BOLD);
            int w = tr.getWidth(t);
            m.pushMatrix();
            m.translate(x + 16f, (float) y);   // top-right corner
            m.scale(SCALE, SCALE);
            context.drawText(tr, t, -w, 0, LEVEL_COLOR, true);
            m.popMatrix();
        }
        if (prestige > 0) {
            Text t = Text.literal("P" + prestige).formatted(Formatting.BOLD);
            m.pushMatrix();
            // bottom-left, so it sits on a different row than the top-right level (no overlap)
            m.translate((float) x, y + 16f - SCALE * tr.fontHeight);
            m.scale(SCALE, SCALE);
            context.drawText(tr, t, 0, 0, PRESTIGE_COLOR, true);
            m.popMatrix();
        }
    }

    /**
     * The gear level — the <b>final</b> name token, or the token just before a
     * trailing {@code P<n>} prestige marker. Gear is named {@code "<base> <level>"}
     * (pickaxes {@code "<base> <level> P<prestige>"}), so the level lives at the end.
     * Returns 0 when the name doesn't end in a level, which deliberately ignores
     * items whose only integer is a <i>leading</i> token (e.g. "630 Ancient Energy").
     */
    private static int levelToken(String[] tokens) {
        if (tokens.length == 0) return 0;
        int idx = tokens.length - 1;
        if (PRESTIGE_TOKEN.matcher(tokens[idx]).matches() && idx > 0) idx--; // skip trailing "P<n>"
        if (!PURE_INT.matcher(tokens[idx]).matches()) return 0;
        try {
            return Integer.parseInt(tokens[idx]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The "P<n>" token's number, else 0. */
    private static int prestigeToken(String[] tokens) {
        for (String tok : tokens) {
            Matcher mt = PRESTIGE_TOKEN.matcher(tok);
            if (mt.matches()) {
                try {
                    return Integer.parseInt(mt.group(1));
                } catch (NumberFormatException ignored) {
                    // keep scanning
                }
            }
        }
        return 0;
    }

    private GearStatsOverlay() {}
}
