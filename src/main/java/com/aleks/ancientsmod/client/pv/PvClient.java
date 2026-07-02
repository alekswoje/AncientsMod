package com.aleks.ancientsmod.client.pv;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.client.screen.PvOverviewScreen;
import com.aleks.ancientsmod.client.screen.PvTerminalScreen;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.PvBundlePayload;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Client-side state machine for the mod's custom {@code /pv} overview screen.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  IDLE          ── /pv typed (no args) ─────────────────────▶ REQUESTING
 *  REQUESTING    ── PKT_PV_BUNDLE arrives ──────────────────▶ OPEN (screen rendered)
 *                └─ timeout (3s) ──▶ IDLE (fallback: send /pv to server)
 *  OPEN          ── ESC / clicked a vault ─────────────────▶ IDLE
 * </pre>
 *
 * <p>{@code /pv 1..7} with explicit args goes straight to the server (vanilla
 * flow) — we only intercept the no-args invocation. {@code /pvsort} also stays
 * vanilla.
 */
public final class PvClient {

    private static final long INTENT_TIMEOUT_MS = 3_000L;

    private static volatile State state = State.IDLE;
    private static volatile long intentSentAtMs = 0L;

    /**
     * Set only while the timeout fallback runs its own {@code sendChatCommand("pv")}.
     * Fabric fires {@link ClientSendMessageEvents#ALLOW_COMMAND} for programmatic
     * sends too, so without this guard {@link #onCommand} would re-intercept the
     * fallback, cancel it, and re-arm REQUESTING — looping the timeout forever and
     * flooding chat with "server didn't respond". The guard lets the fallback pass
     * straight through to the server (and the message print exactly once).
     */
    private static volatile boolean passingThroughFallback = false;

