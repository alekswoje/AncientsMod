package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_DUNGEON_TIMER} — the dungeon run clock.
 *
 * <p>Wire format: {@code byte state (1=running, 2=complete, 3=wiped);
 * varlong elapsedMs; varint tier}. The server heartbeats state 1 at 1 Hz from
 * the START-room "GO!" moment; states 2/3 carry the frozen final time.
 */
public record DungeonTimerPayload(int state, long elapsedMs, int tier, long receivedMs) {

    public static final int STATE_RUNNING = 1;
    public static final int STATE_COMPLETE = 2;
    public static final int STATE_WIPED = 3;

    public static DungeonTimerPayload decode(PacketByteBuf buf) {
        int state    = buf.readByte() & 0xFF;
        long elapsed = Math.max(0L, buf.readVarLong());
        int tier     = Math.max(0, buf.readVarInt());
        return new DungeonTimerPayload(state, elapsed, tier, System.currentTimeMillis());
    }

    public boolean running() { return state == STATE_RUNNING; }
}
