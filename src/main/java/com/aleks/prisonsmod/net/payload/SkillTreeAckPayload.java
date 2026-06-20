package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded {@code PKT_SKILLTREE_ACK}: result of a mod-initiated allocate /
 * refund / respec attempt. Used to show failure toasts; success is implied
 * by the matching STATE push that follows.
 */
public final class SkillTreeAckPayload {

    public final byte action;
    public final String nodeId;
    public final byte result;

    private SkillTreeAckPayload(byte action, String nodeId, byte result) {
        this.action = action;
        this.nodeId = nodeId;
        this.result = result;
    }

    public boolean isSuccess() { return result == Protocol.SKILL_RESULT_SUCCESS; }

    public static SkillTreeAckPayload decode(PacketByteBuf buf) {
        byte action = buf.readByte();
        String nodeId = buf.readString(Protocol.SKILLTREE_MAX_NODE_ID_CHARS);
        byte result = buf.readByte();
        return new SkillTreeAckPayload(action, nodeId, result);
    }
}
