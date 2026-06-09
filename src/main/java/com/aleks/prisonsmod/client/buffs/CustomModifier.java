package com.aleks.prisonsmod.client.buffs;

import com.aleks.prisonsmod.net.payload.BuffSnapshotPayload;

import java.util.Locale;

/**
 * A user-authored "what-if" modifier folded into the {@code /pickbuffs}
 * sandbox — the Path-of-Building "custom modifiers" equivalent. Combines as an
 * additive % (shared pool), a multiplicative × (independent layer), or a flat
 * +N, against a {@link BuffTarget} (one channel or a group). Mutable and
 * persisted by {@link BuffSandboxStore}.
 */
public final class CustomModifier {

    /** Stable id — survives reordering, used for selection + persistence keys. */
    public final int id;
    public String name;            // may be empty → displayName() synthesises one
    public BuffTarget target;
    /** One of {@code BuffSnapshotPayload.KIND_ADDITIVE / _MULTIPLICATIVE / _FLAT_BONUS}. */
    public byte kind;
    /** Additive: fraction (0.25 = +25%). Multiplicative: the × factor. Flat: +N. */
    public double value;
    public boolean enabled;

    public CustomModifier(int id, String name, BuffTarget target, byte kind, double value, boolean enabled) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.target = target == null ? BuffTarget.everything() : target;
        this.kind = kind;
        this.value = value;
        this.enabled = enabled;
    }

    /** Label shown in lists — the user's name, or a synthesised "+25% Mining". */
    public String displayName() {
        if (name != null && !name.isBlank()) return name;
        return formatValue() + " " + target.shortName();
    }

    /** Right-hand readout, e.g. {@code +25%}, {@code ×2}, {@code +3}. */
    public String formatValue() {
        return switch (kind) {
            case BuffSnapshotPayload.KIND_ADDITIVE -> {
                double pct = value * 100.0;
                if (pct == Math.floor(pct)) yield String.format(Locale.US, "%+.0f%%", pct);
                yield String.format(Locale.US, "%+.1f%%", pct);
            }
            case BuffSnapshotPayload.KIND_MULTIPLICATIVE -> {
                if (value == Math.floor(value)) yield String.format(Locale.US, "×%d", (int) value);
                yield String.format(Locale.US, "×%.2f", value);
            }
            default -> { // FLAT_BONUS
                if (value == Math.floor(value)) yield String.format(Locale.US, "+%d", (int) value);
                yield String.format(Locale.US, "+%.1f", value);
            }
        };
    }

    /** Secondary line for the channel-view row, e.g. {@code custom · All Mining}. */
    public String detailLine() {
        return "custom · " + target.displayName();
    }

    public String kindName() {
        return switch (kind) {
            case BuffSnapshotPayload.KIND_ADDITIVE -> "increased %";
            case BuffSnapshotPayload.KIND_MULTIPLICATIVE -> "more ×";
            default -> "flat +";
        };
    }
}
