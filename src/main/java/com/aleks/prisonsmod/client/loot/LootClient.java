package com.aleks.prisonsmod.client.loot;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.screen.LootTableDetailScreen;
import com.aleks.prisonsmod.client.screen.LootTablesScreen;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.LootSnapshotPayload;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Client-side state machine for the mod's custom {@code /loottables} browser.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  IDLE        ── /loottables (or /loot) typed ───────────────▶ REQUESTING
 *  REQUESTING  ── chunks reassemble into a snapshot ──────────▶ OPEN (screen rendered)
 *              └─ timeout (3s) ──▶ IDLE (fallback: send the command to the server)
 *  OPEN        ── ESC ────────────────────────────────────────▶ IDLE (sends PKT_LOOT_CLOSE)
 * </pre>
 *
 * <p>Mirrors {@code PvClient}: we only intercept the bare command (no args);
 * anything else passes through. Snapshots arrive chunked — {@link #onSnapshotChunk}
 * reassembles by version and decodes once complete. A reload / discovery push
 * from the server refreshes any open browser in place.
 */
public final class LootClient {

    private static final long INTENT_TIMEOUT_MS = 3_000L;

    private static volatile State state = State.IDLE;
    private static volatile long intentSentAtMs = 0L;
    /** Guards the timeout fallback's own command send from re-interception. */
    private static volatile boolean passingThroughFallback = false;

    private static volatile LootSnapshotPayload latest = null;

    // ── Chunk reassembly (single in-flight snapshot; in-order TCP delivery) ──
    private static int asmVersion = -1;
    private static int asmCount = 0;
    private static int asmReceived = 0;
    private static int asmBytes = 0;
    private static byte[][] asmChunks = null;

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(LootClient::onCommand);
    }

    private static boolean onCommand(String command) {
        if (passingThroughFallback) return true;
        if (command == null || command.isEmpty()) return true;
        if (!ServerAllowlist.isAllowed()) return true;
        if (!FeatureToggles.isLootBrowserEnabled()) return true;
        if (state != State.IDLE) return true;

        String trimmed = command.trim().toLowerCase(Locale.ROOT);
        // Only intercept the bare command + its /loot alias. Anything with args
        // passes straight through to the server.
        if (!(trimmed.equals("loottables") || trimmed.equals("loot"))) return true;

        // Fast path: open the cached snapshot immediately, refresh in background.
        if (latest != null) {
            state = State.OPEN;
            LootSnapshotPayload cached = latest;
            MinecraftClient.getInstance().execute(() ->
                    MinecraftClient.getInstance().setScreen(new LootTablesScreen(cached)));
            NetworkHandler.sendLootRequest();
            return false;
        }

        intentSentAtMs = System.currentTimeMillis();
        state = State.REQUESTING;
        NetworkHandler.sendLootRequest();
        PrisonsMod.LOGGER.info("[Loot] /loottables intercepted, snapshot requested");
        return false; // cancel outbound command
    }

    /** Called from {@link NetworkHandler} for each inbound snapshot chunk. */
    public static void onSnapshotChunk(int version, int index, int count, byte[] chunk) {
        if (count < 1 || count > Protocol.LOOT_MAX_CHUNKS) return;
        if (index < 0 || index >= count) return;
        if (chunk == null) return;

        if (version != asmVersion || count != asmCount) {
            asmVersion = version;
            asmCount = count;
            asmReceived = 0;
            asmBytes = 0;
            asmChunks = new byte[count][];
        }
        if (asmChunks[index] != null) return; // duplicate chunk
        asmChunks[index] = chunk;
        asmReceived++;
        asmBytes += chunk.length;
        if (asmBytes > Protocol.MAX_LOOT_SNAPSHOT_BYTES) {
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
            onSnapshot(LootSnapshotPayload.decode(full));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("[Loot] snapshot decode failed", t);
        }
    }

    private static void resetAssembly() {
        asmVersion = -1;
        asmCount = 0;
        asmReceived = 0;
        asmBytes = 0;
        asmChunks = null;
    }

    private static void onSnapshot(LootSnapshotPayload payload) {
        latest = payload;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            Screen cur = mc.currentScreen;
            if (cur instanceof LootTablesScreen list) {
                list.onSnapshotUpdated(payload);
                return;
            }
            if (cur instanceof LootTableDetailScreen detail) {
                detail.onSnapshotUpdated(payload);
                return;
            }
            // Arriving from the /loottables intercept — open the browser.
            state = State.OPEN;
            mc.setScreen(new LootTablesScreen(payload));
        });
    }

    /** Re-open the table list (used by the detail screen's Back / ESC). */
    public static void openTableList() {
        LootSnapshotPayload s = latest;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (s == null) {
            NetworkHandler.sendLootRequest();
            return;
        }
        mc.setScreen(new LootTablesScreen(s));
    }

    public static LootSnapshotPayload latest() {
        return latest;
    }

    /** The list screen calls this on ESC so the server stops pushing refreshes. */
    public static void onScreenClosed() {
        state = State.IDLE;
        NetworkHandler.sendLootClose();
    }

    public static void tick() {
        if (state != State.REQUESTING) return;
        if (System.currentTimeMillis() - intentSentAtMs < INTENT_TIMEOUT_MS) return;
        state = State.IDLE;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.getNetworkHandler() != null) {
            passingThroughFallback = true;
            try {
                client.getNetworkHandler().sendChatCommand("loottables");
            } finally {
                passingThroughFallback = false;
            }
            client.player.sendMessage(
                    Text.literal("§7[Loot] Server didn't respond — opened the server menu."), false);
        }
    }

    public enum State {
        IDLE,
        REQUESTING,
        OPEN
    }

    private LootClient() {}
}
