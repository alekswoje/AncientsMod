package com.aleks.prisonsmod.client.skilltree;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.screen.SkillTreeScreen;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.SkillTreeAckPayload;
import com.aleks.prisonsmod.net.payload.SkillTreeOpenPayload;
import com.aleks.prisonsmod.net.payload.SkillTreeStatePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Client-side state singleton for the Tartarus Vision (skill tree) screen.
 *
 * <p>Holds the most recent layout + state, opens / refreshes the screen on
 * S2C packets, and provides the four C2S helpers used by the screen.
 *
 * <p>All access is on the Minecraft client thread (Fabric receivers
 * dispatch there) — no synchronisation needed.
 */
public final class SkillTreeClient {

    private static volatile SkillTreeOpenPayload layout;
    private static volatile SkillTreeStatePayload state;
    /** Monotonic counter — the screen polls this to detect updates without comparing payloads. */
    private static volatile long version;

    /** Per-node "just unlocked" timestamps, so the screen can render a brief amethyst pulse. */
    private static final java.util.Map<String, Long> recentlyUnlockedAtMs = new java.util.concurrent.ConcurrentHashMap<>();
    /** Per-node "just refunded" timestamps — dimmer pulse, mirrors the chisel feedback. */
    private static final java.util.Map<String, Long> recentlyRefundedAtMs = new java.util.concurrent.ConcurrentHashMap<>();
    /** Animation lifetime — pulses fade to zero over this window. */
    public static final long PULSE_LIFETIME_MS = 800L;

    /** Hand-authored adjacency cache — keyed by layout reference so we don't
     *  rebuild on every state push (which would bump the counter). */
    private static volatile java.util.List<int[]> cachedHandAuthoredEdges;
    private static volatile SkillTreeOpenPayload cachedEdgesForLayout;

    private SkillTreeClient() {}

    public static SkillTreeOpenPayload layout() { return layout; }
    public static SkillTreeStatePayload state() { return state; }
    public static long version() { return version; }

    /** 1.0 → fresh pulse, 0.0 → expired. Caller scales alpha / radius by this value. */
    public static float unlockPulse(String nodeId) {
        Long at = recentlyUnlockedAtMs.get(nodeId);
        if (at == null) return 0f;
        long age = System.currentTimeMillis() - at;
        if (age >= PULSE_LIFETIME_MS) return 0f;
        return 1f - (float) age / (float) PULSE_LIFETIME_MS;
    }

    public static float refundPulse(String nodeId) {
        Long at = recentlyRefundedAtMs.get(nodeId);
        if (at == null) return 0f;
        long age = System.currentTimeMillis() - at;
        if (age >= PULSE_LIFETIME_MS) return 0f;
        return 1f - (float) age / (float) PULSE_LIFETIME_MS;
    }

