package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.MiningCompPayload;

public final class MiningCompState {
    private static MiningCompPayload snapshot;
    private static long receivedNs;
    private MiningCompState() {}
    public static void update(MiningCompPayload value) {
        snapshot = value.seconds() > 0 ? value : null;
        receivedNs = System.nanoTime();
    }
    public static void clear() { snapshot = null; }
    public static MiningCompPayload current() {
        if (snapshot == null || System.nanoTime() - receivedNs > 5_000_000_000L || seconds() <= 0) return null;
        return snapshot;
    }
    public static int seconds() {
        return snapshot == null ? 0 : Math.max(0, snapshot.seconds()
                - (int) ((System.nanoTime() - receivedNs) / 1_000_000_000L));
    }
}
