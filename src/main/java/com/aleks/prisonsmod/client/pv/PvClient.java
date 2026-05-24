package com.aleks.prisonsmod.client.pv;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.screen.PvOverviewScreen;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
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

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(PvClient::onCommand);
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

        intentSentAtMs = System.currentTimeMillis();
        state = State.REQUESTING;
        NetworkHandler.sendPvBundleRequest();
        PrisonsMod.LOGGER.info("[PV] /pv intercepted, BUNDLE_REQ sent");
        return false; // cancel outbound command
    }

    public static void onBundle(PvBundlePayload payload) {
        MinecraftClient.getInstance().execute(() -> {
            if (state != State.REQUESTING && state != State.IDLE) {
                PrisonsMod.LOGGER.debug("[PV] BUNDLE ignored, state={}", state);
                return;
            }
            state = State.OPEN;
            MinecraftClient.getInstance().setScreen(new PvOverviewScreen(payload));
        });
    }

    public static void onScreenClosed() {
        state = State.IDLE;
    }

    /** Open a specific PV via the vanilla server flow (used when the user clicks
     *  a vault preview in the overview screen). */
    public static void openVault(int vaultNumber) {
        state = State.IDLE;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) return;
        if (vaultNumber < 1 || vaultNumber > 7) return;
        client.setScreen(null);
        client.getNetworkHandler().sendChatCommand("pv " + vaultNumber);
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
