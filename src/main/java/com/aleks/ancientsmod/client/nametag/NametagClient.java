package com.aleks.ancientsmod.client.nametag;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.screen.NametagScreen;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.payload.NametagAppliedPayload;
import com.aleks.ancientsmod.net.payload.NametagErrorPayload;
import com.aleks.ancientsmod.net.payload.NametagOpenPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Client-side state machine for the item-nametag rename GUI.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  IDLE        ── server PKT_NAMETAG_OPEN ──────────▶ OPEN   (screen shown)
 *  OPEN        ── Confirm ──────────────────────────▶ SUBMITTING
 *              └─ Cancel / ESC ──▶ IDLE (CANCEL sent, server refunds the tag)
 *  SUBMITTING  ── PKT_NAMETAG_APPLIED ──────────────▶ IDLE   (screen closes)
 *              ── PKT_NAMETAG_ERROR (token set) ────▶ OPEN   (message shown, edit + retry)
 *              ── PKT_NAMETAG_ERROR (token empty) ──▶ IDLE   (fatal, screen closes)
 * </pre>
 *
 * <p>Unlike the suggest flow there is no intent packet and no timeout fallback:
 * the server starts this by sending OPEN, and if the mod never answers, the
 * player still has a live rename session the server refunds on quit. Nothing
 * here mutates the item — Confirm sends a string and the server decides.
 */
public final class NametagClient {

    private static volatile State state = State.IDLE;
    private static volatile String token = "";
    private static volatile NametagOpenPayload session = null;
    private static volatile String lastError = "";

    private NametagClient() {}

    public static void onOpen(NametagOpenPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            // A second OPEN replaces the first: the server only ever has one pending
            // rename per player, so an older session is already dead.
            token = p.token;
            session = p;
            lastError = "";
            state = State.OPEN;
            MinecraftClient.getInstance().setScreen(new NametagScreen(p));
            AncientsMod.LOGGER.debug("[Nametag] OPEN for token {}", p.token);
        });
    }

    public static void onApplied(NametagAppliedPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            if (!p.token.equals(token)) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen instanceof NametagScreen) {
                client.setScreen(null);
            }
            reset();
        });
    }

    public static void onError(NametagErrorPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            // Empty token = the session is gone; there is nothing to retry against.
            if (p.token.isEmpty()) {
                if (client.player != null && !p.message.isEmpty()) {
                    client.player.sendMessage(Text.literal("§c[Nametag] " + p.message), false);
                }
                if (client.currentScreen instanceof NametagScreen) {
                    client.setScreen(null);
                }
                reset();
                return;
            }
            if (!p.token.equals(token)) return;
            lastError = p.message;
            if (state == State.SUBMITTING) state = State.OPEN;
        });
    }

    /** Confirm pressed. The name is legacy '&'-form; the server validates and applies it. */
    public static void submit(String name) {
        if (state != State.OPEN || token.isEmpty()) return;
        state = State.SUBMITTING;
        lastError = "";
        NetworkHandler.sendNametagSubmit(token, name);
    }

    /** Cancel / ESC. Tells the server to drop the session and hand the nametag back. */
    public static void cancel() {
        if (state == State.IDLE) return;
        if (!token.isEmpty()) NetworkHandler.sendNametagCancel(token);
        reset();
    }

    public static void reset() {
        state = State.IDLE;
        token = "";
        session = null;
        lastError = "";
    }

    public static State currentState() { return state; }
    public static String currentToken() { return token; }
    public static NametagOpenPayload currentSession() { return session; }
    public static String lastError() { return lastError; }

    public enum State {
        IDLE,
        OPEN,
        SUBMITTING
    }
}
