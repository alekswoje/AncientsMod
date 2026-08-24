package com.aleks.ancientsmod.client.nametag;

import java.util.List;

/**
 * The colour-code contract between the rename GUI and the server.
 *
 * <p>Everything here is a deliberate mirror of PrisonsCore's
 * {@code ItemNametagListener.applyColorCodes} / {@code stripAllColorCodes}. The
 * whole point of the GUI is that what you see while typing is what the item
 * ends up called, so the preview must translate the string the same way the
 * server will and the character counter must count what the server counts. If
 * the server's rules ever change, this file changes with them.
 *
 * <h2>The two rules that are easy to get wrong</h2>
 * <ul>
 *   <li><b>Colour codes are free.</b> The server's 32-character limit is measured
 *       on the STRIPPED string, so a fully hex-coloured name still costs 32.
 *       That is what makes per-character gradients viable at all.</li>
 *   <li><b>Stripping is asymmetric.</b> The server strips {@code &#RRGGBB} and
 *       {@code &[0-9A-FK-OR]} — note that {@code &x} is NOT in the strip set even
 *       though {@code translateAlternateColorCodes} does translate it. Mirrored
 *       exactly, quirk included, so the counter never disagrees with the server.</li>
 * </ul>
 */
public final class NametagFormat {

    /** Codes Bukkit's {@code translateAlternateColorCodes} accepts after '&'. */
    private static final String TRANSLATABLE = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";

