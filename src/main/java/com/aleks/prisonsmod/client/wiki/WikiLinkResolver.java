package com.aleks.prisonsmod.client.wiki;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns an item's rendered tooltip lines into a list of {@link WikiLink}s
 * (which words on which lines are clickable, and which article each opens).
 *
 * <p>Matching is deliberately <b>loose</b>: it keys off colour-stripped,
 * lower-cased keywords rather than exact strings, so it survives the server
 * plugin rewording its trim lore (e.g. "more" vs "increased", "less" vs
 * "reduced", "Full set bonus:" vs "Set bonus (25% per piece):"). For now it
 * only fires on the Spectral Trim; other trims can be added by relaxing the
 * gate and pointing the ability line at a different article.
 */
public final class WikiLinkResolver {

    /** Resolve clickable wiki spans for {@code lines}, or an empty list if this isn't a wired-up item. */
    public static List<WikiLink> resolve(List<Text> lines) {
        if (lines == null || lines.isEmpty()) return List.of();

        List<WikiLink> links = new ArrayList<>();

        // PvE Damage: wired for any trim that shows a "PvE damage" stat (e.g. Olympian),
        // independent of the Spectral-only gate below. Both the "increased PvE damage" and
        // "reduced PvE damage taken" lines link to the same explainer.
        for (int i = 0; i < lines.size(); i++) {
            String low = plain(lines.get(i)).toLowerCase(Locale.ROOT);
            int idx = low.indexOf("pve damage");
            if (idx >= 0) links.add(new WikiLink(i, idx, idx + "pve damage".length(), "pve_damage"));
        }

        // Gate: the remaining mappings are Spectral-Trim-specific for this first version.
        boolean isSpectral = false;
        for (Text t : lines) {
            if (plain(t).toLowerCase(Locale.ROOT).contains("spectral trim")) {
                isSpectral = true;
                break;
            }
        }
        if (!isSpectral) return links;

        for (int i = 0; i < lines.size(); i++) {
            String text = plain(lines.get(i));
            String low = text.toLowerCase(Locale.ROOT);

            if (low.contains("damage dealt")) {
                // Two clickables: the stacking modifier word, and the generic "damage dealt" phrase.
                int[] span = word(low, "more");
                if (span != null) links.add(new WikiLink(i, span[0], span[1], "more"));
                else {
                    span = word(low, "increased");
                    if (span != null) links.add(new WikiLink(i, span[0], span[1], "increased"));
                }
                int dd = low.indexOf("damage dealt");
                links.add(new WikiLink(i, dd, dd + "damage dealt".length(), "damage_dealt"));

            } else if (low.contains("damage taken")) {
                int[] span = word(low, "less");
                if (span != null) links.add(new WikiLink(i, span[0], span[1], "less"));
                else {
                    span = word(low, "reduced");
                    if (span != null) links.add(new WikiLink(i, span[0], span[1], "reduced"));
                }
                // Skip the generic phrase on PvE lines — the PvE pass already links "PvE damage".
                if (!low.contains("pve")) {
                    int dt = low.indexOf("damage taken");
                    links.add(new WikiLink(i, dt, dt + "damage taken".length(), "damage_taken"));
                }

            } else if (low.contains("max hp") || low.contains("max health")) {
                int idx = low.indexOf("max hp");
                int len = 6;
                if (idx < 0) { idx = low.indexOf("max health"); len = 10; }
                links.add(new WikiLink(i, idx, idx + len, "max_hp"));

            } else if (low.contains("each piece grants") || low.contains("of the above")) {
                int start = firstNonSpace(text);
                int end = trimmedEnd(text);
                if (end > start) links.add(new WikiLink(i, start, end, "per_piece"));

            } else if (low.contains("per piece") && !low.contains("set bonus")) {
                // A standalone "25% per piece" note — but not the "Set bonus (25% per piece):" header.
                int idx = low.indexOf("per piece");
                links.add(new WikiLink(i, idx, idx + "per piece".length(), "per_piece"));
            }

            // Ability-description terms, independent of the stat lines above. The
            // ability *name* (e.g. "Phantom Veil") is intentionally not linked.
            int[] hit = word(low, "hit");
            if (hit != null) links.add(new WikiLink(i, hit[0], hit[1], "hit"));
            int[] vanish = word(low, "vanish");
            if (vanish != null) links.add(new WikiLink(i, vanish[0], vanish[1], "vanish"));
        }
        return links;
    }

    /** Plain (formatting-free) text of a line. */
    private static String plain(Text t) {
        String s = t.getString();
        return s == null ? "" : s;
    }

    /** Find a whole-word occurrence of {@code word} in the (already lower-cased) string; returns {start,end} or null. */
    private static int[] word(String low, String word) {
        int from = 0;
        while (true) {
            int idx = low.indexOf(word, from);
            if (idx < 0) return null;
            int end = idx + word.length();
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(low.charAt(idx - 1));
            boolean rightOk = end >= low.length() || !Character.isLetterOrDigit(low.charAt(end));
            if (leftOk && rightOk) return new int[]{idx, end};
            from = idx + 1;
        }
    }

    private static int firstNonSpace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int trimmedEnd(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return i;
    }

    private WikiLinkResolver() {}
}
