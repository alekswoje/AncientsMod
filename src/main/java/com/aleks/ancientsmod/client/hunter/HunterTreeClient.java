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

    /** Auto-spend plan from the last state: -1 off, 0..3 Damage/Defense/Speed/Mixed, or {@link #AUTO_UNKNOWN}. */
    public static final int AUTO_UNKNOWN = -2;
    private static int autoPlan = AUTO_UNKNOWN;
    /** Each preset's plan (-1 off), empty when the server sent none. */
    private static int[] presetAutos = new int[0];
    /** Each preset's player-given name, "" for the default; empty when the server sent none. */
    private static String[] presetNames = new String[0];

    private static int asmVersion = -1;
    private static int asmCount;
    private static int asmReceived;
    private static int asmBytes;
    private static byte[][] asmChunks;

    private HunterTreeClient() {}

    public static HunterTreeLayoutPayload layout() { return layout; }
    public static HunterTreeStatePayload state() { return state; }
    public static int autoPlan() { return autoPlan; }
    /** "Preset 2", or the name the player gave it. */
    public static String presetLabel(int preset) {
        String c = presetCustom(preset);
        return c != null ? c : "Preset " + (preset + 1);
    }

    public static String presetCustom(int preset) {
        String n = preset >= 0 && preset < presetNames.length ? presetNames[preset] : null;
        return n == null || n.isEmpty() ? null : n;
    }

    public static int presetAuto(int preset) { return preset >= 0 && preset < presetAutos.length ? presetAutos[preset] : -1; }

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
        onState(next, AUTO_UNKNOWN, new int[0]);
    }

    public static void onState(HunterTreeStatePayload next, int auto, int[] autos) {
        onState(next, auto, autos, new String[0]);
    }

    public static void onState(HunterTreeStatePayload next, int auto, int[] autos, String[] names) {
        presetNames = names == null ? new String[0] : names;
        autoPlan = auto;
        presetAutos = autos == null ? new int[0] : autos;
        state = next;
        if (!next.open() || layout == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        // Mod tree screens off (Advanced settings): close this view server-side and ask for the
        // vanilla dialog instead. Read-only views of someone else's tree still open here.
        if (!com.aleks.ancientsmod.client.FeatureToggles.isTreeScreensEnabled() && !next.readOnly()) {
            com.aleks.ancientsmod.net.NetworkHandler.sendHunterTreeAction(4, 0);
            mc.execute(() -> { if (mc.player != null) mc.player.networkHandler.sendChatCommand("hunter dialog"); });
            return;
        }
        mc.execute(() -> {
            if (!(mc.currentScreen instanceof HunterTreeScreen)) mc.setScreen(new HunterTreeScreen(mc.currentScreen));
        });
    }

    public static void clear() {
        layout = null;
        state = null;
        autoPlan = AUTO_UNKNOWN;
        presetAutos = new int[0];
        presetNames = new String[0];
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
