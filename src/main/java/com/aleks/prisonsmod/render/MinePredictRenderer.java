package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.MineSpeedsPayload;
import com.aleks.prisonsmod.net.payload.MineStartPayload;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Swing-time mine prediction. Makes high-ping mining feel like low-ping mining
 * by predicting the entire break timeline locally:
 *
 * <ol>
 *   <li><b>Start on swing.</b> The instant the player attacks a block whose type
 *       is in the server-pushed speed table ({@code PKT_MINE_SPEEDS}) — or whose
 *       duration was learned from a previous {@code PKT_MINE_START} — the crack
 *       animation starts. Zero round trips.</li>
 *   <li><b>Reconcile on {@code PKT_MINE_START}.</b> The server's per-block packet
 *       (one RTT later) re-anchors the predicted duration; it is authoritative.</li>
 *   <li><b>Ghost-break on elapse.</b> When the predicted timer ends, the block is
 *       locally swapped to its known replacement (ore → stone/deepslate/netherrack
 *       — mine blocks never become air) and the break flash (particles + sound)
 *       plays locally. The server's real block update confirms it ~RTT/2 later.</li>
 *   <li><b>Rollback on silence.</b> If no server block update confirms the swap
 *       within {@code 2×latency + 500ms}, the original state is restored and ghost
 *       swaps at that position are suppressed for a while (covers meteorites and
 *       other multi-hit blocks the engine can't model).</li>
 * </ol>
 *
 * <p>Server-authoritative throughout: nothing here grants rewards or sends break
 * packets — the server runs its own timer and validates everything. The server
 * also suppresses its own crack stream + break effects for predict-on clients
 * (so nothing renders twice) and grants a ping-bounded completion grace when the
 * player retargets early, so predicted breaks confirm instead of rolling back.
 *
 * <p>The engine only arms itself after seeing a {@code PKT_MINE_START} within
 * the last {@link Protocol#MINE_PREDICT_ARMED_WINDOW_MS} — outside custom-mining
 * areas (cells, lobby) it stays silent.
 */
public final class MinePredictRenderer {

    /** Reserved synthetic entity id range. Far above any real player entity id. */
    private static final int BASE_ENTITY_ID = 1_000_000_000;

    /** Hard cap on concurrent predicted animations. */
    private static final int MAX_ENTRIES = 16;

    /** Number of block particles in the local break flash. Vanilla world-event 2001
     *  spawns ~30 which is visual noise when ores are mined rapidly. */
    private static final int BREAK_FLASH_PARTICLES = 5;

    /** Per-block-type predicted duration + replacement from the server's speed table. */
    private record OreSpeed(int durationMs, BlockState replacement) {}

    private static final Map<Block, OreSpeed> SPEED_TABLE = new HashMap<>();
    /** Durations learned from PKT_MINE_START, for block types the table doesn't cover
     *  (crude ores, rift blocks, rush blocks). */
    private static final Map<Block, Integer> LEARNED_DURATION = new HashMap<>();
    /** Replacements learned from observed post-break server block updates. */
    private static final Map<Block, BlockState> LEARNED_REPLACEMENT = new HashMap<>();
    /** Positions whose last ghost swap rolled back — no swaps there until expiry (ms timestamp). */
    private static final Map<BlockPos, Long> POS_BLACKLIST = new HashMap<>();

    /** Last time the server sent PKT_MINE_START — arms swing-time prediction. */
    private static long lastMineStartMs = 0L;

    private static final Map<BlockPos, Entry> ACTIVE = new HashMap<>();

    private static final class Entry {
        final int entityId;
        final long startMs;
        int durationMs;
        /** Block type being mined (for learning on confirm). */
        final Block ore;
        /** True once the server's PKT_MINE_START re-anchored the duration. */
        boolean serverSynced;
        /** Non-null once the ghost swap happened; holds the state we predicted. */
        BlockState swappedTo;
        /** The pre-swap state, for rollback. */
        BlockState priorState;
        /** Deadline (ms) for the server to confirm the swap before rollback. */
        long confirmDeadlineMs;
        int lastStageSent = -1;

        Entry(int entityId, long startMs, int durationMs, Block ore, boolean serverSynced) {
            this.entityId = entityId;
            this.startMs = startMs;
            this.durationMs = Math.max(1, durationMs);
            this.ore = ore;
            this.serverSynced = serverSynced;
        }

        boolean swapped() {
            return swappedTo != null;
        }
    }

    private static int nextEntityId = BASE_ENTITY_ID;

    // ── Inbound packets ──────────────────────────────────────────────────────

    /** Server speed table — refreshed at 1 Hz while mining. Replaces wholesale. */
    public static void onSpeedTable(MineSpeedsPayload payload) {
        SPEED_TABLE.clear();
        for (MineSpeedsPayload.Row row : payload.rows()) {
            Block ore = blockFor(row.oreId());
            Block replacement = blockFor(row.replacementId());
            if (ore == null) continue;
            SPEED_TABLE.put(ore, new OreSpeed(row.durationMs(),
                    replacement == null ? null : replacement.getDefaultState()));
        }
    }

    /** Server-origin per-block start — authoritative duration reconciliation. */
    public static void onMineStart(MineStartPayload payload) {
        lastMineStartMs = System.currentTimeMillis();
        if (!FeatureToggles.isMinePredictEnabled()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;

        BlockPos pos = payload.pos();
        Entry existing = ACTIVE.get(pos);
        if (existing != null && !existing.swapped()) {
            // We predicted this block on swing — adopt the server's authoritative
            // duration but keep our (earlier) local start time. Also learn the
            // duration for this ore so future first-swings are exact.
            existing.durationMs = Math.max(1, payload.durationMs());
            existing.serverSynced = true;
            LEARNED_DURATION.put(existing.ore, payload.durationMs());
            return;
        }
        if (existing != null) {
            // Already swapped (e.g. the server restarted the same block's task
            // after our predicted finish). Leave the confirm logic to resolve it.
            return;
        }

        BlockState state = world.getBlockState(pos);
        if (!state.isAir()) {
            LEARNED_DURATION.put(state.getBlock(), payload.durationMs());
        }

        // No local prediction was running (unknown block type, or the engine
        // wasn't armed yet) — start a server-paced entry now, like the legacy
        // behavior, so the crack still animates without the server's stream.
        if (payload.durationMs() < Protocol.INSTA_BREAK_THRESHOLD_MS) {
            Entry e = newEntry(world, pos, payload.durationMs(), state.getBlock(), true);
            if (e != null) ghostBreak(client, world, pos, e);
            return;
        }
        newEntry(world, pos, payload.durationMs(), state.getBlock(), true);
    }

    /** Server-origin cancel — the player released before completion. */
    public static void onMineCancel(BlockPos pos) {
        Entry entry = ACTIVE.get(pos);
        if (entry == null) return;
        if (entry.swapped()) {
            // The server may still grace-finish this block (its abort fires the
            // cancel before the grace check completes the break) — shorten the
            // confirm window and let confirm/rollback resolve it instead of
            // flickering the block back immediately.
            entry.confirmDeadlineMs = Math.min(entry.confirmDeadlineMs,
                    System.currentTimeMillis() + Protocol.MINE_PREDICT_CONFIRM_MIN_MS);
            return;
        }
        clearCrack(pos, entry);
        ACTIVE.remove(pos);
    }

    /**
     * Every server block update at a tracked position resolves that prediction
     * (called from the network-handler mixin, on the render thread, after the
     * state has been applied to the world).
     */
    public static void onServerBlockUpdate(BlockPos pos, BlockState newState) {
        Entry entry = ACTIVE.get(pos);
        if (entry == null) return;
        if (entry.ore != null && newState.getBlock() == entry.ore) {
            // Server re-asserted the ore (resync / rejected break). The world
            // already shows the server's state; just drop the prediction.
            if (entry.swapped()) {
                POS_BLACKLIST.put(pos.toImmutable(), System.currentTimeMillis() + Protocol.MINE_PREDICT_POS_BLACKLIST_MS);
            }
            clearCrack(pos, entry);
            ACTIVE.remove(pos);
            return;
        }
        // Block changed → this is the authoritative break. Learn the replacement
        // for first-swing swaps on future blocks of this type.
        if (entry.ore != null && !newState.isAir()) {
            LEARNED_REPLACEMENT.put(entry.ore, newState);
        }
        if (!entry.swapped()) {
            // Crack-only prediction (no replacement known) — play the flash now,
            // at confirmation time, since the server no longer sends its own
            // break effects to predict-on clients.
            MinecraftClient client = MinecraftClient.getInstance();
            ClientWorld world = client.world;
            if (world != null && entry.priorState == null) {
                playBreakFlash(client, world, pos, entry.ore == null ? newState : entry.ore.getDefaultState());
            }
        }
        clearCrack(pos, entry);
        ACTIVE.remove(pos);
    }

    // ── Per-frame engine ─────────────────────────────────────────────────────

    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) {
            if (!ACTIVE.isEmpty()) ACTIVE.clear();
            return;
        }

        long now = System.currentTimeMillis();

        boolean attacking = client.options.attackKey.isPressed();
        BlockPos targeted = null;
        HitResult hit = client.crosshairTarget;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            targeted = bhr.getBlockPos();
        }

        // Advance / expire current predictions.
        Iterator<Map.Entry<BlockPos, Entry>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Entry> e = it.next();
            Entry entry = e.getValue();
            BlockPos pos = e.getKey();

            if (entry.swapped()) {
                // Awaiting server confirmation — independent of mouse state.
                if (now >= entry.confirmDeadlineMs) {
                    rollback(world, pos, entry);
                    it.remove();
                }
                continue;
            }

            long elapsed = now - entry.startMs;

            // Self-cancel: player released attack or moved the crosshair off the
            // block. No grace window — an earlier 40ms grace leaked the first
            // crack stage on single taps.
            boolean stillTargeting = pos.equals(targeted);
            if (!attacking || !stillTargeting) {
                clearCrack(pos, entry);
                it.remove();
                continue;
            }

            if (elapsed >= entry.durationMs) {
                ghostBreak(client, world, pos, entry);
                if (!entry.swapped()) it.remove(); // crack-only: wait for the server's break
                continue;
            }

            int stage = (int) Math.min(9, (elapsed * 10) / entry.durationMs);
            if (stage != entry.lastStageSent) {
                world.setBlockBreakingInfo(entry.entityId, pos, stage);
                entry.lastStageSent = stage;
            }
        }

        // Expire stale blacklist entries occasionally (cheap; map stays tiny).
        if (!POS_BLACKLIST.isEmpty()) {
            POS_BLACKLIST.values().removeIf(deadline -> now >= deadline);
        }

        // Swing-start: begin a new prediction the instant the player attacks a
        // block whose timeline we know — no round trip.
        if (!FeatureToggles.isMinePredictEnabled()) return;
        if (!attacking || targeted == null || ACTIVE.containsKey(targeted)) return;
        if (now - lastMineStartMs > Protocol.MINE_PREDICT_ARMED_WINDOW_MS) return; // not in a custom-mining area

        BlockState state = world.getBlockState(targeted);
        if (state.isAir()) return;
        Block block = state.getBlock();
        OreSpeed tableRow = SPEED_TABLE.get(block);
        // Keep BOTH branches boxed: mixing a primitive (durationMs()) with an
        // Integer (map.get) in a ternary makes Java unbox the Integer branch to
        // find a common type, NPE-ing on a null map hit BEFORE the null guard
        // below can run. Resolve each branch explicitly instead.
        Integer duration = tableRow != null ? Integer.valueOf(tableRow.durationMs()) : LEARNED_DURATION.get(block);
        if (duration == null) return;

        Entry entry = newEntry(world, targeted, duration, block, false);
        if (entry != null && duration < Protocol.INSTA_BREAK_THRESHOLD_MS) {
            ghostBreak(client, world, targeted, entry);
            if (!entry.swapped()) ACTIVE.remove(targeted);
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static Entry newEntry(ClientWorld world, BlockPos pos, int durationMs, Block ore, boolean serverSynced) {
        if (ACTIVE.size() >= MAX_ENTRIES) {
            Iterator<Map.Entry<BlockPos, Entry>> it = ACTIVE.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<BlockPos, Entry> oldest = it.next();
                if (oldest.getValue().swapped()) {
                    rollback(world, oldest.getKey(), oldest.getValue());
                } else {
                    clearCrack(oldest.getKey(), oldest.getValue());
                }
                it.remove();
            }
        }
        int entityId = nextEntityId++;
        if (nextEntityId > BASE_ENTITY_ID + 1_000_000) nextEntityId = BASE_ENTITY_ID;
        Entry entry = new Entry(entityId, System.currentTimeMillis(), durationMs, ore, serverSynced);
        ACTIVE.put(pos.toImmutable(), entry);
        return entry;
    }

    /**
     * Predicted completion: swap the block to its known replacement locally and
     * play the break flash. If no replacement is known (or the position recently
     * rolled back), this only plays the crack-clear — the flash then plays when
     * the server's real block update arrives.
     */
    private static void ghostBreak(MinecraftClient client, ClientWorld world, BlockPos pos, Entry entry) {
        clearCrack(pos, entry);

        BlockState prior = world.getBlockState(pos);
        BlockState replacement = null;
        OreSpeed tableRow = entry.ore != null ? SPEED_TABLE.get(entry.ore) : null;
        if (tableRow != null) replacement = tableRow.replacement();
        if (replacement == null && entry.ore != null) replacement = LEARNED_REPLACEMENT.get(entry.ore);

        Long blacklistedUntil = POS_BLACKLIST.get(pos);
        boolean blacklisted = blacklistedUntil != null && System.currentTimeMillis() < blacklistedUntil;

        if (replacement == null || blacklisted || prior.isAir()) {
            return; // crack-only prediction; server's block update finishes the job
        }

        playBreakFlash(client, world, pos, prior);
        if (replacement != prior) {
            world.setBlockState(pos, replacement, Block.NOTIFY_ALL);
        }
        entry.priorState = prior;
        entry.swappedTo = replacement;
        entry.confirmDeadlineMs = System.currentTimeMillis() + confirmWindowMs(client);
    }

    /** 2×latency + 500ms, clamped — how long we wait for the server to confirm a swap. */
    private static long confirmWindowMs(MinecraftClient client) {
        int latency = 0;
        try {
            if (client.player != null && client.getNetworkHandler() != null) {
                PlayerListEntry self = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
                if (self != null) latency = Math.max(0, self.getLatency());
            }
        } catch (Throwable ignored) {
        }
        long window = 2L * latency + 500L;
        return Math.max(Protocol.MINE_PREDICT_CONFIRM_MIN_MS,
                Math.min(Protocol.MINE_PREDICT_CONFIRM_MAX_MS, window));
    }

    /** Local break flash: a handful of block particles + the break sound. */
    private static void playBreakFlash(MinecraftClient client, ClientWorld world, BlockPos pos, BlockState brokenState) {
        if (brokenState == null || brokenState.isAir()) return;
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        BlockStateParticleEffect fx = new BlockStateParticleEffect(ParticleTypes.BLOCK, brokenState);
        for (int i = 0; i < BREAK_FLASH_PARTICLES; i++) {
            client.particleManager.addParticle(fx,
                    cx + (r.nextDouble() - 0.5) * 0.5,
                    cy + (r.nextDouble() - 0.5) * 0.5,
                    cz + (r.nextDouble() - 0.5) * 0.5,
                    (r.nextDouble() - 0.5) * 0.2,
                    r.nextDouble() * 0.2,
                    (r.nextDouble() - 0.5) * 0.2);
        }
        world.playSound(null, cx, cy, cz,
                brokenState.getSoundGroup().getBreakSound(),
                SoundCategory.BLOCKS,
                0.6f, 0.9f + r.nextFloat() * 0.2f);
    }

    /** The server never confirmed our swap — restore reality and remember not to
     *  swap at this position for a while. */
    private static void rollback(ClientWorld world, BlockPos pos, Entry entry) {
        if (entry.priorState != null && world.getBlockState(pos) == entry.swappedTo) {
            world.setBlockState(pos, entry.priorState, Block.NOTIFY_ALL);
        }
        POS_BLACKLIST.put(pos.toImmutable(), System.currentTimeMillis() + Protocol.MINE_PREDICT_POS_BLACKLIST_MS);
        PrisonsMod.LOGGER.debug("MinePredict rollback at {}", pos);
    }

    private static void clearCrack(BlockPos pos, Entry entry) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world != null && entry.lastStageSent >= 0) {
            world.setBlockBreakingInfo(entry.entityId, pos, -1);
        }
        entry.lastStageSent = -1;
    }

    /** Bukkit Material name → client Block, or null when unknown on this client. */
    private static Block blockFor(String bukkitMaterialName) {
        if (bukkitMaterialName == null || bukkitMaterialName.isEmpty()) return null;
        try {
            Identifier id = Identifier.of("minecraft", bukkitMaterialName.toLowerCase(Locale.ROOT));
            if (!Registries.BLOCK.containsId(id)) return null;
            return Registries.BLOCK.get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Called when the mod is disabled (leaving an allowlisted server) or on disconnect. */
    public static void reset() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world != null) {
            for (Map.Entry<BlockPos, Entry> e : ACTIVE.entrySet()) {
                Entry entry = e.getValue();
                if (entry.swapped() && entry.priorState != null
                        && world.getBlockState(e.getKey()) == entry.swappedTo) {
                    world.setBlockState(e.getKey(), entry.priorState, Block.NOTIFY_ALL);
                }
                world.setBlockBreakingInfo(entry.entityId, e.getKey(), -1);
            }
        }
        ACTIVE.clear();
        SPEED_TABLE.clear();
        LEARNED_DURATION.clear();
        LEARNED_REPLACEMENT.clear();
        POS_BLACKLIST.clear();
        lastMineStartMs = 0L;
        PrisonsMod.LOGGER.debug("MinePredictRenderer reset");
    }

    private MinePredictRenderer() {}
}
