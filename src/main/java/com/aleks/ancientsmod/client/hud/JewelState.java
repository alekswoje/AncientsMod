package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.JewelSlotsPayload;

import java.util.List;

/** Client-side jewel socket state, replaced wholesale by each server push. */
public final class JewelState {

    private static volatile List<JewelSlotsPayload.Slot> slots = List.of();

    private JewelState() {}

    public static void update(JewelSlotsPayload payload) {
        slots = payload.slots();
        // A server only pushes 4 sockets once it knows about the fourth one,
        // which landed alongside the ANCT-525 rarity-word rename — so the count
        // doubles as a signal for which vocabulary generation this server
        // speaks. See ServerVocabulary for how that's used and its failure mode.
        com.aleks.ancientsmod.client.loot.ServerVocabulary.noteJewelSlotCount(slots.size());
        // Pushes are rare (join, handshake, socket changes, prestige), so this
        // is a couple of lines a session and makes "is the HUD empty or is the
        // packet missing?" answerable straight from the client log.
        com.aleks.ancientsmod.AncientsMod.LOGGER.info("AncientsMod: jewel slots updated ({} slots)",
                slots.size());
    }

    public static List<JewelSlotsPayload.Slot> slots() {
        return slots;
    }

    /**
     * Sockets the server's last push carried — and therefore the number of
     * name/model pairs it is writing per page in the loadout packet, which has
     * no width field of its own.
     *
     * <p>Before anything has arrived this answers {@link
     * Protocol#MAX_JEWEL_SLOTS}: a server that knows this client's protocol
     * minor writes four, and an older one always writes three, which
     * {@code JewelLoadoutsPayload} detects for itself rather than depending on
     * the two packets arriving in order.
     */
    public static int socketWireWidth() {
        List<JewelSlotsPayload.Slot> current = slots;
        return current.isEmpty() ? Protocol.MAX_JEWEL_SLOTS : current.size();
    }

    /** True until the server has pushed anything (vanilla-ish servers, pre-join). */
    public static boolean isEmpty() {
        return slots.isEmpty();
    }

    public static void clear() {
        slots = List.of();
    }
}
