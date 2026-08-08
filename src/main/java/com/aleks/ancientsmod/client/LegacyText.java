package com.aleks.ancientsmod.client;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

/**
 * Renders a legacy section-coded string — the form everything the server
 * bundles for us arrives in (item names, lore lines, jewel stat lines) — as a
 * styled {@link Text}.
 *
 * <p>Lives here rather than on a screen because more than one surface needs
 * it: the item/cell terminals, and the jewel sockets and HUD. Re-colouring
 * server text a flat colour throws away the meaning the colours carry (a
 * jewel's tier badge is colour-coded, so a green repaint loses the tier).
 */
public final class LegacyText {

    private LegacyText() {}

    /**
     * Parses {@code s} into styled text, including the BungeeCord
     * §x§r§r§g§g§b§b hex format. The vanilla parser doesn't understand §x, so
     * it collapses a hex run to the last nibble's colour — a #FF0000 name
     * renders black — which is why coloured names looked wrong. Here we expand
     * the run to a true RGB {@link TextColor}. Defensive: a dangling / partial
     * § at the end (e.g. a truncation artifact) is dropped rather than
     * mis-coloured.
     */
    public static Text parse(String s) {
        if (s == null || s.isEmpty()) return Text.empty();
        MutableText root = Text.empty();
        StringBuilder run = new StringBuilder();
        Style style = Style.EMPTY;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < n) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                if (code == 'x' && i + 13 < n) {
                    String hex = readHexRun(s, i);
                    if (hex != null) {
                        flushRun(root, run, style);
                        style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
                        i += 14;
                        continue;
                    }
                }
                Formatting fmt = Formatting.byCode(code);
                if (fmt != null) {
                    flushRun(root, run, style);
                    style = applyCode(style, fmt);
                }
                i += 2; // consume the token (unknown codes are dropped, like vanilla)
                continue;
            }
            run.append(c);
            i++;
        }
        flushRun(root, run, style);
        return root;
    }

    /** Parse a §x§r§r§g§g§b§b run starting at {@code i} into 6 hex digits, or null
     *  if the 14-char shape isn't intact (e.g. a truncated tail). */
    private static String readHexRun(String s, int i) {
        StringBuilder hex = new StringBuilder(6);
        for (int k = 0; k < 6; k++) {
            int p = i + 2 + k * 2;
            if (p + 1 >= s.length() || s.charAt(p) != '§') return null;
            char h = s.charAt(p + 1);
            if (Character.digit(h, 16) < 0) return null;
            hex.append(h);
        }
        return hex.toString();
    }

    private static Style applyCode(Style style, Formatting fmt) {
        if (fmt == Formatting.RESET) return Style.EMPTY;
        if (fmt.isColor()) return Style.EMPTY.withColor(fmt); // a colour resets modifiers (vanilla)
        return switch (fmt) {
            case BOLD -> style.withBold(true);
            case ITALIC -> style.withItalic(true);
            case UNDERLINE -> style.withUnderline(true);
            case STRIKETHROUGH -> style.withStrikethrough(true);
            case OBFUSCATED -> style.withObfuscated(true);
            default -> style;
        };
    }

    private static void flushRun(MutableText root, StringBuilder run, Style style) {
        if (run.length() == 0) return;
        root.append(Text.literal(run.toString()).setStyle(style));
        run.setLength(0);
    }
}
