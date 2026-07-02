package com.aleks.ancientsmod.render;

import com.aleks.ancientsmod.net.payload.HudUpdatePayload;

/**
 * Live rift HUD overlay: current points, rank, time remaining. Holds a single
 * immutable snapshot; rendering reads whatever was last pushed by the server.
 *
 * <p>If no update arrives within {@link #STALE_MS}, the HUD fades out — this
 * avoids the HUD showing stale data when the rift ends or the player leaves.
 */
public final class RiftHud {

    private static volatile HudUpdatePayload SNAPSHOT;
    private static volatile long LAST_UPDATE_MS;

    public static final long STALE_MS = 5_000L;

    public static void update(HudUpdatePayload p) {
        SNAPSHOT = p;
        LAST_UPDATE_MS = System.currentTimeMillis();
    }

    public static HudUpdatePayload current() {
        HudUpdatePayload snap = SNAPSHOT;
        if (snap == null) return null;
        if (System.currentTimeMillis() - LAST_UPDATE_MS > STALE_MS) return null;
        return snap;
    }

    private RiftHud() {}
}
