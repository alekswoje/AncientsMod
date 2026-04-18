package com.aleks.prisonsmod.net;

import net.minecraft.util.Identifier;

/**
 * PrisonsMod network protocol.
 *
 * <h2>Security model</h2>
 * <p>
 * The mod is open source. Any public release must assume an adversary:
 * <ul>
 *   <li>has full knowledge of the protocol (no security through obscurity),</li>
 *   <li>can operate a malicious server that tries to corrupt, crash, or DoS mod clients,</li>
 *   <li>can install the mod while intending to cheat.</li>
 * </ul>
 *
 * <h3>Core invariants</h3>
 * <ol>
 *   <li><b>Server is authoritative.</b> The mod never sends packets that cause the
 *       server to grant rewards, mutate inventory, or influence scoring. The only
 *       C2S traffic allowed is a one-shot presence handshake; everything else is
 *       S2C cosmetic.</li>
 *   <li><b>Display-only.</b> Packets describe events that already happened. The mod
 *       renders visuals. It never writes game state.</li>
 *   <li><b>Defensive decoding.</b> Every payload is bounds-checked before it is
 *       enqueued for rendering. Malformed packets are dropped silently (with an
 *       optional debug log); they cannot crash the client.</li>
 *   <li><b>Rate limited.</b> Each packet type has a soft per-second cap. Excess
 *       packets are dropped at the receiver before any allocation occurs. This
 *       protects against a compromised or spoofed server spamming effects to
 *       OOM the client.</li>
 *   <li><b>Bounded memory.</b> Renderers hold fixed-size buffers of pending
 *       visual entities; oldest are evicted when full.</li>
 * </ol>
 *
 * <h2>Versioning</h2>
 * The channel identifier carries the <i>major</i> protocol version
 * ({@link #CHANNEL_V1}). Breaking changes use a new channel; servers and mods
 * that disagree on major version simply do not communicate (no handshake
 * required). Within a channel, each packet begins with a single-byte
 * {@code PKT_*} type id, followed by a bounded payload. Unknown type ids are
 * ignored silently so new packet types roll out without breaking old clients.
 */
public final class Protocol {

    /** Major protocol channel. Breaking changes bump this to v2, v3, etc. */
    public static final Identifier CHANNEL_V1 = Identifier.of("prisonsmod", "v1");

    // --- Packet type ids (S2C) ---
    public static final byte PKT_POINT_GAIN = 1;
    public static final byte PKT_CASCADE    = 2;
    public static final byte PKT_HUD_UPDATE = 3;
    /**
     * Low-latency "you are now mining this block" hint. Emitted by the server the
     * same tick as {@code BlockDamageEvent} so the mod can begin a predicted
     * break-crack animation ~100ms before the server's normal progress packets
     * would arrive.
     */
    public static final byte PKT_MINE_START = 4;

    /**
     * "Forget the in-flight prediction at (x, y, z)" — emitted on {@code
     * BlockDamageAbortEvent} when the player releases mining before completion.
     * Mod clears the predicted crack so a single tap doesn't briefly show a
     * full break animation.
     */
    public static final byte PKT_MINE_CANCEL = 5;

    // --- Packet type ids (C2S) ---
    /** One-shot handshake sent on login so the server can flag mod presence. Has no effect on gameplay. */
    public static final byte PKT_HANDSHAKE  = 101;

    // --- Hard size caps (wire-level) ---
    /** Maximum bytes for any single S2C payload. Larger packets are dropped. */
    public static final int MAX_PAYLOAD_BYTES = 256;

    // --- Semantic bounds (validated post-decode) ---
    public static final int MAX_POINTS_PER_EVENT = 10_000_000;
    public static final int MAX_BLOCKS_PER_CASCADE = 10_000;
    public static final int MAX_RANK = 100_000;
    public static final long MAX_TIME_REMAINING_MS = 10L * 60L * 1000L;

    // --- Rate limits (per-second, receiver-enforced) ---
    public static final int RATE_POINT_GAIN_PER_SEC = 100;
    public static final int RATE_CASCADE_PER_SEC    = 10;
    public static final int RATE_HUD_UPDATE_PER_SEC = 5;
    public static final int RATE_MINE_START_PER_SEC = 40;   // theoretical max mining speed
    public static final int RATE_MINE_CANCEL_PER_SEC = 40;  // one per start at most

    // --- Renderer caps (memory bounds) ---
    public static final int MAX_FLOATING_NUMBERS_ON_SCREEN = 200;
    public static final int MAX_CASCADE_EFFECTS_QUEUED = 8;

    // --- Mining predict bounds ---
    public static final int MAX_MINE_DURATION_MS = 30_000;
    /** Predictions below this duration skip the crack ladder and fire an "insta-break" flash instead. */
    public static final int INSTA_BREAK_THRESHOLD_MS = 100;

    private Protocol() {}
}
