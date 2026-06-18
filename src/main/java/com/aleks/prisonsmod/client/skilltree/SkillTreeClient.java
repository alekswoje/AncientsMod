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

    // ── Chunk reassembly for the OPEN layout (single in-flight; in-order TCP) ──
    private static int asmVersion = -1;
    private static int asmCount = 0;
    private static int asmReceived = 0;
    private static int asmBytes = 0;
    private static byte[][] asmChunks = null;

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
     * One chunk of a chunked OPEN payload (the 1000+ node mega-tree exceeds the
     * single-message ceiling). Reassembles by version; once every chunk has
     * arrived, decodes the concatenated body exactly like a single OPEN packet
     * (which carries everything after the type byte) and opens the screen.
     * Mirrors {@code LootClient.onSnapshotChunk}.
     */
    public static void onOpenChunk(int version, int index, int count, byte[] chunk) {
        if (count < 1 || count > Protocol.SKILLTREE_MAX_CHUNKS) return;
        if (index < 0 || index >= count) return;
        if (chunk == null) return;
        if (version != asmVersion || count != asmCount) {
            asmVersion = version;
            asmCount = count;
            asmReceived = 0;
            asmBytes = 0;
            asmChunks = new byte[count][];
        }
        if (asmChunks[index] != null) return; // duplicate
        asmChunks[index] = chunk;
        asmReceived++;
        asmBytes += chunk.length;
        if (asmBytes > Protocol.MAX_SKILLTREE_TOTAL_BYTES) { resetAssembly(); return; }
        if (asmReceived < asmCount) return;

        byte[] full = new byte[asmBytes];
        int off = 0;
        for (byte[] c : asmChunks) { System.arraycopy(c, 0, full, off, c.length); off += c.length; }
        resetAssembly();
        try {
            net.minecraft.network.PacketByteBuf buf =
                    new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(full));
            onOpen(SkillTreeOpenPayload.decode(buf));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("SkillTree: chunked OPEN decode failed", t);
        }
    }

    private static void resetAssembly() {
        asmVersion = -1;
        asmCount = 0;
        asmReceived = 0;
        asmBytes = 0;
        asmChunks = null;
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
     * Adjacency the screen renders. The mega-tree generator authors a clean
     * graph server-side and sends it verbatim in {@code layout.edges} (no
     * client-side topology reconstruction, no auto-adjacency noise), so we
     * render those edges directly. Kept as a method (rather than inlining
     * {@code lay.edges}) so the path BFS + allocatable check share one source.
     *
     * <p>If the layout is null this returns an empty list — callers can
     * safely iterate it.
     */
    public static java.util.List<int[]> handAuthoredEdges(SkillTreeOpenPayload lay) {
        return lay == null ? java.util.Collections.emptyList() : lay.edges;
    }

    /** Clear cached layout/state on disconnect so the next server doesn't see stale data. */
    public static void reset() {
        layout = null;
        state = null;
        resetAssembly();
        recentlyUnlockedAtMs.clear();
        recentlyRefundedAtMs.clear();
        version++;
        PrisonsMod.LOGGER.debug("SkillTreeClient: reset");
    }
}
