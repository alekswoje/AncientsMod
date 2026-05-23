package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.net.payload.GangRosterPayload;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side cache of the local player's active gang members. Refreshed
 * by {@code PKT_GANG_ROSTER} packets — the server periodically broadcasts
 * the current roster and on membership change.
 *
 * <p>UUIDs in this set are checked against rendered entities to decide
 * peaceful-PvP fade + raycast-skip behavior. Empty set = not in a gang.
 */
public final class GangRoster {

    private static volatile Set<UUID> members = Set.of();

    public static void update(GangRosterPayload payload) {
        if (payload == null) {
            members = Set.of();
            return;
        }
        members = Collections.unmodifiableSet(new HashSet<>(payload.uuids()));
    }

    public static boolean contains(UUID uuid) {
        return uuid != null && members.contains(uuid);
    }

    public static boolean isEmpty() {
        return members.isEmpty();
    }

    public static void reset() {
        members = Set.of();
    }

    private GangRoster() {}
}
