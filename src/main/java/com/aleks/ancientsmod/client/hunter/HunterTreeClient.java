package com.aleks.ancientsmod.client.hunter;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.screen.HunterTreeScreen;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.HunterTreeLayoutPayload;
import com.aleks.ancientsmod.net.payload.HunterTreeStatePayload;
import net.minecraft.client.MinecraftClient;

/**
 * The Hunter tree the server last sent: its layout (sent on every {@code /hunter}) and the
 * viewer's state (re-sent after every change while the screen is open). A state packet with
 * the open flag opens {@link HunterTreeScreen}. Client thread only.
 */
public final class HunterTreeClient {

    private static HunterTreeLayoutPayload layout;
    private static HunterTreeStatePayload state;

    private static int asmVersion = -1;
    private static int asmCount;
    private static int asmReceived;
    private static int asmBytes;
    private static byte[][] asmChunks;

    private HunterTreeClient() {}

    public static HunterTreeLayoutPayload layout() { return layout; }
    public static HunterTreeStatePayload state() { return state; }

    public static void onChunk(int version, int index, int count, byte[] chunk) {
        if (count < 1 || count > Protocol.HUNTER_TREE_MAX_CHUNKS || index < 0 || index >= count || chunk == null) return;
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
        if (asmBytes > Protocol.MAX_HUNTER_TREE_TOTAL_BYTES) {
            reset();
            return;
        }
        if (asmReceived < asmCount) return;
        byte[] full = new byte[asmBytes];
        int off = 0;
        for (byte[] c : asmChunks) {
            System.arraycopy(c, 0, full, off, c.length);
            off += c.length;
        }
        reset();
        try {
            layout = HunterTreeLayoutPayload.decode(full);
        } catch (Throwable t) {
            AncientsMod.LOGGER.debug("[HunterTree] layout decode failed", t);
        }
    }

    public static void onState(HunterTreeStatePayload next) {
        state = next;
        if (!next.open() || layout == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (!(mc.currentScreen instanceof HunterTreeScreen)) mc.setScreen(new HunterTreeScreen(mc.currentScreen));
        });
    }

    public static void clear() {
        layout = null;
        state = null;
        reset();
    }

    private static void reset() {
        asmVersion = -1;
        asmCount = 0;
        asmReceived = 0;
        asmBytes = 0;
        asmChunks = null;
    }
}
