package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.BitSet;

/**
 * The viewer's Hunter tree state ({@link Protocol#PKT_HUNTER_TREE_STATE}). {@code taken} holds
 * node indices into the last {@link HunterTreeLayoutPayload}; the start node is always taken.
 */
public record HunterTreeStatePayload(boolean open, boolean readOnly, String owner, int points, int level,
                                     String xpLine, int activePreset, int presetsUnlocked, int[] presetSpent,
                                     String presetHint, long respecCost, long refundCost, BitSet taken,
                                     float[] statValue, float[] statCap) {

    public static HunterTreeStatePayload decode(PacketByteBuf buf) {
        int flags = buf.readUnsignedByte();
        String owner = HunterTreeLayoutPayload.str(buf);
        int points = buf.readVarInt();
        int level = buf.readVarInt();
        String xp = HunterTreeLayoutPayload.str(buf);
        int active = buf.readVarInt();
        int unlocked = buf.readVarInt();
        int presets = HunterTreeLayoutPayload.bounded(buf.readVarInt(), 16);
        int[] spent = new int[presets];
        for (int i = 0; i < presets; i++) spent[i] = buf.readVarInt();
        String hint = HunterTreeLayoutPayload.str(buf);
        long respec = buf.readLong();
        long refund = buf.readLong();
        int n = HunterTreeLayoutPayload.bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_NODES);
        BitSet taken = new BitSet();
        for (int i = 0; i < n; i++) {
            taken.set(HunterTreeLayoutPayload.bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_NODES - 1));
        }
        int s = HunterTreeLayoutPayload.bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_NAMES);
        float[] value = new float[s], cap = new float[s];
        for (int i = 0; i < s; i++) {
            value[i] = buf.readFloat();
            cap[i] = buf.readFloat();
        }
        return new HunterTreeStatePayload((flags & 1) != 0, (flags & 2) != 0, owner, points, level, xp,
                active, unlocked, spent, hint, respec, refund, taken, value, cap);
    }
}
