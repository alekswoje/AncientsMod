package com.aleks.prisonsmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws the dust percentage (e.g. "45%") in the bottom-right corner of
 * Enchant Dust and Calcified Dust item slots, matching the dust's name color.
 *
 * <p>PrisonsCore stores the value as a PDC integer but also writes it into the
 * lore as "Success Boost: +45%" / "Calcite Boost: +25%", which syncs to the
 * client. We parse the lore rather than NBT so no server change is needed.
 * Gated to PrisonsCore items (non-empty {@code custom_data}).
 */
public final class DustPercentOverlay {

    private static final float SCALE = 0.5f;
    private static final int ENCHANT_COLOR = 0xFFFFC83C;  // gold (matches Enchant Dust name)
    private static final int CALCIFIED_COLOR = 0xFFFF55FF; // light purple (matches Calcified Dust name)

    private static final Pattern PCT = Pattern.compile("\\+(\\d+)%");

    public static void render(DrawContext context, int x, int y, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom == null || custom.isEmpty()) return;

        Text nameText = stack.getName();
        if (nameText == null) return;
        String name = nameText.getString();

        int color;
        if (name.contains("Enchant Dust")) {
            color = ENCHANT_COLOR;
        } else if (name.contains("Calcified Dust")) {
            color = CALCIFIED_COLOR;
        } else {
            return;
        }

        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return;

        int pct = -1;
        for (Text line : lore.lines()) {
            Matcher m = PCT.matcher(line.getString());
            if (m.find()) {
                try {
                    pct = Integer.parseInt(m.group(1));
                    break;
                } catch (NumberFormatException ignored) {}
            }
        }
        if (pct < 0) return;

        String label = pct + "%";
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int w = tr.getWidth(label);
        org.joml.Matrix3x2fStack mat = context.getMatrices();
        mat.pushMatrix();
        mat.translate(x + 16f, (float) y);   // top-right corner
        mat.scale(SCALE, SCALE);
        context.drawText(tr, Text.literal(label).formatted(Formatting.BOLD), -w, 0, color, true);
        mat.popMatrix();
    }

    private DustPercentOverlay() {}
}
