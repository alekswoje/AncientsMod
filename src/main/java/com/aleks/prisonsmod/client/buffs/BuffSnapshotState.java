package com.aleks.prisonsmod.client.buffs;

import com.aleks.prisonsmod.net.payload.BuffSnapshotPayload;

/**
 * Holds the most-recent server snapshot for the active session. The buff
 * screen reads from here and rebuilds itself when an update lands; if the
 * screen is open at update time, it stays open and refreshes in place.
 */
public final class BuffSnapshotState {

    private static volatile BuffSnapshotPayload latest;
    private static volatile long lastUpdatedMs;

    private BuffSnapshotState() {}

    public static void update(BuffSnapshotPayload snapshot) {
        latest = snapshot;
        lastUpdatedMs = System.currentTimeMillis();
    }

    public static BuffSnapshotPayload latest() { return latest; }

    public static long lastUpdatedMs() { return lastUpdatedMs; }

    public static void clear() {
        latest = null;
        lastUpdatedMs = 0L;
    }
}
