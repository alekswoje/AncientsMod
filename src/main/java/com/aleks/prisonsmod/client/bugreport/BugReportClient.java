package com.aleks.prisonsmod.client.bugreport;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.screen.BugReportScreen;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.BugReportAiReplyPayload;
import com.aleks.prisonsmod.net.payload.BugReportErrorPayload;
import com.aleks.prisonsmod.net.payload.BugReportFiledPayload;
import com.aleks.prisonsmod.net.payload.BugReportOpenPayload;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side state machine + entry points for the in-game bug-report flow.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  IDLE                              ── /bugreport (or /bugreport ARGS) ──▶ INTENT_SENT
 *  INTENT_SENT (no UI yet)           ── server PKT_BUGREPORT_OPEN ─────────▶ OPEN
 *                                    └─ timeout (5s) ──▶ IDLE (fallback to vanilla)
 *  OPEN (UI shown, user editing)     ── Submit button ──▶ SUBMITTING
 *                                    └─ Cancel/close ──▶ IDLE (server told via CLOSE)
 *  SUBMITTING (waiting on server)    ── PKT_BUGREPORT_FILED ──▶ AWAITING_AI
 *  AWAITING_AI                       ── PKT_BUGREPORT_AI_REPLY ──▶ CHATTING
 *  CHATTING (chat-thread mode)       ── user followup ──▶ AWAITING_AI
 *                                    ── user closes ─────▶ IDLE
 *                                    ── server status=ESCALATED ──▶ ESCALATED (read-only)
 * </pre>
 *
 * <p>All packet entry points (onOpen / onAiReply / onFiled / onError) hop to
 * the Minecraft client thread before mutating state or touching the screen.
 *
 * <p>The server never sees /bugreport when this client intercepts it; we use
 * Fabric's {@code ClientSendMessageEvents.ALLOW_COMMAND} hook to cancel the
 * outbound command and replace it with a {@code PKT_BUGREPORT_INTENT} packet.
 */
public final class BugReportClient {

    /** Time we wait for the server's OPEN reply before falling back to vanilla /bugreport. */
    private static final long INTENT_TIMEOUT_MS = 5_000L;

    private static volatile State state = State.IDLE;
    private static volatile String token = "";
    private static volatile String reportId = "";
    private static volatile long intentSentAtMs = 0L;
    private static volatile String prefill = "";
    private static volatile List<BugReportOpenPayload.Section> sections = List.of();
    private static final List<ChatLine> chatLines = new ArrayList<>();

