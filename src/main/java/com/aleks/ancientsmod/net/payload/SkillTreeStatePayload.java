package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
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
    public final int level;
    public final long xp;
    public final long xpRequired;
    /** Player's current money balance, for affording checks. -1 if the
     *  server didn't send it (older server — treat cost as affordable). */
    public final double balance;

    private SkillTreeStatePayload(Set<String> unlocked, int banked, int available,
                                   int spent, double respecCost,
                                   int level, long xp, long xpRequired, double balance) {
        this.unlocked = unlocked;
        this.banked = banked;
        this.available = available;
        this.spent = spent;
        this.respecCost = respecCost;
        this.level = level;
        this.xp = xp;
        this.xpRequired = xpRequired;
        this.balance = balance;
    }

    /** True when the player can't afford a full respec (balance known and short). */
    public boolean canAffordRespec() {
        return balance < 0 || balance >= respecCost;
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
        int level = buf.readInt();
        long xp = buf.readLong();
        long xpRequired = buf.readLong();
        // Balance is a trailing field — guard so an older server that doesn't
        // send it decodes cleanly (treated as "affordable", -1).
        double balance = buf.isReadable(8) ? buf.readDouble() : -1.0;
        return new SkillTreeStatePayload(unlocked, banked, available, spent, respecCost,
                level, xp, xpRequired, balance);
    }
}
