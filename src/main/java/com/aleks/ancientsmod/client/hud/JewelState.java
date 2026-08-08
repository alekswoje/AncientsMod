package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.JewelSlotsPayload;

import java.util.List;

/** Client-side jewel socket state, replaced wholesale by each server push. */
public final class JewelState {

    private static volatile List<JewelSlotsPayload.Slot> slots = List.of();

    private JewelState() {}

    public static void update(JewelSlotsPayload payload) {
        slots = payload.slots();
    }

    public static List<JewelSlotsPayload.Slot> slots() {
        return slots;
    }

    /** True until the server has pushed anything (vanilla-ish servers, pre-join). */
    public static boolean isEmpty() {
        return slots.isEmpty();
    }

    public static void clear() {
        slots = List.of();
    }
}
