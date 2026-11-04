package com.aleks.ancientsmod.client.mining;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.screen.MiningTreeScreen;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.HunterTreeLayoutPayload;
import com.aleks.ancientsmod.net.payload.HunterTreeStatePayload;
import net.minecraft.client.MinecraftClient;

/**
 * The mining tree view the server last sent: its layout (sent on every {@code /miningtree}) and the
 * viewer's state (re-sent after every change while the screen is open). A state packet with
 * the open flag opens {@link MiningTreeScreen}. Client thread only.
 */
public final class MiningTreeClient {

    private static HunterTreeLayoutPayload layout;
    private static HunterTreeStatePayload state;
    /** Auto-spend preset from the last state: -1 off, 0..3 Energy/XP/Money/Mixed, or {@link #AUTO_UNKNOWN}. */
    public static final int AUTO_UNKNOWN = -2;
    private static int autoPreset = AUTO_UNKNOWN;

    private static int asmVersion = -1;
    private static int asmCount;
    private static int asmReceived;
    private static int asmBytes;
    private static byte[][] asmChunks;

    private MiningTreeClient() {}

    public static HunterTreeLayoutPayload layout() { return layout; }
    public static HunterTreeStatePayload state() { return state; }
    public static int autoPreset() { return autoPreset; }

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
            layout = null;
            layout = HunterTreeLayoutPayload.decode(full);
        } catch (Throwable t) {
            AncientsMod.LOGGER.debug("[MiningTree] layout decode failed", t);
        }
    }

    public static void onState(HunterTreeStatePayload next, int auto) {
        state = next;
        autoPreset = auto;
        if (!next.open() || layout == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        // Mod tree screens off (Advanced settings): tell the server this view closed and ask for
        // the vanilla dialog a player without the mod gets. A read-only view of someone else's
        // tree has no command to re-ask with, so it still opens here.
        if (!com.aleks.ancientsmod.client.FeatureToggles.isTreeScreensEnabled() && !next.readOnly()) {
            com.aleks.ancientsmod.net.NetworkHandler.sendMiningTreeAction(4, 0);
            String cmd = next.activePreset() > 0 ? "miningtree dialog ascendancy" : "miningtree dialog";
            mc.execute(() -> { if (mc.player != null) mc.player.networkHandler.sendChatCommand(cmd); });
            return;
        }
        mc.execute(() -> {
            if (mc.currentScreen instanceof MiningTreeScreen screen) screen.resetView();
            else mc.setScreen(new MiningTreeScreen(mc.currentScreen));
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