    /**
     * When true, the next time the server opens the PersonalVaultMenu chest
     * GUI (title "Personal Vaults") the mod should swap it for a fresh
     * overview. Set when the player navigates into a vault from the overview;
     * cleared once the swap has been consumed or the player explicitly closes
     * the overview without re-navigating.
     */
    private static volatile boolean expectingMenuReopen = false;

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(PvClient::onCommand);
        // Detect the server reopening the PersonalVaultMenu chest GUI after
        // a vault-or-picker close. When the flag is set (meaning the user
        // arrived here via the mod overview), trigger a fresh bundle request
        // and let onBundle replace the chest screen with the overview.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!expectingMenuReopen) return;
            if (!ServerAllowlist.isAllowed()) return;
            if (!FeatureToggles.isPvTerminalEnabled()) return;
            if (!(screen instanceof HandledScreen<?> hs)) return;
            String title = hs.getTitle().getString();
            if (title == null) return;
            if (!title.equals("Personal Vaults")) return;
            // One-shot: consume the flag immediately so we don't loop if the
            // bundle never comes back.
            expectingMenuReopen = false;
            // Open with cached bundle right away so the user never sees the
            // server's chest GUI flicker; refresh in background.
            PvBundlePayload cached = latestBundle;
            if (cached != null) {
                state = State.OPEN;
                client.execute(() -> client.setScreen(openScreenFor(cached)));
            }
            NetworkHandler.sendPvBundleRequest();
        });
    }

    private static boolean onCommand(String command) {
        // The timeout fallback re-enters here (Fabric fires ALLOW_COMMAND for
        // programmatic sends); let it through rather than re-intercept + loop.
        if (passingThroughFallback) return true;
        if (command == null || command.isEmpty()) return true;
        if (!ServerAllowlist.isAllowed()) return true;
        if (!FeatureToggles.isPvTerminalEnabled()) return true;
        if (state != State.IDLE) return true;

        String trimmed = command.trim().toLowerCase(Locale.ROOT);
        // Only intercept bare /pv (and the personalvault alias). /pv 1, /pv 2,
        // /pvsort, etc. all pass through to the server as vanilla commands.
        if (!(trimmed.equals("pv") || trimmed.equals("personalvault"))) return true;

        // Fast path: if we already have a cached bundle, open the overview
        // immediately and request a fresh bundle in the background. Avoids
        // the 3s timeout fallback when the server's rate-limit blocks a quick
        // re-open of /pv.
        if (latestBundle != null) {
            state = State.OPEN;
            PvBundlePayload cached = latestBundle;
            MinecraftClient.getInstance().execute(() ->
                    MinecraftClient.getInstance().setScreen(openScreenFor(cached)));
            NetworkHandler.sendPvBundleRequest();
            AncientsMod.LOGGER.info("[PV] /pv intercepted, opened cached + bg refresh");
            return false;
        }

        // First-time path: no cache yet, wait for the first bundle.
        intentSentAtMs = System.currentTimeMillis();
        state = State.REQUESTING;
        NetworkHandler.sendPvBundleRequest();
        AncientsMod.LOGGER.info("[PV] /pv intercepted, BUNDLE_REQ sent (no cache)");
        return false; // cancel outbound command
    }

    public static void onBundle(PvBundlePayload payload) {
        latestBundle = payload;
        MinecraftClient.getInstance().execute(() -> {
            // A pending /pvsee open: this bundle carries the inspected player's
            // vaults — force the terminal (pvsee mode) regardless of the user's
            // own card-vs-terminal preference.
            if (pendingPvSee) {
                pendingPvSee = false;
                state = State.OPEN;
                MinecraftClient.getInstance().setScreen(
                        new PvTerminalScreen(payload, pvSeeTargetName));
                return;
            }
            // If a mod-side PV screen is open, this bundle is a refresh —
            // re-render in place instead of swapping screens.
            Screen current = MinecraftClient.getInstance().currentScreen;
            if (current instanceof PvOverviewScreen overview) {
                overview.onBundleUpdated(payload);
                return;
            }
            if (current instanceof PvTerminalScreen terminal) {
                terminal.onBundleUpdated(payload);
                return;
            }
            // Otherwise we're arriving from /pv intercept or post-vault-close.
            state = State.OPEN;
            MinecraftClient.getInstance().setScreen(openScreenFor(payload));
        });
    }

    // ── Chunk reassembly for oversized bundles (single in-flight; in-order TCP) ──
    // Bundles that fit one packet still arrive whole via PKT_PV_BUNDLE → onBundle.
    // Heavy vaults exceed a single packet and arrive split across PKT_PV_BUNDLE_CHUNK;
    // we reassemble by version, then decode the SAME body a single packet carries and
    // hand it to onBundle so the screen-open path is identical.
    private static int asmVersion = -1;
    private static int asmCount = 0;
    private static int asmReceived = 0;
    private static int asmBytes = 0;
    private static byte[][] asmChunks = null;

    /** Called from {@link NetworkHandler} for each inbound PV-bundle chunk. */
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
            onBundle(PvBundlePayload.decode(buf));
        } catch (Throwable t) {
            AncientsMod.LOGGER.debug("[PV] chunked bundle decode failed", t);
        }
    }

    private static void resetAssembly() {
        asmVersion = -1;
        asmCount = 0;
        asmReceived = 0;
        asmBytes = 0;
        asmChunks = null;
    }

    /** Returns the right /pv landing screen based on the user's settings:
     *  the terminal view if the test toggle is on, otherwise the card overview. */
    private static Screen openScreenFor(PvBundlePayload payload) {
        if (FeatureToggles.isPvTerminalEnabled()) {
            return new PvTerminalScreen(payload);
        }
        return new PvOverviewScreen(payload);
    }

    public static void onScreenClosed() {
        state = State.IDLE;
    }

    public static void onPickerClosed() {
        // Picker dismissed via ESC or Back button — return to the overview if
        // we still have data, otherwise just go idle.
        state = State.IDLE;
    }

    /**
     * Reset all {@code /pv} flow state. Called from the client DISCONNECT handler.
     * These are static fields that survive a reconnect, so a server reboot while
     * the terminal was open (or a bundle request was in flight) would otherwise
     * leave {@code state} stuck at OPEN/REQUESTING forever — making every later
     * {@code /pv} fall straight through to the server's default chest menu (no
     * timeout message, because the intercept never re-arms) until a full client
     * restart. The cell terminal already resets here (CellTermClient.reset), which
     * is why it never got stuck; the PV terminal was simply missing from the list.
     */
    public static void reset() {
        state = State.IDLE;
        intentSentAtMs = 0L;
        passingThroughFallback = false;
        expectingMenuReopen = false;
        pendingPvSee = false;
        pvSeeTargetName = "";
        latestBundle = null;
        // Drop any half-received chunked bundle so a stale reassembly from the
        // previous session can't bleed into the next one.
        asmVersion = -1;
        asmCount = 0;
        asmReceived = 0;
        asmBytes = 0;
        asmChunks = null;
    }

    /** Re-open the overview from the picker's "Back" button. Uses the latest
     *  cached bundle if available, else requests a fresh one. */
    public static void openOverviewFromPicker() {
        MinecraftClient client = MinecraftClient.getInstance();
        PvBundlePayload b = latestBundle;
        if (b != null) {
            state = State.OPEN;
            client.setScreen(openScreenFor(b));
        } else {
            state = State.REQUESTING;
            intentSentAtMs = System.currentTimeMillis();
            NetworkHandler.sendPvBundleRequest();
        }
    }

    private static volatile PvBundlePayload latestBundle = null;

    /** Set by {@link #onOpenTerminal} when the server initiates a /pvsee view;
     *  consumed by the next {@link #onBundle} (the target's data) to open the
     *  terminal in pvsee mode. */
    private static volatile boolean pendingPvSee = false;
    private static volatile String pvSeeTargetName = "";

    /**
     * Server told us (via PKT_PV_OPEN_TERMINAL) to open the PV terminal on
     * another player's vaults — the result of an admin running
     * {@code /pvsee <player>}. The target's bundle is pushed immediately after,
     * and {@link #onBundle} opens the terminal in pvsee mode. The target name
     * is display-only; the server tracks the real target and re-checks the
     * admin's permission on every action.
     */
    public static void onOpenTerminal(String targetName, boolean editable) {
        pvSeeTargetName = (targetName == null) ? "" : targetName;
        pendingPvSee = true;
    }

    /** Open a specific PV from the overview. Sends a custom packet rather
     *  than {@code /pv N} so the server can open with
     *  {@code reopenMenuOnClose=true}; combined with the menu-intercept flag
     *  below, closing the vault returns the player to a fresh overview. */
    public static void openVault(int vaultNumber) {
        state = State.IDLE;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;
        if (vaultNumber < 1 || vaultNumber > Protocol.PV_MAX_VAULTS) return;
        expectingMenuReopen = true;
        client.setScreen(null);
        NetworkHandler.sendPvOpenRequest(vaultNumber);
    }

    public static boolean isExpectingMenuReopen() {
        return expectingMenuReopen;
    }

    /** Cached PV bundle from the last server-side PKT_PV_BUNDLE. May be null
     *  until the first /pv. */
    public static PvBundlePayload latestBundle() {
        return latestBundle;
    }

    public static void clearExpectingMenuReopen() {
        expectingMenuReopen = false;
    }

    public static void tick() {
        if (state != State.REQUESTING) return;
        if (System.currentTimeMillis() - intentSentAtMs < INTENT_TIMEOUT_MS) return;
        // Server didn't respond — fall back to the vanilla command flow.
        state = State.IDLE;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.getNetworkHandler() != null) {
            passingThroughFallback = true;
            try {
                client.getNetworkHandler().sendChatCommand("pv");
            } finally {
                passingThroughFallback = false;
            }
            client.player.sendMessage(
                    Text.literal("§7[PV] Server didn't respond — sent the command directly."),
                    false);
        }
    }

    public static State currentState() {
        return state;
    }

    public enum State {
        IDLE,
        REQUESTING,
        OPEN
    }

    private PvClient() {}
}
