package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.ParticleTypes;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side renderer for Powerball fireballs.
 *
 * <p>The server normally spawns one real {@code ItemDisplay} per ball and
 * teleports it every tick, streaming a per-tick entity-move packet to the
 * miner — a flood that lags low-bandwidth clients when procs stack (e.g.
 * Riftborn's 2x proc rate). When this feature is on, the mod reports it via
 * {@code PKT_POWERBALL_STATE}; the server then stops rendering the ball
 * server-side and instead sends a compact {@code PKT_POWERBALL} spawn hint per
 * ball plus one update per bounce. The mod extrapolates straight-line motion
 * between updates and draws a flame stream along the path.
 *
 * <p>Strictly cosmetic: the ball's block-breaking stays 100% server-
 * authoritative. We only render where the server's bounce updates place the
 * ball, so the visual lands where ore actually breaks. Because the body is
 * drawn with particles, it honours the client's particle video setting — a
 * player on "Minimal" particles can toggle this feature off to get the
 * server-rendered ball back.
 */
public final class PowerballRenderer {

    /** Hard cap on concurrently-tracked balls so a misbehaving server can't grow client memory without bound. */
    private static final int MAX_BALLS = 256;
    /** Grace past a ball's stated lifetime before we self-expire it — covers a dropped despawn packet so a ball never ghosts. */
    private static final long LIFETIME_GRACE_MS = 1500L;
    /** Flame samples emitted along each tick's travel segment (keeps the trail continuous at the ball's speed). */
    private static final int TRAIL_SAMPLES = 2;

    /** Keyed by the server's compact wire id. Accessed only on the client thread
     *  (both the packet receiver and END_CLIENT_TICK run there), but kept
     *  concurrent as cheap insurance against a future off-thread dispatch. */
    private static final Map<Integer, Ball> BALLS = new ConcurrentHashMap<>();

    private static final class Ball {
        double x, y, z;
        double vx, vy, vz; // blocks per tick — matches the server's per-tick position step
        final long spawnMs;
        final int lifetimeMs;

        Ball(double x, double y, double z, double vx, double vy, double vz, long spawnMs, int lifetimeMs) {
            this.x = x; this.y = y; this.z = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.spawnMs = spawnMs;
            this.lifetimeMs = lifetimeMs;
        }
    }

    public static void onSpawn(int wireId, double x, double y, double z,
                               double vx, double vy, double vz, int lifetimeMs, long nowMs) {
        if (!FeatureToggles.isPowerballRenderEnabled()) return;
        if (BALLS.size() >= MAX_BALLS) return; // runaway guard — drop rather than evict
        BALLS.put(wireId, new Ball(x, y, z, vx, vy, vz, nowMs, Math.max(0, lifetimeMs)));
    }

    public static void onBounce(int wireId, double x, double y, double z,
                                double vx, double vy, double vz) {
        Ball b = BALLS.get(wireId);
        if (b == null) return;
        // Snap to the authoritative bounce position + velocity. Any forward
        // extrapolation drift since the last update is corrected here.
        b.x = x; b.y = y; b.z = z;
        b.vx = vx; b.vy = vy; b.vz = vz;
    }

    public static void onDespawn(int wireId, boolean fizzle) {
        Ball b = BALLS.remove(wireId);
        if (b != null && fizzle) spawnFizzle(b.x, b.y, b.z);
    }

    /** Clear all tracked balls — called on disconnect and when the toggle flips off. */
    public static void reset() {
        BALLS.clear();
    }

    /** Advance + draw every tracked ball. Called once per client tick. */
    public static void tick(long nowMs) {
        if (BALLS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        boolean draw = ServerAllowlist.isAllowed() && FeatureToggles.isPowerballRenderEnabled();

        Iterator<Map.Entry<Integer, Ball>> it = BALLS.entrySet().iterator();
        while (it.hasNext()) {
            Ball b = it.next().getValue();
            // Self-expire by lifetime so a missed despawn never leaves a ghost.
            if (nowMs - b.spawnMs > (long) b.lifetimeMs + LIFETIME_GRACE_MS) {
                it.remove();
                continue;
            }
            double prevX = b.x, prevY = b.y, prevZ = b.z;
            // One tick of straight-line motion — matches the server's per-tick
            // step between bounces; bounce packets re-anchor the path.
            b.x += b.vx;
            b.y += b.vy;
            b.z += b.vz;
            if (draw) emitTrail(client, prevX, prevY, prevZ, b.x, b.y, b.z);
        }
    }

    private static void emitTrail(MinecraftClient client, double x0, double y0, double z0,
                                  double x1, double y1, double z1) {
        // FLAME matches the server's original trail particle; sampling along the
        // segment substitutes for the solid fire-charge body the server used to
        // render, so the ball reads as a small bouncing fireball. particleManager
        // honours the client's particle video setting (same as world.addParticle).
        for (int i = 1; i <= TRAIL_SAMPLES; i++) {
            double t = (double) i / TRAIL_SAMPLES;
            client.particleManager.addParticle(ParticleTypes.FLAME,
                    x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t,
                    0.0, 0.0, 0.0);
        }
        // A small flame at the head gives the leading edge a bit more body.
        client.particleManager.addParticle(ParticleTypes.SMALL_FLAME, x1, y1, z1, 0.0, 0.0, 0.0);
    }

    private static void spawnFizzle(double x, double y, double z) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        for (int i = 0; i < 6; i++) {
            client.particleManager.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.02, 0.0);
        }
    }

    private PowerballRenderer() {}
}
