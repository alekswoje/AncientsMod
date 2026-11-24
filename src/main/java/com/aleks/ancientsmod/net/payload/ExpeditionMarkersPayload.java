package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/** {@link Protocol#PKT_EXPEDITION_MARKERS}: the boss arena, the packs still to clear and the map's features. */
public record ExpeditionMarkersPayload(int bossState, int bossX, int bossZ, List<Pack> packs, List<Poi> pois) {
    public static final int BOSS_NONE = 0, BOSS_ALIVE = 1, BOSS_DEAD = 2;

    public record Pack(int x, int z, int alive, boolean rare, boolean unspawned) {}

    /** A map feature (an altar, a breach, a fissure): {@code Protocol.EXPEDITION_POI_*} kind and state. */
    public record Poi(int x, int z, int kind, int state) {}

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
        List<Poi> pois = new ArrayList<>();
        if (buf.readableBytes() > 0) {
            int n = buf.readVarInt();
            if (n < 0 || n > Protocol.MAX_EXPEDITION_POIS) throw new IllegalArgumentException("bad expedition features");
            for (int i = 0; i < n; i++) {
                int x = buf.readInt(), z = buf.readInt();
                int kind = buf.readUnsignedByte(), state = buf.readUnsignedByte();
                pois.add(new Poi(x, z, kind, state));
            }
        }
        return new ExpeditionMarkersPayload(boss, bx, bz, List.copyOf(packs), List.copyOf(pois));
    }
}
