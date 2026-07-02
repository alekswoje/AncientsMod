package com.aleks.ancientsmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws a booster's <b>multiplier</b> (bold, top-left) and <b>total duration</b>
 * (bottom-right) on its item slot, so players can read what a booster is worth
 * without hovering. Color-matched to the boost type (green XP, aqua Energy, gold
 * Ore, purple Shard).
 *
 * <p>All data is pulled from the synced display name + lore — PrisonsCore writes
 * {@code "<color>XP Booster"} as the name and {@code "Multiplier: 2.00"} /
 * {@code "Duration: 30m"} as lore lines, both of which always reach the client.
 * No server change and no packet needed; it degrades to nothing if the format
 * ever differs. Gated to PrisonsCore items (non-empty {@code custom_data}) so a
 * renamed vanilla emerald can't trigger it.
 *
 * <p>The duration shown is the booster's <i>total</i> grant (what it gives when
 * activated), not a live countdown — the item hasn't been used yet. The running
 * countdown lives in the separate active-booster HUD.
 */
public final class BoosterItemOverlay {

    private static final float MULT_SCALE = 0.75f;
    private static final float DUR_SCALE = 0.65f;
    private static final float DUR_MAX_WIDTH = 16f; // px budget before a long duration is shrunk

    private static final Pattern MULTIPLIER = Pattern.compile("Multiplier:\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");
    private static final Pattern DURATION = Pattern.compile("Duration:\\s*(.+?)\\s*$");

    /** Boost type → display color (ARGB). Globals share their boost's color. */
    private enum Boost {
        XP("XP Booster", 0xFF4FE36A),
        ENERGY("Energy Booster", 0xFF3DD6E6),
        ORE("Ore Booster", 0xFFF5B824),
        SHARD("Shard Booster", 0xFFC24BFF);

        final String nameNeedle;
        final int color;
        Boost(String nameNeedle, int color) { this.nameNeedle = nameNeedle; this.color = color; }
    }

    public static void render(DrawContext context, int x, int y, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom == null || custom.isEmpty()) return; // PrisonsCore custom items only

        Text nameText = stack.getName();
        if (nameText == null) return;
        String name = nameText.getString();
        Boost boost = matchBoost(name);
        if (boost == null) return;

        String mult = null, dur = null;
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                String s = line.getString();
                if (mult == null) {
                    Matcher m = MULTIPLIER.matcher(s);
                    if (m.find()) mult = formatMultiplier(m.group(1));
                }
                if (dur == null) {
                    Matcher d = DURATION.matcher(s);
                    if (d.find()) dur = d.group(1).trim();
                }
            }
        }
        if (mult == null && dur == null) return;

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        org.joml.Matrix3x2fStack m = context.getMatrices();

        // Multiplier — bold, top-left corner.
        if (mult != null) {
            MutableText t = Text.literal(mult).formatted(Formatting.BOLD);
            m.pushMatrix();
            m.translate((float) x, (float) y);
            m.scale(MULT_SCALE, MULT_SCALE);
            context.drawText(tr, t, 0, 0, boost.color, true);
            m.popMatrix();
        }

        // Duration — bottom-right, right-aligned, shrunk to fit the cell width.
        // Boosters can now stack, and there's no way to fit both the duration
        // text and vanilla's stack-count number in that one corner without
        // crowding it — so skip the duration entirely once stacked. The
        // multiplier badge (the more useful glance-info) still shows, and the
        // duration is still readable from the tooltip lore on hover.
        if (dur != null && stack.getCount() <= 1) {
            int w = tr.getWidth(dur);
            float scale = Math.min(DUR_SCALE, DUR_MAX_WIDTH / (float) Math.max(1, w));
            m.pushMatrix();
            m.translate(x + 16f, y + 16f - scale * tr.fontHeight);
            m.scale(scale, scale);
            context.drawText(tr, Text.literal(dur), -w, 0, boost.color, true);
            m.popMatrix();
        }
    }

    private static Boost matchBoost(String name) {
        for (Boost b : Boost.values()) {
            if (name.contains(b.nameNeedle)) return b;
        }
        return null;
    }

    /** "2.00" → "2x", "1.50" → "1.5x", "1.25" → "1.25x". */
    private static String formatMultiplier(String raw) {
        try {
            double v = Double.parseDouble(raw.replace(",", ""));
            String num = (v == Math.floor(v))
                    ? Long.toString((long) v)
                    : trimZeros(String.format(Locale.US, "%.2f", v));
            return num + "x";
        } catch (NumberFormatException e) {
            return raw + "x";
        }
    }

    /** "1.50" → "1.5", "1.20" → "1.2", "1.00" → "1". */
    private static String trimZeros(String s) {
        if (s.indexOf('.') < 0) return s;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    private BoosterItemOverlay() {}
}
