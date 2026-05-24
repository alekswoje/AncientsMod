package com.aleks.prisonsmod.client.suggest;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.screen.SuggestScreen;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.payload.SuggestErrorPayload;
import com.aleks.prisonsmod.net.payload.SuggestFiledPayload;
import com.aleks.prisonsmod.net.payload.SuggestOpenPayload;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Client-side state machine + entry points for the in-game suggest flow.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  IDLE          ── /suggest typed ────────────────────────▶ INTENT_SENT
 *  INTENT_SENT   ── server PKT_SUGGEST_OPEN ───────────────▶ OPEN
 *                └─ timeout (5s) ──▶ IDLE (fallback: send command as chat)
 *  OPEN          ── Submit ────────────────────────────────▶ SUBMITTING
 *                └─ Cancel/ESC ──▶ IDLE (CLOSE sent)
 *  SUBMITTING    ── PKT_SUGGEST_FILED ────────────────────▶ IDLE
 *                ── PKT_SUGGEST_ERROR (non-empty token) ──▶ OPEN (message shown)
 * </pre>
 *
 * <p>The server never sees /suggest when this client intercepts it.
 */
public final class SuggestClient {

    /** Time we wait for the server's OPEN reply before falling back to vanilla. */
    private static final long INTENT_TIMEOUT_MS = 5_000L;

    private static volatile State state = State.IDLE;
    private static volatile String token = "";
    private static volatile long intentSentAtMs = 0L;
    private static volatile String lastError = "";

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(SuggestClient::onCommand);
    }

    private static boolean onCommand(String command) {
        if (command == null || command.isEmpty()) return true;
        if (!ServerAllowlist.isAllowed()) return true;
        if (!FeatureToggles.isSuggestUiEnabled()) return true;
        if (state != State.IDLE) return true;

        String lower = command.toLowerCase(Locale.ROOT);
        if (!(lower.equals("suggest") || lower.startsWith("suggest "))) return true;

        token = "";
        lastError = "";
        intentSentAtMs = System.currentTimeMillis();
        state = State.INTENT_SENT;
        NetworkHandler.sendSuggestIntent();
        PrisonsMod.LOGGER.info("[Suggest] /suggest intercepted, INTENT sent");
        return false; // cancel outbound command
    }

    public static void onOpen(SuggestOpenPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            if (state != State.INTENT_SENT && state != State.IDLE) {
                PrisonsMod.LOGGER.debug("[Suggest] OPEN ignored, state={}", state);
                return;
            }
            token = p.token;
            lastError = "";
            state = State.OPEN;
            MinecraftClient.getInstance().setScreen(new SuggestScreen());
        });
    }

    public static void onFiled(SuggestFiledPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            if (!p.token.equals(token)) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§a[Suggest] §fSubmitted! Thanks for the feedback."), false);
            }
            // Close the screen if it's still open.
            if (client.currentScreen instanceof SuggestScreen) {
                client.setScreen(null);
            }
            reset();
        });
    }

    public static void onError(SuggestErrorPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            // Empty-token error = fatal (no session was issued). Show as chat and reset.
            if (p.token.isEmpty()) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§c[Suggest] " + p.message), false);
                }
                if (client.currentScreen instanceof SuggestScreen) {
                    client.setScreen(null);
                }
                reset();
                return;
            }
            if (!p.token.equals(token)) return;
            lastError = p.message;
            // Bounce back to OPEN so the user can edit and retry.
            if (state == State.SUBMITTING) state = State.OPEN;
        });
    }

    public static void submit(byte category, String body) {
        if (state != State.OPEN) return;
        state = State.SUBMITTING;
        lastError = "";
        NetworkHandler.sendSuggestSubmit(token, category, body);
    }

    public static void close() {
        if (state == State.IDLE) return;
        if (!token.isEmpty()) NetworkHandler.sendSuggestClose(token);
        reset();
    }

    public static void reset() {
        state = State.IDLE;
        token = "";
        intentSentAtMs = 0L;
        lastError = "";
    }

    public static void tick() {
        if (state != State.INTENT_SENT) return;
        if (System.currentTimeMillis() - intentSentAtMs < INTENT_TIMEOUT_MS) return;
        reset();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.getNetworkHandler() != null) {
            // Fallback — the server-side command will tell them to install the mod
            // (or, if they're modded but the handshake hadn't completed, drive the
            // flow manually). Either way the user gets useful output.
            client.getNetworkHandler().sendChatCommand("suggest");
            client.player.sendMessage(
                    Text.literal("§7[Suggest] Server didn't respond — sent the command directly."),
                    false);
        }
    }

    public static State currentState() { return state; }
    public static String currentToken() { return token; }
    public static String lastError() { return lastError; }

    public enum State {
        IDLE,
        INTENT_SENT,
        OPEN,
        SUBMITTING
    }

    private SuggestClient() {}
}
