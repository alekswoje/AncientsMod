package com.aleks.prisonsmod.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses the enchant block on RooPrisons items behind Shift.
 *
 * <p>RooPrisons pickaxes and gear put their enchants as the first block of
 * lore lines, terminated by a blank line. When the item has any custom NBT
 * (the server-side plugin always sets PDC data on its items, so
 * {@code minecraft:custom_data} is non-empty), and the first lore block is
 * more than one line, we replace it with a single placeholder unless Shift
 * is held.
 *
 * <p>Vanilla items always pass through untouched: either they have no
 * custom_data, or their lore isn't structured as a leading enchant block.
 *
 * <p>Fires for every tooltip the client renders, so it covers both inventory
 * hover and chat {@code [item]} hover without any extra wiring.
 */
public final class TooltipCollapse {

    /**
     * Minimum number of consecutive enchant-looking lines before we bother
     * collapsing. A 1–2 line tooltip block isn't worth the indirection.
     */
    private static final int MIN_LINES_TO_COLLAPSE = 3;

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (!ServerAllowlist.isAllowed()) return;
            if (!FeatureToggles.isEnchantCollapseEnabled()) return;
            if (isShiftDown()) return;
            if (!looksLikeRooPrisonsItem(stack)) return;
            collapseLeadingBlock(lines);
        });
    }

    private static boolean isShiftDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return false;
        Window window = client.getWindow();
        if (window == null) return false;
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /**
     * An item is treated as a RooPrisons pickaxe/gear for collapse purposes
     * if it carries any custom NBT (the server-side plugin always sets PDC
     * markers on its items). Vanilla unmodified items are skipped so their
     * enchanted-book tooltips etc. are never touched.
     */
    private static boolean looksLikeRooPrisonsItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        return custom != null && !custom.isEmpty();
    }

    /**
     * Replace the first contiguous block of non-blank lore lines (after the
     * display-name line at index 0) with a collapsed placeholder.
     */
    private static void collapseLeadingBlock(List<Text> lines) {
        if (lines.size() < MIN_LINES_TO_COLLAPSE + 1) return;

        int start = 1; // skip the display-name line at index 0
        int end = start;
        while (end < lines.size() && !isBlank(lines.get(end))) {
            end++;
        }
        int count = end - start;
        if (count < MIN_LINES_TO_COLLAPSE) return;

        Text placeholder = Text.literal("... ")
                .formatted(Formatting.DARK_GRAY)
                .append(Text.literal(count + " enchants").formatted(Formatting.GRAY))
                .append(Text.literal(" (hold ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("Shift").formatted(Formatting.WHITE))
                .append(Text.literal(")").formatted(Formatting.DARK_GRAY));

        List<Text> kept = new ArrayList<>(lines.subList(0, start));
        kept.add(placeholder);
        kept.addAll(lines.subList(end, lines.size()));
        lines.clear();
        lines.addAll(kept);
    }

    private static boolean isBlank(Text line) {
        if (line == null) return true;
        String s = line.getString();
        return s == null || s.isEmpty() || s.trim().isEmpty();
    }

    private TooltipCollapse() {}
}
