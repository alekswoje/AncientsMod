package com.aleks.ancientsmod.client.energycalc;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the client can legitimately know about one Ancient item's energy
 * economy, read straight out of the item's synced {@code minecraft:custom_data}.
 *
 * <p>PrisonsCore stores its per-item state in a Bukkit PersistentDataContainer,
 * which Bukkit serializes into {@code custom_data} under {@code PublicBukkitValues}
 * with {@code prisonscore:}-namespaced keys. That whole compound rides along with
 * the normal item sync, so none of this needs a packet or a server change — it is
 * the same trick {@code PickaxeBlocksTooltip} uses for the ore tally.
 *
 * <h2>What is deliberately absent</h2>
 * The server never puts the <i>cost curve</i> on the item, only the next step of it. So
 * {@link #energyToNextLevel} is real, but "total energy to max", the pickaxe prestige
 * energy ladder and the prestige ore requirements are not readable here and must not be
 * reconstructed client-side — the coefficients live in {@code config/gear.yml} +
 * {@code config/economy.yml} and get re-tuned.
 *
 * <p>Those figures now arrive separately, as the server-priced reference table in
 * {@link EnergyReferenceState} ({@code PKT_ENERGY_REFERENCE}). {@link EnergyCalcScreen}
 * joins the two: this record supplies the item's level, the table supplies its tier's
 * cost curve, and subtracting gives what is left to pay.
 */
public record AncientItemStats(
        String displayName,
        boolean pickaxe,
        int level,
        int prestige,
        long storedEnergy,
        long energyToNextLevel,
        int requiredPlayerLevel,
        int maxLevelOverride,
        /** Total energy from here to max level. -1 = the server doesn't send it. */
        long energyToMax
) {

    /** Bukkit serializes its PersistentDataContainer into custom_data under this compound. */
    private static final String PDC_ROOT = "PublicBukkitValues";

    private static final String KEY_PICKAXE        = "prisonscore:ancient_pickaxe";
    private static final String KEY_GEAR           = "prisonscore:ancient_gear";
    private static final String KEY_LEVEL          = "prisonscore:ancient_level";
    private static final String KEY_PRESTIGE       = "prisonscore:pickaxe_prestige_level";
    private static final String KEY_STORED_ENERGY  = "prisonscore:stored_prisons_energy";
    private static final String KEY_ENERGY_TO_LVL  = "prisonscore:prisons_energy_to_level";
    private static final String KEY_REQUIRED_LEVEL = "prisonscore:ancient_required_level";
    private static final String KEY_MAX_OVERRIDE   = "prisonscore:item_max_ancient_level";

    /**
     * Optional per-item total-energy-to-max key. PrisonsCore does not write it — the
     * reference table ({@code PKT_ENERGY_REFERENCE}) covers the same ground for every
     * tier at once. Kept as a read because it is a strictly better source when present:
     * it would be correct even for an item whose cap is not its tier's (a level-capped
     * quest gift, or a piece carrying bonus enchant slots), which the tier curve cannot
     * price. Absent = the screen falls back to the tier curve.
     */
    private static final String KEY_ENERGY_TO_MAX  = "prisonscore:prisons_energy_to_max";

    /** Parse an Ancient pickaxe / gear item, or null if this isn't one. */
    public static @Nullable AncientItemStats of(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom == null || custom.isEmpty()) return null;

        NbtCompound pdc = custom.copyNbt().getCompound(PDC_ROOT).orElse(null);
        if (pdc == null) return null;

        boolean isPickaxe = pdc.contains(KEY_PICKAXE);
        boolean isGear = pdc.contains(KEY_GEAR);
        if (!isPickaxe && !isGear) return null;

        String name = stack.getName() == null ? "" : stack.getName().getString();
        return new AncientItemStats(
                name,
                isPickaxe,
                (int) num(pdc, KEY_LEVEL, 0),
                (int) num(pdc, KEY_PRESTIGE, 0),
                num(pdc, KEY_STORED_ENERGY, 0),
                num(pdc, KEY_ENERGY_TO_LVL, -1),
                (int) num(pdc, KEY_REQUIRED_LEVEL, 0),
                (int) num(pdc, KEY_MAX_OVERRIDE, -1),
                num(pdc, KEY_ENERGY_TO_MAX, -1)
        );
    }

    /** Energy still needed for the next level, or -1 when the cost is unknown. */
    public long energyShortfall() {
        if (energyToNextLevel < 0) return -1;
        return Math.max(0, energyToNextLevel - storedEnergy);
    }

    /** Progress toward the next level as 0..1, or -1 when the cost is unknown. */
    public double nextLevelProgress() {
        if (energyToNextLevel <= 0) return -1;
        return Math.min(1.0, (double) storedEnergy / (double) energyToNextLevel);
    }

    /**
     * Read a PDC number regardless of which numeric NBT type Bukkit chose for it
     * (INTEGER lands as NbtInt, LONG as NbtLong, DOUBLE as NbtDouble). Strings that
     * happen to hold a number are accepted too, so a server-side type change can't
     * silently blank a column.
     */
    private static long num(NbtCompound pdc, String key, long fallback) {
        NbtElement el = pdc.get(key);
        if (el == null) return fallback;
        if (el instanceof AbstractNbtNumber n) return n.longValue();
        String s = pdc.getString(key).orElse("");
        if (s.isEmpty()) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
