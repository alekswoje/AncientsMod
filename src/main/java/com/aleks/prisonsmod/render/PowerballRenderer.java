package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side renderer for Powerball fireballs — a 1:1 replacement for the
 * server's ItemDisplay-based ball, with zero per-tick entity packets.
 *
 * <p>The server normally spawns one real {@code ItemDisplay} (an enchanted
 * fire-charge) per ball and teleports it every tick, streaming an entity-move
 * packet to the miner — a flood that lags low-bandwidth clients when procs
 * stack (Riftborn 2x). When this feature is on, the mod reports it via
 * {@code PKT_POWERBALL_STATE}; the server then stops rendering the ball and
 * sends only a compact spawn hint + one update per bounce, and the mod draws
 * the ball locally.
 *
 * <p><b>Body — rendered to match the server's ItemDisplay exactly.</b> We use
 * the literal vanilla item-display render path in the world pass: build an
 * {@link ItemRenderState} from the fire-charge stack via the item model manager
 * ({@link ItemDisplayContext#FIXED}, world lighting) and submit it to the world
 * renderer's command queue, with the same 180° Y flip vanilla's
 * {@code ItemDisplayEntityRenderer} applies. Result is pixel-identical to a real
 * ItemDisplay: world block/sky lighting (not GUI-lit), the Unbreaking enchant
 * glint, and a fixed (non-billboard) world orientation. (Item models render fine
 * in {@code WorldRenderEvents.AFTER_ENTITIES} — only world-space *text* broke in
 * the 1.21.11 refactor, which is why labels use the HUD pass.)
 *
 * <p><b>Trail.</b> One vanilla {@code FLAME} particle every 2 ticks at the ball
 * position with ~0.03 jitter — matching the server's original trail exactly.
 *
 * <p>Strictly cosmetic: block-breaking stays 100% server-authoritative; we only
 * draw where the server's bounce updates place the ball.
 */
public final class PowerballRenderer {

    /** Hard cap on concurrently-tracked balls so a misbehaving server can't grow client memory without bound. */
    private static final int MAX_BALLS = 256;
    /** Grace past a ball's stated lifetime before we self-expire it — covers a dropped despawn packet so a ball never ghosts. */
    private static final long LIFETIME_GRACE_MS = 1500L;
    /** Cull the body past this distance (server stops sending updates beyond ~96 anyway). */
    private static final double MAX_DRAW_DISTANCE = 96.0;

    /** The displayed item: an enchanted fire charge (glint via override — no registry lookup needed),
     *  exactly the item the server's ItemDisplay used. */
    private static final ItemStack FIRE_CHARGE = new ItemStack(Items.FIRE_CHARGE);
    static {
        FIRE_CHARGE.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
    }

    /** One reusable render state, repopulated per ball per frame (vanilla does the same per entity). */
    private static final ItemRenderState ITEM_STATE = new ItemRenderState();

    /** Drives the every-2-ticks trail cadence (matches the server's POWERBALL_TRAIL_INTERVAL_TICKS = 2). */
    private static long trailTickCounter = 0;

    /** Keyed by the server's compact wire id. Touched on the client thread (packet receiver,
     *  END_CLIENT_TICK, world render all run there); concurrent as cheap insurance. */
    private static final Map<Integer, Ball> BALLS = new ConcurrentHashMap<>();

    private static final class Ball {
        double px, py, pz;   // previous-tick position (for per-frame interpolation)
        double x, y, z;      // current-tick position
        double vx, vy, vz;   // blocks per tick — matches the server's per-tick step
        final long spawnMs;
        final int lifetimeMs;

        Ball(double x, double y, double z, double vx, double vy, double vz, long spawnMs, int lifetimeMs) {
            this.px = x; this.py = y; this.pz = z;
            this.x = x; this.y = y; this.z = z;
            this.vx = vx; this.vy = vy; this.vz = vz;
            this.spawnMs = spawnMs;
            this.lifetimeMs = lifetimeMs;
        }
    }

    public static void register() {
        // Item models render correctly in the world pass (same stage the GangPing beam uses).
        WorldRenderEvents.AFTER_ENTITIES.register(PowerballRenderer::onWorldRender);
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
        // Snap both current AND previous to the authoritative bounce so the next
        // frame doesn't interpolate a streak across the corner.
        b.px = x; b.py = y; b.pz = z;
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

    /** Advance positions one tick + emit the trail. Called once per client tick. */
    public static void tick(long nowMs) {
        if (BALLS.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        boolean on = ServerAllowlist.isAllowed() && FeatureToggles.isPowerballRenderEnabled();
        // Single FLAME every 2 ticks (server cadence). Counter only advances while balls exist — fine.
        boolean trailTick = (trailTickCounter++ % 2 == 0);

        Iterator<Map.Entry<Integer, Ball>> it = BALLS.entrySet().iterator();
        while (it.hasNext()) {
            Ball b = it.next().getValue();
            // Self-expire by lifetime so a missed despawn never leaves a ghost.
            if (nowMs - b.spawnMs > (long) b.lifetimeMs + LIFETIME_GRACE_MS) {
                it.remove();
                continue;
            }
            b.px = b.x; b.py = b.y; b.pz = b.z;
            // One tick of straight-line motion — matches the server's per-tick
            // step between bounces; bounce packets re-anchor the path.
            b.x += b.vx;
            b.y += b.vy;
            b.z += b.vz;
            if (on && trailTick) emitTrail(client, b.x, b.y, b.z);
        }
    }

    /** Draw each ball's fire-charge item model in the world, matching the server ItemDisplay. */
    private static void onWorldRender(WorldRenderContext context) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!FeatureToggles.isPowerballRenderEnabled()) return;
        if (BALLS.isEmpty()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) return;

        MatrixStack matrices = context.matrices();
        OrderedRenderCommandQueue queue = context.commandQueue();
        Camera camera = client.gameRenderer.getCamera();
        if (matrices == null || queue == null || camera == null) return;

        Vec3d camPos = camera.getCameraPos();
        float tickDelta = client.getRenderTickCounter().getTickProgress(true);

        for (Ball b : BALLS.values()) {
            double wx = b.px + (b.x - b.px) * tickDelta;
            double wy = b.py + (b.y - b.py) * tickDelta;
            double wz = b.pz + (b.z - b.pz) * tickDelta;

            double dx = wx - camPos.x, dy = wy - camPos.y, dz = wz - camPos.z;
            if (dx * dx + dy * dy + dz * dz > MAX_DRAW_DISTANCE * MAX_DRAW_DISTANCE) continue;

            // Build the item render state exactly like a non-living entity / ItemDisplay would.
            client.getItemModelManager().clearAndUpdate(
                    ITEM_STATE, FIRE_CHARGE, ItemDisplayContext.FIXED, client.world, null, 0);

            // World block+sky light at the ball position — same as a real entity (not GUI/full-bright).
            int light = WorldRenderer.getLightmapCoordinates(client.world, BlockPos.ofFloored(wx, wy, wz));

            matrices.push();
            matrices.translate(dx, dy, dz); // world geometry is camera-relative
            // The exact 180° Y flip vanilla's ItemDisplayEntityRenderer applies to a FIXED display.
            matrices.multiply(RotationAxis.POSITIVE_Y.rotation((float) Math.PI));
            // The literal vanilla item-display draw. The queue is flushed by vanilla's
            // RenderDispatcher at pass end — do NOT flush it ourselves.
            ITEM_STATE.render(matrices, queue, light, OverlayTexture.DEFAULT_UV, 0);
            matrices.pop();
        }
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
