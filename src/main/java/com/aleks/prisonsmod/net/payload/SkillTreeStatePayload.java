package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.HashSet;
import java.util.Set;

/**
 * Decoded {@code PKT_SKILLTREE_STATE}: per-player skill-tree state. Sent
 * immediately after the open packet and on every successful allocate /
 * refund / respec — mod- or chisel-driven.
 */
public final class SkillTreeStatePayload {

    public final Set<String> unlocked;
    public final int banked;
    public final int available;
    public final int spent;
    public final double respecCost;

    private SkillTreeStatePayload(Set<String> unlocked, int banked, int available,
                                   int spent, double respecCost) {
        this.unlocked = unlocked;
        this.banked = banked;
        this.available = available;
        this.spent = spent;
        this.respecCost = respecCost;
    }

    public static SkillTreeStatePayload decode(PacketByteBuf buf) {
        int unlockedCount = buf.readShort() & 0xFFFF;
        if (unlockedCount > Protocol.SKILLTREE_MAX_NODES) {
            throw new IllegalArgumentException("skill tree state: unlocked count too large");
        }
        Set<String> unlocked = new HashSet<>(unlockedCount * 2);
        for (int i = 0; i < unlockedCount; i++) {
            unlocked.add(buf.readString(Protocol.SKILLTREE_MAX_NODE_ID_CHARS));
        }
        int banked = buf.readInt();
        int available = buf.readInt();
        int spent = buf.readInt();
        double respecCost = buf.readDouble();
        return new SkillTreeStatePayload(unlocked, banked, available, spent, respecCost);
    }
}
