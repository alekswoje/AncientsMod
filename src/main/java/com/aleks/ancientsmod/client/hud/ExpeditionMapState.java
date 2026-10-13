package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.ExpeditionMapPayload;
import com.aleks.ancientsmod.net.payload.ExpeditionMarkersPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * The Expedition minimap's data: the map picture as a GPU texture plus the live markers. Only the
 * server decides when it exists (it sends the picture on entering a run world and a clear on
 * leaving); a join or a disconnect drops it too, and a server that goes quiet for {@link #STALE_NS}
 * hides it.
 * Client thread only.
 */
public final class ExpeditionMapState {
    /** Markers arrive every few seconds at worst; a longer silence means we are no longer in a run. */
    private static final long STALE_NS = 12_000_000_000L;
    public static final Identifier TEXTURE = Identifier.of("ancientsmod", "dynamic/expedition_map");

    private static ExpeditionMapPayload layout;
    private static boolean textureLive;
    private static ExpeditionMarkersPayload markers;
    private static long markersNs;

    // chunk reassembly, same scheme as the PV bundle
    private static int asmVersion = -1;
    private static int asmCount;
    private static int asmReceived;
    private static int asmBytes;
    private static byte[][] asmChunks;

    private ExpeditionMapState() {}

    public static void onChunk(int version, int index, int count, byte[] chunk) {
        if (count < 1 || count > Protocol.EXPEDITION_MAP_MAX_CHUNKS || index < 0 || index >= count || chunk == null) return;
        if (version != asmVersion || count != asmCount) {
            asmVersion = version;
            asmCount = count;
            asmReceived = 0;
            asmBytes = 0;
            asmChunks = new byte[count][];
        }
        if (asmChunks[index] != null) return;
        asmChunks[index] = chunk;
        asmReceived++;
        asmBytes += chunk.length;
        if (asmBytes > Protocol.MAX_EXPEDITION_MAP_TOTAL_BYTES) {
            resetAssembly();
            return;
        }
        if (asmReceived < asmCount) return;
        byte[] full = new byte[asmBytes];
        int off = 0;
        for (byte[] c : asmChunks) {
            System.arraycopy(c, 0, full, off, c.length);
            off += c.length;
        }
        resetAssembly();
        try {
            setLayout(ExpeditionMapPayload.decode(full));
        } catch (Throwable t) {
            AncientsMod.LOGGER.debug("[ExpeditionMap] picture decode failed", t);
        }
    }

    private static void resetAssembly() {
        asmVersion = -1;
        asmCount = 0;
        asmReceived = 0;
        asmBytes = 0;
        asmChunks = null;
    }

    private static void setLayout(ExpeditionMapPayload next) {
        releaseTexture();
        layout = next;
        markersNs = System.nanoTime();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, next.width(), next.height(), false);
        try {
            int[] argb = next.argb();
            for (int v = 0; v < next.height(); v++)
                for (int u = 0; u < next.width(); u++) image.setColorArgb(u, v, argb[v * next.width() + u]);
            mc.getTextureManager().registerTexture(TEXTURE, new NativeImageBackedTexture(() -> "ancientsmod expedition map", image));
            textureLive = true;
        } catch (Throwable t) {
            image.close();
            layout = null;
            AncientsMod.LOGGER.warn("[ExpeditionMap] texture upload failed: {}", t.toString());
        }
    }

    public static void onMarkers(ExpeditionMarkersPayload p) {
        markers = p;
        markersNs = System.nanoTime();
    }

    /** Drops the picture, the markers and any half-received picture. */
    public static void clear() {
        releaseTexture();
        layout = null;
        markers = null;
        resetAssembly();
    }

    private static void releaseTexture() {
        if (!textureLive) return;
        textureLive = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.getTextureManager().destroyTexture(TEXTURE);
    }

    /** The picture, or null outside an Expedition. A map the server stopped talking about is hidden
     *  (kept, so it comes back if the markers resume after a lag spike; a join or clear frees it). */
    public static ExpeditionMapPayload layout() {
        return textureLive && System.nanoTime() - markersNs <= STALE_NS ? layout : null;
    }

    public static ExpeditionMarkersPayload markers() { return markers; }
}
