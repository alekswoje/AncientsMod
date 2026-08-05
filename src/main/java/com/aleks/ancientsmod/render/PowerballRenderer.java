package com.aleks.ancientsmod.render;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ServerAllowlist;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side renderer for Powerball fireballs — a replacement for the server's
 * ItemDisplay-based ball that costs zero per-tick entity-move packets.
 *
 * <p>The server normally spawns one real {@code ItemDisplay} (an enchanted
 * fire-charge) per ball and teleports it every tick, streaming an entity-move
 * packet to the miner — a flood that lags low-bandwidth clients when procs
 * stack (Riftborn 2x). When this feature is on, the mod reports it via
 * {@code PKT_POWERBALL_STATE}; the server then stops rendering the ball and
 * sends only a compact spawn hint + one update per bounce, and the mod draws
 * the ball locally.
 *
 * <p><b>Body — a real client-side {@link ItemEntity}.</b> We spawn an actual
 * fire-charge item entity into the {@link ClientWorld} (no-gravity, no-clip,
 * never-despawn, a reserved high entity id) and drive its position from the
 * server's spawn/bounce hints + local per-tick extrapolation. Because it's a
 * real entity it renders through the vanilla/Sodium entity pipeline — unlike a
 * manual geometry draw in a world-render event, which Sodium/Iris/Distant
 * Horizons silently discard (that was the "only the trail shows" bug). The
 * entity is purely visual: it lives only in the client world, is never ticked
 * into gameplay, and the server stays fully authoritative over the real ball.
 *
 * <p><b>Trail.</b> One vanilla {@code FLAME} particle every 2 ticks at the ball
 * position — matching the server's original trail. Particles render under every
 * modpack (same system the mine-prediction crack uses).
 */
public final class PowerballRenderer {

    /** Hard cap on concurrently-tracked balls so a misbehaving server can't grow client memory without bound. */
    private static final int MAX_BALLS = 256;
    /** Grace past a ball's stated lifetime before we self-expire it — covers a dropped despawn packet so a ball never ghosts. */
    private static final long LIFETIME_GRACE_MS = 1500L;

    /** Reserved high entity-id range for our fake client-side balls — far above
     *  any server-assigned id, so it never collides with a real entity. */
    private static final int ENTITY_ID_BASE = 1_900_000_000;
    private static int nextEntityId = ENTITY_ID_BASE;

