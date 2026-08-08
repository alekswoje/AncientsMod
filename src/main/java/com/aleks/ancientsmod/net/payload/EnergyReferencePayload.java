package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

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
        List<PickTier> pickTiers
) {

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

        /** Sum of every prestige step's energy cost — the full P1..Pmax bill. */
        public long totalPrestigeEnergy() {
            long total = 0;
            for (PrestigeStep s : ladder) total += s.energyCost();
            return total;
        }
    }

    private static final EnergyReferencePayload EMPTY =
            new EnergyReferencePayload(0, List.of(), List.of());

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

            return new EnergyReferencePayload(tax, List.copyOf(gear), List.copyOf(picks));
        } catch (Exception e) {
            // Truncated or hostile packet — drop the whole table rather than render half of it.
            return EMPTY;
        }
    }

    public static EnergyReferencePayload empty() { return EMPTY; }
}
