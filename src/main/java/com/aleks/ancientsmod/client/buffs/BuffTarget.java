package com.aleks.ancientsmod.client.buffs;

import com.aleks.ancientsmod.net.payload.BuffSnapshotPayload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What a {@link CustomModifier} applies to in the {@code /pickbuffs} sandbox —
 * either a single channel (e.g. Mining XP) or a group of channels (All Mining,
 * All Combat, Everything). Path-of-Building lets a custom mod hit a whole stat
 * category; this is the equivalent.
 */
public final class BuffTarget {

    public enum Group { EVERYTHING, MINING, COMBAT }

    private final boolean group;
    private final Group groupId;   // valid when group == true
    private final byte channelId;  // valid when group == false

    private BuffTarget(boolean group, Group groupId, byte channelId) {
        this.group = group;
        this.groupId = groupId;
        this.channelId = channelId;
    }

    public static BuffTarget everything() { return new BuffTarget(true, Group.EVERYTHING, (byte) 0); }
    public static BuffTarget group(Group g) { return new BuffTarget(true, g, (byte) 0); }
    public static BuffTarget channel(byte id) { return new BuffTarget(false, null, id); }

    public boolean isGroup() { return group; }
    public Group groupId() { return groupId; }
    public byte channelId() { return channelId; }

    /** True if this target should fold into the given channel's sandbox math. */
    public boolean matches(byte chId) {
        if (!group) return chId == channelId;
        return switch (groupId) {
            case EVERYTHING -> true;
            case MINING -> isMining(chId);
            case COMBAT -> isCombat(chId);
        };
    }

    public String displayName() {
        if (!group) return BuffSnapshotPayload.channelDisplayName(channelId);
        return switch (groupId) {
            case EVERYTHING -> "Everything";
            case MINING -> "All Mining";
            case COMBAT -> "All Combat";
        };
    }

    /** Compact form for {@link CustomModifier#detailLine()} and row labels. */
    public String shortName() {
        if (!group) return BuffSnapshotPayload.channelDisplayName(channelId);
        return switch (groupId) {
            case EVERYTHING -> "All";
            case MINING -> "Mining";
            case COMBAT -> "Combat";
        };
    }

    public String serialize() {
        if (group) {
            return switch (groupId) {
                case EVERYTHING -> "all";
                case MINING -> "g:MINING";
                case COMBAT -> "g:COMBAT";
            };
        }
        return "c:" + channelId;
    }

    public static BuffTarget parse(String s) {
        if (s == null || s.isEmpty() || s.equals("all")) return everything();
        if (s.startsWith("g:")) {
            try { return group(Group.valueOf(s.substring(2))); }
            catch (IllegalArgumentException e) { return everything(); }
        }
        if (s.startsWith("c:")) {
            try { return channel(Byte.parseByte(s.substring(2))); }
            catch (NumberFormatException e) { return everything(); }
        }
        return everything();
    }

    /** Two targets are equal if they resolve to the same scope. */
    public boolean sameAs(BuffTarget o) {
        if (o == null) return false;
        if (group != o.group) return false;
        return group ? groupId == o.groupId : channelId == o.channelId;
    }

    /**
     * The options offered by the builder's target cycle-button: the three
     * groups first, then every channel present in the current snapshot (or all
     * known channels when no snapshot is loaded yet).
     */
    public static List<BuffTarget> selectable(Collection<Byte> presentChannels) {
        List<BuffTarget> out = new ArrayList<>();
        out.add(everything());
        out.add(group(Group.MINING));
        out.add(group(Group.COMBAT));
        if (presentChannels != null && !presentChannels.isEmpty()) {
            for (byte id : presentChannels) out.add(channel(id));
        } else {
            for (byte id = 1; id <= 10; id++) out.add(channel(id));
        }
        return out;
    }

    private static boolean isMining(byte id) {
        return id >= BuffSnapshotPayload.CH_MINING_XP && id <= BuffSnapshotPayload.CH_MINING_METEOR;
    }

    private static boolean isCombat(byte id) {
        // Combat group spans Damage Dealt/Taken, Boss Dealt/Taken (7–10) and the
        // crit channels (11–12) — every combat-side channel id.
        return id >= BuffSnapshotPayload.CH_COMBAT_OUT && id <= BuffSnapshotPayload.CH_CRIT_DAMAGE;
    }
}