    /** The displayed item: an enchanted fire charge (glint via override), exactly
     *  the item the server's ItemDisplay used. */
    private static final ItemStack FIRE_CHARGE = new ItemStack(Items.FIRE_CHARGE);
    static {
        FIRE_CHARGE.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    /** Drives the every-2-ticks trail cadence (matches the server's POWERBALL_TRAIL_INTERVAL_TICKS = 2). */
    private static long trailTickCounter = 0;

    /** Keyed by the server's compact wire id. Touched on the client thread (packet receiver,
     *  END_CLIENT_TICK all run there); concurrent as cheap insurance. */
    private static final Map<Integer, Ball> BALLS = new ConcurrentHashMap<>();

    private static final class Ball {
        double x, y, z;      // current position (authoritative spawn/bounce + local extrapolation)
        double vx, vy, vz;   // blocks per tick — matches the server's per-tick step
        final long spawnMs;
        final int lifetimeMs;
        ItemEntity entity;   // the client-side visual entity (null if spawn failed)
        final int entityId;

        Ball(double x, double y, double z, double vx, double vy, double vz,
             long spawnMs, int lifetimeMs, int entityId) {
            this.x = x; this.y = y; this.z = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.spawnMs = spawnMs;
            this.lifetimeMs = lifetimeMs;
            this.entityId = entityId;
        }
    }

    /** No world-render hook needed — the ItemEntity renders itself via the entity pipeline. */
    public static void register() { }

    public static void onSpawn(int wireId, double x, double y, double z,
                               double vx, double vy, double vz, int lifetimeMs, long nowMs) {
        if (!FeatureToggles.isPowerballRenderEnabled()) return;
        if (BALLS.size() >= MAX_BALLS) return; // runaway guard — drop rather than evict
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client == null ? null : client.world;
        if (world == null) return;

        int id = nextEntityId++;
        if (nextEntityId > ENTITY_ID_BASE + 1_000_000) nextEntityId = ENTITY_ID_BASE;

        Ball ball = new Ball(x, y, z, vx, vy, vz, nowMs, Math.max(0, lifetimeMs), id);
        ball.entity = spawnEntity(world, id, x, y, z);
        BALLS.put(wireId, ball);
    }

    public static void onBounce(int wireId, double x, double y, double z,
                                double vx, double vy, double vz) {
        Ball b = BALLS.get(wireId);
        if (b == null) return;
        // Snap to the authoritative bounce so the next tick doesn't extrapolate
        // a streak across the corner.
        b.x = x; b.y = y; b.z = z;
        b.vx = vx; b.vy = vy; b.vz = vz;
        if (b.entity != null) b.entity.setPosition(x, y, z);
    }

    public static void onDespawn(int wireId, boolean fizzle) {
        Ball b = BALLS.remove(wireId);
        if (b == null) return;
        removeEntity(b);
        if (fizzle) spawnFizzle(b.x, b.y, b.z);
    }

    /** Clear all tracked balls — called on disconnect and when the toggle flips off. */
    public static void reset() {
        for (Ball b : BALLS.values()) removeEntity(b);
        BALLS.clear();
    }

    /** Advance positions one tick, drive the entity, emit the trail. Once per client tick. */
    public static void tick(long nowMs) {
        if (BALLS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client == null ? null : client.world;
        if (world == null) { reset(); return; }
        boolean on = ServerAllowlist.isAllowed() && FeatureToggles.isPowerballRenderEnabled();
        boolean trailTick = (trailTickCounter++ % 2 == 0);

        Iterator<Map.Entry<Integer, Ball>> it = BALLS.entrySet().iterator();
        while (it.hasNext()) {
            Ball b = it.next().getValue();
            // Self-expire by lifetime so a missed despawn never leaves a ghost.
            if (nowMs - b.spawnMs > (long) b.lifetimeMs + LIFETIME_GRACE_MS) {
                removeEntity(b);
                it.remove();
                continue;
            }
            // One tick of straight-line motion — matches the server's per-tick
            // step between bounces; bounce packets re-anchor the path.
            b.x += b.vx;
            b.y += b.vy;
            b.z += b.vz;
            // Respawn the entity if it was lost (world reload, etc.).
            if (on && (b.entity == null || b.entity.isRemoved())) {
                b.entity = spawnEntity(world, b.entityId, b.x, b.y, b.z);
            }
            if (b.entity != null) b.entity.setPosition(b.x, b.y, b.z);
            if (on && trailTick) emitTrail(client, b.x, b.y, b.z);
        }
    }

    /** Create a no-physics fire-charge ItemEntity in the client world at (x,y,z). */
    private static ItemEntity spawnEntity(ClientWorld world, int id, double x, double y, double z) {
        try {
            ItemEntity e = new ItemEntity(world, x, y, z, FIRE_CHARGE.copy());
            e.setId(id);
            e.setNoGravity(true);
            e.noClip = true;
            e.setNeverDespawn();
            e.setVelocity(0, 0, 0);
            e.setPosition(x, y, z);
            world.addEntity(e);
            return e;
        } catch (Throwable t) {
            return null; // never let a render-only entity break the packet handler
        }
    }

    private static void removeEntity(Ball b) {
        if (b.entity == null) return;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            ClientWorld world = client == null ? null : client.world;
            if (world != null) world.removeEntity(b.entityId, Entity.RemovalReason.DISCARDED);
            else b.entity.discard();
        } catch (Throwable ignored) {
        }
        b.entity = null;
    }

    private static void emitTrail(MinecraftClient client, double x, double y, double z) {
        // One FLAME at the ball position with ~0.03 jitter, zero velocity — matches the server trail.
        ThreadLocalRandom r = ThreadLocalRandom.current();
        client.particleManager.addParticle(ParticleTypes.FLAME,
                x + (r.nextDouble() - 0.5) * 0.06,
                y + (r.nextDouble() - 0.5) * 0.06,
                z + (r.nextDouble() - 0.5) * 0.06,
                0.0, 0.0, 0.0);
    }

    private static void spawnFizzle(double x, double y, double z) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        // 2 SMOKE with ~0.1 spread — matches the server's fizzle particles.
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 2; i++) {
            client.particleManager.addParticle(ParticleTypes.SMOKE,
                    x + (r.nextDouble() - 0.5) * 0.2,
                    y + (r.nextDouble() - 0.5) * 0.2,
                    z + (r.nextDouble() - 0.5) * 0.2,
                    0.0, 0.01, 0.0);
        }
    }

    private PowerballRenderer() {}
}