    /**
     * Server sent a fresh layout — store it and open the screen if it
     * isn't already on top of {@link MinecraftClient#currentScreen}.
     */
    public static void onOpen(SkillTreeOpenPayload payload) {
        layout = payload;
        version++;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            // Idempotent — if the screen is already open it just refreshes.
            if (!(mc.currentScreen instanceof SkillTreeScreen)) {
                mc.setScreen(new SkillTreeScreen());
            }
        });
    }

    /**
     * Server sent fresh per-player state — diff against the previous
     * snapshot to capture per-node "just changed" timestamps for the
     * unlock pulse animation, then bump version so the screen re-renders.
     */
    public static void onState(SkillTreeStatePayload payload) {
        SkillTreeStatePayload prev = state;
        long now = System.currentTimeMillis();
        if (prev != null) {
            // Diff: newly unlocked = in new but not old.
            for (String id : payload.unlocked) {
                if (!prev.unlocked.contains(id)) recentlyUnlockedAtMs.put(id, now);
            }
            // Diff: newly refunded = in old but not new.
            for (String id : prev.unlocked) {
                if (!payload.unlocked.contains(id)) recentlyRefundedAtMs.put(id, now);
            }
        }
        // Prune ancient pulse timestamps so the map doesn't grow forever.
        long cutoff = now - PULSE_LIFETIME_MS * 4;
        recentlyUnlockedAtMs.entrySet().removeIf(e -> e.getValue() < cutoff);
        recentlyRefundedAtMs.entrySet().removeIf(e -> e.getValue() < cutoff);
        state = payload;
        version++;
    }

    /**
     * Server reported the result of a mod-initiated action. Success is
     * implied by the matching STATE push; ack is mainly for showing
     * failure toasts.
     */
    public static void onAck(SkillTreeAckPayload payload) {
        if (payload.isSuccess()) {
            // Light feedback chime — matches the in-world bell + amethyst chime
            // for chisel allocations.
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                if (payload.action == Protocol.SKILL_ACTION_ALLOCATE) {
                    mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.8f);
                } else if (payload.action == Protocol.SKILL_ACTION_REFUND) {
                    mc.player.playSound(SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, 0.9f, 1.4f);
                } else if (payload.action == Protocol.SKILL_ACTION_RESPEC) {
                    mc.player.playSound(SoundEvents.BLOCK_ANVIL_USE, 0.4f, 1.3f);
                }
            }
            return;
        }
        // Failure — surface as a chat toast (kept simple; no separate UI layer).
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        String reason = reasonFor(payload.result);
        mc.player.sendMessage(Text.literal("✦ " + reason).formatted(Formatting.RED), true);
        mc.player.playSound(SoundEvents.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
    }

    private static String reasonFor(byte result) {
        return switch (result) {
            case Protocol.SKILL_RESULT_ALREADY_UNLOCKED  -> "Already allocated.";
            case Protocol.SKILL_RESULT_PREREQ_MISSING    -> "Allocate an adjacent node first.";
            case Protocol.SKILL_RESULT_NOT_ENOUGH_POINTS -> "Not enough skill points.";
            case Protocol.SKILL_RESULT_NOT_UNLOCKED      -> "That node isn't allocated.";
            case Protocol.SKILL_RESULT_HAS_DEPENDENTS    -> "Refund leaf nodes first.";
            case Protocol.SKILL_RESULT_NOT_ENOUGH_MONEY  -> "Not enough money to respec.";
            case Protocol.SKILL_RESULT_NOTHING_TO_RESPEC -> "Nothing to respec.";
            case Protocol.SKILL_RESULT_ECONOMY_ERROR     -> "Economy error — try again.";
            case Protocol.SKILL_RESULT_INVALID           -> "That can't be done.";
            default                                       -> "Unknown error.";
        };
    }

    // ── C2S helpers, delegated to NetworkHandler ────────────────────────────

    public static void requestOpen() {
        NetworkHandler.sendSkillTreeOpenRequest();
    }

    public static void requestAllocate(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) return;
        NetworkHandler.sendSkillTreeAllocate(nodeId);
    }

    public static void requestRefund(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) return;
        NetworkHandler.sendSkillTreeRefund(nodeId);
    }

    public static void requestRespec() {
        NetworkHandler.sendSkillTreeRespec();
    }

    public static boolean isUnlocked(String nodeId) {
        SkillTreeStatePayload s = state;
        return s != null && s.unlocked.contains(nodeId);
    }

    /**
     * True iff {@code nodeId} has at least one unlocked neighbour (or is
     * itself the gate). Walks the hand-authored adjacency (NOT
     * {@code layout.edges}, which mixes in {@code autoConnectAdjacent}
     * noise the GUI doesn't render) so the "ready to allocate" halo
     * matches what the player can actually see connected on screen.
     *
     * <p>Server may still accept allocations via the broader auto-adj set
     * — that's fine, this is just for the visual allocatable indicator.
     */
    public static boolean isAllocatable(String nodeId) {
        SkillTreeOpenPayload lay = layout;
        SkillTreeStatePayload st = state;
        if (lay == null || st == null) return false;
        if (st.unlocked.contains(nodeId)) return false;
        SkillTreeOpenPayload.Node n = lay.nodeById(nodeId);
        if (n == null) return false;
        if (n.autoUnlocked) return true; // gate is always allocatable
        Integer idx = lay.indexById.get(nodeId);
        if (idx == null) return false;
        for (int[] e : handAuthoredEdges(lay)) {
            int other = -1;
            if (e[0] == idx) other = e[1];
            else if (e[1] == idx) other = e[0];
            if (other < 0) continue;
            SkillTreeOpenPayload.Node neighbour = lay.nodes.get(other);
            if (neighbour.autoUnlocked) return true;
            if (st.unlocked.contains(neighbour.id)) return true;
        }
        return false;
    }

    /**
     * Hand-authored adjacency derived from the layout's id conventions.
     * The server's {@code layout.edges} list mixes the explicit intentional
     * edges (gate↔hw1, hw chain, notable↔smalls, entry-small↔trunk, cap
     * ↔notable, bridge_1↔bridge_2) with {@code autoConnectAdjacent} noise
     * (every grid-adjacent cell connects, regardless of region) — the
     * noise reads as a dense spider web inside each cluster and between
     * cluster pairs from neighbouring regions. PoE doesn't do that; it
     * uses explicit hand-authored adjacency only.
     *
     * <p>We rebuild the explicit edges here from the id conventions and
     * cache by layout reference (immutable after {@link #onOpen}) so it
     * runs once per layout, not per frame.
     *
     * <p>If the layout is null this returns an empty list — callers can
     * safely iterate it.
     */
    public static java.util.List<int[]> handAuthoredEdges(SkillTreeOpenPayload lay) {
        if (lay == null) return java.util.Collections.emptyList();
        java.util.List<int[]> cached = cachedHandAuthoredEdges;
        if (cached != null && cachedEdgesForLayout == lay) return cached;
        cached = buildHandAuthoredEdges(lay);
        cachedHandAuthoredEdges = cached;
        cachedEdgesForLayout = lay;
        return cached;
    }

    private static java.util.List<int[]> buildHandAuthoredEdges(SkillTreeOpenPayload lay) {
        java.util.Map<String, Integer> idx = lay.indexById;
        java.util.List<int[]> out = new java.util.ArrayList<>(220);
        // Cluster table — {clusterNumber, armDepth, pSign}. pSign drives
        // which small is the highway entry (s3 / s2 / s1 for perp+ /
        // perp- / on-axis respectively, matching SkillTreeLayout.addCluster).
        int[][] CLUSTERS = {
                { 1, 2,  +1 }, { 2, 2,  -1 },
                { 3, 4,  +1 }, { 4, 4,  -1 },
                { 5, 9,   0 },
                { 6, 6,  +1 }, { 7, 6,  -1 },
        };
        String[] REGIONS = { "north", "east", "west", "south" };
        for (String region : REGIONS) {
            // gate → hw1
            addEdge(out, idx, "gate", region + "_hw1");
            // highway chain hw1 ↔ hw2 ↔ … ↔ hw8
            for (int d = 1; d < 8; d++) {
                addEdge(out, idx, region + "_hw" + d, region + "_hw" + (d + 1));
            }
            // Per-cluster: PoE-style wheel + tail topology.
            //   4 smalls form a diamond around the cluster centre:
            //     entry-small (toward arm) — sideA — outward-small (far)
            //                              ╲   ╱
            //                              sideB
            //   notable hangs off the outward-small as a tail at the far end.
            //   entry-small ↔ trunk attaches the cluster to the highway.
            //   Caps (cluster has one) extend further past the notable.
            //
            //   Slot assignment from server's s0..s3 offsets (forward / back / perp+ / perp-):
            //     p>0  → entry=s3 (perp-)  outward=s2 (perp+)  sides={s0,s1}
            //     p<0  → entry=s2 (perp+)  outward=s3 (perp-)  sides={s0,s1}
            //     p==0 → entry=s1 (back)   outward=s0 (fwd)    sides={s2,s3}
            for (int[] cluster : CLUSTERS) {
                int n = cluster[0];
                int a = cluster[1];
                int pSign = cluster[2];
                String cPrefix = region + "_c" + n;
                String notableId = cPrefix + "_notable";
                if (idx.get(notableId) == null) continue;
                int entryIdx, outwardIdx, sideAIdx, sideBIdx;
                if (pSign > 0)       { entryIdx = 3; outwardIdx = 2; sideAIdx = 0; sideBIdx = 1; }
                else if (pSign < 0)  { entryIdx = 2; outwardIdx = 3; sideAIdx = 0; sideBIdx = 1; }
                else                 { entryIdx = 1; outwardIdx = 0; sideAIdx = 2; sideBIdx = 3; }
                String entrySmall   = cPrefix + "_s" + entryIdx;
                String outwardSmall = cPrefix + "_s" + outwardIdx;
                String sideA        = cPrefix + "_s" + sideAIdx;
                String sideB        = cPrefix + "_s" + sideBIdx;
                // Diamond ring (4 edges)
                addEdge(out, idx, entrySmall, sideA);
                addEdge(out, idx, entrySmall, sideB);
                addEdge(out, idx, sideA,      outwardSmall);
                addEdge(out, idx, sideB,      outwardSmall);
                // Tail — notable hangs off the outward small ONLY. Nothing
                // else inside the wheel connects to the notable.
                addEdge(out, idx, outwardSmall, notableId);
                // Entry from highway → entry-small
                String trunkId = region + "_hw" + Math.min(8, a);
                addEdge(out, idx, entrySmall, trunkId);
            }
            // Branch caps → cluster notables (caps continue past the notable;
            // they're tails BEYOND the cluster wheel, not part of it).
            addEdge(out, idx, region + "_capA",  region + "_c3_notable");
            addEdge(out, idx, region + "_capB",  region + "_c4_notable");
            addEdge(out, idx, region + "_capC",  region + "_c7_notable");
            addEdge(out, idx, region + "_grand", region + "_c5_notable");
        }
        // Bridge pair edge — server only explicitly drew bridge_1↔bridge_2.
        String[][] BRIDGE_PAIRS = {
                { "north_bridge_east_1",  "north_bridge_east_2"  },
                { "east_bridge_south_1",  "east_bridge_south_2"  },
                { "south_bridge_west_1",  "south_bridge_west_2"  },
                { "west_bridge_north_1",  "west_bridge_north_2"  },
        };
        for (String[] pair : BRIDGE_PAIRS) {
            addEdge(out, idx, pair[0], pair[1]);
        }
        // Bridge endpoints → cluster side-small facing the adjacent region.
        // Each corner (e.g. north→east) hosts two clusters whose side-smalls
        // face each other across the corner; the bridge sits in between.
        //
        //   For each bridge:
        //     bridge_1 attaches to the cluster whose arm runs along ±y
        //     bridge_2 attaches to the cluster whose arm runs along ±x
        //   The exact side-small (sideA = s0 vs sideB = s1) depends on
        //   whether the cluster sits perp+ or perp- of its arm:
        //     perp+ cluster (c1) → sideA (s0) is the corner-facing side
        //     perp- cluster (c2) → sideB (s1) is the corner-facing side
        //   Derivation: for a p>0 cluster at fan +φ from arm, perp_CCW
        //   (= sideA) is at angle (arm+90°+φ) which is the "leading" side
        //   into the next CCW region. Mirror for p<0.
        String[][] BRIDGE_TO_SIDE = {
                // north→east corner (north_c1 perp+ ↔ east_c2 perp-)
                { "north_bridge_east_1", "north_c1_s0" },   // north_c1 sideA → east
                { "north_bridge_east_2", "east_c2_s1"  },   // east_c2  sideB → north
                // east→south corner (east_c1 perp+ ↔ south_c2 perp-)
                { "east_bridge_south_1", "south_c2_s1" },   // south_c2 sideB → east (bridge_1 on south side)
                { "east_bridge_south_2", "east_c1_s0"  },   // east_c1  sideA → south
                // south→west corner (south_c1 perp+ ↔ west_c2 perp-)
                { "south_bridge_west_1", "south_c1_s0" },   // south_c1 sideA → west
                { "south_bridge_west_2", "west_c2_s1"  },   // west_c2  sideB → south
                // west→north corner (west_c1 perp+ ↔ north_c2 perp-)
                { "west_bridge_north_1", "north_c2_s1" },   // north_c2 sideB → west (bridge_1 on north side)
                { "west_bridge_north_2", "west_c1_s0"  },   // west_c1  sideA → north
        };
        for (String[] pair : BRIDGE_TO_SIDE) {
            addEdge(out, idx, pair[0], pair[1]);
        }
        return out;
    }

    private static void addEdge(java.util.List<int[]> out,
                                 java.util.Map<String, Integer> idx,
                                 String a, String b) {
        Integer ai = idx.get(a);
        Integer bi = idx.get(b);
        if (ai == null || bi == null) return;
        out.add(new int[] { ai, bi });
    }

    /** Clear cached layout/state on disconnect so the next server doesn't see stale data. */
    public static void reset() {
        layout = null;
        state = null;
        cachedHandAuthoredEdges = null;
        cachedEdgesForLayout = null;
        recentlyUnlockedAtMs.clear();
        recentlyRefundedAtMs.clear();
        version++;
        PrisonsMod.LOGGER.debug("SkillTreeClient: reset");
    }
}
