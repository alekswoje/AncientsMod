package com.aleks.ancientsmod.net.payload;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class ExpeditionMapPayloadTest {
    // Mirrors the plugin's ExpeditionMinimap.encode wire body.
    private static byte[] body(String id, int w, int h, int[] palette, byte[] idx) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeString(id); buf.writeInt(912); buf.writeInt(-112); buf.writeByte(1);
            buf.writeShort(w); buf.writeShort(h);
            buf.writeVarInt(palette.length);
            for (int c : palette) buf.writeInt(c);
            Deflater d = new Deflater();
            d.setInput(idx); d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            while (!d.finished()) out.write(tmp, 0, d.deflate(tmp));
            d.end();
            byte[] packed = out.toByteArray();
            buf.writeVarInt(packed.length); buf.writeBytes(packed);
            byte[] all = new byte[buf.readableBytes()];
            buf.readBytes(all);
            return all;
        } finally { buf.release(); }
    }

    @Test void decodesPaletteIndices() {
        int w = 20, h = 10;
        byte[] idx = new byte[w * h];
        for (int i = 0; i < idx.length; i++) idx[i] = (byte) (i % 3);
        var p = ExpeditionMapPayload.decode(body("drowned_abbey", w, h, new int[]{0, 0xFF112233, 0xFF445566}, idx));
        assertEquals("drowned_abbey", p.mapId());
        assertEquals(912, p.originX()); assertEquals(-112, p.originZ());
        assertEquals(w, p.width()); assertEquals(h, p.height());
        assertEquals(0, p.argb()[0]); assertEquals(0xFF112233, p.argb()[1]); assertEquals(0xFF445566, p.argb()[5]);
    }

    @Test void rejectsOversizedPictures() {
        byte[] idx = new byte[1025];
        assertThrows(IllegalArgumentException.class,
                () -> ExpeditionMapPayload.decode(body("x", 1025, 1, new int[]{0}, idx)));
    }

    @Test void rejectsIndicesOutsideThePalette() {
        byte[] idx = new byte[4];
        idx[2] = 5;
        assertThrows(IllegalArgumentException.class,
                () -> ExpeditionMapPayload.decode(body("x", 2, 2, new int[]{0, 0xFFFFFFFF}, idx)));
    }

    @Test void rejectsShortPixelData() {
        // Claims 8x8 but carries 4 pixels.
        byte[] full = body("x", 8, 8, new int[]{0}, new byte[4]);
        assertThrows(IllegalArgumentException.class, () -> ExpeditionMapPayload.decode(full));
        byte[] good = body("x", 8, 8, new int[]{0}, new byte[64]);
        assertThrows(IllegalArgumentException.class,
                () -> ExpeditionMapPayload.decode(Arrays.copyOf(good, good.length - 3)));
    }

    @Test void markersDecodeFlags() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeByte(1); buf.writeInt(10); buf.writeInt(-20); buf.writeVarInt(2);
            buf.writeInt(1); buf.writeInt(2); buf.writeVarInt(5); buf.writeByte(1);
            buf.writeInt(3); buf.writeInt(4); buf.writeVarInt(7); buf.writeByte(2);
            var m = ExpeditionMarkersPayload.decode(buf);
            assertEquals(ExpeditionMarkersPayload.BOSS_ALIVE, m.bossState());
            assertTrue(m.packs().get(0).rare()); assertFalse(m.packs().get(0).unspawned());
            assertTrue(m.packs().get(1).unspawned()); assertEquals(7, m.packs().get(1).alive());
        } finally { buf.release(); }
    }

    @Test void markersRejectTooManyPacks() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeByte(0); buf.writeInt(0); buf.writeInt(0); buf.writeVarInt(129);
            assertThrows(IllegalArgumentException.class, () -> ExpeditionMarkersPayload.decode(buf));
        } finally { buf.release(); }
    }
}
