package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.client.hud.MiningSimState;
import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded form of {@link Protocol#PKT_MININGSIM_SHARED} — a mining-sim session somebody
 * else put up with {@code [sim]} in chat, delivered when we click the link.
 *
 * <p>Carries the same body as a {@link MiningSimPayload} snapshot plus who shared it, what
 * they called it, and the rate curve. The curve travels because the server never had one:
 * it only ever knows session totals, and the graph is accumulated client-side from the
 * 1 Hz stream — so a share made from the sharer's archive brings their curve along.
 *
 * <p>The snapshot is re-wrapped as a {@code MiningSimPayload} marked final so every tab
 * of the screen can render it with no special-casing beyond where the data comes from.
 */
public record MiningSimSharedPayload(String ownerName, String label, boolean own,
                                     MiningSimPayload snapshot,
                                     List<MiningSimState.RatePoint> history) {

    /** How the imported session is named in the local archive. */
    public String archiveLabel() {
        return ownerName + " — " + label;
    }

    public static MiningSimSharedPayload decode(PacketByteBuf buf) {
        int flags = buf.readByte() & 0xFF;
        boolean own = (flags & 1) != 0;
        String ownerName = buf.readString(Protocol.MAX_MININGSIM_OWNER_CHARS);
        String label = buf.readString(Protocol.MAX_MININGSIM_SHARE_NAME_CHARS);

        long elapsed = Math.max(0L, buf.readVarLong());
        long miningElapsed = Math.max(1L, buf.readVarLong());
        long xp = Math.max(0L, buf.readVarLong());
        long energy = Math.max(0L, buf.readVarLong());
        double money = Math.max(0L, buf.readVarLong()) / 1000.0;

        int sourceCount = Math.min(Math.max(0, buf.readVarInt()), Protocol.MAX_MININGSIM_ROWS);
        List<MiningSimPayload.Row> sources = new ArrayList<>(sourceCount);
        for (int i = 0; i < sourceCount; i++) {
            String src = buf.readString(Protocol.MAX_MININGSIM_LABEL_CHARS);
            long rowXp = Math.max(0L, buf.readVarLong());
            long rowEnergy = Math.max(0L, buf.readVarLong());
            double rowMoney = Math.max(0L, buf.readVarLong()) / 1000.0;
            sources.add(new MiningSimPayload.Row(src, rowXp, rowEnergy, rowMoney));
        }

        int procCount = Math.min(Math.max(0, buf.readVarInt()), Protocol.MAX_MININGSIM_ROWS);
        List<MiningSimPayload.ProcRow> procs = new ArrayList<>(procCount);
        for (int i = 0; i < procCount; i++) {
            String name = buf.readString(Protocol.MAX_MININGSIM_LABEL_CHARS);
            procs.add(new MiningSimPayload.ProcRow(name, Math.max(0, buf.readVarInt())));
        }

        int pointCount = Math.min(Math.max(0, buf.readVarInt()), Protocol.MAX_MININGSIM_SHARE_POINTS);
        List<MiningSimState.RatePoint> history = new ArrayList<>(pointCount);
        // Point times arrive as deltas from the previous point and are rebased onto now,
        // because the graph only uses their order — a shared run may be days old.
        long base = System.currentTimeMillis();
        long at = 0L;
        for (int i = 0; i < pointCount; i++) {
            at += Math.max(0L, buf.readVarLong());
            long xpHr = Math.max(0L, buf.readVarLong());
            long energyHr = Math.max(0L, buf.readVarLong());
            double moneyHr = Math.max(0L, buf.readVarLong()) / 1000.0;
            history.add(new MiningSimState.RatePoint(base + at, xpHr, energyHr, moneyHr));
        }

        MiningSimPayload snapshot = new MiningSimPayload(false, true, false,
                elapsed, miningElapsed, xp, energy, money,
                List.copyOf(sources), List.copyOf(procs), System.currentTimeMillis());

        return new MiningSimSharedPayload(ownerName, label, own, snapshot, List.copyOf(history));
    }
}
