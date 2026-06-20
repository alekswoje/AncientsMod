package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_RIFT_BUDGET} — per-player Tartarus Rift
 * daily-time state.
 *
 * <p>Wire format after the type byte: {@code byte flags (bit0=available,
 * bit1=consuming), varint remainingSeconds, varint secondsUntilReset}.
 */
public record RiftBudgetPayload(boolean available, boolean consuming,
                                int remainingSeconds, int secondsUntilReset) {

    private static final int MAX_SECONDS = 24 * 3600; // defensive cap (1 day)

    public static RiftBudgetPayload decode(PacketByteBuf buf) {
        int flags = buf.readByte() & 0xFF;
        boolean available = (flags & 0x1) != 0;
        boolean consuming = (flags & 0x2) != 0;
        int remaining = buf.readVarInt();
        int reset = buf.readVarInt();
        if (remaining < 0 || remaining > MAX_SECONDS) remaining = 0;
        if (reset < 0 || reset > MAX_SECONDS) reset = 0;
        return new RiftBudgetPayload(available, consuming, remaining, reset);
    }
}
