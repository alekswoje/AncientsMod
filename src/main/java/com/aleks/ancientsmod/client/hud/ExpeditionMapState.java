package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.ExpeditionMapPayload;
import com.aleks.ancientsmod.net.payload.ExpeditionMarkersPayload;

/**
 * The Expedition minimap's data: the map picture (pixels) plus the live markers. Only the
 * server decides when it exists (it sends the picture on entering a run world and a clear on
 * leaving); a join or a disconnect drops it too, and a server that goes quiet for {@link #STALE_NS}
 * hides it.
 * Client thread only.
 */
public final class ExpeditionMapState {
    /** Markers arrive every few seconds at worst; a longer silence means we are no longer in a run. */
    private static final long STALE_NS = 12_000_000_000L;

    private static ExpeditionMapPayload layout;
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
        layout = next;
        markersNs = System.nanoTime();
        ExpeditionMinimapHud.INSTANCE.invalidate();
    }

    public static void onMarkers(ExpeditionMarkersPayload p) {
        markers = p;
        markersNs = System.nanoTime();
    }

    /** Drops the picture, the markers, any half-received picture and the HUD's texture. */
    public static void clear() {
        layout = null;
        markers = null;
        resetAssembly();
        ExpeditionMinimapHud.INSTANCE.release();
    }

    /** The picture, or null outside an Expedition. A map the server stopped talking about is hidden
     *  (kept, so it comes back if the markers resume after a lag spike; a join or clear frees it). */
    public static ExpeditionMapPayload layout() {
        return layout != null && System.nanoTime() - markersNs <= STALE_NS ? layout : null;
    }

    public static ExpeditionMarkersPayload markers() { return markers; }
}
