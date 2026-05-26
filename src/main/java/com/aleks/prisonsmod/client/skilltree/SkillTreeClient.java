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

    private SkillTreeClient() {}

    public static SkillTreeOpenPayload layout() { return layout; }
    public static SkillTreeStatePayload state() { return state; }
    public static long version() { return version; }

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
     * Server sent fresh per-player state — update + bump version so the
     * screen re-renders.
     */
    public static void onState(SkillTreeStatePayload payload) {
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
     * itself the gate). Mirrors {@code SkillTreeManager.tryUnlock} adjacency
     * check so the UI can correctly distinguish allocatable vs locked
     * before the player clicks.
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
        for (int[] e : lay.edges) {
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

    /** Clear cached layout/state on disconnect so the next server doesn't see stale data. */
    public static void reset() {
        layout = null;
        state = null;
        version++;
        PrisonsMod.LOGGER.debug("SkillTreeClient: reset");
    }
}
