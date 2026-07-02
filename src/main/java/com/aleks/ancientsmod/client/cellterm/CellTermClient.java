package com.aleks.ancientsmod.client.cellterm;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.screen.CellTerminalScreen;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.CellTermBundlePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

/**
 * Client-side state machine for the Cell Vault Terminal.
 *
 * <p>Unlike {@link com.aleks.ancientsmod.client.pv.PvClient} there is no
 * command interception and no request/timeout flow — the feature is purely
 * server-push (like {@code /pvsee}): when a modded player opens their cell's
 * vault chest, the server cancels the vanilla chest GUI and sends
 * {@code PKT_CELLTERM_OPEN} (label + editable flag) followed immediately by a
 * {@code PKT_CELLTERM_BUNDLE} (or its chunked equivalent). The OPEN arms the
 * pending-open state here; the bundle consumes it and opens the screen.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  IDLE     ── PKT_CELLTERM_OPEN ───────────────▶ PENDING (label + editable held)
 *  PENDING  ── bundle arrives ──────────────────▶ OPEN (screen rendered)
 *  OPEN     ── bundle arrives ──────────────────▶ OPEN (in-place refresh)
 *  OPEN     ── ESC / E ─────────────────────────▶ IDLE (cursor-return → C2S close)
 *  any      ── PKT_CELLTERM_CLOSE(reason) ──────▶ IDLE (screen dropped, NO C2S echo)
 * </pre>
 *
 * <p>Bundles with no pending open and no open screen are stale (e.g. arrived
 * after a force-close) and are ignored. When the {@code cellTerminal} feature
 * toggle is off, OPEN packets are ignored outright — the server also stops
 * intercepting vault chests once it receives our
 * {@link NetworkHandler#sendCellTermState(boolean)} report, so a stray OPEN
 * only happens in the brief window around a toggle flip.
 *
 * <p>Purely reactive — no event/tick registration needed; everything is driven
 * by {@link NetworkHandler} dispatch. {@link #reset()} is wired to the client
 * DISCONNECT event so pending state never survives a server hop.
 */
public final class CellTermClient {

    /** Set by {@link #onOpen}; consumed by the next {@link #onBundle} (which
     *  opens the screen with the held label + editable flag). */
    private static volatile boolean pendingOpen = false;
    private static volatile String pendingLabel = "";
    private static volatile boolean pendingEditable = false;

    /**
     * Server told us (via PKT_CELLTERM_OPEN) that it cancelled a vault-chest
     * open and wants the terminal shown. The cell's bundle is pushed
     * immediately after; {@link #onBundle} opens the screen.
     */
    public static void onOpen(String cellLabel, boolean editable) {
        // Toggle off → ignore the push. The server won't send once it has our
        // PKT_CELLTERM_STATE(0), so this only covers the flip-in-flight window.
        if (!FeatureToggles.isCellTerminalEnabled()) return;
        pendingLabel = (cellLabel == null) ? "" : cellLabel;
        pendingEditable = editable;
        pendingOpen = true;
    }

    /** Fresh full snapshot from the server — either the session-opening bundle
     *  or a refresh (post-op, periodic, or PKT_CELLTERM_REFRESH_REQ reply). */
    public static void onBundle(CellTermBundlePayload payload) {
        MinecraftClient.getInstance().execute(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            // If the terminal is already open, this bundle is a refresh —
            // re-render in place instead of swapping screens.
            if (client.currentScreen instanceof CellTerminalScreen screen) {
                screen.onBundleUpdated(payload);
                return;
            }
            if (pendingOpen) {
                pendingOpen = false;
                client.setScreen(new CellTerminalScreen(payload, pendingLabel, pendingEditable));
                return;
            }
            // No pending open and no open screen — stale bundle (e.g. raced a
            // force-close). Ignore.
        });
    }

    // ── Chunk reassembly for oversized bundles (single in-flight; in-order TCP) ──
    // Bundles that fit one packet arrive whole via PKT_CELLTERM_BUNDLE → onBundle.
    // Item-heavy cells exceed a single packet and arrive split across
    // PKT_CELLTERM_BUNDLE_CHUNK; we reassemble by version, then decode the SAME
    // body a single packet carries and hand it to onBundle so the screen-open
    // path is identical. Same scheme (and shared caps) as PvClient's PV chunks.
    private static int asmVersion = -1;
    private static int asmCount = 0;
    private static int asmReceived = 0;
    private static int asmBytes = 0;
    private static byte[][] asmChunks = null;

    /** Called from {@link NetworkHandler} for each inbound cellterm-bundle chunk. */
    public static void onBundleChunk(int version, int index, int count, byte[] chunk) {
        if (count < 1 || count > Protocol.PV_BUNDLE_MAX_CHUNKS) return;
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
        if (asmBytes > Protocol.MAX_PV_BUNDLE_TOTAL_BYTES) {
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
            PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(full));
            onBundle(CellTermBundlePayload.decode(buf));
        } catch (Throwable t) {
            AncientsMod.LOGGER.debug("[CellTerm] chunked bundle decode failed", t);
        }
    }

    private static void resetAssembly() {
        asmVersion = -1;
        asmCount = 0;
        asmReceived = 0;
        asmBytes = 0;
        asmChunks = null;
    }

    /**
     * Server force-closed the session (range exceeded, raid start, access
     * revoked, vault chest gone, world change). Drop the screen WITHOUT going
     * through its close() path — the client must NOT echo a C2S
     * {@link Protocol#PKT_CELLTERM_CLOSE_C2S} back (the server already ended
     * the session), and the server handles any held cursor stack itself.
     */
    public static void onForceClose(String reason) {
        MinecraftClient.getInstance().execute(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            boolean hadSession = pendingOpen
                    || client.currentScreen instanceof CellTerminalScreen;
            pendingOpen = false;
            if (client.currentScreen instanceof CellTerminalScreen) {
                // setScreen(null) bypasses Screen.close(), so no cursor-return /
                // C2S close is sent — exactly what a server-initiated close wants.
                client.setScreen(null);
            }
            if (hadSession && reason != null && !reason.isEmpty() && client.player != null) {
                client.player.sendMessage(
                        Text.literal("§7[Cell] §f" + reason), false);
            }
        });
    }

    /**
     * Called by {@link CellTerminalScreen}'s close-out hook — i.e. the CLIENT
     * initiated the close (ESC / inventory key). The screen has already sent
     * the cursor-return (if the cursor was non-empty) FIRST; here we clear the
     * pending state and end the server-side session.
     */
    public static void onScreenClosed() {
        pendingOpen = false;
        pendingLabel = "";
        pendingEditable = false;
        NetworkHandler.sendCellTermClose();
    }

    /** Disconnect hygiene: drop pending-open state + any half-assembled bundle
     *  so nothing leaks into the next server session. */
    public static void reset() {
        pendingOpen = false;
        pendingLabel = "";
        pendingEditable = false;
        resetAssembly();
    }

    private CellTermClient() {}
}
