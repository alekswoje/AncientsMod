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
 *       server to grant rewards, mutate inventory, or influence scoring. C2S is
 *       limited to (a) a one-shot presence handshake, (b) UI-trigger requests
 *       that contain only intent (gang ping, buff-refresh, bug-report open), and
 *       (c) bug-report conversation text — all of which the server treats as
 *       untrusted display input and rate-limits hard.</li>
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
    public static final byte PKT_HUD_UPDATE = 3;
    /**
     * Server-broadcast gang ping: "a gang-mate pinged this point". The server
     * has already resolved the recipient list (sender's online gang members in
     * the same world) — anything arriving here is authoritative and the client
     * just renders it. Sender identity in the payload is display-only; the
     * server chose who receives the packet.
     */
    public static final byte PKT_GANG_PING  = 6;
    /**
     * Server-broadcast meteor landing ping: "a meteor is going to land here".
     * Rendered as a world-space beam with a label ("Meteor" / "Heroic Meteor")
     * and server-chosen colour. Unlike gang pings, lifetime is carried in the
     * payload so the server can size the beam to the actual fall time.
     */
    public static final byte PKT_METEOR_PING = 7;
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

    /**
     * Snapshot of the local player's current active gang roster (UUIDs of
     * fellow active members, excluding self). Sent periodically and on
     * membership change. Empty roster (count=0) means "no gang" — drives the
     * peaceful-PvP phase-through fade.
     */
    public static final byte PKT_GANG_ROSTER = 8;

    /**
     * One-bit "are you currently in a duel fight" hint. Used to gate the
     * peaceful-PvP phase-through so the mod never hides gang teammates that
     * are technically opponents in a duel.
     */
    public static final byte PKT_DUEL_STATE = 9;

    /**
     * Periodic snapshot of the local player's currently-active boosters.
     * Drives the moveable booster-timers HUD. Empty snapshot (count=0)
     * means "no boosters" and clears the widget.
     */
    public static final byte PKT_BOOSTER_UPDATE = 10;

    // Booster snapshot enum byte values (must match plugin PrisonsModChannel).
    public static final byte BOOSTER_SRC_GLOBAL   = 0;
    public static final byte BOOSTER_SRC_PERSONAL = 1;
    public static final byte BOOSTER_SRC_COMP     = 2;
    public static final byte BOOSTER_SRC_CHATGAME = 3;
    public static final byte BOOSTER_KIND_XP     = 0;
    public static final byte BOOSTER_KIND_ENERGY = 1;
    public static final byte BOOSTER_KIND_ORE    = 2;
    public static final byte BOOSTER_KIND_SHARD  = 3;

    /** Hard cap on entries per booster snapshot (mirrors plugin). */
    public static final int MAX_BOOSTER_ENTRIES = 16;

    /**
     * Periodic snapshot of cluster event timers (KOTH, BAH, Meteor, Rift, etc.).
     * Drives the moveable Events HUD. Same wire approach as boosters: server
     * heartbeats every second, client extrapolates between heartbeats.
     */
    public static final byte PKT_EVENT_TIMERS = 11;

    /**
     * Live snapshot of a single landed meteorite the local player has just
     * interacted with (right-click or block break). Drives the moveable
     * Meteorite HUD. {@code remaining=0} is the "destroyed" signal and clears
     * the widget when the location matches the tracked meteorite.
     *
     * <p>Wire format: {@code varint+string worldName; int x, y, z;
     * varint+string tierName; byte R, G, B; byte refined; int remaining}.
     */
    public static final byte PKT_METEORITE_HUD = 12;

    /**
     * Periodic snapshot of the local player's active cooldowns. Drives the
     * moveable Cooldowns HUD. Empty (count=0) means "no active cooldowns" and
     * collapses the widget.
     */
    public static final byte PKT_COOLDOWNS = 13;

    /**
     * Full {@code /pickbuffs} breakdown snapshot pushed when the player runs
     * {@code /pickbuffs} on a modded client, or in response to a
     * {@link #PKT_BUFF_REFRESH_REQ} from the buffs screen. Bypasses
     * {@link #MAX_PAYLOAD_BYTES} — bounded by {@link #MAX_SNAPSHOT_PAYLOAD_BYTES}.
     */
    public static final byte PKT_BUFF_SNAPSHOT = 14;

    // Cooldown categories (must match plugin PrisonsModChannel).
    public static final byte CD_CAT_COMMANDS = 0;
    public static final byte CD_CAT_COMBAT   = 1;
    public static final byte CD_CAT_ENCHANT  = 2;
    public static final byte CD_CAT_PICKAXE  = 3;

    // Cooldown ids within category.
    public static final byte CD_CMD_FIX    = 0;
    public static final byte CD_CMD_EAT    = 1;
    public static final byte CD_CMD_FEED   = 2;
    public static final byte CD_CMD_JET    = 3;
    public static final byte CD_COMBAT_TAG = 0;
    // Enchant proc ids (must match plugin).
    public static final byte CD_ENCH_DEVOUR         = 0;
    public static final byte CD_ENCH_LAST_STAND     = 1;
    public static final byte CD_ENCH_ENLIGHTEN      = 2;
    public static final byte CD_ENCH_PAINKILLER     = 3;
    public static final byte CD_ENCH_PRISMATIC_EFF  = 4;
    public static final byte CD_ENCH_BERSERK        = 5;
    public static final byte CD_ENCH_ADRENALINE     = 6;

    /** Hard cap on entries per cooldown snapshot (mirrors plugin). */
    public static final int MAX_COOLDOWN_ENTRIES = 32;

    /** Session PvE kill/drop tallies snapshot — drives the moveable Stats HUD. */
    public static final byte PKT_PVE_STATS = 15;

    /** Hard cap on rows per PvE stats snapshot (kills or drops). */
    public static final int MAX_PVE_STAT_ROWS = 64;

    /**
     * Server response to a {@link #PKT_BUGREPORT_INTENT}: opens the in-game
     * Bug Report UI populated with the sanitized snapshot the server would file.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  token         (server-issued, 1..{@link #BUGREPORT_MAX_TOKEN_CHARS})
     *   varint+string  prefill       (args from the original /bugreport)
     *   byte           sectionCount  (≤ {@link #BUGREPORT_MAX_SECTIONS})
     *   for each section:
     *     byte         sectionId     (BR_SECTION_*; unknown ids render as "Other")
     *     varint+string title        (≤ {@link #BUGREPORT_MAX_TITLE_CHARS})
     *     byte         lineCount     (≤ {@link #BUGREPORT_MAX_LINES_PER_SECTION})
     *     for each line: varint+string text (≤ {@link #BUGREPORT_MAX_LINE_CHARS})
     * </pre>
     *
     * <p>Bounded by {@link #MAX_SNAPSHOT_PAYLOAD_BYTES}. The server sanitizes
     * the snapshot (drops other players' UUIDs etc.) before sending, so the
     * open-source mod never receives server-internal identifiers.
     */
    public static final byte PKT_BUGREPORT_OPEN = 16;

    /**
     * AI reply in an in-flight bug-report conversation. Sent each time Hermes
     * returns new text for the mod's chat thread.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  token       (matches the open token; if unknown, mod drops)
     *   varint+string  message     (≤ {@link #BUGREPORT_MAX_AI_MESSAGE_CHARS})
     *   byte           status      (BR_STATUS_*)
     * </pre>
     */
    public static final byte PKT_BUGREPORT_AI_REPLY = 17;

    /**
     * Confirmation that the bug report was filed server-side. Carries the
     * {@code BR-XXXXXX} id so the mod can show it in the chat header.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  token
     *   varint+string  reportId
     * </pre>
     */
    public static final byte PKT_BUGREPORT_FILED = 18;

    /**
     * Error in the bug-report flow (token expired, rate-limited, AI offline).
     * Mod surfaces the message as a chat-thread notice; the flow continues
     * unless the user closes the screen.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  token        (may be empty if no token issued yet)
     *   varint+string  message      (≤ {@link #BUGREPORT_MAX_AI_MESSAGE_CHARS})
     * </pre>
     */
    public static final byte PKT_BUGREPORT_ERROR = 19;

    /**
     * Server response to a {@link #PKT_SUGGEST_INTENT}: open the suggestion screen
     * with the provided session token. Single string payload.
     */
    public static final byte PKT_SUGGEST_OPEN  = 20;

    /** Server confirmed the suggestion was forwarded; mod closes the screen. */
    public static final byte PKT_SUGGEST_FILED = 21;

    /**
     * Soft error in the suggest flow (rate-limited, Discord unlinked, token expired).
     * If the token is empty the mod treats it as fatal and closes the screen.
     */
    public static final byte PKT_SUGGEST_ERROR = 22;

    /**
     * Bundle of all 7 PVs' contents (material id + custom name + amount per
     * non-empty slot, plus affinity csv per vault). Drives the mod's custom
     * {@code /pv} overview screen. Bounded by {@link #MAX_PV_BUNDLE_BYTES}.
     */
    public static final byte PKT_PV_BUNDLE = 23;

    /**
     * Live mining rates snapshot (XP/h, Energy/h, $/h) for the Stats HUD
     * mining section. Server emits at 1 Hz only while a live mining window is
     * active — when the wire goes quiet, the client section stales out and
     * disappears. Wire: type byte + 3× varint (xp/h, energy/h, $/h).
     */
    public static final byte PKT_MINING_STATS = 24;

    // Suggest category enum-bytes (must match plugin PrisonsModChannel).
    public static final byte SUGGEST_CAT_MOD    = 0;
    public static final byte SUGGEST_CAT_SERVER = 1;

    // Suggest wire bounds.
    public static final int SUGGEST_MAX_TOKEN_CHARS = 32;
    public static final int SUGGEST_MAX_BODY_CHARS  = 1024;
    public static final int SUGGEST_MAX_ERROR_CHARS = 256;

    // Bug report section ids (selector for icon/colour in the UI).
    public static final byte BR_SECTION_PLAYER          = 1;
    public static final byte BR_SECTION_SERVER_STATE    = 2;
    public static final byte BR_SECTION_RECENT_COMMANDS = 3;
    public static final byte BR_SECTION_RING_BUFFER     = 4;
    public static final byte BR_SECTION_NEARBY          = 5;
    public static final byte BR_SECTION_INVENTORY_LOG   = 6;
    public static final byte BR_SECTION_INVENTORY       = 7;
    public static final byte BR_SECTION_OTHER           = 8;

    // Bug report AI reply status.
    public static final byte BR_STATUS_REPLIED   = 0;  // AI sent text, conversation open
    public static final byte BR_STATUS_RESOLVED  = 1;  // AI marked resolved; UI shows "resolved" badge
    public static final byte BR_STATUS_ESCALATED = 2;  // AI handed off; Discord ticket opened
    public static final byte BR_STATUS_ERROR     = 3;  // AI errored mid-flow; UI shows a retry hint

    // Bug-report category bits — server-side BugReportCategory enum mirrors these.
    public static final int BR_CAT_LOST_ITEMS = 1 <<  0;
    public static final int BR_CAT_DEATH_PVP  = 1 <<  1;
    public static final int BR_CAT_TELEPORT   = 1 <<  2;
    public static final int BR_CAT_ECONOMY    = 1 <<  3;
    public static final int BR_CAT_MINING     = 1 <<  4;
    public static final int BR_CAT_EVENT      = 1 <<  5;
    public static final int BR_CAT_VISUAL     = 1 <<  6;
    public static final int BR_CAT_PERF       = 1 <<  7;
    public static final int BR_CAT_OTHER      = 1 <<  8;

    // Bug-report wire bounds.
    public static final int BUGREPORT_MAX_TOKEN_CHARS = 32;
    public static final int BUGREPORT_MAX_SECTIONS = 16;
    public static final int BUGREPORT_MAX_LINES_PER_SECTION = 64;
    public static final int BUGREPORT_MAX_LINE_CHARS = 128;
    public static final int BUGREPORT_MAX_TITLE_CHARS = 48;
    public static final int BUGREPORT_MAX_PREFILL_CHARS = 256;
    public static final int BUGREPORT_MAX_DESCRIPTION_CHARS = 1024;
    public static final int BUGREPORT_MAX_AI_MESSAGE_CHARS = 4096;
    public static final int BUGREPORT_MAX_REPORT_ID_CHARS = 32;
    public static final int BUGREPORT_MAX_FOLLOWUP_CHARS = 1024;

    // Event id enum-byte values (must match plugin PrisonsModChannel). Add new
    // entries at the END to stay wire-compatible with older servers.
    public static final byte EVENT_KOTH              = 0;
    public static final byte EVENT_BAH               = 1;
    public static final byte EVENT_METEOR            = 2;
    public static final byte EVENT_RIFT              = 3;
    public static final byte EVENT_MINING_COMP       = 4;
    public static final byte EVENT_METEORITE         = 5;
    public static final byte EVENT_MINING_RUSH       = 6;
    public static final byte EVENT_HOT_ZONE          = 7;
    public static final byte EVENT_HEROIC_METEOR     = 8;
    public static final byte EVENT_ORACLE            = 9;
    public static final byte EVENT_OUTPOST           = 10;
    public static final byte EVENT_CHAT_GAMES        = 11;
    public static final byte EVENT_METEORITE_SHOWER  = 12;

    public static final byte EVENT_STATE_COUNTDOWN = 0;
    public static final byte EVENT_STATE_ACTIVE    = 1;
    public static final byte EVENT_STATE_DISABLED  = 2;
    public static final byte EVENT_STATE_UNKNOWN   = 3;

    /** Hard cap on entries per event-timers snapshot (mirrors plugin). */
    public static final int MAX_EVENT_ENTRIES = 16;

    // --- Packet type ids (C2S) ---
    /** One-shot handshake sent on login so the server can flag mod presence. Has no effect on gameplay. */
    public static final byte PKT_HANDSHAKE  = 101;
    /**
     * Client request: "I want to ping this world-space point for my gang."
     * Payload carries only coordinates + a hold-flag. Server authenticates the
     * sender from the channel connection, resolves the gang, validates range,
     * rate-limits, and broadcasts PKT_GANG_PING to recipients. Client identity
     * is NEVER taken from this payload.
     */
    public static final byte PKT_GANG_PING_REQ = (byte) 102;
    /**
     * Buffs-screen refresh request. Single-byte payload — the server identifies
     * the sender from the channel connection and resends a {@link #PKT_BUFF_SNAPSHOT}.
     * Rate-limited server-side to one per second.
     */
    public static final byte PKT_BUFF_REFRESH_REQ = (byte) 103;

    /**
     * Player ran {@code /bugreport} on a modded client. The mod intercepts the
     * command and sends this packet; the server replies with a
     * {@link #PKT_BUGREPORT_OPEN} containing the snapshot to display.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  prefillDescription   (≤ {@link #BUGREPORT_MAX_PREFILL_CHARS}, may be empty)
     * </pre>
     */
    public static final byte PKT_BUGREPORT_INTENT = (byte) 104;

    /**
     * Player clicked Submit in the bug-report UI. The server validates the
     * token (must match a live preview), files the report using its stashed
     * snapshot + the description + categories from this packet, and kicks off
     * the AI investigation.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  token        (issued by {@link #PKT_BUGREPORT_OPEN})
     *   varint         categoryMask (BR_CAT_* OR'd together)
     *   varint+string  description  (≤ {@link #BUGREPORT_MAX_DESCRIPTION_CHARS})
     * </pre>
     */
    public static final byte PKT_BUGREPORT_SUBMIT = (byte) 105;

    /**
     * Player typed a follow-up message in the chat-thread mode. The server
     * forwards it to Hermes and pushes the reply back via
     * {@link #PKT_BUGREPORT_AI_REPLY}.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  token
     *   varint+string  message   (≤ {@link #BUGREPORT_MAX_FOLLOWUP_CHARS})
     * </pre>
     */
    public static final byte PKT_BUGREPORT_FOLLOWUP = (byte) 106;

    /**
     * Player clicked "Talk to staff". The server asks Hermes to open a Discord
     * ticket with the full transcript so a human can take over.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  token
     * </pre>
     */
    public static final byte PKT_BUGREPORT_ESCALATE = (byte) 107;

    /**
     * Player closed the UI (either via "Mark resolved" or by dismissing). The
     * server marks the conversation closed and frees the preview token.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  token
     *   byte           resolved   (1 if user clicked "Mark resolved", 0 if just dismissed)
     * </pre>
     */
    public static final byte PKT_BUGREPORT_CLOSE = (byte) 108;

    /**
     * Player ran {@code /suggest}. The mod intercepts the command and sends this
     * (no payload — server identifies the player from the connection). Server
     * replies with {@link #PKT_SUGGEST_OPEN} on success or {@link #PKT_SUGGEST_ERROR}.
     */
    public static final byte PKT_SUGGEST_INTENT = (byte) 109;

    /**
     * Player clicked Submit in the suggest UI.
     * <p>Wire format after the type byte:
     * <pre>
     *   varint+string  token       (issued by {@link #PKT_SUGGEST_OPEN})
     *   byte           category    ({@link #SUGGEST_CAT_MOD} or {@link #SUGGEST_CAT_SERVER})
     *   varint+string  body        (≤ {@link #SUGGEST_MAX_BODY_CHARS})
     * </pre>
     */
    public static final byte PKT_SUGGEST_SUBMIT = (byte) 110;

    /** Player dismissed the suggest UI without submitting. */
    public static final byte PKT_SUGGEST_CLOSE  = (byte) 111;
    /**
     * Player ran {@code /pv} (no args) on a modded client. The mod intercepts
     * the command and sends this; the server replies with {@link #PKT_PV_BUNDLE}
     * containing all 7 PVs' summarized contents.
     */
    public static final byte PKT_PV_BUNDLE_REQ = (byte) 112;
    /**
     * Player clicked a vault card in the mod's overview. Server opens the
     * vault chest GUI with {@code reopenMenuOnClose=true} so closing returns
     * to the PersonalVaultMenu (which the mod intercepts and swaps for a
     * fresh overview). Wire format: single byte vault (1..7).
     */
    public static final byte PKT_PV_OPEN_REQ = (byte) 113;
    /**
     * Player right-clicked a vault card in the mod's overview — open the
     * affinity picker for that vault. Wire format: single byte vault (1..7).
     */
    public static final byte PKT_PV_AFFINITY_OPEN_REQ = (byte) 114;
    /** Toggle a single category on a vault. Server enforces exclusivity and
     *  replies with PKT_PV_BUNDLE. Wire: byte vault, varint+string key. */
    public static final byte PKT_PV_AFFINITY_TOGGLE = (byte) 115;
    /** Clear every affinity bound to a vault. Wire: byte vault. */
    public static final byte PKT_PV_AFFINITY_CLEAR = (byte) 116;
    /** Apply an affinity preset (overwrites every unlocked PV's affinities).
     *  Wire: varint+string presetKey. */
    public static final byte PKT_PV_APPLY_PRESET = (byte) 117;
    /** Trigger /pvsort and re-send the bundle so the overview refreshes
     *  in place. No payload. */
    public static final byte PKT_PV_SORT_REQ = (byte) 118;
    /**
     * Report the current state of the client-side booster HUD toggle. Server
     * uses this to default the action-bar booster line OFF while the mod is
     * rendering boosters in its widget — players can still flip it back on
     * via {@code /toggles} → Action Bar → Show Boosters.
     *
     * <p>Sent on every join (right after the handshake) and whenever the toggle
     * flips. Wire format: type byte + single state byte (1 = HUD on, 0 = off).
     */
    public static final byte PKT_BOOSTER_HUD_STATE = (byte) 119;
    /** Sent by the shift-click mixin when the player shift-clicks a player-
     *  inventory slot while a PV menu is open. The server runs affinity
     *  routing in place of the vanilla container click, so the client never
     *  applies its predicted "item lands in open container" state — no
     *  flicker as the server corrects the prediction. Wire: type byte +
     *  {@code int playerInvSlot} (0..35, Bukkit ordering — hotbar 0..8,
     *  main inv 9..35). */
    public static final byte PKT_PV_SHIFT_CLICK_REQ = (byte) 120;

    /** Swap two vaults' contents + affinities. Sent by the overview screen on
     *  drag-drop. Wire: byte fromVault, byte toVault. Server replies with a
     *  fresh PKT_PV_BUNDLE so the screen re-renders. */
    public static final byte PKT_PV_SWAP_REQ = (byte) 121;

    /**
     * Report the current state of the Stats HUD mining-section toggle. Server
     * uses this to default the action-bar XP/h / Energy/h / $/h trio OFF while
     * the mod is rendering those same values in its widget — players can still
     * flip it back on via {@code /toggles} → Action Bar → Show Rates.
     *
     * <p>Sent on every join (right after the handshake) and whenever the toggle
     * flips. Wire format: type byte + single state byte (1 = section on, 0 = off).
     */
    public static final byte PKT_MINING_HUD_STATE = (byte) 122;

    /**
     * Report whether the PV-overview / affinity-routing feature is enabled
     * in the mod's settings. Server uses this to gate affinity routing
     * (shift-click cascade, /pvsort, preset apply) — when the player turns
     * this off, the server falls back to vanilla shift-click behavior so
     * items aren't silently rerouted by stale affinity bindings.
     *
     * <p>Sent on every join (right after the handshake) and whenever the
     * toggle flips. Wire format: type byte + single state byte
     * (1 = features on, 0 = off).
     */
    public static final byte PKT_PV_FEATURES_STATE = (byte) 123;

    // --- Hard size caps (wire-level) ---
    /** Maximum bytes for any single cosmetic S2C payload. Larger packets are dropped. */
    public static final int MAX_PAYLOAD_BYTES = 256;
    /** Larger cap reserved for {@link #PKT_BUFF_SNAPSHOT}, which carries every layer with a label. */
    public static final int MAX_SNAPSHOT_PAYLOAD_BYTES = 16_384;
    /** Even larger cap reserved for {@link #PKT_PV_BUNDLE}: 7 vaults × up to
     *  162 slots each can produce dense payloads. */
    public static final int MAX_PV_BUNDLE_BYTES = 65_536;

    // --- PV bundle bounds ---
    // Set well above the current server-side cap so future bumps don't break
    // older jars. The decoder rejects bundles only when the server claims
    // MORE vaults than this — a tighter cap would force a coordinated
    // re-release of the mod every time VAULTS_PER_PLAYER goes up.
    public static final int PV_MAX_VAULTS = 64;
    public static final int PV_MAX_SLOTS = 162; // 6 rows × 9 cols × multiple rows of extras
    public static final int PV_MAX_MATERIAL_KEY_CHARS = 48;
    public static final int PV_MAX_DISPLAY_NAME_CHARS = 64;
    public static final int PV_MAX_AFFINITY_CSV_CHARS = 256;

    // --- Semantic bounds (validated post-decode) ---
    public static final int MAX_POINTS_PER_EVENT = 10_000_000;
    public static final int MAX_RANK = 100_000;
    public static final long MAX_TIME_REMAINING_MS = 10L * 60L * 1000L;

    // --- Rate limits (per-second, receiver-enforced) ---
    public static final int RATE_POINT_GAIN_PER_SEC = 100;
    public static final int RATE_HUD_UPDATE_PER_SEC = 5;
    public static final int RATE_MINE_START_PER_SEC = 40;   // theoretical max mining speed
    public static final int RATE_MINE_CANCEL_PER_SEC = 40;  // one per start at most
    /** Max inbound pings per second — bounds renderer state if a server misbehaves. */
    public static final int RATE_GANG_PING_PER_SEC = 10;
    /** Max inbound meteor pings per second. */
    public static final int RATE_METEOR_PING_PER_SEC = 5;
    /** Roster + duel state are low-frequency keepalives — a handful per second is the ceiling. */
    public static final int RATE_GANG_ROSTER_PER_SEC = 4;
    public static final int RATE_DUEL_STATE_PER_SEC = 4;
    /** Booster heartbeat is 1 Hz from the server; ceiling at 5 absorbs jitter without letting a misbehaving server flood the renderer. */
    public static final int RATE_BOOSTER_UPDATE_PER_SEC = 5;
    /** Event timer heartbeat — same shape as boosters. */
    public static final int RATE_EVENT_TIMERS_PER_SEC = 5;
    /** Cooldowns heartbeat — same shape as boosters. */
    public static final int RATE_COOLDOWNS_PER_SEC = 5;
    /** PvE stats heartbeat — same shape. */
    public static final int RATE_PVE_STATS_PER_SEC = 5;
    /** Mining stats heartbeat — same shape. */
    public static final int RATE_MINING_STATS_PER_SEC = 5;
    /** Per-block-break + right-click. A meteorite is 200–300 blocks; cap at theoretical max mining cadence. */
    public static final int RATE_METEORITE_HUD_PER_SEC = 40;
    /** Buff snapshot is on-demand (only on /pickbuffs or refresh-button). */
    public static final int RATE_BUFF_SNAPSHOT_PER_SEC = 5;
    /** Bug-report inbound packets are user-driven; a handful per second absorbs jitter. */
    public static final int RATE_BUGREPORT_PER_SEC = 5;
    /** Suggest inbound packets are user-driven; a handful per second is plenty. */
    public static final int RATE_SUGGEST_PER_SEC = 5;
    /** PV bundle is on-demand only (one packet per /pv intercept) — cap low. */
    public static final int RATE_PV_BUNDLE_PER_SEC = 3;

    // --- Meteorite HUD tunables ---
    /** Max tier-name length the server can send us (e.g. "Ancient Debris" → 14). */
    public static final int METEORITE_HUD_MAX_TIER_CHARS = 16;
    /** Max world-name length (matches gang/meteor ping conventions). */
    public static final int METEORITE_HUD_MAX_WORLD_CHARS = 32;
    /** Defensive upper bound on remaining count — meteorites cap at 300, this leaves room. */
    public static final int METEORITE_HUD_MAX_REMAINING = 100_000;
    /** If no fresh packet arrives within this window, the HUD self-clears. Safety net for clients that miss the destroyed broadcast. */
    public static final long METEORITE_HUD_STALE_AFTER_MS = 30_000L;

    /** Active gang on the season2 cluster maxes at 4 members. Decoder hard-caps at this to bound memory. */
    public static final int MAX_GANG_ROSTER_MEMBERS = 8;

    // --- Gang ping tunables ---
    /** Maximum blocks from the sender to the ping target (matches server validation). */
    public static final double GANG_PING_MAX_RADIUS = 100.0;
    /** Hold duration before the keybind switches from "ping at feet" to "ping at cursor". */
    public static final long GANG_PING_HOLD_THRESHOLD_MS = 200L;
    /** Ping render lifetime (fades out over the final quarter). */
    public static final long GANG_PING_LIFETIME_MS = 30_000L;
    /** Hard cap on the sender display name the server can send us. */
    public static final int GANG_PING_MAX_NAME_CHARS = 16;
    /** Hard cap on the internal world name attached to a ping. */
    public static final int GANG_PING_MAX_WORLD_CHARS = 32;

    // --- Meteor ping tunables ---
    /** Server-provided lifetime is clamped to this range on decode. */
    public static final int METEOR_PING_MIN_LIFETIME_MS = 1_000;
    public static final int METEOR_PING_MAX_LIFETIME_MS = 600_000;

    // --- Renderer caps (memory bounds) ---
    public static final int MAX_FLOATING_NUMBERS_ON_SCREEN = 200;

    // --- Mining predict bounds ---
    public static final int MAX_MINE_DURATION_MS = 30_000;
    /** Predictions below this duration skip the crack ladder and fire an "insta-break" flash instead. */
    public static final int INSTA_BREAK_THRESHOLD_MS = 100;

    private Protocol() {}
}
