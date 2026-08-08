package com.aleks.ancientsmod.client.energycalc;

import com.aleks.ancientsmod.net.payload.EnergyReferencePayload;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Holds the last {@link EnergyReferencePayload} the server sent.
 *
 * <p>One table per session, replaced wholesale on each reply. Cleared on disconnect so a
 * different server (or a re-tuned config) can never be read from a stale cache.
 */
public final class EnergyReferenceState {

    private static volatile EnergyReferencePayload current = EnergyReferencePayload.empty();
    private static volatile long receivedAtMs = 0L;

    private EnergyReferenceState() {}

    public static void update(EnergyReferencePayload payload) {
        current = payload == null ? EnergyReferencePayload.empty() : payload;
        receivedAtMs = System.currentTimeMillis();
    }

    public static void clear() {
        current = EnergyReferencePayload.empty();
        receivedAtMs = 0L;
    }

    public static EnergyReferencePayload get() { return current; }

    /** True once a table has arrived — drives the screen's "waiting for server" state. */
    public static boolean hasData() { return receivedAtMs > 0 && !current.isEmpty(); }

    /**
     * The gear tier row matching a tier label, case-insensitively.
     *
     * <p>Used to fill the "To max" column for a worn piece: the client knows the item's
     * material (hence its tier name) and level, the server sent that tier's curve.
     */
    public static @Nullable EnergyReferencePayload.GearTier gearTier(@Nullable String label) {
        if (label == null || label.isEmpty()) return null;
        for (EnergyReferencePayload.GearTier t : current.gearTiers()) {
            if (t.label().equalsIgnoreCase(label)) return t;
        }
        return null;
    }

    public static @Nullable EnergyReferencePayload.PickTier pickTier(@Nullable String label) {
        if (label == null || label.isEmpty()) return null;
        for (EnergyReferencePayload.PickTier t : current.pickTiers()) {
            if (t.label().equalsIgnoreCase(label)) return t;
        }
        return null;
    }

    /**
     * Tier name for a vanilla item id, matching the server's tier labels.
     * {@code minecraft:netherite_chestplate} → {@code "Netherite"}. Returns null for
     * anything that isn't a tiered armour/weapon/pickaxe material.
     */
    public static @Nullable String tierLabelFor(@Nullable String itemIdPath) {
        if (itemIdPath == null) return null;
        String p = itemIdPath.toLowerCase(Locale.ROOT);
        if (p.startsWith("chainmail_") || p.startsWith("wooden_sword") || p.startsWith("wooden_axe")) return "Chainmail";
        if (p.startsWith("wooden_")) return "Wood";
        if (p.startsWith("stone_")) return "Stone";
        if (p.startsWith("golden_")) return "Gold";
        if (p.startsWith("iron_")) return "Iron";
        if (p.startsWith("diamond_")) return "Diamond";
        if (p.startsWith("netherite_")) return "Netherite";
        // Season 2 wands are blaze rods and mirror netherite gear server-side.
        if (p.equals("blaze_rod")) return "Netherite";
        return null;
    }
}
