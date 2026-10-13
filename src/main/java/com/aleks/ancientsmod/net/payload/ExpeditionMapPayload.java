package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A reassembled Expedition map picture (the body of {@link Protocol#PKT_EXPEDITION_MAP_CHUNK}).
 * Pixel (u, v) covers world blocks from {@code (originX + u*blocksPerPixel, originZ + v*blocksPerPixel)};
 * {@code argb} is row-major from the north-west corner, 0 = transparent.
 *
 * <pre>
 *   string  mapId           (≤ 32)
 *   int     originX, originZ
 *   ubyte   blocksPerPixel  (1..64)
 *   ushort  width, height   (1..1024)
 *   varint  paletteSize     (1..256)
 *   int[]   palette ARGB
 *   varint  deflatedLength
 *   bytes   deflate(width*height palette indices)
 * </pre>
 */
public record ExpeditionMapPayload(String mapId, int originX, int originZ, int blocksPerPixel,
                                   int width, int height, int[] argb) {

    public static ExpeditionMapPayload decode(byte[] body) {
        if (body == null || body.length == 0 || body.length > Protocol.MAX_EXPEDITION_MAP_TOTAL_BYTES)
            throw new IllegalArgumentException("bad expedition map size");
        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(body));
        try {
            String id = buf.readString(Protocol.EXPEDITION_MAP_MAX_ID_CHARS);
            int ox = buf.readInt(), oz = buf.readInt();
            int bpp = buf.readUnsignedByte();
            int w = buf.readUnsignedShort(), h = buf.readUnsignedShort();
            if (bpp < 1 || bpp > 64 || w < 1 || h < 1
                    || w > Protocol.EXPEDITION_MAP_MAX_SIDE || h > Protocol.EXPEDITION_MAP_MAX_SIDE)
                throw new IllegalArgumentException("bad expedition map dimensions");
            if (Math.abs((long) ox) > 30_000_000L || Math.abs((long) oz) > 30_000_000L)
                throw new IllegalArgumentException("bad expedition map origin");
            int size = buf.readVarInt();
            if (size < 1 || size > 256 || buf.readableBytes() < size * 4)
                throw new IllegalArgumentException("bad expedition map palette");
            int[] palette = new int[size];
            for (int i = 0; i < size; i++) palette[i] = buf.readInt();
            int packedLen = buf.readVarInt();
            if (packedLen < 0 || packedLen > buf.readableBytes())
                throw new IllegalArgumentException("bad expedition map length");
            byte[] packed = new byte[packedLen];
            buf.readBytes(packed);
            byte[] idx = inflate(packed, w * h);
            int[] argb = new int[w * h];
            for (int i = 0; i < argb.length; i++) {
                int p = idx[i] & 0xFF;
                if (p >= size) throw new IllegalArgumentException("bad expedition map index");
                argb[i] = palette[p];
            }
            return new ExpeditionMapPayload(id, ox, oz, bpp, w, h, argb);
        } finally {
            buf.release();
        }
    }

    /** Inflates exactly {@code expected} bytes; anything short, long or corrupt is rejected. */
    static byte[] inflate(byte[] packed, int expected) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(packed);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inf.finished()) {
                int got = inf.inflate(out, n, expected - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += got;
            }
            if (n != expected || !inf.finished()) throw new IllegalArgumentException("bad expedition map pixels");
            return out;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("bad expedition map pixels", e);
        } finally {
            inf.end();
        }
    }
}
