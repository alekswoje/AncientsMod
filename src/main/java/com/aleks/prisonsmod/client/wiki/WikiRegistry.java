package com.aleks.prisonsmod.client.wiki;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-mod wiki's article store. Seeded with the terms the Spectral Trim
 * tooltip exposes; the generic articles are reusable by other trims later, with
 * only the per-set ability being item-specific.
 *
 * <p><b>“Increased/Reduced” vs “More/Less” is the key distinction</b> and they
 * are deliberately separate articles:
 * <ul>
 *   <li><b>Increased / Reduced</b> — <i>additive</i>. All sources add into one
 *       shared pool, then apply once. Reduced is capped at 100% (= immunity),
 *       which is why it's rarely used as raw DR.</li>
 *   <li><b>More / Less</b> — <i>multiplicative</i>. Each instance is an
 *       independent multiplier (50% less ×3 = ×0.5×0.5×0.5 = ×0.125, never
 *       150% and never 100%).</li>
 * </ul>
 * Keep entries short — one tight paragraph each. Edit the strings to reword.
 */
public final class WikiRegistry {

    private static final Map<String, WikiEntry> ENTRIES = new LinkedHashMap<>();

    static {
        register(new WikiEntry("increased", "“Increased”", List.of(
                "Additive. Every “increased” bonus to the same stat adds into one shared pool, and that total is applied once — 5% increased and 10% increased give 15% increased, not two separate steps. (A flat “+5%” base is a different thing and isn't part of this pool.) The multiplicative counterpart is “More.”")));

        register(new WikiEntry("reduced", "“Reduced”", List.of(
                "Additive. All your “reduced” bonuses to the same stat add into one shared pool, capped at 100% — at 100% the stat is fully negated (e.g. total damage immunity). Because stacking it can reach that cap, it's used sparingly. The multiplicative counterpart is “Less.”")));

        register(new WikiEntry("more", "“More”", List.of(
                "Multiplicative. Each “more” is its own independent multiplier — they never share a pool, so each keeps its full value. Three 50% more = ×1.5 × 1.5 × 1.5, not 150% more. The additive counterpart is “Increased.”")));

        register(new WikiEntry("less", "“Less”", List.of(
                "Multiplicative. Each “less” is an independent multiplier — they stack by multiplying, not adding. 50% less three times = ×0.5 × 0.5 × 0.5, so you take 12.5% — never 150%, and never a full 100%. The additive counterpart is “Reduced.”")));

        register(new WikiEntry("max_hp", "Max HP", List.of(
                "Raises your maximum health: +4 Max HP is two extra hearts, so it takes more total damage to drop you. The value shown is the full 4-piece amount (25% per piece).")));

        register(new WikiEntry("damage_dealt", "Damage Dealt", List.of(
                "A generic boost to your outgoing damage, not tied to one weapon. It multiplies your melee strikes and any enchant proc that lands as a melee-type hit from you. Enchants that deal damage another way — draining health directly, or applying a damage-over-time / tick effect like bleed, poison, or fire — don't get boosted; only the direct hit does.")));

        register(new WikiEntry("damage_taken", "Damage Taken", List.of(
                "A generic cut to incoming damage, not tied to any one attacker or weapon. It softens hits from enemies broadly instead of just one source — but only actual attacks, not non-combat damage like fall, fire, or lava.")));

        register(new WikiEntry("hit", "“Hit”", List.of(
                "A “hit” is a direct strike that lands on you — a melee blow or a projectile from an attacker. It does NOT include damage-over-time or environmental damage: fall, fire ticks, poison, lava, drowning — even if a player set that fire or poison. Only the direct blow counts, so effects that trigger “when you take a hit” won't fire from those.")));

        register(new WikiEntry("vanish", "“Vanish”", List.of(
                "“Vanish” makes you completely disappear for 1s (Phantom Veil) — your body, armor, and floating name are hidden from everyone else, and you can't be hit at all while it lasts. It's a brief, total escape window: enough to break an enemy's lock and reposition or get away. It's not a damage buff.")));

        register(new WikiEntry("per_piece", "Each Piece Grants 25%", List.of(
                "Stat bonuses scale with pieces worn — 25% each (1 = 25% … 4 = 100%), so the numbers shown are the full 4-piece totals. The full-set ability is the exception: all 4 pieces, no scaling.")));

        register(new WikiEntry("pve_damage", "PvE Damage", List.of(
                "“PvE” means Player vs Environment — combat against hostile mobs, not other players. “Increased PvE damage” raises what you deal to mobs; “reduced PvE damage taken” softens what they deal back. Neither does anything in PvP, and bosses are excluded — boss fights ignore both. Shown at the full 4-piece amount (25% per piece).")));
    }

    private static void register(WikiEntry entry) {
        ENTRIES.put(entry.id(), entry);
    }

    /** Article for the given id, or {@code null} if unknown. */
    public static WikiEntry get(String id) {
        return id == null ? null : ENTRIES.get(id);
    }

    private WikiRegistry() {}
}
