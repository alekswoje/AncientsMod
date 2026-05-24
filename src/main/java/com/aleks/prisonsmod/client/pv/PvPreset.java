package com.aleks.prisonsmod.client.pv;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * Mirror of the server-side {@code PvAffinityPreset} enum. Storage key is the
 * lowercase enum name — wire format for {@code PKT_PV_APPLY_PRESET}.
 *
 * <p>The actual binding map lives on the server; this enum only carries
 * display metadata (name, description, icon) so the client picker can render
 * tiles. If the server adds a new preset, older clients show only the
 * presets they know about — the server still accepts the apply packet for
 * any preset its enum knows.
 */
public enum PvPreset {
    HOARDER("hoarder", "Hoarder",
            "Maximum spread — every category gets its own slot when possible",
            Items.SHULKER_BOX),
    CURRENCY_FOCUSED("currency_focused", "Currency-Focused",
            "Currencies clustered together, gear shunted to a single overflow",
            Items.GOLD_INGOT),
    ENDGAME_RAIDER("endgame_raider", "Endgame Raider",
            "Boss loot and high-tier rewards have priority slots",
            Items.NETHERITE_AXE),
    MINER("miner", "Miner",
            "Mining loop first — dust, shards, mats, boosters",
            Items.DIAMOND_PICKAXE);

    private final String storageKey;
    private final String displayName;
    private final String description;
    private final Item icon;

    PvPreset(String storageKey, String displayName, String description, Item icon) {
        this.storageKey = storageKey;
        this.displayName = displayName;
        this.description = description;
        this.icon = icon;
    }

    public String storageKey() { return storageKey; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public Item icon() { return icon; }
}
