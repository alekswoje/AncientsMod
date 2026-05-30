package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.MineStartPayload;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Latency-mitigation renderer for mining. Two behaviors depending on predicted
 * duration:
 *
 * <ul>
 *   <li><b>Normal break</b> ({@code duration >= INSTA_BREAK_THRESHOLD_MS}):
 *       draws an accelerating {@code setBlockBreakingInfo} crack animation so
 *       the player sees progress ~100ms earlier than waiting for the server's
 *       real {@code BlockDestructionPacket}.</li>
 *   <li><b>Insta-break</b> ({@code duration < INSTA_BREAK_THRESHOLD_MS}):
 *       fires the vanilla block-break world event (particles + sound) at the
 *       block so the disappearance feels snappy instead of muted. No crack
 *       ladder — there'd be no time to see it.</li>
 * </ul>
 *
 * <p>Three cancellation paths keep a single tap from showing a full break:
 * <ol>
 *   <li>Server-origin {@link #onMineCancel}, fired on
 *       {@code BlockDamageAbortEvent}. Authoritative.</li>
 *   <li>Client-side: if the player isn't holding attack OR their crosshair
 *       has moved off the predicted block, the entry is dropped inside
 *       {@link #tick}. Responsive — no round-trip.</li>
 *   <li>Self-expiry when predicted duration elapses.</li>
 * </ol>
 */
public final class MinePredictRenderer {

    /** Reserved synthetic entity id range. Far above any real player entity id. */
    private static final int BASE_ENTITY_ID = 1_000_000_000;

    /** Hard cap on concurrent predicted animations. */
    private static final int MAX_ENTRIES = 16;

    /** Number of block particles to spawn on insta-break. Vanilla world-event 2001
     *  spawns ~30 which is visual noise in the rift where stone is mined rapidly. */
    private static final int INSTA_BREAK_PARTICLES = 5;

    private static final Map<BlockPos, Entry> ACTIVE = new HashMap<>();

    private static final class Entry {
        final int entityId;
        final long startMs;
        final int durationMs;
        int lastStageSent = -1;

        Entry(int entityId, long startMs, int durationMs) {
            this.entityId = entityId;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }
    }

    private static int nextEntityId = BASE_ENTITY_ID;

    public static void onMineStart(MineStartPayload payload) {
        // Feature toggle: when off, we accept the packet (rate limiter accounting
        // stays honest) but don't render anything — the server's real progress
        // packets will drive the crack animation with the natural latency.
        if (!FeatureToggles.isMinePredictEnabled()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;

        // Insta-break path: no time for a crack ladder. Play a toned-down version
        // of the vanilla block-break effect — a handful of particles + the break
        // sound — so the disappearance isn't muted. We skip syncWorldEvent(2001)
        // because it spawns ~30 particles which is visual noise in rapid mining.
        if (payload.durationMs() < Protocol.INSTA_BREAK_THRESHOLD_MS) {
            BlockState state = world.getBlockState(payload.pos());
            if (!state.isAir()) {
                BlockPos pos = payload.pos();
                double cx = pos.getX() + 0.5;
                double cy = pos.getY() + 0.5;
                double cz = pos.getZ() + 0.5;
                ThreadLocalRandom r = ThreadLocalRandom.current();
                BlockStateParticleEffect fx = new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
                for (int i = 0; i < INSTA_BREAK_PARTICLES; i++) {
                    client.particleManager.addParticle(fx,
                            cx + (r.nextDouble() - 0.5) * 0.5,
                            cy + (r.nextDouble() - 0.5) * 0.5,
                            cz + (r.nextDouble() - 0.5) * 0.5,
                            (r.nextDouble() - 0.5) * 0.2,
                            r.nextDouble() * 0.2,
                            (r.nextDouble() - 0.5) * 0.2);
                }
                world.playSound(null, cx, cy, cz,
                        state.getSoundGroup().getBreakSound(),
                        SoundCategory.BLOCKS,
                        0.6f, 0.9f + r.nextFloat() * 0.2f);
            }
            return;
        }

        // Normal break path: crack-animation prediction.
        if (ACTIVE.size() >= MAX_ENTRIES) {
            Iterator<Map.Entry<BlockPos, Entry>> it = ACTIVE.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<BlockPos, Entry> oldest = it.next();
                world.setBlockBreakingInfo(oldest.getValue().entityId, oldest.getKey(), -1);
                it.remove();
            }
        }

        Entry existing = ACTIVE.remove(payload.pos());
        if (existing != null) {
            world.setBlockBreakingInfo(existing.entityId, payload.pos(), -1);
        }

        int entityId = nextEntityId++;
        if (nextEntityId > BASE_ENTITY_ID + 1_000_000) nextEntityId = BASE_ENTITY_ID;

        Entry entry = new Entry(entityId, System.currentTimeMillis(), Math.max(1, payload.durationMs()));
        ACTIVE.put(payload.pos(), entry);
    }

    /** Server-origin cancel — authoritative. */
    public static void onMineCancel(BlockPos pos) {
        clearOne(pos);
    }

    /**
     * Tick every client frame: advance stages, expire finished entries, and
     * cancel any prediction the player is no longer committing to (attack key
     * released or crosshair moved off the block).
     */
    public static void tick() {
        if (ACTIVE.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) {
            ACTIVE.clear();
            return;
        }

        boolean attacking = client.options.attackKey.isPressed();
        BlockPos targeted = null;
        HitResult hit = client.crosshairTarget;
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            targeted = bhr.getBlockPos();
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Entry>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Entry> e = it.next();
            Entry entry = e.getValue();
            long elapsed = now - entry.startMs;
            BlockPos pos = e.getKey();

            // Self-cancel: if the player isn't holding attack or their crosshair
            // has moved off the predicted block, drop it immediately. No grace
            // window — an earlier 40ms grace leaked the first crack stage on
            // single taps. For taps, attackKey will already read false on the
            // first tick we see after packet arrival, which is what we want.
            boolean stillTargeting = pos.equals(targeted);
            if (!attacking || !stillTargeting) {
                world.setBlockBreakingInfo(entry.entityId, pos, -1);
                it.remove();
                continue;
            }

            // Past the predicted duration? Clear — the server's real packets take over.
            if (elapsed >= entry.durationMs) {
                world.setBlockBreakingInfo(entry.entityId, pos, -1);
                it.remove();
                continue;
            }

            // 10 stages (0-9); -1 clears.
            int stage = (int) Math.min(9, (elapsed * 10) / entry.durationMs);
            if (stage != entry.lastStageSent) {
                world.setBlockBreakingInfo(entry.entityId, pos, stage);
                entry.lastStageSent = stage;
            }
        }
    }

    /** Called when the mod is disabled (leaving an allowlisted server) or toggled off. */
    public static void reset() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world != null) {
            for (Map.Entry<BlockPos, Entry> e : ACTIVE.entrySet()) {
                world.setBlockBreakingInfo(e.getValue().entityId, e.getKey(), -1);
            }
        }
        ACTIVE.clear();
        PrisonsMod.LOGGER.debug("MinePredictRenderer reset");
    }

    private static void clearOne(BlockPos pos) {
        Entry entry = ACTIVE.remove(pos);
        if (entry == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world != null) {
            world.setBlockBreakingInfo(entry.entityId, pos, -1);
        }
    }

    private MinePredictRenderer() {}
}
