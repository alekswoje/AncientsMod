package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.OutpostStatePayload;

import java.util.List;

public final class OutpostState {

    private static volatile List<OutpostStatePayload.Entry> entries = List.of();

    private OutpostState() {}

    public static void update(OutpostStatePayload p) {
        entries = p.entries();
    }

    public static List<OutpostStatePayload.Entry> entries() {
        return entries;
    }

    public static boolean isEmpty() {
        return entries.isEmpty();
    }
}
