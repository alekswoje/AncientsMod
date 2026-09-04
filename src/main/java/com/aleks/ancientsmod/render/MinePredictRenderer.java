package com.aleks.ancientsmod.render;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.hud.MeteoriteState;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.MineSpeedsPayload;
import com.aleks.ancientsmod.net.payload.MineStartPayload;
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
 *   <li><b>Rollback on refusal, not on silence.</b> A swap rolls back once the
 *       server has demonstrably moved past the block without breaking it — it
 *       started a newer block ({@code PKT_MINE_START} for a later entry) or sent
 *       {@code PKT_MINE_CANCEL} for this one — and the soft confirm window
 *       ({@code 2×latency + 500ms}) has passed; or, whatever the server has said,
 *       after {@link Protocol#MINE_PREDICT_CONFIRM_HARD_MS}. A server that has
 *       sent nothing is stalled, and its confirmation is still in flight: rolling
 *       back on a wall-clock timer alone popped freshly mined blocks back into
 *       place on every server hitch. On rollback the original state is restored
 *       and ghost swaps at that position are suppressed for a while, re-suppressed
 *       on every further predicted break there (covers multi-hit blocks the engine
 *       can't model). Meteorites skip the swap up front instead — see
 *       {@link #ghostBreak}.</li>
 * </ol>
 *
 * <p>Server-authoritative throughout: nothing here grants rewards or sends break
 * packets — the server runs its own timer and validates everything. The server
 * also suppresses its own crack stream + break effects for predict-on clients
 * (so nothing renders twice) and grants a ping-bounded completion grace when the
 * player retargets early, so predicted breaks confirm instead of rolling back.
 *
 * <p><b>The server's break-effect suppression is unconditional</b> — it keys off
 * the reported predict-on flag alone, not off whether this engine still holds a
 * live prediction for that block. So every break we abandon early would render
 * nothing at all. {@link #OWED_FLASH} closes that: whenever an unswapped entry
 * is given up while the server may still break the block (predicted timer
 * elapsed with no known replacement, position blacklisted after a rollback,
 * attack released or crosshair moved off while the server's completion grace
 * finishes it, {@code PKT_MINE_CANCEL}, entry evicted at the cap), the position
 * is recorded as owing a flash and the server's own block update pays it. Every
 * break therefore renders exactly one effect.
 *
 * <p>{@link #PAUSED} is the counterpart for the crack itself. The server keeps a
 * paused block's mining progress and resumes from it (its
 * {@code mining.progress-persist-ticks} window), and {@code MiningResumeMixin}
 * re-sends {@code START_DESTROY_BLOCK} on look-back so it does — but the
 * {@code PKT_MINE_START} that follows carries the <em>full</em> duration, not the
 * remaining one. Left alone the crack would restart from stage 0 on every
 * look-away/look-back and every release/re-press. Remembering the elapsed time
 * per position and resuming from it keeps the predicted timeline on the server's.
 *
 * <p>The engine only arms itself after seeing a {@code PKT_MINE_START} within
 * the last {@link Protocol#MINE_PREDICT_ARMED_WINDOW_MS} — outside custom-mining
 * areas (cells, lobby) it stays silent.
 */
public final class MinePredictRenderer {

    /** Reserved synthetic entity id range. Far above any real player entity id. */
    private static final int BASE_ENTITY_ID = 1_000_000_000;

    /** Hard cap on concurrent predicted animations. Swapped entries wait out server
     *  stalls (up to {@link Protocol#MINE_PREDICT_CONFIRM_HARD_MS}) before resolving,
     *  so at endgame speeds a 2s hitch can leave a dozen of them pending. */
    private static final int MAX_ENTRIES = 32;

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

    /** A break flash this engine owes a position because it dropped the prediction
     *  before the server's block update landed, while the server was suppressing
     *  its own effects for us. Paid out by {@link #onServerBlockUpdate}. */
    private record OwedFlash(BlockState priorState, long expiresAtMs) {}

    /** Elapsed prediction time remembered for a position whose break was paused
     *  (attack released, crosshair moved off, {@code PKT_MINE_CANCEL}). The server
     *  resumes its own mining task from the same point, so a resumed prediction
     *  seeds its start time from here rather than restarting the crack. */
    private record PausedProgress(Block ore, long elapsedMs, long expiresAtMs) {}

    private static final Map<BlockPos, OwedFlash> OWED_FLASH = new HashMap<>();
    private static final Map<BlockPos, PausedProgress> PAUSED = new HashMap<>();

    /** Last time the server sent PKT_MINE_START — arms swing-time prediction. */
    private static long lastMineStartMs = 0L;

    /** ClickLock state (server-driven mining without the attack key held). While
     *  on, the cancel gate keeps server-paced predictions alive on crosshair
     *  targeting alone — see {@link #tick()}. */
    private static boolean clickLockActive = false;

    private static final Map<BlockPos, Entry> ACTIVE = new HashMap<>();

    private static long entrySeq = 0L;

    // ── Telemetry ────────────────────────────────────────────────────────────

    /** Counters since the last reset. Read by the Predict HUD and {@code /ancientsmod predict}. */
    public static final class Stats {
        /** Swing-time predictions started / server-paced entries created. */
        public long predictions, serverPaced;
        /** Ghost swaps made / confirmed by a server block update / crack-only completions confirmed. */
        public long swaps, confirms, crackOnlyConfirms;
        /** Our own first PKT_MINE_START that landed after the ghost swap (a server hitch) and was adopted. */
        public long lateStartAdopted;
        public long rollbackTimeout, rollbackMovedOn, rollbackReassert, rollbackSecondStart, rollbackEvicted;
        public long cancelsReceived, selfCancels;
        /** Client ticks where vanilla's own break progress was frozen on a ghost-swapped block. */
        public long localBreakFrozen;
        public long confirmLatencySumMs, confirmLatencyMaxMs;
        public long lastRollbackMs;
        public String lastRollbackReason = "";

        public long rollbacks() {
            return rollbackTimeout + rollbackMovedOn + rollbackReassert + rollbackSecondStart + rollbackEvicted;
        }

        public long confirmLatencyAvgMs() {
            return confirms == 0 ? 0L : confirmLatencySumMs / confirms;
        }
    }

    private static Stats stats = new Stats();
    private static long statsSinceMs = System.currentTimeMillis();
    private static volatile boolean debugLog = false;

    public static Stats stats() { return stats; }
    public static long statsSinceMs() { return statsSinceMs; }
    public static void resetStats() { stats = new Stats(); statsSinceMs = System.currentTimeMillis(); }
    public static boolean isDebugLog() { return debugLog; }
    public static void setDebugLog(boolean on) { debugLog = on; }
    public static int activeCount() { return ACTIVE.size(); }

    /**
     * True while the block at {@code pos} is showing our ghost replacement, the
     * server has told us it owns this break, and it has not confirmed it yet.
     *
     * <p>The {@code serverSynced} half matters: it means a {@code PKT_MINE_START}
     * arrived for THIS block, which is the only proof the server is running a
     * custom mining task on it and will break it on its own schedule. Blocks that
     * bypass custom mining — cell-region ore, Skywars islands — break with plain
     * vanilla rules and never get a START. They CAN still be swing-predicted (the
     * speed table is keyed by ore type and the armed window lasts 60s, so walking
     * from the mine into a cell can ghost-swap a cell ore), and freezing vanilla's
     * break there would stop the block from ever breaking until the 2.5s hard
     * deadline. Requiring the START keeps the freeze on server-owned breaks only.
     *
     * <p>Read by {@code MiningLocalBreakFreezeMixin}. Vanilla keeps its own break
     * progress at the position it is mining and recomputes the per-tick delta from
     * whatever block state is there — {@code isCurrentlyBreaking} compares position
     * and held item only, never the state. Our swap puts a SOFTER block under the
     * crosshair (stone 1.5 vs ore 3.0, netherrack 0.4 vs quartz ore 3.0, nether
     * bricks 2.0 vs ancient debris 30) and can flip {@code canHarvest} false→true
     * for another 3.3x, while the progress accumulated on the ore carries over. So
     * vanilla's local break completes shortly after the ghost swap, sends a
     * {@code STOP_DESTROY_BLOCK} the server cancels and answers by re-asserting the
     * un-broken ore, and parks the client in a 5-tick (250ms) breaking cooldown in
     * which no START goes out for the next block. Net: a 250ms hole in the mining
     * loop plus a re-assert rollback that pops the ore back, once per block.
     */
    public static boolean isServerOwnedGhostAt(BlockPos pos) {
        if (pos == null) return false;
        Entry entry = ACTIVE.get(pos);
        return entry != null && entry.swapped() && entry.serverSynced;
    }

    /** Counts a client tick where the mixin above froze vanilla's local break. */
    public static void noteLocalBreakFrozen() {
        stats.localBreakFrozen++;
    }

    private static void recordRollback(String reason) {
        switch (reason) {
            case "timeout" -> stats.rollbackTimeout++;
            case "server-moved-on" -> stats.rollbackMovedOn++;
            case "reassert" -> stats.rollbackReassert++;
            case "second-start" -> stats.rollbackSecondStart++;
            default -> stats.rollbackEvicted++;
        }
        stats.lastRollbackMs = System.currentTimeMillis();
        stats.lastRollbackReason = reason;
    }

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
        /** Soft deadline (ms): the swap may roll back after this, but only once {@link #serverMovedOn}. */
        long confirmDeadlineMs;
        /** Hard deadline (ms): the swap rolls back after this regardless of what the server has said. */
        long hardDeadlineMs;
        /** When the ghost swap happened — confirm latency is measured from here. */
        long swapMs;
        /** Set once the server has demonstrably processed our abort of this block without breaking
         *  it: a PKT_MINE_START for a newer entry, or a PKT_MINE_CANCEL for this one. Until then a
         *  missing confirmation means the server is stalled, not that it refused the break. */
        boolean serverMovedOn;
        /** Creation order. A PKT_MINE_START for a higher seq proves the server has already handled
         *  every earlier entry's abort (dig packets are processed in order). */
        final long seq;
        int lastStageSent = -1;

        Entry(int entityId, long startMs, int durationMs, Block ore, boolean serverSynced) {
            this.seq = ++entrySeq;
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
        long now = System.currentTimeMillis();
        Entry existing = ACTIVE.get(pos);
        if (existing != null && !existing.serverSynced) {
            // We predicted this block on swing — adopt the server's authoritative
            // duration but keep our (earlier) local start time. Also learn the
            // duration for this ore so future first-swings are exact.
            //
            // Usually this lands mid-crack. After a server hitch it can land AFTER
            // our ghost swap — and that is still the FIRST start for this block, not
            // a rejection: the break the server just began is what will confirm the
            // swap. (Treating it as a second start rolled every such block back to
            // ore and blacklisted the position, then the server's break arrived and
            // swapped it again — the "pops back, then breaks" flicker on every hitch,
            // and on EVERY sub-100ms insta-break, whose swap always precedes it.)
            existing.durationMs = Math.max(1, payload.durationMs());
            existing.serverSynced = true;
            LEARNED_DURATION.put(existing.ore, payload.durationMs());
            if (existing.swapped()) {
                stats.lateStartAdopted++;
                // The server is only now starting this block: give it the full
                // duration plus the usual confirm window before judging the swap.
                existing.confirmDeadlineMs = Math.max(existing.confirmDeadlineMs,
                        now + existing.durationMs + confirmWindowMs(client));
                existing.hardDeadlineMs = Math.max(existing.hardDeadlineMs,
                        now + existing.durationMs + Protocol.MINE_PREDICT_CONFIRM_HARD_MS);
                if (debugLog) {
                    AncientsMod.LOGGER.info("[MinePredict] late server start adopted at {} ({}ms, swapped {}ms ago)",
                            pos, existing.durationMs, now - existing.swapMs);
                }
            }
            markOlderSwapsMovedOn(existing.seq, pos, now);
            return;
        }
        if (existing != null) {
            // A SECOND server start at a position we already track. Swing-time
            // prediction can only run ahead of the server by about one round
            // trip, so once an entry is server-synced another PKT_MINE_START
            // here is not a re-anchor — the server began a brand new break on
            // the same block.
            //
            // Mining rushes make that the norm: the rush block stays in place
            // for all of its swings, so every break restarts on the same
            // BlockPos and no block update ever arrives to retire the old
            // entry. Absorbing the packet kept the FINISHED break's start time,
            // so the entry standing in for the new break was already past its
            // duration and got dropped on the next client tick without ever
            // rendering a stage — and because packets are handled before
            // END_CLIENT_TICK, that race went the same way every time: under
            // ClickLock only every second rush swing showed a crack.
            if (existing.swapped()) {
                // The server would not start another break here if our ghost
                // swap had been real — the block never changed. Put it back.
                rollback(world, pos, existing, "second-start");
            } else {
                clearCrack(pos, existing);
            }
            ACTIVE.remove(pos);
            // No owed flash: the block did not break-and-change, so there is
            // nothing for a later server block update to pay out.
        }

        BlockState state = world.getBlockState(pos);
        if (!state.isAir()) {
            LEARNED_DURATION.put(state.getBlock(), payload.durationMs());
        }

        // No local prediction was running (unknown block type, or the engine
        // wasn't armed yet) — start a server-paced entry now, like the legacy
        // behavior, so the crack still animates without the server's stream.
        Entry paced = newEntry(world, pos, payload.durationMs(), state.getBlock(), true);
        if (paced != null) stats.serverPaced++;
        // The server processing a START here means it has already processed every
        // dig packet the client sent before it — including the aborts of all older
        // swapped entries. Any of those still unconfirmed was not broken.
        markOlderSwapsMovedOn(paced != null ? paced.seq : Long.MAX_VALUE, pos, now);
        if (paced != null && payload.durationMs() < Protocol.INSTA_BREAK_THRESHOLD_MS) {
            ghostBreak(client, world, pos, paced);
        }
    }

    /**
     * The server has started the entry with {@code seq} (or one at {@code pos} with no
     * local entry): every OLDER swapped entry at another position has had its abort
     * processed without a break, so its soft confirm deadline may now fire — after a
     * short grace for the confirming block update that flushes at the end of the
     * server's tick, behind the PKT_MINE_START it just sent.
     */
    private static void markOlderSwapsMovedOn(long seq, BlockPos pos, long now) {
        for (Map.Entry<BlockPos, Entry> e : ACTIVE.entrySet()) {
            Entry other = e.getValue();
            if (!other.swapped() || other.seq >= seq || e.getKey().equals(pos)) continue;
            if (!other.serverMovedOn) {
                other.serverMovedOn = true;
                other.confirmDeadlineMs = Math.min(other.confirmDeadlineMs,
                        now + Protocol.MINE_PREDICT_MOVED_ON_GRACE_MS);
            }
        }
    }

    /** Server-origin ClickLock state. While on, the engine drives server-paced
     *  cracks (from PKT_MINE_START) without requiring the attack key held. */
    public static void onClickLockState(boolean on) {
        clickLockActive = on;
    }

    /** Server-origin cancel — the player released before completion. */
    public static void onMineCancel(BlockPos pos) {
        stats.cancelsReceived++;
        Entry entry = ACTIVE.get(pos);
        if (entry == null) return;
        if (entry.swapped()) {
            // Servers before the 2026-09 fix send this from the abort event itself,
            // before their completion grace may still finish the block — so keep a
            // short confirm window rather than flickering the block back at once.
            // Either way the server has now spoken about this block: the soft
            // deadline may fire.
            entry.serverMovedOn = true;
            entry.confirmDeadlineMs = Math.min(entry.confirmDeadlineMs,
                    System.currentTimeMillis() + Protocol.MINE_PREDICT_CONFIRM_MIN_MS);
            return;
        }
        // Unswapped, and the same abort that produced this cancel can still be
        // grace-finished into a real break by the server. Keep the position owed
        // a flash (the server suppresses its own) and remember the progress the
        // server is persisting, then drop the entry.
        clearCrack(pos, entry);
        pauseEntry(pos, entry);
        ACTIVE.remove(pos);
    }

    /**
     * Every server block update at a tracked position resolves that prediction
     * (called from the network-handler mixin, on the render thread, after the
     * state has been applied to the world).
     */
    public static void onServerBlockUpdate(BlockPos pos, BlockState newState) {
        Entry entry = ACTIVE.get(pos);
        if (entry == null) {
            payOwedFlash(pos, newState);
            return;
        }
        // A live entry owns this position's effect — it either already flashed on
        // the ghost swap or flashes below. Drop any stale debt so it can't fire a
        // second effect on a later update here.
        OWED_FLASH.remove(pos);
        if (entry.ore != null && newState.getBlock() == entry.ore) {
            // Server re-asserted the ore (resync / rejected break). The world
            // already shows the server's state; just drop the prediction.
            if (entry.swapped()) {
                POS_BLACKLIST.put(pos.toImmutable(), System.currentTimeMillis() + Protocol.MINE_PREDICT_POS_BLACKLIST_MS);
                recordRollback("reassert");
                if (debugLog) AncientsMod.LOGGER.info("[MinePredict] server re-asserted {} at {}", entry.ore, pos);
            }
            clearCrack(pos, entry);
            ACTIVE.remove(pos);
            return;
        }
        // Block changed → this is the authoritative break. Learn the replacement
        // for first-swing swaps on future blocks of this type.
        PAUSED.remove(pos); // the break ends any paused progress the server held here
        POS_BLACKLIST.remove(pos); // proof the server does change this block — predict here again
        if (entry.ore != null && !newState.isAir()) {
            LEARNED_REPLACEMENT.put(entry.ore, newState);
        }
        if (entry.swapped()) {
            long latency = Math.max(0L, System.currentTimeMillis() - entry.swapMs);
            stats.confirms++;
            stats.confirmLatencySumMs += latency;
            if (latency > stats.confirmLatencyMaxMs) stats.confirmLatencyMaxMs = latency;
        } else {
            stats.crackOnlyConfirms++;
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
                // Awaiting server confirmation — independent of mouse state. The soft
                // deadline only counts once the server has moved past this block; a
                // server that has said nothing is stalled and its confirmation is still
                // on its way. The hard deadline bounds the wait regardless.
                boolean hard = now >= entry.hardDeadlineMs;
                boolean soft = entry.serverMovedOn && now >= entry.confirmDeadlineMs;
                if (hard || soft) {
                    rollback(world, pos, entry, hard ? "timeout" : "server-moved-on");
                    it.remove();
                }
                continue;
            }

            long elapsed = now - entry.startMs;

            // Self-cancel: player released attack or moved the crosshair off the
            // block. No grace window — an earlier 40ms grace leaked the first
            // crack stage on single taps. Under ClickLock the server mines
            // without the attack key held, so keep the entry alive on
            // crosshair-targeting alone; PKT_MINE_CANCEL / the crosshair leaving
            // the block still end it.
            boolean miningHeld = attacking || clickLockActive;
            boolean stillTargeting = pos.equals(targeted);
            if (!miningHeld || !stillTargeting) {
                stats.selfCancels++;
                // The server does not necessarily stop here: within a ping-derived
                // grace of completion it finishes the block instead of pausing, and
                // it suppresses the break effects for us either way. So leave the
                // position owed a flash, and remember the progress the server is
                // persisting so a resumed break continues this crack.
                clearCrack(pos, entry);
                pauseEntry(pos, entry);
                it.remove();
                continue;
            }

            if (elapsed >= entry.durationMs) {
                ghostBreak(client, world, pos, entry);
                if (!entry.swapped()) {
                    // Crack-only prediction: no ghost swap and so no flash yet.
                    // The entry cannot stay (a fresh swing must be able to re-predict
                    // here), so hand the flash to the owed-flash ledger instead.
                    owePendingFlash(client, world, pos);
                    it.remove();
                }
                continue;
            }

            int stage = (int) Math.min(9, (elapsed * 10) / entry.durationMs);
            if (stage != entry.lastStageSent) {
                world.setBlockBreakingInfo(entry.entityId, pos, stage);
                entry.lastStageSent = stage;
            }
        }

        // Expire stale bookkeeping occasionally (cheap; maps stay tiny).
        if (!POS_BLACKLIST.isEmpty()) {
            POS_BLACKLIST.values().removeIf(deadline -> now >= deadline);
        }
        if (!OWED_FLASH.isEmpty()) {
            OWED_FLASH.values().removeIf(owed -> now >= owed.expiresAtMs());
        }
        if (!PAUSED.isEmpty()) {
            PAUSED.values().removeIf(paused -> now >= paused.expiresAtMs());
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
        if (entry != null) stats.predictions++;
        if (entry != null && duration < Protocol.INSTA_BREAK_THRESHOLD_MS) {
            ghostBreak(client, world, targeted, entry);
            if (!entry.swapped()) {
                owePendingFlash(client, world, targeted);
                ACTIVE.remove(targeted);
            }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static Entry newEntry(ClientWorld world, BlockPos pos, int durationMs, Block ore, boolean serverSynced) {
        if (ACTIVE.size() >= MAX_ENTRIES) {
            Iterator<Map.Entry<BlockPos, Entry>> it = ACTIVE.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<BlockPos, Entry> oldest = it.next();
                if (oldest.getValue().swapped()) {
                    rollback(world, oldest.getKey(), oldest.getValue(), "evicted");
                } else {
                    // Evicted before the server resolved it — it may still break,
                    // with the server's own effects suppressed. Leave the flash owed.
                    clearCrack(oldest.getKey(), oldest.getValue());
                    owePendingFlash(MinecraftClient.getInstance(), world, oldest.getKey());
                }
                it.remove();
            }
        }
        int entityId = nextEntityId++;
        if (nextEntityId > BASE_ENTITY_ID + 1_000_000) nextEntityId = BASE_ENTITY_ID;

        long now = System.currentTimeMillis();
        long startMs = now;
        // Resume, don't restart. The server persists a paused block's mining
        // progress and picks it back up on the next START_DESTROY_BLOCK (which
        // MiningResumeMixin re-sends on look-back), but the PKT_MINE_START that
        // follows carries the full duration rather than the remainder. Back-date
        // our start by the elapsed time we recorded when the break was paused so
        // the crack continues from where it stopped.
        PausedProgress paused = PAUSED.remove(pos);
        if (paused != null && paused.ore() == ore && now < paused.expiresAtMs()) {
            startMs -= Math.min(paused.elapsedMs(), Math.max(0, durationMs));
        }

        Entry entry = new Entry(entityId, startMs, durationMs, ore, serverSynced);
        ACTIVE.put(pos.toImmutable(), entry);
        return entry;
    }

    /**
     * Record that this position still owes a break flash: the prediction has been
     * given up but the server may yet break the block, and its own break effects
     * are suppressed for predict-on clients. Paid out by {@link #payOwedFlash}.
     */
    private static void owePendingFlash(MinecraftClient client, ClientWorld world, BlockPos pos) {
        if (world == null) return;
        BlockState prior = world.getBlockState(pos);
        if (prior.isAir()) return;
        if (OWED_FLASH.size() >= Protocol.MINE_PREDICT_MAX_TRACKED_POSITIONS) {
            Iterator<Map.Entry<BlockPos, OwedFlash>> it = OWED_FLASH.entrySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        OWED_FLASH.put(pos.toImmutable(),
                new OwedFlash(prior, System.currentTimeMillis() + confirmWindowMs(client)));
    }

    /**
     * A server block update landed at a position with no live prediction. If we
     * owe that position a flash and the block genuinely changed, this is the break
     * the server suppressed its effects for — play ours now.
     */
    private static void payOwedFlash(BlockPos pos, BlockState newState) {
        OwedFlash owed = OWED_FLASH.get(pos);
        if (owed == null) return;
        if (System.currentTimeMillis() >= owed.expiresAtMs()) {
            OWED_FLASH.remove(pos);
            return;
        }
        if (owed.priorState().getBlock() == newState.getBlock()) {
            // Same block re-asserted (resync, or a chunk delta that happens to
            // cover this position) — nothing broke. Keep the debt until it expires.
            return;
        }
        OWED_FLASH.remove(pos);
        PAUSED.remove(pos); // the break ends any paused progress the server held here
        POS_BLACKLIST.remove(pos); // proof the server does change this block — predict here again
        if (!FeatureToggles.isMinePredictEnabled()) return; // server isn't suppressing; it drew its own
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;
        playBreakFlash(client, world, pos, owed.priorState());
    }

    /**
     * Give up an unswapped entry that the server may still resolve either way:
     * leave the position owed a break flash (server grace-finish) and remember the
     * elapsed progress (server pause + resume). Mirrors PrisonsCore's own pause
     * path, which skips persisting progress when nothing has elapsed yet.
     */
    private static void pauseEntry(BlockPos pos, Entry entry) {
        MinecraftClient client = MinecraftClient.getInstance();
        owePendingFlash(client, client.world, pos);

        long elapsed = System.currentTimeMillis() - entry.startMs;
        if (elapsed < 50L || entry.ore == null) return; // sub-tick tap: server persists nothing
        if (PAUSED.size() >= Protocol.MINE_PREDICT_MAX_TRACKED_POSITIONS) {
            Iterator<Map.Entry<BlockPos, PausedProgress>> it = PAUSED.entrySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        PAUSED.put(pos.toImmutable(), new PausedProgress(entry.ore, Math.min(elapsed, entry.durationMs),
                System.currentTimeMillis() + Protocol.MINE_PREDICT_RESUME_WINDOW_MS));
    }

    /**
     * Predicted completion: swap the block to its known replacement locally and
     * play the break flash. If no replacement is known (or the position recently
     * rolled back, or the block is already gone), this only clears the crack and
     * leaves {@code swappedTo} null; the caller is then responsible for making
     * sure the flash still happens on the server's real block update — either by
     * keeping the entry alive or via {@link #owePendingFlash}.
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
        // A landed meteorite is a NETHER_QUARTZ_ORE block the server does NOT
        // change on a break: it decrements the meteorite's remaining-ore counter
        // and leaves the block standing until the last one. The speed table's
        // quartz row still names netherrack as the replacement, so swapping here
        // showed netherrack until the confirm window lapsed and rolled it back.
        // Predict the crack only — the meteorite finish path plays the server's
        // own break particles and sound (it is not predict-suppressed).
        boolean serverKeepsBlock = MeteoriteState.isKnownAt(pos.getX(), pos.getY(), pos.getZ());

        if (replacement == null || blacklisted || serverKeepsBlock || prior.isAir()) {
            if (blacklisted || serverKeepsBlock) {
                // Still swinging at a block the server keeps. Hold the suppression
                // open instead of letting it lapse mid-block into another flicker
                // — POS_BLACKLIST is only ever set on a rollback, so left alone it
                // expires and the very next predicted break swaps again.
                POS_BLACKLIST.put(pos.toImmutable(),
                        System.currentTimeMillis() + Protocol.MINE_PREDICT_POS_BLACKLIST_MS);
            }
            return; // crack-only prediction; server's block update finishes the job
        }

        playBreakFlash(client, world, pos, prior);
        if (replacement != prior) {
            world.setBlockState(pos, replacement, Block.NOTIFY_ALL);
        }
        long now = System.currentTimeMillis();
        entry.priorState = prior;
        entry.swappedTo = replacement;
        entry.swapMs = now;
        entry.serverMovedOn = false;
        entry.confirmDeadlineMs = now + confirmWindowMs(client);
        entry.hardDeadlineMs = now + Protocol.MINE_PREDICT_CONFIRM_HARD_MS;
        stats.swaps++;
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
    private static void rollback(ClientWorld world, BlockPos pos, Entry entry, String reason) {
        if (entry.priorState != null && world.getBlockState(pos) == entry.swappedTo) {
            world.setBlockState(pos, entry.priorState, Block.NOTIFY_ALL);
        }
        POS_BLACKLIST.put(pos.toImmutable(), System.currentTimeMillis() + Protocol.MINE_PREDICT_POS_BLACKLIST_MS);
        recordRollback(reason);
        long waited = entry.swapMs > 0 ? System.currentTimeMillis() - entry.swapMs : -1;
        if (debugLog) {
            AncientsMod.LOGGER.info("[MinePredict] rollback ({}) at {}: {} predicted {}ms, waited {}ms, synced={}",
                    reason, pos, entry.ore, entry.durationMs, waited, entry.serverSynced);
        } else {
            AncientsMod.LOGGER.debug("MinePredict rollback ({}) at {}", reason, pos);
        }
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
        OWED_FLASH.clear();
        PAUSED.clear();
        lastMineStartMs = 0L;
        clickLockActive = false;
        AncientsMod.LOGGER.debug("MinePredictRenderer reset");
    }

    private MinePredictRenderer() {}
}
