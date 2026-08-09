package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.ConfigPaths;
import com.aleks.ancientsmod.net.payload.MiningSimPayload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Latest {@code /miningsim} snapshot plus the rate history the screen graphs, and the
 * archive of finished sessions.
 *
 * <p>The server sends totals, not rates over time — so the history is built here from
 * the 1 Hz stream rather than shipped on every packet. That keeps the wire small and
 * means the graph resolution is whatever the client actually received.
 *
 * <p>Finished sessions persist to {@code config/ancientsmod-miningsim.txt} so a named
 * run survives a relog and can be reopened and compared later. They deliberately survive
 * a disconnect too: an archive that evaporated on every backend hop would make naming
 * pointless. Only the <em>live</em> view is cleared on disconnect.
 */
public final class MiningSimState {

    /** A session with no packet this recent is treated as gone, collapsing the HUD section. */
    private static final long STALE_AFTER_MS = 5_000L;
    /** ~30 min of 1 Hz samples. Older points are dropped from the front. */
    private static final int MAX_HISTORY_POINTS = 1_800;
    /** How many finished sessions to keep. Oldest unnamed ones fall off first. */
    private static final int MAX_ARCHIVED = 25;
    /** Graph points stored per archived session — enough shape without bloating the file. */
    private static final int MAX_STORED_HISTORY = 400;

    private static final String FILE_NAME = "ancientsmod-miningsim.txt";

    /** One point on the rate graph. */
    public record RatePoint(long atMs, long xpPerHour, long energyPerHour, double moneyPerHour) {}

    /** A finished session, kept for the load / compare views. */
    public record ArchivedSession(String label, MiningSimPayload finalSnapshot, List<RatePoint> history) {
        public ArchivedSession withLabel(String newLabel) {
            return new ArchivedSession(newLabel, finalSnapshot, history);
        }
    }

    private static volatile MiningSimPayload latest = null;
    private static volatile long receivedMs = 0L;

    private static final List<RatePoint> HISTORY = Collections.synchronizedList(new ArrayList<>());
    private static final List<ArchivedSession> ARCHIVE = Collections.synchronizedList(new ArrayList<>());