    /** Register the outbound-command interceptor. Call once during client init. */
    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(BugReportClient::onCommand);
    }

    /**
     * Called for every outbound command the player types (after the leading
     * slash is stripped). Returns false to cancel the outbound send — we use
     * that to redirect /bugreport into a plugin-message round trip.
     */
    private static boolean onCommand(String command) {
        if (command == null || command.isEmpty()) return true;
        if (!ServerAllowlist.isAllowed()) return true;        // off-server: vanilla flow
        if (!FeatureToggles.isBugReportUiEnabled()) return true; // disabled in settings
        if (state != State.IDLE) return true;                 // mid-conversation — let server reject

        // Match `bugreport` or `bugreport <args>` (case-insensitive).
        String lower = command.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.equals("bugreport") || lower.startsWith("bugreport "))) return true;

        String args = command.length() > "bugreport".length()
                ? command.substring("bugreport".length()).trim()
                : "";
        prefill = args;
        chatLines.clear();
        sections = List.of();
        reportId = "";
        token = "";
        intentSentAtMs = System.currentTimeMillis();
        state = State.INTENT_SENT;
        NetworkHandler.sendBugReportIntent(args);
        PrisonsMod.LOGGER.info("[BugReport] /bugreport intercepted, INTENT sent");
        return false; // cancel the outbound command — server learns via plugin message instead
    }

    /** Server replied with the sanitized snapshot. Open the screen on the render thread. */
    public static void onOpen(BugReportOpenPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            if (state != State.INTENT_SENT && state != State.IDLE) {
                PrisonsMod.LOGGER.debug("[BugReport] OPEN ignored, state={}", state);
                return;
            }
            token = p.token;
            sections = p.sections;
            if (!p.prefill.isEmpty()) prefill = p.prefill;
            state = State.OPEN;
            MinecraftClient.getInstance().setScreen(new BugReportScreen());
        });
    }

    /** Server confirmed the report was persisted. */
    public static void onFiled(BugReportFiledPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            if (!p.token.equals(token)) return;
            reportId = p.reportId;
            state = State.AWAITING_AI;
            addChatLine(ChatLine.system("Report filed as " + p.reportId + ". Investigating…"));
        });
    }

    /** New AI text in the chat thread. */
    public static void onAiReply(BugReportAiReplyPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            if (!p.token.equals(token)) return;
            addChatLine(ChatLine.ai(p.message));
            switch (p.status) {
                case Protocol.BR_STATUS_RESOLVED  -> state = State.RESOLVED;
                case Protocol.BR_STATUS_ESCALATED -> state = State.ESCALATED;
                case Protocol.BR_STATUS_ERROR     -> {
                    addChatLine(ChatLine.system("Hermes hit an error — staff have been notified."));
                    state = State.ESCALATED;
                }
                default -> state = State.CHATTING;
            }
        });
    }

    /** Soft error from the server (rate-limited, token expired, AI offline). */
    public static void onError(BugReportErrorPayload p) {
        MinecraftClient.getInstance().execute(() -> {
            // Always show the message. If we were waiting for OPEN, fall back to vanilla.
            if (state == State.INTENT_SENT) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§c[BugReport] " + p.message), false);
                }
                reset();
                return;
            }
            if (!p.token.isEmpty() && !p.token.equals(token)) return;
            addChatLine(ChatLine.system(p.message));
        });
    }

    /** Screen Submit button. */
    public static void submit(int categoryMask, String description) {
        if (state != State.OPEN) return;
        addChatLine(ChatLine.player(description));
        state = State.SUBMITTING;
        NetworkHandler.sendBugReportSubmit(token, categoryMask, description);
    }

    /** Screen follow-up text input. */
    public static void followup(String message) {
        if (state != State.CHATTING && state != State.AWAITING_AI) return;
        addChatLine(ChatLine.player(message));
        state = State.AWAITING_AI;
        NetworkHandler.sendBugReportFollowup(token, message);
    }

    /** Screen "Talk to staff" button. */
    public static void escalate() {
        if (state == State.IDLE || state == State.ESCALATED || state == State.RESOLVED) return;
        addChatLine(ChatLine.system("Escalating to staff…"));
        NetworkHandler.sendBugReportEscalate(token);
    }

    /** Screen close — resolved=true if user clicked "Mark resolved". */
    public static void close(boolean resolved) {
        if (state == State.IDLE) return;
        if (!token.isEmpty()) NetworkHandler.sendBugReportClose(token, resolved);
        reset();
    }

    public static void reset() {
        state = State.IDLE;
        token = "";
        reportId = "";
        prefill = "";
        sections = List.of();
        chatLines.clear();
        intentSentAtMs = 0L;
    }

    /** Periodic tick from the client tick loop — drives the INTENT timeout. */
    public static void tick() {
        if (state != State.INTENT_SENT) return;
        if (System.currentTimeMillis() - intentSentAtMs < INTENT_TIMEOUT_MS) return;
        // Timed out — server doesn't have the mod hook or is on an older version.
        // Fall back to vanilla: send the original /bugreport as a chat command.
        String pending = prefill;
        reset();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.getNetworkHandler() != null) {
            String cmd = pending.isEmpty() ? "bugreport" : ("bugreport " + pending);
            client.getNetworkHandler().sendChatCommand(cmd);
            client.player.sendMessage(
                    Text.literal("§7[BugReport] Server didn't respond — sent the command directly."),
                    false);
        }
    }

    // ── Screen accessors ─────────────────────────────────────────────────────

    public static State currentState() { return state; }
    public static String currentToken() { return token; }
    public static String currentReportId() { return reportId; }
    public static String currentPrefill() { return prefill; }
    public static List<BugReportOpenPayload.Section> currentSections() { return sections; }
    public static List<ChatLine> currentChatLines() { return chatLines; }

    private static void addChatLine(ChatLine line) {
        chatLines.add(line);
        // Keep the chat thread bounded so a misbehaving server cannot grow the
        // mod's memory unboundedly via repeat AI replies.
        if (chatLines.size() > 64) chatLines.remove(0);
    }

    public enum State {
        IDLE,           // no flow in progress
        INTENT_SENT,    // sent INTENT, awaiting OPEN
        OPEN,           // screen shown, user editing description + categories
        SUBMITTING,     // SUBMIT sent, awaiting FILED
        AWAITING_AI,    // FILED received, waiting for AI reply
        CHATTING,       // AI reply received, user can follow up
        ESCALATED,      // AI handed off to staff, chat is read-only
        RESOLVED        // AI marked the conversation resolved
    }

    public enum LineKind { SYSTEM, AI, PLAYER }

    public static final class ChatLine {
        public final LineKind kind;
        public final String text;
        public final long timestampMs;
        private ChatLine(LineKind kind, String text) {
            this.kind = kind;
            this.text = text;
            this.timestampMs = System.currentTimeMillis();
        }
        public static ChatLine system(String s) { return new ChatLine(LineKind.SYSTEM, s); }
        public static ChatLine ai(String s)     { return new ChatLine(LineKind.AI, s); }
        public static ChatLine player(String s) { return new ChatLine(LineKind.PLAYER, s); }
    }

    private BugReportClient() {}
}
