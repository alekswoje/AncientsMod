package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.MiningSimPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Latest {@code /miningsim} snapshot plus the rate history the screen graphs.
 *
 * <p>The server sends totals, not rates over time — so the history is built here from
 * the 1 Hz stream rather than shipped on every packet. That keeps the wire small and
 * means the graph resolution is whatever the client actually received.
 *
 * <p>Finished sessions are archived in memory so two runs can be compared (e.g. before
 * and after an enchant change). The archive is deliberately not persisted to disk: it is
 * a within-session comparison aid, and a stale archive from days ago invites comparing
 * runs whose gear and boosters no longer match.
 */
public final class MiningSimState {

    /** A session with no packet this recent is treated as gone, collapsing the HUD section. */
    private static final long STALE_AFTER_MS = 5_000L;
    /** ~30 min of 1 Hz samples. Older points are dropped from the front. */
    private static final int MAX_HISTORY_POINTS = 1_800;
    /** How many finished sessions to keep for comparison. */
    private static final int MAX_ARCHIVED = 10;

    /** One point on the rate graph. */
    public record RatePoint(long atMs, long xpPerHour, long energyPerHour, double moneyPerHour) {}

    /** A finished session, kept for the history/compare view. */
    public record ArchivedSession(String label, MiningSimPayload finalSnapshot, List<RatePoint> history) {}

    private static volatile MiningSimPayload latest = null;
    private static volatile long receivedMs = 0L;

    private static final List<RatePoint> HISTORY = Collections.synchronizedList(new ArrayList<>());
    private static final List<ArchivedSession> ARCHIVE = Collections.synchronizedList(new ArrayList<>());

    private static int sessionCounter = 0;

    private MiningSimState() {}

    public static void update(MiningSimPayload p) {
        if (p == null) return;

        // "No session" clears the live view but keeps the archive — otherwise opening the
        // screen after a session ended would wipe the history you opened it to look at.
        if (p.noSession()) {
            latest = null;
            receivedMs = 0L;
            HISTORY.clear();
            return;
        }

        // A snapshot whose elapsed clock went backwards means the previous session ended
        // and a new one started without us seeing the final packet (relog, backend hop).
        MiningSimPayload prev = latest;
        if (prev != null && p.elapsedMs() < prev.elapsedMs()) {
            HISTORY.clear();
        }

        latest = p;
        receivedMs = p.receivedMs();

        // Don't graph a paused session — a flat line across a break reads as a rate
        // collapse when nothing was actually happening.
        if (!p.paused()) {
            synchronized (HISTORY) {
                HISTORY.add(new RatePoint(p.receivedMs(),
                        p.perHour(p.totalXp()), p.perHour(p.totalEnergy()), p.moneyPerHour()));
                while (HISTORY.size() > MAX_HISTORY_POINTS) HISTORY.remove(0);
            }
        }

        if (p.isFinal()) {
            archiveCurrent(p);
        }
    }

    private static void archiveCurrent(MiningSimPayload finalSnapshot) {
        List<RatePoint> snapshotHistory;
        synchronized (HISTORY) {
            snapshotHistory = List.copyOf(HISTORY);
        }
        sessionCounter++;
        String label = "Session " + sessionCounter;
        synchronized (ARCHIVE) {
            ARCHIVE.add(new ArchivedSession(label, finalSnapshot, snapshotHistory));
            while (ARCHIVE.size() > MAX_ARCHIVED) ARCHIVE.remove(0);
        }
    }

    /** True while the server is still heartbeating a session. */
    public static boolean isLive() {
        return receivedMs > 0L && System.currentTimeMillis() - receivedMs <= STALE_AFTER_MS;
    }

    /**
     * The most recent snapshot, live or not. The screen keeps showing the final snapshot
     * of a finished session, so this deliberately does not stale out like {@link #isLive}.
     */
    public static MiningSimPayload latest() {
        return latest;
    }

    /** The running session, or null when there isn't one. */
    public static MiningSimPayload liveSession() {
        MiningSimPayload p = latest;
        if (p == null || p.isFinal() || !isLive()) return null;
        return p;
    }

    public static boolean isPaused() {
        MiningSimPayload p = liveSession();
        return p != null && p.paused();
    }

    public static List<RatePoint> history() {
        synchronized (HISTORY) {
            return List.copyOf(HISTORY);
        }
    }

    public static List<ArchivedSession> archive() {
        synchronized (ARCHIVE) {
            return List.copyOf(ARCHIVE);
        }
    }

    /** Drop everything — used on disconnect so another server can't inherit this state. */
    public static void reset() {
        latest = null;
        receivedMs = 0L;
        HISTORY.clear();
        ARCHIVE.clear();
        sessionCounter = 0;
    }
}
