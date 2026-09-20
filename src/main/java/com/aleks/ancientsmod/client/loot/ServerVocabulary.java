package com.aleks.ancientsmod.client.loot;

import com.aleks.ancientsmod.net.Protocol;

import java.util.Locale;
import java.util.Set;

/**
 * Tracks which generation of the plugin's rarity vocabulary the CURRENT
 * server speaks (ANCT-525). The live map-1/2 cluster still levels loot and
 * tallies drops as COMMON/UNCOMMON/RARE/EPIC/LEGENDARY/MYTHIC; the map-3+ line
 * renamed the same six levels to SIMPLE/UNCOMMON/ELITE/ULTIMATE/LEGENDARY/GODLY.
 * Nothing on the wire says outright which one a given server runs — the rename
 * shipped without a protocol-minor bump — so this infers it from side signals
 * that DO arrive, and defaults to the OLD vocabulary, since that is what every
 * server the public jar actually talks to today speaks.
 *
 * <h2>Signals, in priority order</h2>
 * <ol>
 *   <li>Jewel socket count at join. The fourth jewel socket landed on {@code
 *       dev} alongside the rename, and the plugin only sends four sockets
 *       ({@link Protocol#MAX_JEWEL_SLOTS}) to a client on {@link
 *       Protocol#PROTOCOL_MINOR} 8+ once the server itself knows about them —
 *       an un-renamed server always sends three. The count is the server's to
 *       state (not gated by the player's own prestige), so it is a real
 *       generation tell rather than a per-player artifact. See {@code
 *       JewelSlotsPayload}'s width note.</li>
 *   <li>Any string-keyed rarity data actually seen this session (PvE drop
 *       tally keys today — see {@code PveStatsState}). A key carrying a word
 *       that exists ONLY in the new ladder (simple / elite / ultimate / godly)
 *       proves the server is renamed. "uncommon" and "legendary" are shared by
 *       both ladders and prove nothing either way.</li>
 * </ol>
 *
 * <h2>Failure mode</h2>
 * <p>Both signals are inferred, not declared. A freshly-joined player on a
 * renamed server sees old words until the first jewel push or rarity-bearing
 * drop arrives (usually within seconds); a session that never triggers either
 * signal (e.g. a short hub-only visit) reads as old even on a renamed server.
 * That is the intended failure direction — defaulting to old is defaulting to
 * what live actually runs, so a wrong guess on the common path shows real
 * words a season early rather than invented ones on the server that matters.
 * Once either signal lands the guess is sticky for the rest of the session.
 */
public final class ServerVocabulary {

    /** Words that exist only in the post-rename ladder — seeing one proves the server is renamed. */
    private static final Set<String> NEW_ONLY_WORDS = Set.of("simple", "elite", "ultimate", "godly");

    private static volatile boolean newGeneration = false;

    private ServerVocabulary() {}

    /** Fed from {@code JewelState.update} with the slot count of every {@code PKT_JEWEL_SLOTS} push. */
    public static void noteJewelSlotCount(int slotCount) {
        if (slotCount >= Protocol.MAX_JEWEL_SLOTS) {
            newGeneration = true;
        }
    }

    /**
     * Fed with each raw string key the server sends wherever a rarity word might
     * appear (today: PvE drop-tally keys). Bare keys ("elite") and subtyped keys
     * ("lootbox:elite_booster_box") are both handled by token-splitting on
     * {@code :} and {@code _}.
     */
    public static void noteKey(String key) {
        if (key == null || key.isEmpty()) return;
        for (String token : key.toLowerCase(Locale.ROOT).split("[:_]")) {
            if (NEW_ONLY_WORDS.contains(token)) {
                newGeneration = true;
                return;
            }
        }
    }

    /** True once either signal has fired this session; false (old vocabulary) until then. */
    public static boolean isNewGeneration() {
        return newGeneration;
    }

    /** Reset on disconnect — the next server this session joins may speak either vocabulary. */
    public static void reset() {
        newGeneration = false;
    }
}
