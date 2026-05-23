package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Decoded form of {@link Protocol#PKT_GANG_ROSTER}.
 *
 * <p>Wire format: {@code byte count; (long mostSigBits, long leastSigBits) * count}.
 * Empty payload (count=0) is a valid "I'm not in a gang anymore" signal.
 *
 * <p>Bounded by {@link Protocol#MAX_GANG_ROSTER_MEMBERS} on decode — a misbehaving
 * server cannot make us allocate an unbounded set.
 */
public record GangRosterPayload(Set<UUID> uuids) {

    public static GangRosterPayload decode(PacketByteBuf buf) {
        int rawCount = buf.readByte() & 0xFF;
        int count = Math.min(rawCount, Protocol.MAX_GANG_ROSTER_MEMBERS);
        Set<UUID> set = new HashSet<>(count);
        for (int i = 0; i < count; i++) {
            long msb = buf.readLong();
            long lsb = buf.readLong();
            set.add(new UUID(msb, lsb));
        }
        // Drain any extras (server claimed > cap) so we don't leave stale bytes in the buf.
        for (int i = count; i < rawCount; i++) {
            if (buf.readableBytes() < 16) break;
            buf.skipBytes(16);
        }
        return new GangRosterPayload(set);
    }
}
