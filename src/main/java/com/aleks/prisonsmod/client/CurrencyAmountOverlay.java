package com.aleks.prisonsmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws a compact value (e.g. {@code 1m}, {@code 1.1k}) in the top-right corner
 * of currency-item slots so players can read a stack's worth at a glance without
 * hovering. Currently handles <b>Ancient Energy</b>.
 *
 * <p>The amount is parsed from the item's display name — which always syncs to
 * the client (e.g. {@code "1,000,000 Ancient Energy"}) — so this needs no server
 * change and degrades to nothing if the name format ever differs.
 *
 * <p>Pure client render. Drawn at TAIL of {@code HandledScreen.drawSlot} via
 * {@link com.aleks.prisonsmod.mixin.client.HandledScreenSlotOverlayMixin}, so it
 * overlays the item icon in the slot's native screen-space coords.
 */
public final class CurrencyAmountOverlay {

    /** "1,000,000 Ancient Energy" → capture the leading comma-grouped number. */
    private static final Pattern ENERGY_NAME = Pattern.compile("^([0-9][0-9,]*)\\s+Ancient Energy");

    /** "$1,000,000.00 Money Note" → capture the dollar amount (commas + optional decimals). */
    private static final Pattern MONEY_NAME = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s+Money Note");

    /** 1e0, 1e3, 1e6, 1e9, 1e12, 1e15, 1e18 — covers the whole long range. */
    private static final String[] SUFFIX = {"", "k", "m", "b", "t", "q", "Q"};

    private static final float BASE_SCALE = 0.8f; // bigger than the old 0.5 so it reads in a slot
    private static final float MAX_WIDTH = 17f;    // px budget before a long label is shrunk to fit
    private static final int COLOR = 0xFFFFFFFF;   // white + shadow reads over the bright orb

    /** Compact label for a currency stack, or {@code null} if this item isn't one we handle. */
    public static String compact(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String name = stack.getName().getString();
        if (name == null) return null;
        Long amount = parseAmount(name.trim());
        return amount == null ? null : abbreviate(amount);
    }

    /** Pull the currency value out of a known item name, or null if it isn't one we handle. */
    private static Long parseAmount(String name) {
        if (name.contains("Ancient Energy")) {
            Matcher m = ENERGY_NAME.matcher(name);
            if (m.find()) return parseLoose(m.group(1));
        }
        if (name.contains("Money Note")) {
            Matcher m = MONEY_NAME.matcher(name);
            if (m.find()) return parseLoose(m.group(1));
        }
        return null;
    }

    /** "1,000,000.00" → 1000000. Strips commas and any fractional part. */
    private static Long parseLoose(String s) {
        s = s.replace(",", "");
        int dot = s.indexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code 1234 → "1.2k"}, {@code 1_000_000 → "1m"}, {@code 1_500_000_000 → "1.5b"}.
     * One decimal place, trailing {@code .0} dropped, floored so we never overstate.
     */
    public static String abbreviate(long value) {
        if (value < 0) return Long.toString(value);
        if (value < 1000) return Long.toString(value);
        double v = value;
        int tier = 0;
        while (v >= 1000.0 && tier < SUFFIX.length - 1) {
            v /= 1000.0;
            tier++;
        }
        double floored = Math.floor(v * 10.0) / 10.0; // 1 decimal, floored
        String num = (floored == Math.floor(floored))
                ? Long.toString((long) floored)                 // 1.0 → "1"
                : String.format(Locale.US, "%.1f", floored);     // → "1.1"
        return num + SUFFIX[tier];
    }

    /**
     * Draw the compact amount in the top-right of an item cell whose icon top-left
     * is at screen-space {@code (x, y)} (16×16). No-op for non-currency items.
     * Called for every stack-overlay draw, so it covers hotbar + inventory + containers.
     */
    public static void render(DrawContext context, int x, int y, ItemStack stack) {
        String txt = compact(stack);
        if (txt == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        int w = tr.getWidth(txt);
        // Bigger by default; shrink only if a long label ("999.9Q") would overflow the cell.
        float scale = Math.min(BASE_SCALE, MAX_WIDTH / (float) Math.max(1, w));

        org.joml.Matrix3x2fStack matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x + 16f, (float) y); // anchor at the cell's top-right corner
        matrices.scale(scale, scale);
        context.drawText(tr, txt, -w, 0, COLOR, true); // right-aligned, glyph tops at anchor y
        matrices.popMatrix();
    }

    private CurrencyAmountOverlay() {}
}
