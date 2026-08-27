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
    /** When a kill/drop tally or the Hunter XP total last went UP. */
    private static volatile long lastActivityMs = 0L;

    private PveStatsState() {}

    public static void update(PveStatsPayload payload) {
        long prevTally = tally(kills) + tally(drops);
        long prevHunter = sessionHunterXp;

        worldName = payload.worldName() == null ? "" : payload.worldName();
        kills = new LinkedHashMap<>(payload.kills());
        drops = new LinkedHashMap<>(payload.drops());
        hunterXpPerHour = Math.max(0L, payload.hunterXpPerHour());
        sessionHunterXp = Math.max(0L, payload.sessionHunterXp());
        receivedMs = payload.receivedMs();

        // The heartbeat runs at 1 Hz regardless of what the player is doing, so
        // arrival time is not activity. Only a tally going UP means something
        // actually happened -- that timestamp is what the Stats HUD uses to
        // decide whether PvE or mining currently owns the widget.
        if (tally(kills) + tally(drops) > prevTally || sessionHunterXp > prevHunter) {
            lastActivityMs = payload.receivedMs();
        }
    }

    private static long tally(Map<String, Integer> m) {
        long sum = 0L;
        for (Integer v : m.values()) if (v != null && v > 0) sum += v;
        return sum;
    }

    /** Epoch-ms of the last kill / drop / Hunter-XP gain (0 = nothing yet this session). */
    public static long lastActivityMs() {
        return lastActivityMs;
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