    private static int sessionCounter = 0;
    private static volatile boolean loaded = false;

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
        // A session that recorded nothing isn't worth a slot — stopping an idle session
        // would otherwise push a real one out of the archive.
        if (finalSnapshot.totalXp() <= 0 && finalSnapshot.totalEnergy() <= 0
                && finalSnapshot.totalMoney() <= 0) {
            return;
        }
        List<RatePoint> snapshotHistory;
        synchronized (HISTORY) {
            snapshotHistory = downsample(HISTORY, MAX_STORED_HISTORY);
        }
        sessionCounter++;
        String label = "Session " + sessionCounter;
        synchronized (ARCHIVE) {
            ARCHIVE.add(new ArchivedSession(label, finalSnapshot, snapshotHistory));
            while (ARCHIVE.size() > MAX_ARCHIVED) ARCHIVE.remove(0);
        }
        save();
    }

    /** Evenly thin a point list down to {@code max} entries, keeping first and last. */
    private static List<RatePoint> downsample(List<RatePoint> src, int max) {
        int n = src.size();
        if (n <= max) return List.copyOf(src);
        List<RatePoint> out = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            out.add(src.get((int) ((long) i * (n - 1) / (max - 1))));
        }
        return List.copyOf(out);
    }

    /** Rename an archived session. Persists immediately so the name survives a crash. */
    public static void rename(int index, String newLabel) {
        if (newLabel == null) return;
        String clean = newLabel.strip();
        if (clean.isEmpty()) return;
        // Newlines and the field separator would corrupt the flat file on the next load.
        clean = clean.replace('\n', ' ').replace('\r', ' ').replace('\u001F', ' ').replace('\u001E', ' ');
        if (clean.length() > 48) clean = clean.substring(0, 48);
        synchronized (ARCHIVE) {
            if (index < 0 || index >= ARCHIVE.size()) return;
            ARCHIVE.set(index, ARCHIVE.get(index).withLabel(clean));
        }
        save();
    }

    public static void delete(int index) {
        synchronized (ARCHIVE) {
            if (index < 0 || index >= ARCHIVE.size()) return;
            ARCHIVE.remove(index);
        }
        save();
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

    /**
     * Clear the live view on disconnect. The archive is left alone on purpose — named
     * sessions are meant to outlive the connection they were recorded on.
     */
    public static void reset() {
        latest = null;
        receivedMs = 0L;
        HISTORY.clear();
    }

    // ── Persistence ─────────────────────────────────────────────────────────
    //
    // Flat text, one session per line, unit-separator delimited:
    //   label US elapsedMs US miningElapsedMs US xp US energy US moneyMillis
    //         US sources(name=xp,energy,moneyMillis joined by RS)
    //         US procs(name=count joined by RS)
    //         US history(atMs,xp/h,energy/h,money/h joined by RS)
    //
    // Chosen over JSON because the payload is flat and this stays greppable by hand;
    // a malformed line is skipped rather than failing the whole load.

    private static final char US = '\u001F'; // field separator
    private static final char RS = '\u001E'; // row separator

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path path = ConfigPaths.resolve(FILE_NAME);
        if (!Files.isRegularFile(path)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AncientsMod.LOGGER.warn("failed to load {}: {}", FILE_NAME, e.getMessage());
            return;
        }
        int highestNumbered = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                ArchivedSession s = parseLine(line);
                if (s == null) continue;
                ARCHIVE.add(s);
                // Keep the auto-numbering ahead of anything already on disk so a fresh
                // session can't collide with a restored "Session 3".
                if (s.label().startsWith("Session ")) {
                    try {
                        highestNumbered = Math.max(highestNumbered,
                                Integer.parseInt(s.label().substring(8).trim()));
                    } catch (NumberFormatException ignored) {
                        // A renamed session that merely starts with "Session " — not a counter.
                    }
                }
            } catch (RuntimeException e) {
                AncientsMod.LOGGER.warn("skipping malformed miningsim archive line: {}", e.toString());
            }
        }
        while (ARCHIVE.size() > MAX_ARCHIVED) ARCHIVE.remove(0);
        sessionCounter = highestNumbered;
    }

    private static ArchivedSession parseLine(String line) {
        String[] f = line.split(String.valueOf(US), -1);
        if (f.length < 9) return null;
        String label = f[0];
        long elapsed = Long.parseLong(f[1]);
        long miningElapsed = Math.max(1L, Long.parseLong(f[2]));
        long xp = Long.parseLong(f[3]);
        long energy = Long.parseLong(f[4]);
        double money = Long.parseLong(f[5]) / 1000.0;

        List<MiningSimPayload.Row> sources = new ArrayList<>();
        if (!f[6].isEmpty()) {
            for (String row : f[6].split(String.valueOf(RS), -1)) {
                int eq = row.lastIndexOf('=');
                if (eq < 0) continue;
                String[] v = row.substring(eq + 1).split(",");
                if (v.length < 3) continue;
                sources.add(new MiningSimPayload.Row(row.substring(0, eq),
                        Long.parseLong(v[0]), Long.parseLong(v[1]), Long.parseLong(v[2]) / 1000.0));
            }
        }

        List<MiningSimPayload.ProcRow> procs = new ArrayList<>();
        if (!f[7].isEmpty()) {
            for (String row : f[7].split(String.valueOf(RS), -1)) {
                int eq = row.lastIndexOf('=');
                if (eq < 0) continue;
                procs.add(new MiningSimPayload.ProcRow(row.substring(0, eq),
                        Integer.parseInt(row.substring(eq + 1))));
            }
        }

        List<RatePoint> history = new ArrayList<>();
        if (!f[8].isEmpty()) {
            for (String row : f[8].split(String.valueOf(RS), -1)) {
                String[] v = row.split(",");
                if (v.length < 4) continue;
                history.add(new RatePoint(Long.parseLong(v[0]), Long.parseLong(v[1]),
                        Long.parseLong(v[2]), Long.parseLong(v[3]) / 1000.0));
            }
        }

        MiningSimPayload snap = new MiningSimPayload(false, true, false, elapsed, miningElapsed,
                xp, energy, money, List.copyOf(sources), List.copyOf(procs), 0L);
        return new ArchivedSession(label, snap, List.copyOf(history));
    }

    public static void save() {
        List<ArchivedSession> snapshot = archive();
        StringBuilder sb = new StringBuilder();
        for (ArchivedSession s : snapshot) {
            MiningSimPayload p = s.finalSnapshot();
            sb.append(s.label()).append(US)
              .append(p.elapsedMs()).append(US)
              .append(p.miningElapsedMs()).append(US)
              .append(p.totalXp()).append(US)
              .append(p.totalEnergy()).append(US)
              .append(Math.round(p.totalMoney() * 1000.0)).append(US);
            for (int i = 0; i < p.sources().size(); i++) {
                MiningSimPayload.Row r = p.sources().get(i);
                if (i > 0) sb.append(RS);
                sb.append(r.source()).append('=')
                  .append(r.xp()).append(',').append(r.energy()).append(',')
                  .append(Math.round(r.money() * 1000.0));
            }
            sb.append(US);
            for (int i = 0; i < p.procs().size(); i++) {
                MiningSimPayload.ProcRow r = p.procs().get(i);
                if (i > 0) sb.append(RS);
                sb.append(r.name()).append('=').append(r.count());
            }
            sb.append(US);
            for (int i = 0; i < s.history().size(); i++) {
                RatePoint pt = s.history().get(i);
                if (i > 0) sb.append(RS);
                sb.append(pt.atMs()).append(',').append(pt.xpPerHour()).append(',')
                  .append(pt.energyPerHour()).append(',').append(Math.round(pt.moneyPerHour() * 1000.0));
            }
            sb.append('\n');
        }
        Path path = ConfigPaths.resolve(FILE_NAME);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AncientsMod.LOGGER.warn("failed to save {}: {}", FILE_NAME, e.getMessage());
        }
    }
}
