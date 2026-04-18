package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.CascadePayload;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Cascade "big moment" effects: screen shake, radial particles, combo banner.
 * Capped at {@link Protocol#MAX_CASCADE_EFFECTS_QUEUED} concurrent effects.
 */
public final class CascadeEffectRenderer {

    public static final class Effect {
        public final int size;
        public final int blocksBroken;
        public final int points;
        public final double x, y, z;
        public final long spawnedAtMs;
        public Effect(CascadePayload p, long now) {
            this.size = p.size();
            this.blocksBroken = p.blocksBroken();
            this.points = p.points();
            this.x = p.x();
            this.y = p.y();
            this.z = p.z();
            this.spawnedAtMs = now;
        }
    }

    public static final long LIFETIME_MS = 1500L;

    private static final Deque<Effect> QUEUE = new ArrayDeque<>(Protocol.MAX_CASCADE_EFFECTS_QUEUED);

    public static void enqueue(CascadePayload p) {
        long now = System.currentTimeMillis();
        if (QUEUE.size() >= Protocol.MAX_CASCADE_EFFECTS_QUEUED) {
            QUEUE.pollFirst();
        }
        QUEUE.addLast(new Effect(p, now));
    }

    public static void tick(long nowMs) {
        while (!QUEUE.isEmpty() && (nowMs - QUEUE.peekFirst().spawnedAtMs) > LIFETIME_MS) {
            QUEUE.pollFirst();
        }
    }

    public static Iterable<Effect> active() { return QUEUE; }

    private CascadeEffectRenderer() {}
}
