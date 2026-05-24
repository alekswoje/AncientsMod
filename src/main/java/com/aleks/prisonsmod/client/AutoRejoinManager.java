package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.PrisonsMod;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/**
 * Auto-rejoin the last allowlisted server after an involuntary disconnect.
 *
 * <p>Activated only while the vanilla {@link DisconnectedScreen} is showing
 * (the screen the client puts up on a kick, server restart, or network drop).
 * Player-initiated disconnects ("Disconnect" / "Save and Quit to Title" in the
 * pause menu) skip {@code DisconnectedScreen} entirely, so they bypass us by
 * construction — no opt-in vs. opt-out heuristic needed.
 *
 * <p>Cadence: 5 s grace, then a connect attempt every 5 s while the screen
 * stays visible. Each failed attempt bounces us back to {@code DisconnectedScreen}
 * and the countdown restarts. If the user clicks any button (Back to Title /
 * Back to Server List), the screen changes away → {@link #tick} resets the state
 * and we don't fight the user.
 *
 * <p>Backend routing on the way back in is handled entirely server-side by
 * the proxy ({@code LastServerTracker} + {@code ConnectQueueManager}): the
 * client just reconnects to the same {@link ServerInfo} entry it joined
 * originally (e.g. {@code play.ancients.gg}); the proxy sends the player to
 * their last cluster backend, or auto-queues if that backend is still down.
 */
public final class AutoRejoinManager {

    private static final long RETRY_INTERVAL_MS = 5_000L;

    /** Last allowlisted server we successfully joined; reused as the reconnect target. */
    private static volatile ServerInfo lastServer = null;
    private static volatile ServerAddress lastAddress = null;

    /** Wall-clock time at which the next reconnect attempt should fire. 0 = none scheduled. */
    private static volatile long nextAttemptAt = 0L;

    /** How many reconnect attempts we've fired since the last successful join. */
    private static volatile int attemptCount = 0;

    private AutoRejoinManager() {}

    /** Called from {@code ClientPlayConnectionEvents.JOIN}. Saves the entry if the server is on the allowlist. */
    public static void onJoin(ServerInfo info) {
        // Successful join = reset any pending rejoin state.
        nextAttemptAt = 0L;
        attemptCount = 0;
        if (info == null || info.address == null) return;
        if (!ServerAllowlist.isAllowed()) return;
        lastServer = info;
        try {
            lastAddress = ServerAddress.parse(info.address);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.warn("AutoRejoin: failed to parse address '{}': {}", info.address, t.getMessage());
            lastServer = null;
            lastAddress = null;
        }
    }

    /**
     * Per-tick driver. Run from {@code ClientTickEvents.END_CLIENT_TICK}.
     * Handles every transition of the small state machine described in the
     * class doc.
     */
    public static void tick(MinecraftClient mc) {
        if (!FeatureToggles.isAutoRejoinEnabled()) {
            nextAttemptAt = 0L;
            return;
        }
        if (mc.world != null) return; // in-game; nothing to do

        Screen screen = mc.currentScreen;
        if (screen instanceof ConnectScreen) {
            // A reconnect attempt is in flight (either ours or the user's).
            return;
        }
        if (!(screen instanceof DisconnectedScreen)) {
            // User navigated away from the kick screen → respect that.
            if (nextAttemptAt != 0L) nextAttemptAt = 0L;
            return;
        }
        if (lastServer == null || lastAddress == null) return;

        long now = System.currentTimeMillis();
        if (nextAttemptAt == 0L) {
            nextAttemptAt = now + RETRY_INTERVAL_MS;
            return;
        }
        if (now < nextAttemptAt) return;

        attemptCount++;
        nextAttemptAt = 0L; // re-armed on the next tick if the attempt fails back to DisconnectedScreen
        ServerInfo info = lastServer;
        ServerAddress address = lastAddress;
        // Parent for the ConnectScreen: a fresh server-list screen. If our reconnect
        // fails back to a DisconnectedScreen, its "Back to Server List" button then
        // returns the user to the multiplayer list, not the title screen.
        Screen parent = new MultiplayerScreen(new TitleScreen());
        PrisonsMod.LOGGER.info("AutoRejoin: attempt #{} → {}", attemptCount, info.address);
        ConnectScreen.connect(parent, mc, address, info, false, null);
    }

    /** Registered on every screen init; attaches the countdown overlay to {@link DisconnectedScreen}. */
    public static void onScreenInit(Screen screen) {
        if (!(screen instanceof DisconnectedScreen)) return;
        ScreenEvents.afterRender(screen).register(AutoRejoinManager::renderOverlay);
    }

    private static void renderOverlay(Screen screen, DrawContext context, int mouseX, int mouseY, float tickDelta) {
        if (!FeatureToggles.isAutoRejoinEnabled()) return;
        if (lastServer == null) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        TextRenderer tr = mc.textRenderer;

        int secs = secondsUntilNextAttempt();
        String text = secs > 0
                ? "PrisonsMod: auto-rejoin in " + secs + "s"
                : "PrisonsMod: reconnecting…";
        if (attemptCount > 0 && secs > 0) {
            text = text + "  (attempt " + (attemptCount + 1) + ")";
        }
        int width = tr.getWidth(text);
        int x = (screen.width - width) / 2;
        int y = screen.height - 14;
        // Pale yellow, fully opaque. drawTextWithShadow adds a 1px black drop so it stays legible over the dirt panorama.
        context.drawTextWithShadow(tr, text, x, y, 0xFFFFE066);
    }

    private static int secondsUntilNextAttempt() {
        long at = nextAttemptAt;
        if (at == 0L) return 0;
        long left = at - System.currentTimeMillis();
        if (left <= 0L) return 0;
        return (int) Math.ceil(left / 1000.0);
    }
}
