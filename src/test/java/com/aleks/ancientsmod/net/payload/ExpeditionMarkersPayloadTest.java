package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpeditionMarkersPayloadTest {
    // Mirrors the plugin's PrisonsModChannel.sendExpeditionMarkers body (after the packet id).
    private static PacketByteBuf markers(boolean features) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeByte(1); buf.writeInt(40); buf.writeInt(-80);
        buf.writeVarInt(1);
        buf.writeInt(10); buf.writeInt(20); buf.writeVarInt(5); buf.writeByte(Protocol.EXPEDITION_PACK_RARE);
        if (features) {
            buf.writeVarInt(2);
            buf.writeInt(-3); buf.writeInt(7); buf.writeByte(Protocol.EXPEDITION_POI_ALTAR); buf.writeByte(Protocol.EXPEDITION_POI_STATE_READY);
            buf.writeInt(100); buf.writeInt(-50); buf.writeByte(Protocol.EXPEDITION_POI_BREACH); buf.writeByte(Protocol.EXPEDITION_POI_STATE_DONE);
        }
        return buf;
    }

    @Test void olderServerSendsNoFeatures() {
        PacketByteBuf buf = markers(false);
        try {
            var p = ExpeditionMarkersPayload.decode(buf);
            assertEquals(1, p.packs().size());
            assertTrue(p.packs().get(0).rare());
            assertTrue(p.pois().isEmpty());
        } finally { buf.release(); }
    }

    @Test void readsFeaturesAfterThePacks() {
        PacketByteBuf buf = markers(true);
        try {
            var p = ExpeditionMarkersPayload.decode(buf);
            assertEquals(2, p.pois().size());
            assertEquals(new ExpeditionMarkersPayload.Poi(-3, 7, Protocol.EXPEDITION_POI_ALTAR, Protocol.EXPEDITION_POI_STATE_READY), p.pois().get(0));
            assertEquals(Protocol.EXPEDITION_POI_BREACH, p.pois().get(1).kind());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    @Test void rejectsTooManyFeatures() {
        PacketByteBuf buf = markers(false);
        try {
            buf.writeVarInt(Protocol.MAX_EXPEDITION_POIS + 1);
            assertThrows(IllegalArgumentException.class, () -> ExpeditionMarkersPayload.decode(buf));
        } finally { buf.release(); }
    }
}
