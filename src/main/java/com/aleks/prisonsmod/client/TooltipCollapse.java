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
 * Trims the enchant block on RooPrisons items.
 *
 * <ul>
 *   <li>Default — keep the top {@link #SHOWN_BY_DEFAULT} highest-rarity
 *       enchants (the server orders the block by rarity) and replace the
 *       rest with "{@code ... X more (hold Shift)}".</li>
 *   <li>With Shift held — show the full enchant list, untouched.</li>
 * </ul>
 *
 * <p>RooPrisons items are detected via presence of any
 * {@code minecraft:custom_data} NBT, which the server-side plugin always
 * writes on pickaxe/gear PDC markers.
 *
 * <p>Fires for every tooltip the client renders, so it covers inventory
 * hover and chat {@code [item]} hover with no extra wiring.
 */
public final class TooltipCollapse {

    /** Number of enchant lines to keep visible in the default (non-Shift) state. */
    private static final int SHOWN_BY_DEFAULT = 5;

    /** Only trim when there are more enchants than we'd show by default. */
    private static final int MIN_LINES_TO_COLLAPSE = SHOWN_BY_DEFAULT + 1;

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (!ServerAllowlist.isAllowed()) return;
            if (!FeatureToggles.isEnchantCollapseEnabled()) return;
            if (!looksLikeRooPrisonsItem(stack)) return;
            if (isShiftDown()) return; // show everything untouched
            applyEnchantTrim(lines);
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

    private static boolean looksLikeRooPrisonsItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        return custom != null && !custom.isEmpty();
    }

    private static void applyEnchantTrim(List<Text> lines) {
        if (lines.size() < MIN_LINES_TO_COLLAPSE + 1) return;

        int start = 1; // skip the display-name line at index 0
        int end = start;
        while (end < lines.size() && !isBlank(lines.get(end))) {
            end++;
        }
        int count = end - start;
        if (count <= SHOWN_BY_DEFAULT) return; // nothing to hide

        int hidden = count - SHOWN_BY_DEFAULT;
        List<Text> rebuilt = new ArrayList<>(lines.size() - hidden + 1);
        rebuilt.addAll(lines.subList(0, start + SHOWN_BY_DEFAULT));
        rebuilt.add(buildSummaryLine(hidden));
        rebuilt.addAll(lines.subList(end, lines.size()));

        lines.clear();
        lines.addAll(rebuilt);
    }

    private static Text buildSummaryLine(int hidden) {
        return Text.literal("... ")
                .formatted(Formatting.DARK_GRAY)
                .append(Text.literal(hidden + " more").formatted(Formatting.GRAY))
                .append(Text.literal(" (hold ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("Shift").formatted(Formatting.WHITE))
                .append(Text.literal(")").formatted(Formatting.DARK_GRAY));
    }

    private static boolean isBlank(Text line) {
        if (line == null) return true;
        String s = line.getString();
        return s == null || s.isEmpty() || s.trim().isEmpty();
    }

    private TooltipCollapse() {}
}
