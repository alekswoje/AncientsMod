package com.aleks.prisonsmod.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses enchant-marked tooltip lines behind Shift.
 *
 * <p>The server (PrisonsCore) prefixes every pickaxe enchant lore line with
 * a zero-width space ({@code \u200B}). Vanilla clients see no difference —
 * the character is invisible. This handler detects those marked lines and,
 * when Shift isn't held, replaces the contiguous block with a single
 * placeholder line so the tooltip doesn't dominate the screen.
 *
 * <p>When Shift is held, lines pass through untouched. The zero-width space
 * stays in the text but renders as nothing, so there's no visual cost.
 *
 * <p>Fires for every tooltip the client renders, so it covers both inventory
 * hover and chat {@code [item]} hover without any extra wiring.
 */
public final class TooltipCollapse {

    private static final String MARKER = "\u200B";

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (!ServerAllowlist.isAllowed()) return;
            if (!FeatureToggles.isEnchantCollapseEnabled()) return;
            if (isShiftDown()) return;
            collapseMarkedLines(lines);
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

    private static void collapseMarkedLines(List<Text> lines) {
        int firstMarked = -1;
        int lastMarked = -1;
        int count = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (isMarked(lines.get(i))) {
                if (firstMarked < 0) firstMarked = i;
                lastMarked = i;
                count++;
            }
        }
        if (count <= 1) return;

        Text placeholder = Text.literal("... ")
                .formatted(Formatting.DARK_GRAY)
                .append(Text.literal(count + " enchants").formatted(Formatting.GRAY))
                .append(Text.literal(" (hold ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("Shift").formatted(Formatting.WHITE))
                .append(Text.literal(")").formatted(Formatting.DARK_GRAY));

        List<Text> kept = new ArrayList<>(lines.subList(0, firstMarked));
        kept.add(placeholder);
        if (lastMarked + 1 < lines.size()) {
            kept.addAll(lines.subList(lastMarked + 1, lines.size()));
        }
        lines.clear();
        lines.addAll(kept);
    }

    private static boolean isMarked(Text line) {
        if (line == null) return false;
        String s = line.getString();
        return s != null && s.contains(MARKER);
    }

    private TooltipCollapse() {}
}
