package com.aleks.prisonsmod.client.pv;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Mirror of the server-side {@code PvAffinityCategory} enum — needed because
 * the bundle wire format only ships storage keys, not display metadata. Keep
 * order and storage keys in sync with the server enum so that bindings round-
 * trip cleanly.
 *
 * <p>Adding a new category requires updating both the server enum and this
 * mirror. Unknown categories arriving from a newer server are still tolerated
 * (rendered as raw storage key) via {@link #fromStorageKey} returning {@code
 * null} — the picker just won't show a tile for them.
 */
public enum PvCategory {
    ENCHANT_DUST("enchant_dust", "Enchant Dust", "Regular and calcified enchant dust", Items.GLOWSTONE_DUST),
    SHARDS("shards", "Shards", "Raw shards and shard satchels", Items.PRISMARINE_SHARD),
    ENCHANT_ORBS("enchant_orbs", "Enchant Orbs", "Godly, mystery, and exceptional orbs", Items.PURPLE_DYE),
    BOSS_ESSENCES("boss_essences", "Boss Essences", "Echidna / Lamia / Hephaestus / Thanatos", Items.GHAST_TEAR),
    BOSS_KEYS("boss_keys", "Boss Keys", "Altar keys for summoning bosses", Items.TRIPWIRE_HOOK),
    CRATES_AND_BOXES("crates_and_boxes", "Crates & Boxes", "Lootboxes, bundles, lockboxes, crates, lootbags", Items.CHEST),
    TRINKETS("trinkets", "Trinkets", "Lamia's Veil, Echidna's Strand, Fortune's Dice", Items.AMETHYST_SHARD),
    BOOSTERS("boosters", "Boosters", "Personal and global boosters", Items.FIREWORK_ROCKET),
    MONEY_NOTES("money_notes", "Money Notes", "Bank notes", Items.PAPER),
    ANCIENT_ENERGY("ancient_energy", "Ancient Energy", "Storable energy items", Items.EXPERIENCE_BOTTLE),
    REROLL_TOKENS("reroll_tokens", "Reroll Tokens", "Path / perk / base-stat / prestige tokens", Items.NETHER_STAR),
    ANCIENT_MOTES("ancient_motes", "Ancient Motes", "Ancient mote currency", Items.AMETHYST_BLOCK),
    CLUE_CASKETS("clue_caskets", "Clue Caskets", "Casket rewards from clue scrolls", Items.WRITABLE_BOOK),
    MINING_MATERIALS("mining_materials", "Mining Materials", "Condensed ore, bot items and fuel", Items.RAW_IRON),
    PICKAXES_AND_GEAR("pickaxes_and_gear", "Pickaxes & Gear", "Prisons picks and gear", Items.NETHERITE_PICKAXE),
    CONTRABAND("contraband", "Contraband", "Smuggling contraband", Items.BARRIER),
    /** Catch-all overflow — bind this to a PV to route any shift-clicked
     *  item that didn't match a real category. */
    UNCATEGORIZED("uncategorized", "Uncategorized",
            "Anything that doesn't match another category", Items.HOPPER);

    private final String storageKey;
    private final String displayName;
    private final String description;
    private final Item icon;

    PvCategory(String storageKey, String displayName, String description, Item icon) {
        this.storageKey = storageKey;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
    }

    public String storageKey() { return storageKey; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public Item icon() { return icon; }

    public static PvCategory fromStorageKey(String key) {
        if (key == null) return null;
        for (PvCategory c : values()) {
            if (c.storageKey.equals(key)) return c;
        }
        return null;
    }
}
