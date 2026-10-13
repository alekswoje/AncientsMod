package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/** {@link Protocol#PKT_EXPEDITION_MARKERS}: the boss arena and the packs still to clear. */
public record ExpeditionMarkersPayload(int bossState, int bossX, int bossZ, List<Pack> packs) {
    public static final int BOSS_NONE = 0, BOSS_ALIVE = 1, BOSS_DEAD = 2;

    public record Pack(int x, int z, int alive, boolean rare, boolean unspawned) {}

    public static ExpeditionMarkersPayload decode(PacketByteBuf buf) {
        int boss = buf.readUnsignedByte();
        int bx = buf.readInt(), bz = buf.readInt();
        int count = buf.readVarInt();
        if (boss > BOSS_DEAD || count < 0 || count > Protocol.MAX_EXPEDITION_PACK_MARKERS)
            throw new IllegalArgumentException("bad expedition markers");
        List<Pack> packs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int x = buf.readInt(), z = buf.readInt();
            int alive = buf.readVarInt();
            int flags = buf.readUnsignedByte();
            if (alive < 0 || alive > 10_000) throw new IllegalArgumentException("bad expedition pack");
            packs.add(new Pack(x, z, alive, (flags & Protocol.EXPEDITION_PACK_RARE) != 0,
                    (flags & Protocol.EXPEDITION_PACK_UNSPAWNED) != 0));
        }
        return new ExpeditionMarkersPayload(boss, bx, bz, List.copyOf(packs));
    }
}
