package com.aleks.prisonsmod.client.pv;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.screen.PvAffinityPickerScreen;
import com.aleks.prisonsmod.client.screen.PvOverviewScreen;
import com.aleks.prisonsmod.client.screen.PvPresetsScreen;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
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
            if (!FeatureToggles.isPvOverviewEnabled()) return;
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
                client.execute(() -> client.setScreen(new PvOverviewScreen(cached)));
            }
            NetworkHandler.sendPvBundleRequest();
        });
    }

    private static boolean onCommand(String command) {
        if (command == null || command.isEmpty()) return true;
        if (!ServerAllowlist.isAllowed()) return true;
        if (!FeatureToggles.isPvOverviewEnabled()) return true;
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
                    MinecraftClient.getInstance().setScreen(new PvOverviewScreen(cached)));
            NetworkHandler.sendPvBundleRequest();
            PrisonsMod.LOGGER.info("[PV] /pv intercepted, opened cached + bg refresh");
            return false;
        }

        // First-time path: no cache yet, wait for the first bundle.
        intentSentAtMs = System.currentTimeMillis();
        state = State.REQUESTING;
        NetworkHandler.sendPvBundleRequest();
        PrisonsMod.LOGGER.info("[PV] /pv intercepted, BUNDLE_REQ sent (no cache)");
        return false; // cancel outbound command
    }

    public static void onBundle(PvBundlePayload payload) {
        latestBundle = payload;
        MinecraftClient.getInstance().execute(() -> {
            // If a mod-side PV screen is open, this bundle is a refresh —
            // re-render in place instead of swapping screens.
            Screen current = MinecraftClient.getInstance().currentScreen;
            if (current instanceof PvAffinityPickerScreen picker) {
                picker.onBundleUpdated(payload);
                return;
            }
            if (current instanceof PvPresetsScreen presets) {
                presets.onBundleUpdated(payload);
                return;
            }
            if (current instanceof PvOverviewScreen overview) {
                overview.onBundleUpdated(payload);
                return;
            }
            // Otherwise we're arriving from /pv intercept or post-vault-close.
            state = State.OPEN;
            MinecraftClient.getInstance().setScreen(new PvOverviewScreen(payload));
        });
    }

    public static void onScreenClosed() {
        state = State.IDLE;
    }

    public static void onPickerClosed() {
        // Picker dismissed via ESC or Back button — return to the overview if
        // we still have data, otherwise just go idle.
        state = State.IDLE;
    }

    /** Re-open the overview from the picker's "Back" button. Uses the latest
     *  cached bundle if available, else requests a fresh one. */
    public static void openOverviewFromPicker() {
        MinecraftClient client = MinecraftClient.getInstance();
        PvBundlePayload b = latestBundle;
        if (b != null) {
            state = State.OPEN;
            client.setScreen(new PvOverviewScreen(b));
        } else {
            state = State.REQUESTING;
            intentSentAtMs = System.currentTimeMillis();
            NetworkHandler.sendPvBundleRequest();
        }
    }

    /** Open the presets screen from the picker's "Presets" button. */
    public static void openPresetsFromPicker(int returnVault) {
        MinecraftClient client = MinecraftClient.getInstance();
        PvBundlePayload b = latestBundle;
        if (b == null) {
            NetworkHandler.sendPvBundleRequest();
            return;
        }
        client.setScreen(new PvPresetsScreen(returnVault, b));
    }

    /** Re-open the affinity picker from the presets screen's "Back" button. */
    public static void openAffinityPickerFromPresets(int vaultNumber) {
        MinecraftClient client = MinecraftClient.getInstance();
        PvBundlePayload b = latestBundle;
        if (b == null) {
            NetworkHandler.sendPvBundleRequest();
            return;
        }
        client.setScreen(new PvAffinityPickerScreen(vaultNumber, b));
    }

    private static volatile PvBundlePayload latestBundle = null;

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

    /** Open the client-side affinity picker for a vault. Triggered by
     *  right-click on an overview card. Uses the cached bundle so categories
     *  show current bindings; toggle clicks send PKT_PV_AFFINITY_TOGGLE and
     *  the server replies with an updated bundle that we route back via
     *  {@link #onBundle}. */
    public static void openAffinityPicker(int vaultNumber) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;
        if (vaultNumber < 1 || vaultNumber > Protocol.PV_MAX_VAULTS) return;
        if (latestBundle == null) {
            // No data yet — kick off a bundle request and the post-bundle path
            // will open the overview; user can right-click again.
            NetworkHandler.sendPvBundleRequest();
            return;
        }
        client.setScreen(new PvAffinityPickerScreen(vaultNumber, latestBundle));
    }

    public static boolean isExpectingMenuReopen() {
        return expectingMenuReopen;
    }

    /** Cached PV bundle from the last server-side PKT_PV_BUNDLE. Used by the
     *  shift-click mixin to decide whether to intercept (only when at least
     *  one vault has an affinity bound). May be null until the first /pv. */
    public static PvBundlePayload latestBundle() {
        return latestBundle;
    }

    /** True if the cached bundle indicates at least one vault has a non-empty
     *  affinity binding. Cheap call — used per shift-click. */
    public static boolean hasAnyAffinity() {
        PvBundlePayload b = latestBundle;
        if (b == null) return false;
        for (PvBundlePayload.Vault v : b.vaults) {
            String csv = v.affinityCsv;
            if (csv != null && !csv.isEmpty()) return true;
        }
        return false;
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
            client.getNetworkHandler().sendChatCommand("pv");
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
