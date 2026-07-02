package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.PveStatsPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/** Latest PvE stats snapshot, written by the network handler, read by {@link StatsHud} each frame. */
public final class PveStatsState {

    /** Stale-after window — 8 s is generous given the 1-Hz heartbeat. */
    private static final long STALE_AFTER_MS = 8_000L;

    private static volatile String worldName = "";
    private static volatile Map<String, Integer> kills = new LinkedHashMap<>();
    private static volatile Map<String, Integer> drops = new LinkedHashMap<>();
    private static volatile long hunterXpPerHour = 0L;
    private static volatile long sessionHunterXp = 0L;
    private static volatile long receivedMs = 0L;

    private PveStatsState() {}

    public static void update(PveStatsPayload payload) {
        worldName = payload.worldName() == null ? "" : payload.worldName();
        kills = new LinkedHashMap<>(payload.kills());
        drops = new LinkedHashMap<>(payload.drops());
        hunterXpPerHour = Math.max(0L, payload.hunterXpPerHour());
        sessionHunterXp = Math.max(0L, payload.sessionHunterXp());
        receivedMs = payload.receivedMs();
    }

    public static boolean isStale() {
        return System.currentTimeMillis() - receivedMs > STALE_AFTER_MS;
    }

    public static String worldName() {
        return isStale() ? "" : worldName;
    }

    public static Map<String, Integer> kills() {
        return isStale() ? new LinkedHashMap<>() : kills;
    }

    public static Map<String, Integer> drops() {
        return isStale() ? new LinkedHashMap<>() : drops;
    }

    /** Rolling projection of Hunter XP earned per hour (0 when idle or stale). */
    public static long hunterXpPerHour() {
        return isStale() ? 0L : hunterXpPerHour;
    }

    /** Total Hunter XP earned this session (0 when stale). */
    public static long sessionHunterXp() {
        return isStale() ? 0L : sessionHunterXp;
    }
}