    /** The 16 legacy colours, in vanilla code order, with their true RGB. */
    public static final char[] COLOR_CODES = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };
    public static final int[] COLOR_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    public static final String[] COLOR_NAMES = {
            "Black", "Dark Blue", "Dark Green", "Dark Aqua", "Dark Red", "Dark Purple", "Gold", "Gray",
            "Dark Gray", "Blue", "Green", "Aqua", "Red", "Light Purple", "Yellow", "White"
    };

    /** Format codes, in the order the toolbar shows them. */
    public static final char[] FORMAT_CODES = { 'l', 'o', 'n', 'm', 'k' };
    public static final String[] FORMAT_NAMES = { "Bold", "Italic", "Underline", "Strikethrough", "Obfuscated" };

    private NametagFormat() {}

    // ── Server mirror ────────────────────────────────────────────────────────

    /**
     * '&'-form to section-form, exactly as the server does it: expand
     * {@code &#RRGGBB} into the BungeeCord {@code §x§R§R§G§G§B§B} run first, then
     * translate the remaining single-character codes.
     *
     * <p>Feed the result to {@code LegacyText.parse} to render it.
     */
    public static String toSection(String input) {
        if (input == null || input.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(input.length() + 16);
        int i = 0;
        int n = input.length();
        while (i < n) {
            if (input.charAt(i) == '&' && i + 7 < n && input.charAt(i + 1) == '#'
                    && isHexRun(input, i + 2)) {
                sb.append((char) 0xA7).append('x');
                for (int k = 0; k < 6; k++) {
                    sb.append((char) 0xA7).append(input.charAt(i + 2 + k));
                }
                i += 8;
                continue;
            }
            sb.append(input.charAt(i));
            i++;
        }
        // Second pass mirrors ChatColor.translateAlternateColorCodes('&', ...).
        char[] out = sb.toString().toCharArray();
        for (int k = 0; k + 1 < out.length; k++) {
            if (out[k] == '&' && TRANSLATABLE.indexOf(out[k + 1]) > -1) {
                out[k] = (char) 0xA7;
                out[k + 1] = Character.toLowerCase(out[k + 1]);
            }
        }
        return new String(out);
    }

    /**
     * The string with every colour code removed — what the server measures against
     * its length cap, and what a gradient is laid out over.
     */
    public static String strip(String input) {
        if (input == null || input.isEmpty()) return "";
        // Pass 1: &#RRGGBB
        StringBuilder pass1 = new StringBuilder(input.length());
        int i = 0;
        int n = input.length();
        while (i < n) {
            if (input.charAt(i) == '&' && i + 7 < n && input.charAt(i + 1) == '#'
                    && isHexRun(input, i + 2)) {
                i += 8;
                continue;
            }
            pass1.append(input.charAt(i));
            i++;
        }
        // Pass 2: &[0-9A-FK-OR], case-insensitive. Deliberately excludes 'x' to
        // match the server's strip pattern.
        StringBuilder out = new StringBuilder(pass1.length());
        i = 0;
        n = pass1.length();
        while (i < n) {
            if (pass1.charAt(i) == '&' && i + 1 < n && isStrippableCode(pass1.charAt(i + 1))) {
                i += 2;
                continue;
            }
            out.append(pass1.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** Visible character count — the number the server compares against its cap. */
    public static int visibleLength(String input) {
        return strip(input).length();
    }

    private static boolean isStrippableCode(char c) {
        char l = Character.toLowerCase(c);
        return (l >= '0' && l <= '9') || (l >= 'a' && l <= 'f') || (l >= 'k' && l <= 'o') || l == 'r';
    }

    private static boolean isHexRun(String s, int at) {
        for (int k = 0; k < 6; k++) {
            if (Character.digit(s.charAt(at + k), 16) < 0) return false;
        }
        return true;
    }

    // ── Authoring helpers ────────────────────────────────────────────────────

    /** {@code "&#RRGGBB"} for an RGB int, upper-case, as the GUI inserts it. */
    public static String hexToken(int rgb) {
        return "&#" + String.format("%06X", rgb & 0xFFFFFF);
    }

    /**
     * The colour code active at {@code caret}, as a display string ("&a",
     * "&#FF55AA") or empty if the text before the caret carries no colour yet.
     * Powers the "colour at cursor" readout so you can see what you are editing.
     */
    public static String colorAtCaret(String input, int caret) {
        if (input == null || input.isEmpty()) return "";
        int limit = Math.max(0, Math.min(caret, input.length()));
        String found = "";
        int i = 0;
        while (i < limit) {
            if (input.charAt(i) == '&' && i + 7 < input.length() && input.charAt(i + 1) == '#'
                    && isHexRun(input, i + 2) && i + 8 <= limit) {
                found = input.substring(i, i + 8);
                i += 8;
                continue;
            }
            if (input.charAt(i) == '&' && i + 1 < input.length() && i + 2 <= limit) {
                char c = Character.toLowerCase(input.charAt(i + 1));
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                    found = input.substring(i, i + 2);
                    i += 2;
                    continue;
                }
                if (c == 'r') {
                    found = "";
                    i += 2;
                    continue;
                }
            }
            i++;
        }
        return found;
    }

    /** RGB of a colour token as returned by {@link #colorAtCaret}, or -1 if there is none. */
    public static int rgbOfToken(String token) {
        if (token == null || token.length() < 2 || token.charAt(0) != '&') return -1;
        if (token.length() == 8 && token.charAt(1) == '#') {
            try {
                return Integer.parseInt(token.substring(2), 16);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        char c = Character.toLowerCase(token.charAt(1));
        for (int i = 0; i < COLOR_CODES.length; i++) {
            if (COLOR_CODES[i] == c) return COLOR_RGB[i];
        }
        return -1;
    }

    /**
     * Re-colours the visible text as a gradient across {@code stops}, re-emitting
     * {@code formats} on every character.
     *
     * <p>The per-character re-emit is not redundant: in Minecraft a colour code
     * RESETS the active formatting, so a gradient laid over a bold name silently
     * un-bolds it from the first character onward unless the format code follows
     * each colour.
     *
     * <p>Returns null when the encoded result would exceed {@code maxEncoded} —
     * every character costs 9 characters of hex plus 2 per format, so a long name
     * with several formats can outrun the packet's cap. The caller surfaces that
     * as a message rather than sending a truncated string.
     */
    public static String applyGradient(String input, List<Integer> stops, String formats, int maxEncoded) {
        String plain = strip(input);
        if (plain.isEmpty() || stops == null || stops.size() < 2) return input;

        String fmt = formats == null ? "" : formats;
        StringBuilder out = new StringBuilder(plain.length() * 12);
        int len = plain.length();
        for (int i = 0; i < len; i++) {
            float t = len == 1 ? 0f : (float) i / (float) (len - 1);
            out.append(hexToken(sample(stops, t)));
            out.append(fmt);
            out.append(plain.charAt(i));
        }
        String result = out.toString();
        return result.length() > maxEncoded ? null : result;
    }

    /** Piecewise-linear sample across N stops at {@code t} in [0,1]. */
    public static int sample(List<Integer> stops, float t) {
        int n = stops.size();
        if (n == 1) return stops.get(0);
        float scaled = Math.max(0f, Math.min(1f, t)) * (n - 1);
        int idx = (int) Math.floor(scaled);
        if (idx >= n - 1) return stops.get(n - 1);
        return lerpRgb(stops.get(idx), stops.get(idx + 1), scaled - idx);
    }

    public static int lerpRgb(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Parses "RRGGBB" or "#RRGGBB" (any case) into an RGB int, or -1 if malformed. */
    public static int parseHexInput(String s) {
        if (s == null) return -1;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return -1;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(t.charAt(i), 16) < 0) return -1;
        }
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
