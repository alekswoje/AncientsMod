package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.PointGainPayload;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Floating "+points" numbers that pop off broken blocks and drift upward.
 *
 * <p>Bounded at {@link Protocol#MAX_FLOATING_NUMBERS_ON_SCREEN}; oldest entries
 * are evicted before new ones are enqueued. This prevents a malicious or buggy
 * server from building up unbounded state on the client.
 *
 * <p>This is a skeleton — the actual rendering hook ties into Fabric's
 * {@code HudRenderCallback} / {@code WorldRenderEvents.AFTER_ENTITIES} depending
 * on whether we want screen-space or world-space numbers.
 */
public final class FloatingNumberRenderer {

    public static final class Entry {
        public final int points;
        public final double x, y, z;
        public final long spawnedAtMs;
        public Entry(PointGainPayload p, long now) {
            this.points = p.points();
            this.x = p.x();
            this.y = p.y();
            this.z = p.z();
            this.spawnedAtMs = now;
        }
    }

    /** Lifetime of a single number on screen. */
    public static final long LIFETIME_MS = 1200L;

    private static final Deque<Entry> QUEUE = new ArrayDeque<>(Protocol.MAX_FLOATING_NUMBERS_ON_SCREEN);

    public static void register() {
        // Wire to WorldRenderEvents.AFTER_ENTITIES or HudRenderCallback here.
        // Skeleton intentionally empty so the project compiles without a
        // Fabric API version mismatch blocking early iteration.
    }

    public static void enqueue(PointGainPayload p) {
        long now = System.currentTimeMillis();
        if (QUEUE.size() >= Protocol.MAX_FLOATING_NUMBERS_ON_SCREEN) {
            QUEUE.pollFirst();
        }
        QUEUE.addLast(new Entry(p, now));
    }

    /** Call every client tick to prune expired numbers. */
    public static void tick(long nowMs) {
        while (!QUEUE.isEmpty() && (nowMs - QUEUE.peekFirst().spawnedAtMs) > LIFETIME_MS) {
            QUEUE.pollFirst();
        }
    }

    /** Render-thread accessor; never mutate the queue from here. */
    public static Iterable<Entry> visible() { return QUEUE; }

    private FloatingNumberRenderer() {}
}
