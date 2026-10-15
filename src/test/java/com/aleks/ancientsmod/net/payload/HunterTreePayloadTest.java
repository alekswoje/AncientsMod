package com.aleks.ancientsmod.net.payload;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class HunterTreePayloadTest {

    /** hunter-tree-layout-v2.bin is the plugin's real Map 5 tree body (HunterTreeModBridgeTest writes it). */
    @Test
    void decodesThePluginsMap5Tree() throws Exception {
        byte[] body;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("hunter-tree-layout-v2.bin")) {
            assertNotNull(in);
            body = in.readAllBytes();
        }
        HunterTreeLayoutPayload l = HunterTreeLayoutPayload.decode(body);
        assertEquals(3, l.rings());
        assertEquals(517, l.nodes().size());
        assertEquals(550, l.edgeA().length);
        assertEquals(8, l.stats().size());
        assertEquals(39, l.abilities().size());
        assertEquals(12, l.labels().size());
        assertEquals("Predation", l.labels().get(0).name());
        assertEquals(1, l.nodes().stream().filter(n -> n.kind() == 0).count());
        assertEquals(10, l.nodes().stream().filter(n -> n.kind() == 4).count());
        assertTrue(l.nodes().stream().allMatch(n -> n.icon() >= 0 && n.icon() < 23));
        var start = l.nodes().stream().filter(n -> n.kind() == 0).findFirst().orElseThrow();
        assertEquals(0f, start.px(), 1e-6);
        assertEquals(0f, start.py(), 1e-6);
        // round: every other node sits on a circle well clear of the start
        assertTrue(l.nodes().stream().filter(n -> n.kind() != 0).allMatch(n -> Math.hypot(n.px(), n.py()) > 50));
    }

    /** Mirrors HunterTreeModBridge.stateBody. */
    @Test
    void decodesAState() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeByte(1 | 2);
        buf.writeString("Aleks");
        buf.writeVarInt(7);
        buf.writeVarInt(23);
        buf.writeString("1.2k / 5k XP | 24.0% to Lv. 24");
        buf.writeVarInt(1);
        buf.writeVarInt(2);
        buf.writeVarInt(3);
        buf.writeVarInt(10); buf.writeVarInt(4); buf.writeVarInt(0);
        buf.writeString("");
        buf.writeLong(82_500);
        buf.writeLong(7_500);
        buf.writeVarInt(2); buf.writeVarInt(5); buf.writeVarInt(300);
        buf.writeVarInt(1); buf.writeFloat(0.24f); buf.writeFloat(1f);
        HunterTreeStatePayload s = HunterTreeStatePayload.decode(buf);
        assertTrue(s.open());
        assertTrue(s.readOnly());
        assertEquals("Aleks", s.owner());
        assertEquals(7, s.points());
        assertEquals(1, s.activePreset());
        assertEquals(4, s.presetSpent()[1]);
        assertEquals(82_500, s.respecCost());
        assertTrue(s.taken().get(300));
        assertFalse(s.taken().get(6));
        assertEquals(0.24f, s.statValue()[0], 1e-6);
    }
}
