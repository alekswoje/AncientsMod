package com.aleks.ancientsmod.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Hides the grey roll ranges on rolled items ("+4.7 to maximum Life (4-6)") unless Alt is held,
 * the way Path of Exile shows them on Alt.
 *
 * <p>The server always writes the ranges into the lore, so players without the mod keep seeing
 * them. Only a grey run that is nothing but ranges ({@code (4-6)}, {@code (10-20, 2-4)}) is
 * dropped; other grey brackets such as {@code (0 / 14,200)} stay. Registered after the wiki
 * tooltip, which snapshots the tooltip while Alt is held, so the pinned copy keeps its ranges.
 */
public final class RollRanges {

    private static final String NUM = "\\d+(?:\\.\\d+)?%?";
    private static final String RANGE = NUM + "\\s*-\\s*" + NUM;
    private static final Pattern RANGES = Pattern.compile("\\s*\\(" + RANGE + "(?:,\\s*" + RANGE + ")*\\)");

    private static final TextColor GRAY = TextColor.fromFormatting(Formatting.GRAY);
    private static final TextColor DARK_GRAY = TextColor.fromFormatting(Formatting.DARK_GRAY);

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (!ServerAllowlist.isAllowed()) return;
            if (isAltDown()) return;
            for (int i = 1; i < lines.size(); i++) {
                Text stripped = strip(lines.get(i));
                if (stripped != null) lines.set(i, stripped);
            }
        });
    }

    /** The line without its range runs, or the line itself when Alt is held. */
    public static Text unlessAlt(Text line) {
        if (isAltDown()) return line;
        Text stripped = strip(line);
        return stripped != null ? stripped : line;
    }

    /** The line without its range runs, or null when it has none. */
    static Text strip(Text line) {
        boolean[] changed = {false};
        MutableText out = Text.empty();
        line.visit((style, str) -> {
            if (str.isEmpty()) return Optional.empty();
            String kept = str;
            if (isGrey(style)) {
                kept = RANGES.matcher(str).replaceAll("");
                if (!kept.equals(str)) changed[0] = true;
            }
            if (!kept.isEmpty()) out.append(Text.literal(kept).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return changed[0] ? out : null;
    }

    private static boolean isGrey(Style style) {
        TextColor c = style.getColor();
        return GRAY.equals(c) || DARK_GRAY.equals(c);
    }

    private static boolean isAltDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return false;
        Window window = client.getWindow();
        if (window == null) return false;
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    private RollRanges() {}
}
