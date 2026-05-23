package com.aleks.prisonsmod.client.buffs;

import com.aleks.prisonsmod.net.payload.BuffSnapshotPayload;

import java.util.List;

/**
 * Generic stacker shared by the buffs screen — given a list of layers and a
 * per-layer "is this layer enabled in the sandbox" predicate, recomputes the
 * channel's final value the same way the server would.
 *
 * <p>Formula:
 * <pre>
 *   pool        = 1 + Σ value(ADDITIVE active)
 *   pool        = max(0, pool)
 *   flat        = Σ value(FLAT_BONUS active)
 *   base        = first BASE layer's value if present, else 1.0
 *   final       = (base + flat) * pool * Π value(MULTIPLICATIVE active)
 * </pre>
 *
 * <p>For "money" / "shards" / "damage taken" channels the BASE layer carries
 * the meaningful starting value (sell value 1.0, base shard %, baseline 1.0
 * damage taken). For mining channels without a BASE layer, the implicit base
 * is 1.0 (a pure multiplier).
 */
public final class BuffStacker {

    private BuffStacker() {}

    public interface ActivePredicate {
        boolean isActive(int layerIndex);
    }

    /** Result of one channel computation. Final is the combined value, components are useful for debugging tooltips. */
    public static final class Result {
        public final double base;
        public final double additivePool;
        public final double flatBonus;
        public final double multiplicativeProduct;
        public final double finalValue;

        public Result(double base, double additivePool, double flatBonus, double multiplicativeProduct, double finalValue) {
            this.base = base;
            this.additivePool = additivePool;
            this.flatBonus = flatBonus;
            this.multiplicativeProduct = multiplicativeProduct;
            this.finalValue = finalValue;
        }
    }

    public static Result compute(List<BuffSnapshotPayload.Layer> layers, ActivePredicate active) {
        double additiveSum = 0.0;
        double flatBonus = 0.0;
        double mult = 1.0;
        double base = Double.NaN;
        boolean baseFound = false;

        for (int i = 0; i < layers.size(); i++) {
            BuffSnapshotPayload.Layer l = layers.get(i);
            if (l.kind == BuffSnapshotPayload.KIND_BASE) {
                // BASE is informational; only the FIRST base layer counts as
                // the channel baseline so flag/bool-style "yes/no" base layers
                // (e.g. money's tax-bypass row) don't overwrite the real one.
                if (!baseFound) {
                    base = l.value;
                    baseFound = true;
                }
                continue;
            }
            if (!active.isActive(i)) continue;
            switch (l.kind) {
                case BuffSnapshotPayload.KIND_ADDITIVE -> additiveSum += l.value;
                case BuffSnapshotPayload.KIND_FLAT_BONUS -> flatBonus += l.value;
                case BuffSnapshotPayload.KIND_MULTIPLICATIVE -> mult *= l.value;
                default -> { /* unknown — ignore */ }
            }
        }
        double pool = Math.max(0.0, 1.0 + additiveSum);
        double startBase = baseFound ? base : 1.0;
        double finalValue = (startBase + flatBonus) * pool * mult;
        return new Result(startBase, pool, flatBonus, mult, finalValue);
    }
}
