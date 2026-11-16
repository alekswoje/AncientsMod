package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decoded {@link Protocol#PKT_ENERGY_REFERENCE} — the server's gear/pickaxe energy cost
 * table.
 *
 * <p>None of this is derivable client-side (upgrade coefficients, exponents, gear max
 * levels and the prestige ladder all live in server config and get re-tuned), which is
 * exactly why it comes over the wire instead of being hardcoded here.
 *
 * <p>Decoding is defensive per the protocol's rules: every count is bounds-checked before
 * a single allocation, and a malformed table yields an empty one rather than a partial
 * mixture of real and garbage rows.
 */
public record EnergyReferencePayload(
        int energyTaxPercent,
        List<GearTier> gearTiers,
        List<PickTier> pickTiers,
        /** The player block at the end of the packet. Null from a server that predates it. */
        @Nullable Profile profile
) {

    /**
     * The requesting player's own numbers, each read server-side through the function the game
     * uses, so the mining tree, trims, jewels and boosters are already in them.
     *
     * @param taxBasisPoints   extract tax outside a safe zone, in hundredths of a percent
     * @param inSafeZone       standing in a safe zone when the table was sent (extracting is free there)
     * @param holdingPickaxe   a pickaxe was in the main hand; the next three are 0 without one
     * @param heldCapacity     energy that pickaxe holds in this player's hands
     * @param energyMultiplier energy a hand-mined ore pays, as a multiple of the ore's base energy
     * @param energyPerHour    live mining rate, 0 when idle
     * @param rungStoredLevels the level a pickaxe stores on each ladder rung, in ladder order
     *                         (1, 5, 10, 11). The item carries the stored level; players see P1..P4.
     */
    public record Profile(int taxBasisPoints, boolean inSafeZone, boolean holdingPickaxe,
                          long heldCapacity, double energyMultiplier, long energyPerHour,
                          int[] rungStoredLevels) {

        /** {@code 840 -> "8.4"}, {@code 1200 -> "12"}. */
        public String taxText() {
            double pct = Math.round(taxBasisPoints / 10.0) / 10.0;
            return pct == Math.rint(pct) ? Long.toString((long) pct) : String.format(Locale.US, "%.1f", pct);
        }

        /** How many times the held pickaxe fills to gather {@code energy} (0 = nothing to gather, or no pickaxe). */
        public long fillsFor(long energy) {
            if (energy <= 0 || heldCapacity <= 0) return 0L;
            return (energy + heldCapacity - 1) / heldCapacity;
        }

        /** Seconds of mining at the live rate to gain {@code energy}; -1 when idle or past a year. */
        public long secondsToGain(long energy) {
            if (energy <= 0) return 0L;
            if (energyPerHour <= 0) return -1L;
            double seconds = energy * 3600.0 / energyPerHour;
            return seconds > 365L * 86_400L ? -1L : (long) Math.ceil(seconds);
        }
    }

    /** The extract tax as a fraction, from the precise figure when the server sent one. */
    public double taxRate() {
        return profile != null ? profile.taxBasisPoints() / 10_000.0 : energyTaxPercent / 100.0;
    }

    /**
     * The prestige number players see for the level a pickaxe stores. Without the player block
     * the server is on the old one-step-per-level ladder and the two are the same number.
     */
    public int shownPrestige(int stored) {
        if (profile == null) return stored;
        int shown = 0;
        for (int rung : profile.rungStoredLevels()) if (stored >= rung) shown++;
        return shown;
    }

    /** The rung a pickaxe at stored level {@code stored} prestiges to next, or null at the top. */
    public @Nullable PrestigeStep nextStep(PickTier tier, int stored) {
        if (tier == null) return null;
        List<PrestigeStep> ladder = tier.ladder();
        if (profile == null) {
            for (PrestigeStep step : ladder) if (step.prestige() == stored + 1) return step;
            return null;
        }
        int[] rungs = profile.rungStoredLevels();
        for (int i = 0; i < rungs.length && i < ladder.size(); i++) {
            if (rungs[i] > stored) return ladder.get(i);
        }
        return null;
    }

    /** True when a pickaxe at stored level {@code stored} already stands on ladder row {@code index}. */
    public boolean reached(int index, int stored) {
        if (profile == null) return false;
        int[] rungs = profile.rungStoredLevels();
        return index >= 0 && index < rungs.length && stored >= rungs[index];
    }

    /**
     * @param cumulativeToLevel index {@code n} = total energy to take a fresh piece of this
     *                          tier from level 0 to level {@code n}. Length is
     *                          {@code maxLevel + 1}, so the last entry is the total to max
     *                          and {@code last - cumulativeToLevel[current]} is what is
     *                          left on a piece you already own.
     */
    public record GearTier(String label, int maxLevel, long[] cumulativeToLevel) {

        public long totalToMax() {
            return cumulativeToLevel.length == 0 ? -1 : cumulativeToLevel[cumulativeToLevel.length - 1];
        }

        /** Energy still owed to take a piece at {@code level} to max, or -1 if unknown. */
        public long remainingFrom(int level) {
            if (cumulativeToLevel.length == 0) return -1;
            if (level < 0 || level >= cumulativeToLevel.length) return -1;
            return Math.max(0, totalToMax() - cumulativeToLevel[level]);
        }
    }

    /** One rung of a pickaxe tier's prestige ladder. */
    public record PrestigeStep(int prestige, long energyCost, String oreLabel, long oreCount) {}

    /**
     * @param curveLevels     sampled levels (every 10 up to 100 — pickaxes have no max level,
     *                        so the curve is a sample rather than an exhaustive array)
     * @param curveCumulative total energy to take a fresh pickaxe from 0 to that level
     */
    public record PickTier(String label, int[] curveLevels, long[] curveCumulative,
                           List<PrestigeStep> ladder) {

        /** Sum of every ladder row's energy cost: the bill for a fresh pickaxe to reach the top. */
        public long totalPrestigeEnergy() {
            long total = 0;
            for (PrestigeStep s : ladder) total += s.energyCost();
            return total;
        }
    }

    private static final EnergyReferencePayload EMPTY =
            new EnergyReferencePayload(0, List.of(), List.of(), null);

    public boolean isEmpty() { return gearTiers.isEmpty() && pickTiers.isEmpty(); }

    public static EnergyReferencePayload decode(PacketByteBuf buf) {
        try {
            int tax = buf.readUnsignedByte();
            if (tax > 100) tax = 100;

            int gearCount = buf.readVarInt();
            if (gearCount < 0 || gearCount > Protocol.ENERGY_REF_MAX_TIERS) return EMPTY;
            List<GearTier> gear = new ArrayList<>(gearCount);
            for (int i = 0; i < gearCount; i++) {
                String label = buf.readString(Protocol.ENERGY_REF_MAX_LABEL_CHARS);
                int maxLevel = buf.readVarInt();
                int curveLen = buf.readVarInt();
                if (maxLevel < 0 || curveLen < 0 || curveLen > Protocol.ENERGY_REF_MAX_CURVE) return EMPTY;
                long[] curve = new long[curveLen];
                for (int c = 0; c < curveLen; c++) curve[c] = buf.readVarLong();
                gear.add(new GearTier(label, maxLevel, curve));
            }

            int pickCount = buf.readVarInt();
            if (pickCount < 0 || pickCount > Protocol.ENERGY_REF_MAX_TIERS) return EMPTY;
            List<PickTier> picks = new ArrayList<>(pickCount);
            for (int i = 0; i < pickCount; i++) {
                String label = buf.readString(Protocol.ENERGY_REF_MAX_LABEL_CHARS);

                int curveLen = buf.readVarInt();
                if (curveLen < 0 || curveLen > Protocol.ENERGY_REF_MAX_CURVE) return EMPTY;
                int[] levels = new int[curveLen];
                long[] curve = new long[curveLen];
                for (int c = 0; c < curveLen; c++) {
                    levels[c] = buf.readVarInt();
                    curve[c] = buf.readVarLong();
                }

                int ladderLen = buf.readVarInt();
                if (ladderLen < 0 || ladderLen > Protocol.ENERGY_REF_MAX_LADDER) return EMPTY;
                List<PrestigeStep> ladder = new ArrayList<>(ladderLen);
                for (int s = 0; s < ladderLen; s++) {
                    int prestige = buf.readVarInt();
                    long energy = buf.readVarLong();
                    String ore = buf.readString(Protocol.ENERGY_REF_MAX_LABEL_CHARS);
                    long oreCount = buf.readVarLong();
                    ladder.add(new PrestigeStep(prestige, energy, ore, oreCount));
                }

                picks.add(new PickTier(label, levels, curve, ladder));
            }

            return new EnergyReferencePayload(tax, List.copyOf(gear), List.copyOf(picks), decodeProfile(buf));
        } catch (Exception e) {
            // Truncated or hostile packet — drop the whole table rather than render half of it.
            return EMPTY;
        }
    }

    /**
     * The player block after the tables. A packet that ends at the last ladder is an older
     * server, and a block that does not parse is dropped on its own: the price tables before it
     * are whole and still worth showing.
     */
    private static @Nullable Profile decodeProfile(PacketByteBuf buf) {
        if (!buf.isReadable()) return null;
        try {
            int version = buf.readVarInt();
            if (version < 1) return null;
            int taxBp = Math.max(0, Math.min(10_000, buf.readVarInt()));
            int flags = buf.readUnsignedByte();
            long capacity = Math.max(0L, buf.readVarLong());
            double multiplier = Math.max(0, buf.readVarInt()) / 1000.0;
            long perHour = Math.max(0L, buf.readVarLong());
            int rungCount = buf.readVarInt();
            if (rungCount < 0 || rungCount > Protocol.ENERGY_REF_MAX_LADDER) return null;
            int[] rungs = new int[rungCount];
            for (int i = 0; i < rungCount; i++) rungs[i] = buf.readVarInt();
            return new Profile(taxBp, (flags & 1) != 0, (flags & 2) != 0, capacity, multiplier, perHour, rungs);
        } catch (Exception e) {
            return null;
        }
    }

    public static EnergyReferencePayload empty() { return EMPTY; }
}
