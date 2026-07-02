package com.aleks.ancientsmod.client.wiki;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns an item's rendered tooltip lines into a list of {@link WikiLink}s
 * (which words on which lines are clickable, and which article each opens).
 *
 * <p>Works on <b>any</b> armor trim — both the trim item (paper) and a piece of
 * armor with a trim applied. Matching is deliberately <b>loose</b>: it keys off
 * colour-stripped, lower-cased keywords rather than exact strings, so it survives
 * the server rewording its trim lore. Trim-stat linking is scoped to the trim block
 * of the tooltip (from the "Trim:" / "Set bonus" / "Full Set Ability" marker down to
 * the "…all 4 pieces…" footer) so the gear/enchant lines on trimmed armor and the
 * flavour lore under the footer are left alone.
 *
 * <p>A short list of unambiguous standalone keywords ({@link #GLOBAL_PHRASES}, e.g.
 * "Death Ward") is also linked <b>anywhere</b> in the tooltip — so the same shared
 * mechanic is clickable on the trim that grants it, the enchant that grants it, and
 * the rune that grants it alike, none of which share a tooltip shape.
 */
public final class WikiLinkResolver {

    /**
     * Noun / ability phrases. Ordered MOST-SPECIFIC FIRST so a longer phrase
     * claims its span before a shorter substring can grab part of it (e.g.
     * "meteorite mining speed" wins over "mining speed"; "pve damage" wins over
     * "damage taken" on a "less PvE damage taken" line). {phrase (lowercase), entryId}.
     */
    private static final String[][] PHRASES = {
            {"preserve meteorite blocks", "meteorite_preserve"},
            {"energy from meteorites", "meteorite_energy"},
            {"meteorite mining speed", "meteorite_speed"},
            {"damage to ignited enemies", "ignited"},
            {"damage to cell guards", "cell_guards"},
            {"damage from cell guards", "cell_guards"},
            {"damage to cell doors", "cell_doors"},
            {"powerball proc chance", "powerball"},
            {"powerball chain proc", "powerball"},
            {"powerball split", "powerball"},
            {"chance to find shards", "shards"},
            {"outpost cap rate", "outpost_cap"},
            {"momentum cap", "momentum"},
            {"proc chances", "proc_chances"},
            {"proc chance", "proc_chances"},
            {"true damage", "true_damage"},
            {"phantom mines", "phantom_mines"},
            {"armor durability", "durability"},
            {"second roll", "second_roll"},
            {"pve damage", "pve_damage"},
            {"damage dealt", "damage_dealt"},
            {"damage taken", "damage_taken"},
            {"max health", "max_hp"},
            {"max hp", "max_hp"},
            {"mining speed", "mining_speed"},
            {"riftcharged", "powerball"},
            {"powerball", "powerball"},
            {"conquest", "conquest"},
            {"wither", "wither"},
            {"star-forged", "star_forged"},
            {"titan's grip", "claimed"},
            {"claimed", "claimed"},
            {"echoes", "phantom_mines"},
            {"cell guards", "cell_guards"},
            {"cell guard", "cell_guards"},
            {"cell doors", "cell_doors"},
            {"cell door", "cell_doors"},
            {"momentum", "momentum"},
            {"ignites", "ignited"},
            {"ignited", "ignited"},
            {"vanish", "vanish"},
            {"luck", "luck"},
            {"shards", "shards"},
            {"hits", "hit"},
            {"hit", "hit"},
            {"per piece", "per_piece"},
    };

    /**
     * Standalone keywords linkable ANYWHERE in the tooltip, not just inside a trim
     * block — for shared mechanics that appear across trims, enchants, and runes
     * (none of which share a tooltip shape). Keep this list tight and unambiguous.
     */
    private static final String[][] GLOBAL_PHRASES = {
            {"death ward", "death_ward"},
    };

    /** Stacking-modifier words — only linked on stat lines (lines containing a '%'). */
    private static final String[][] MODIFIERS = {
            {"increased", "increased"},
            {"reduced", "reduced"},
            {"more", "more"},
            {"less", "less"},
    };

    /** Resolve clickable wiki spans for {@code lines}, or an empty list if nothing links. */
    public static List<WikiLink> resolve(List<Text> lines) {
        if (lines == null || lines.isEmpty()) return List.of();

        List<WikiLink> links = new ArrayList<>();

        // Trim-stat linking: scoped to the trim block (first marker → "…4 pieces…"
        // footer). Non-trim items (enchanted gear, runes) have no such block and
        // skip straight to the global keyword pass below.
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String low = plain(lines.get(i)).toLowerCase(Locale.ROOT).trim();
            if (low.startsWith("trim:") || low.contains("set bonus") || low.contains("full set ability")) {
                start = i;
                break;
            }
        }
        if (start >= 0) {
            int end = lines.size() - 1;
            for (int i = start; i < lines.size(); i++) {
                if (plain(lines.get(i)).toLowerCase(Locale.ROOT).contains("4 pieces")) {
                    end = i;
                    break;
                }
            }
            for (int i = start; i <= end; i++) {
                String low = plain(lines.get(i)).toLowerCase(Locale.ROOT);
                if (low.isEmpty()) continue;
                List<int[]> claimed = new ArrayList<>();

                // Modifier words first (so they claim their short spans) — stat lines only.
                if (low.indexOf('%') >= 0) {
                    for (String[] m : MODIFIERS) {
                        int[] span = firstWord(low, m[0], claimed);
                        if (span != null) {
                            claimed.add(span);
                            links.add(new WikiLink(i, span[0], span[1], m[1]));
                        }
                    }
                }
                // Noun / ability phrases, longest-first, non-overlapping.
                for (String[] p : PHRASES) {
                    int[] span = firstWord(low, p[0], claimed);
                    if (span != null) {
                        claimed.add(span);
                        links.add(new WikiLink(i, span[0], span[1], p[1]));
                    }
                }
            }
        }

        // Global keyword pass: link unambiguous standalone mechanics (e.g. Death Ward)
        // anywhere they appear, on any item. Skips spans already claimed by the
        // trim-block pass so a keyword inside a trim block isn't linked twice.
        for (int i = 0; i < lines.size(); i++) {
            String low = plain(lines.get(i)).toLowerCase(Locale.ROOT);
            if (low.isEmpty()) continue;
            List<int[]> claimed = claimedOn(links, i);
            for (String[] g : GLOBAL_PHRASES) {
                int[] span = firstWord(low, g[0], claimed);
                if (span != null) {
                    claimed.add(span);
                    links.add(new WikiLink(i, span[0], span[1], g[1]));
                }
            }
        }

        return links;
    }

    /** Spans already linked on line {@code lineIndex}, so a later pass won't double-link. */
    private static List<int[]> claimedOn(List<WikiLink> links, int lineIndex) {
        List<int[]> out = new ArrayList<>();
        for (WikiLink l : links) {
            if (l.lineIndex() == lineIndex) out.add(new int[]{l.startCol(), l.endCol()});
        }
        return out;
    }

    /** Plain (formatting-free) text of a line. */
    private static String plain(Text t) {
        String s = t.getString();
        return s == null ? "" : s;
    }

    /**
     * First whole-word occurrence of {@code word} in the (already lower-cased)
     * string that doesn't overlap an already-claimed range; {start,end} or null.
     */
    private static int[] firstWord(String low, String word, List<int[]> claimed) {
        int from = 0;
        while (true) {
            int idx = low.indexOf(word, from);
            if (idx < 0) return null;
            int end = idx + word.length();
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(low.charAt(idx - 1));
            boolean rightOk = end >= low.length() || !Character.isLetterOrDigit(low.charAt(end));
            if (leftOk && rightOk && !overlaps(idx, end, claimed)) return new int[]{idx, end};
            from = idx + 1;
        }
    }

    private static boolean overlaps(int s, int e, List<int[]> claimed) {
        for (int[] c : claimed) {
            if (s < c[1] && c[0] < e) return true;
        }
        return false;
    }

    private WikiLinkResolver() {}
}
