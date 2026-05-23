package com.aleks.prisonsmod.client;

/**
 * Client-side cache of whether the local player is currently in a duel
 * fight. Refreshed by {@code PKT_DUEL_STATE} packets from the server.
 *
 * <p>Used to suppress peaceful-PvP phase-through during duels — the user
 * doesn't want their gang teammates to vanish if one of them is the duel
 * opponent.
 */
public final class DuelState {

    private static volatile boolean inDuel = false;

    public static void set(boolean value) {
        inDuel = value;
    }

    public static boolean isInDuel() {
        return inDuel;
    }

    public static void reset() {
        inDuel = false;
    }

    private DuelState() {}
}
