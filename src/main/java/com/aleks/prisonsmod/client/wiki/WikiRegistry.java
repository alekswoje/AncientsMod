package com.aleks.prisonsmod.client.wiki;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-mod wiki's article store. Each entry is a short, <b>generic</b>
 * explanation of a term or mechanic — deliberately NOT tied to any one trim,
 * set, or item — so the same article can be reused anywhere that term appears
 * in the game. State what it <i>does</i>, not what it's good for: no filler,
 * no editorializing, one tight paragraph.
 *
 * <p><b>“Increased/Reduced” vs “More/Less”</b> are kept as separate articles:
 * increased/reduced are <i>additive</i> (one shared pool, applied once, capped
 * at 100%); more/less are <i>multiplicative</i> (each instance independent).
 */
public final class WikiRegistry {

    private static final Map<String, WikiEntry> ENTRIES = new LinkedHashMap<>();

    static {
        register(new WikiEntry("increased", "“Increased”", List.of(
                "Additive. Every “increased” bonus to the same stat adds into one shared pool, and that total is applied once — 5% increased and 10% increased give 15% increased, not two separate steps. (A flat “+5%” base is a different thing and isn't part of this pool.) The multiplicative counterpart is “More.”")));

        register(new WikiEntry("reduced", "“Reduced”", List.of(
                "Additive. All your “reduced” bonuses to the same stat add into one shared pool, capped at 100% — at 100% the stat is fully negated (e.g. total damage immunity). The multiplicative counterpart is “Less.”")));

        register(new WikiEntry("more", "“More”", List.of(
                "Multiplicative. Each “more” is its own independent multiplier — they never share a pool, so each keeps its full value. Three 50% more = ×1.5 × 1.5 × 1.5, not 150% more. The additive counterpart is “Increased.”")));

        register(new WikiEntry("less", "“Less”", List.of(
                "Multiplicative. Each “less” is an independent multiplier — they stack by multiplying, not adding. 50% less three times = ×0.5 × 0.5 × 0.5 = 12.5% — never 150%, and never a full 100%. The additive counterpart is “Reduced.”")));

        register(new WikiEntry("max_hp", "Max HP", List.of(
                "Raises your maximum health. +2 Max HP is one extra heart.")));

        register(new WikiEntry("damage_dealt", "Damage Dealt", List.of(
                "A boost to your outgoing damage, not tied to one weapon. It multiplies your melee strikes and any enchant proc that lands as a melee-type hit from you. Enchants that deal damage another way — draining health directly, or applying a damage-over-time / tick effect like bleed, poison, or fire — don't get boosted; only the direct hit does.")));

        register(new WikiEntry("damage_taken", "Damage Taken", List.of(
                "A cut to incoming damage from attacks, not tied to any one attacker or weapon. It doesn't reduce non-combat damage like fall, fire, or lava.")));

        register(new WikiEntry("pve_damage", "PvE Damage", List.of(
                "“PvE” means Player vs Environment — combat against hostile mobs, not other players. “More PvE damage” raises what you deal to mobs; “less PvE damage taken” lowers what they deal you. Neither applies in PvP, and boss fights ignore both.")));

        register(new WikiEntry("hit", "“Hit”", List.of(
                "A “hit” is a direct strike that lands on you. It does NOT include damage-over-time or environmental damage — fall, fire ticks, poison, lava, drowning — even if a player caused it. Only the direct blow counts, so effects that trigger “when you take a hit” won't fire from those.")));

        register(new WikiEntry("vanish", "“Vanish”", List.of(
                "Vanish makes you briefly disappear completely — your body, armor, and floating name hidden from everyone, and you can't be hit at all while it lasts.")));

        register(new WikiEntry("cell_guards", "Cell Guards", List.of(
                "Cell guards are the NPC defenders inside player cells. “More damage to cell guards” raises the damage you deal them; “less damage from cell guards” lowers the damage they deal you. Each is its own independent multiplier.")));

        register(new WikiEntry("cell_doors", "Cell Doors", List.of(
                "Cell doors are the reinforced doors sealing a cell during a raid. “More damage to cell doors” breaks them faster.")));

        register(new WikiEntry("ignited", "Ignited Enemies", List.of(
                "“Ignited” means on fire. “More damage to ignited enemies” raises the damage you deal to a target while it's burning. It only counts while they're alight.")));

        register(new WikiEntry("proc_chances", "Proc Chance", List.of(
                "A “proc” is a random-chance effect firing — an enchant triggering, a perk going off. “Increased proc chances” multiplies your base chance, so +10% turns a 30% roll into 33%.")));

        register(new WikiEntry("powerball", "Powerball", List.of(
                "Powerball is a mining enchant that fires a ball that smashes through a line of blocks. Bonuses to it raise its proc chance, chain it, or fire doubled or extra balls.")));

        register(new WikiEntry("momentum", "Momentum", List.of(
                "Momentum is a mining meter that fills as you keep mining and scales your output with it. “Increased Momentum cap” raises its ceiling. Stop mining and it drains back down.")));

        register(new WikiEntry("outpost_cap", "Outpost Cap Rate", List.of(
                "The Outpost is a contested objective gangs fight to control. “Increased Outpost cap rate” captures it faster while you stand in the zone.")));

        register(new WikiEntry("durability", "Armor Durability", List.of(
                "Durability is how much use a piece of gear takes before it breaks. “More armor durability” increases it.")));

        register(new WikiEntry("luck", "Luck", List.of(
                "Luck shifts your loot rolls toward better, rarer results. It's an additive pool — every luck source adds together before it's applied.")));

        register(new WikiEntry("second_roll", "Second Roll", List.of(
                "A “second roll” is an extra, independent roll of a loot table on the same kill — separate from the first roll.")));

        register(new WikiEntry("meteorite_preserve", "Preserving Meteorite Blocks", List.of(
                "“Chance to preserve meteorite blocks” means a meteorite block you mine sometimes isn't used up — it stays for you to mine again.")));

        register(new WikiEntry("meteorite_energy", "Energy from Meteorites", List.of(
                "“Increased energy from meteorites” raises the Ancient Energy each meteorite block pays out.")));

        register(new WikiEntry("meteorite_speed", "Meteorite Mining Speed", List.of(
                "“More meteorite mining speed” breaks meteorite ore faster. It applies only to meteorite blocks, on top of your normal mining speed.")));

        register(new WikiEntry("shards", "Finding Shards", List.of(
                "Shards are the enchanting drops you find while mining. “More chance to find shards” raises how often a mined block yields one.")));

        register(new WikiEntry("mining_speed", "Mining Speed", List.of(
                "Mining speed is how fast you break blocks. “More mining speed” is a flat boost to all your mining.")));

        register(new WikiEntry("wither", "Wither", List.of(
                "Wither is a decay effect — black hearts that drain health over time, and unlike poison it can kill.")));

        register(new WikiEntry("conquest", "Conquest Stacks", List.of(
                "Conquest stacks build while your target hasn't hit you back, up to 5. Each stack adds Wither duration and proc chance, and they all reset the moment they land a hit on you.")));

        register(new WikiEntry("true_damage", "True Damage", List.of(
                "True damage ignores armor, resistance, and every damage-reduction layer — it always deals its full amount.")));

        register(new WikiEntry("phantom_mines", "Phantom Mines", List.of(
                "Phantom mines are blocks broken by unseen “echoes” of you that mine on their own. The drops go to you, and your pickaxe enchants proc more often on them.")));

        register(new WikiEntry("star_forged", "Star-Forged", List.of(
                "“Star-Forged” gives mined meteorite ore a chance to drop already refined, skipping the refining step.")));

        register(new WikiEntry("claimed", "Claimed Meteorites", List.of(
                "A “claimed” meteorite is briefly locked to you the instant you start mining it — others can't mine it during that window.")));

        register(new WikiEntry("per_piece", "Each Piece Grants 25%", List.of(
                "Set bonuses scale with how many matching pieces you wear — 25% per piece, so all four gives the full value shown. Full-set abilities are the exception: they need all four and don't scale.")));
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
