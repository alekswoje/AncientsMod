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
    public static final byte CD_CMD_FIXALL = 4;
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
     * non-empty slot, plus a now-vestigial empty affinity-csv field per vault
     * kept for wire compatibility). Drives the mod's {@code /pv} terminal +
     * card views. Bounded by {@link #MAX_PV_BUNDLE_BYTES}.
     */
    public static final byte PKT_PV_BUNDLE = 23;

    /**
     * S2C — open the PV terminal pointed at another player's vaults (admin
     * {@code /pvsee}). The target's bundle follows immediately as a
     * {@link #PKT_PV_BUNDLE}. Carries no UUID — only a display name + editable
     * flag; the server tracks the real target and re-checks permission per
     * action. Wire: {@code varint+string targetName; byte editable}.
     * Byte 36 is free on both the master and season2 schemes (35 is
     * PKT_MINING_BLOCKS).
     */
    public static final byte PKT_PV_OPEN_TERMINAL = 36;
    /** Max chars for the target name in {@link #PKT_PV_OPEN_TERMINAL}. */
    public static final int PV_OPEN_TERMINAL_MAX_NAME_CHARS = 16;

    /**
     * S2C — one chunk of a PV bundle too large to fit a single {@link #PKT_PV_BUNDLE}
     * packet. Same scheme as {@link #PKT_LOOT_SNAPSHOT_CHUNK}: the server splits the body
     * (everything a single PKT_PV_BUNDLE carries after its type byte) into ordered chunks;
     * the mod reassembles by {@code version} and decodes the reassembled body with the same
     * {@link com.aleks.prisonsmod.net.payload.PvBundlePayload} decoder. The server only
     * chunks for clients that advertise {@link #PROTOCOL_MINOR} ≥ 1 in their handshake, so
     * an older jar that doesn't handle this id keeps getting single packets (no break).
     * Wire per chunk: {@code int version; varint chunkIndex; varint chunkCount; varint len; byte[len] body}.
     * Bounded by {@link #MAX_PV_BUNDLE_CHUNK_BYTES} per packet, {@link #MAX_PV_BUNDLE_TOTAL_BYTES} total.
     * Byte 37 is free on both the master and season2 schemes — keep it that way on merge.
     */
    public static final byte PKT_PV_BUNDLE_CHUNK = 37;

    /**
     * Live mining rates snapshot (XP/h, Energy/h, $/h) for the Stats HUD
     * mining section. Server emits at 1 Hz only while a live mining window is
     * active — when the wire goes quiet, the client section stales out and
     * disappears. Wire: type byte + 3× varint (xp/h, energy/h, $/h).
     */
    public static final byte PKT_MINING_STATS = 24;

    /**
     * Fullbright blacklist for the mod's client-side fullbright feature. List
     * of Bukkit world names where fullbright should NOT activate. Sent once
     * after the handshake. Wire: type byte + varint count + repeating
     * varint+UTF8 world name. Empty list = fullbright everywhere.
     */
    public static final byte PKT_FULLBRIGHT_BLACKLIST = 25;

    /** Hard cap on worlds per blacklist packet (mirrors plugin). */
    public static final int MAX_FULLBRIGHT_WORLDS = 32;
    /** Hard cap on a single blacklisted world name length (mirrors plugin). */
    public static final int MAX_FULLBRIGHT_WORLD_NAME_CHARS = 64;

    /**
     * S2C — per-player Tartarus Rift daily-time HUD state (mirrors plugin
     * PrisonsModChannel#PKT_RIFT_BUDGET). Replaces the rift row in the Events
     * HUD now that the rift is a personal daily activity. Wire after the type
     * byte: byte flags (bit0=available, bit1=consuming), varint remainingSeconds,
     * varint secondsUntilReset.
     */
    public static final byte PKT_RIFT_BUDGET = 26;

    /**
     * S2C — full dungeon skill tree layout. Pushed when a player clicks
     * "Tartarus Vision" on the Oracle NPC. Bounded by
     * {@link #MAX_SKILLTREE_PAYLOAD_BYTES}. The mod's screen opens on receipt
     * and renders the tree using the bundled layout (positions, names,
     * effects, adjacency).
     */
    public static final byte PKT_SKILLTREE_OPEN = 28;

    /**
     * S2C — per-player skill-tree state (unlocked set + banked/available/spent
     * points + live respec cost). Sent immediately after a SKILLTREE_OPEN
     * and on every successful allocate / refund / respec (mod- or chisel-
     * driven). The mod's open screen re-renders live on each push.
     */
    public static final byte PKT_SKILLTREE_STATE = 29;

    /**
     * S2C — result of a mod-initiated allocate / refund / respec. Mostly used
     * for failure toasts; success is implied by the matching STATE push.
     */
    public static final byte PKT_SKILLTREE_ACK = 30;

    // Skill tree wire bounds (mirror plugin PrisonsModChannel).
    public static final int MAX_SKILLTREE_PAYLOAD_BYTES = 32_768;
    public static final int SKILLTREE_MAX_NODES = 512;
    public static final int SKILLTREE_MAX_EDGES = 1024;
    public static final int SKILLTREE_MAX_NODE_ID_CHARS = 32;
    public static final int SKILLTREE_MAX_NODE_NAME_CHARS = 48;

    // Branch byte values (must match plugin BRANCH_*).
    public static final byte BRANCH_GATE      = 0;
    public static final byte BRANCH_ASSAULT   = 1;
    public static final byte BRANCH_ENDURANCE = 2;
    public static final byte BRANCH_AGILITY   = 3;
    public static final byte BRANCH_FORTUNE   = 4;

    // Skill effect type bytes (must match plugin SKILL_EFFECT_*).
    public static final byte SKILL_EFFECT_DUNGEON_DAMAGE_PCT            = 0;
    public static final byte SKILL_EFFECT_DUNGEON_BOSS_DAMAGE_PCT       = 1;
    public static final byte SKILL_EFFECT_DUNGEON_DAMAGE_REDUCTION_PCT  = 2;
    public static final byte SKILL_EFFECT_DUNGEON_MAX_HP_FLAT           = 3;
    public static final byte SKILL_EFFECT_DUNGEON_MOVE_SPEED_PCT        = 4;
    public static final byte SKILL_EFFECT_DUNGEON_JUMP_BOOST_FLAT       = 5;
    public static final byte SKILL_EFFECT_CHEST_COST_REDUCTION_PCT      = 6;
    public static final byte SKILL_EFFECT_RUNE_RARITY_UPGRADE_CHANCE    = 7;
    public static final byte SKILL_EFFECT_BONUS_RUNE_DROP_CHANCE        = 8;
    public static final byte SKILL_EFFECT_DUNGEON_LIFESTEAL_PCT         = 9;
    public static final byte SKILL_EFFECT_DUNGEON_CULLING_THRESHOLD_PCT = 10;
    public static final byte SKILL_EFFECT_DUNGEON_DOUBLE_JUMP_FLAT      = 11;

    // Skill tree ACK action codes.
    public static final byte SKILL_ACTION_ALLOCATE = 0;
    public static final byte SKILL_ACTION_REFUND   = 1;
    public static final byte SKILL_ACTION_RESPEC   = 2;

    // Skill tree ACK result codes.
    public static final byte SKILL_RESULT_SUCCESS           = 0;
    public static final byte SKILL_RESULT_ALREADY_UNLOCKED  = 1;
    public static final byte SKILL_RESULT_PREREQ_MISSING    = 2;
    public static final byte SKILL_RESULT_NOT_ENOUGH_POINTS = 3;
    public static final byte SKILL_RESULT_NOT_UNLOCKED      = 4;
    public static final byte SKILL_RESULT_HAS_DEPENDENTS    = 5;
    public static final byte SKILL_RESULT_NOT_ENOUGH_MONEY  = 6;
    public static final byte SKILL_RESULT_INVALID           = 7;
    public static final byte SKILL_RESULT_NOTHING_TO_RESPEC = 8;
    public static final byte SKILL_RESULT_ECONOMY_ERROR     = 9;

    /**
     * One chunk of the loot-browser catalog snapshot (server → mod), pushed in
     * response to {@link #PKT_LOOT_REQ} and again on {@code /loottablesplit
     * reload} or a fresh discovery. The full body is split into ordered chunks;
     * the mod reassembles by {@code version} and decodes once the last chunk
     * arrives. Wire per chunk:
     * {@code int version; varint chunkIndex; varint chunkCount; varint len; byte[len] body}.
     * Bounded by {@link #MAX_LOOT_CHUNK_BYTES} per packet, {@link #MAX_LOOT_SNAPSHOT_BYTES} total.
     * NB: byte 25 is PKT_FULLBRIGHT_BLACKLIST on this season2 branch, so the loot
     * snapshot is renumbered 25 → 31 here to match the season2 plugin. Public
     * master uses 25.
     */
    public static final byte PKT_LOOT_SNAPSHOT_CHUNK = 31;

    /**
     * Server → client: the set of custom item textures this player has turned
     * off in {@code /toggles → Custom Textures}. The mod ignores those (item,
     * CMD) pairs at model-resolution time so the items render vanilla.
     *
     * <p>Wire format: {@code varint count; for each: string itemId (≤64), varint cmd}.
     * Re-sent on join and on every toggle change.
     */
    public static final byte PKT_DISABLED_TEXTURES = 32;

    public static final int MAX_DISABLED_TEXTURES = 128;

    /**
     * Periodic snapshot of all outpost states (name, gang, capture %). Drives the
     * moveable Outpost HUD. Wire: type byte + count byte + per-outpost (id string,
     * gangName string, percent byte, flags byte). Empty gangName = neutral.
     */
    public static final byte PKT_OUTPOST_STATE = 33;

    public static final int MAX_OUTPOST_ENTRIES    = 8;
    public static final int OUTPOST_MAX_ID_CHARS   = 16;
    public static final int OUTPOST_MAX_GANG_CHARS = 16;
    public static final int RATE_OUTPOST_STATE_PER_SEC = 5;
    /** flags bit2: outpost is fully owned by the player's own gang. */
    public static final int OUTPOST_FLAG_OWN_GANG  = 4;

    /**
     * S2C — Powerball fireball render hint. Lets the mod draw the bouncing
     * fireball client-side so the server stops spawning a per-ball ItemDisplay
     * and stops streaming a per-tick entity-move packet to the miner. Path stays
     * server-authoritative: straight-line travel is extrapolated client-side and
     * the server pushes one update per bounce. After the type byte: a sub-op,
     * then per op:
     * <pre>
     *   SPAWN  : varint wireId; double x,y,z; float vx,vy,vz; varint lifetimeMs
     *   BOUNCE : varint wireId; double x,y,z; float vx,vy,vz
     *   DESPAWN: varint wireId; byte fizzle
     * </pre>
     * Byte 27 is free on both the master and season2 server schemes — keep it so.
     */
    public static final byte PKT_POWERBALL = 27;
    public static final byte POWERBALL_OP_SPAWN   = 0;
    public static final byte POWERBALL_OP_BOUNCE  = 1;
    public static final byte POWERBALL_OP_DESPAWN = 2;

    /**
     * Server-broadcast mining-rush ping: "a mining rush block spawned here".
     * Same wire format and renderer path as {@link #PKT_METEOR_PING} — a
     * world-space beam + HUD label with a server-chosen per-tier ore colour and
     * a payload-carried lifetime sized to the rush's expiry window. The server
     * only sends this to players of the rush's tier, so each player sees the
     * beam for the mine they can actually rush. Client-gated by the
     * "Mining rush pings" toggle (dropped at intake when off).
     *
     * <p>Byte 34 is free on BOTH the master/dev scheme (tops out at 33) and the
     * season2 scheme (28-30 = skilltree, 31 = loot, 33 = outpost), so this id
     * needs no renumber when merging dev→season2 — same anchor strategy as
     * {@link #PKT_POWERBALL}. Keep it that way.
     */
    public static final byte PKT_MINING_RUSH_PING = 34;

    /**
     * Per-ore mined-block counts (lifetime on the held pickaxe + this-session)
     * for the Stats HUD "blocks" section. Server emits at 1 Hz only while a live
     * mining window is active — when the wire goes quiet, the section stales out.
     * Wire: type byte + {@code byte count; for each: varint+string oreId (Bukkit
     * Material name), varint lifetime, varint session}.
     *
     * <p>Byte 35 is free on BOTH the master/dev scheme (tops at 34 = mining-rush)
     * and the season2 scheme (28-33), so it needs no renumber when merging
     * dev→season2 — same anchor strategy as {@link #PKT_POWERBALL}. Keep it so.
     */
    public static final byte PKT_MINING_BLOCKS = 35;

    /** Hard cap on per-ore rows in a mining-blocks snapshot (mirrors plugin). */
    public static final int MAX_MINING_BLOCK_ROWS = 32;
    /** Max ore-id (Bukkit Material name) length accepted on the wire. */
    public static final int MINING_BLOCK_MAX_ID_CHARS = 48;

    /**
     * Manual mining-session snapshot (running totals + elapsed clock) for the
     * Stats HUD "session" section, driven by the player's {@code /miningtrack
     * start|stop|reset}. Unlike {@link #PKT_MINING_STATS} / {@link #PKT_MINING_BLOCKS},
     * the server emits this at 1 Hz for as long as a session <i>exists</i>
     * (running OR stopped) — so the frozen totals stay on screen after a stop —
     * and goes quiet only on reset/quit, at which point the section stales out.
     *
     * <p>Wire after the type byte: {@code byte state (1=running, 2=stopped);
     * varlong elapsedMs; varlong totalXp; varlong totalEnergy; varlong totalMoney;
     * varlong totalBlocks}.
     *
     * <p>Byte 38 is free on BOTH the master/dev scheme (tops at 37) and the
     * season2 scheme, so it needs no renumber when merging dev→season2 — same
     * anchor strategy as {@link #PKT_MINING_BLOCKS}. Keep it that way.
     */
    public static final byte PKT_MINING_SESSION = 38;

    public static final byte MINING_SESSION_STATE_RUNNING = 1;
    public static final byte MINING_SESSION_STATE_STOPPED = 2;

    // ── Cell Vault Terminal (S2C 41-44, C2S 137-144 — wire spec v1) ─────────

    /**
     * S2C — open the Cell Vault Terminal: an aggregated, searchable grid of ALL
     * containers in the player's cell (vault + chests + barrels), in the PV
     * terminal's style. Pushed by the server after it cancels the vanilla
     * vault-chest open for a modded player — there is NO client-side command or
     * screen interception for this feature (purely server-push, like the
     * {@code /pvsee} flow). A {@link #PKT_CELLTERM_BUNDLE} (or its chunked
     * equivalent) follows immediately and force-opens the screen.
     *
     * <p>Wire after the type byte: {@code varint+string cellLabel} (≤
     * {@link #CELLTERM_MAX_CELL_LABEL_CHARS} chars, legacy colour codes allowed,
     * client renders it verbatim); {@code byte flags} (bit0 = editable; always
     * 1 for now).
     *
     * <p>Byte 41 is free on ALL THREE schemes (mod master, PrisonsCore dev,
     * PrisonsCore season2) — keep it that way on merge. The whole id block
     * (S2C 41-44, C2S 137-144) is gated server-side on the handshake's
     * {@link #PROTOCOL_MINOR} ≥ 2.
     */
    public static final byte PKT_CELLTERM_OPEN = 41;

    /**
     * S2C — full snapshot of the cell's containers (server-authoritative; the
     * client discards all optimistic predictions whenever one lands). Wire
     * after the type byte:
     * <pre>
     *   varint containerCount            (≤ {@link #CELLTERM_MAX_CONTAINERS})
     *   per container:
     *     varint containerId             (stable within a session; vault is always id 0)
     *     varint+string label            (≤ {@link #CELLTERM_MAX_CONTAINER_LABEL_CHARS}: "Vault", "Chest 2", "Barrel 1")
     *     short slotCount                (≤ {@link #CELLTERM_MAX_SLOTS_PER_CONTAINER})
     *     short nonEmptyCount            (≤ slotCount)
     *     per non-empty slot: IDENTICAL codec to PV bundle slots
     *       (short slotIndex; varint+string materialKey; varint+string displayName;
     *        int amount; byte loreCount; per lore line varint+string)
     * </pre>
     * Single-packet cap = {@link #MAX_PV_BUNDLE_BYTES} (shared with the PV
     * bundle); larger bodies arrive split across
     * {@link #PKT_CELLTERM_BUNDLE_CHUNK}. Byte 42 is free on all three schemes
     * — keep it that way on merge.
     */
    public static final byte PKT_CELLTERM_BUNDLE = 42;

    /**
     * S2C — one chunk of an oversized cell-terminal bundle. Same scheme (and
     * shared size caps) as {@link #PKT_PV_BUNDLE_CHUNK}: {@code int version;
     * varint chunkIndex; varint chunkCount; varint len; byte[len] body};
     * reassembled = the body a single {@link #PKT_CELLTERM_BUNDLE} carries
     * after its type byte. Chunk bodies are 24 KiB, total ≤
     * {@link #MAX_PV_BUNDLE_TOTAL_BYTES}, ≤ {@link #PV_BUNDLE_MAX_CHUNKS}
     * chunks. Minor ≥ 2 clients always support chunking (no legacy fallback
     * needed). Byte 43 is free on all three schemes — keep it that way on merge.
     */
    public static final byte PKT_CELLTERM_BUNDLE_CHUNK = 43;

    /**
     * S2C — server force-closes the cell-terminal screen/session (cell sold,
     * permissions changed, etc.). Wire after the type byte:
     * {@code varint+string reason} (≤ {@link #CELLTERM_MAX_CLOSE_REASON_CHARS},
     * may be empty; the client shows a non-empty reason to the player). The
     * client must NOT echo a C2S {@link #PKT_CELLTERM_CLOSE_C2S} back. Byte 44
     * is free on all three schemes — keep it that way on merge.
     *
     * <p>(Spec name on both sides is PKT_CELLTERM_CLOSE; this constant carries
     * no suffix collision only because the C2S one is named
     * {@link #PKT_CELLTERM_CLOSE_C2S} here.)
     */
    public static final byte PKT_CELLTERM_CLOSE = 44;

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
    /** One-shot handshake sent on login so the server can flag mod presence. Has no effect on gameplay.
     *  Carries a trailing {@link #PROTOCOL_MINOR} byte that newer servers read for feature gating;
     *  older servers stop at the type byte and ignore it. */
    public static final byte PKT_HANDSHAKE  = 101;
    /** Mod protocol MINOR version, sent as the byte after {@link #PKT_HANDSHAKE}. Bumped within the
     *  same major (channel {@code prisonsmod:v1}) when the client gains the ability to handle a new
     *  server→client packet additively. Minor 1 = can reassemble {@link #PKT_PV_BUNDLE_CHUNK}.
     *  Minor 2 = client understands the cell-terminal packets (S2C 41-44, C2S 137-144). */
    public static final int PROTOCOL_MINOR = 2;
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
    // C2S bytes 114-118 were the PV affinity/sort packets (AFFINITY_OPEN_REQ,
    // AFFINITY_TOGGLE, AFFINITY_CLEAR, APPLY_PRESET, SORT_REQ). The affinity +
    // /pvsort system was removed (replaced by the PV terminal); these ids are
    // now free.
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
    /** Terminal deposit: the player shift-clicked a player-inventory slot while
     *  the PV terminal screen is open. The server pushes that stack into the
     *  first accessible vault with space (merge-then-fill, no affinity) and
     *  replies with a fresh PKT_PV_BUNDLE. Wire: type byte +
     *  {@code int playerInvSlot} (0..35, Bukkit ordering — hotbar 0..8,
     *  main inv 9..35). */
    public static final byte PKT_PV_SHIFT_CLICK_REQ = (byte) 120;

    /** Swap two vaults' contents. Sent by the overview screen on drag-drop.
     *  Wire: byte fromVault, byte toVault. Server replies with a fresh
     *  PKT_PV_BUNDLE so the screen re-renders. */
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

    // C2S byte 123 was PKT_PV_FEATURES_STATE (gated the now-removed affinity
    // routing). Retired with the affinity system.

    /** C2S — player clicked "Tartarus Vision" on the Oracle NPC or asked the
     *  mod to reopen the screen. Server replies with PKT_SKILLTREE_OPEN +
     *  PKT_SKILLTREE_STATE. No payload. */
    public static final byte PKT_SKILLTREE_OPEN_REQ  = (byte) 124;

    /** C2S — allocate a node. Wire: varint+string nodeId. Server replies with
     *  PKT_SKILLTREE_ACK + PKT_SKILLTREE_STATE. */
    public static final byte PKT_SKILLTREE_ALLOCATE  = (byte) 125;

    /** C2S — refund a single node (free, no money cost). Same wire shape. */
    public static final byte PKT_SKILLTREE_REFUND    = (byte) 126;

    /** C2S — full respec (charges money per dungeon level). No payload. */
    public static final byte PKT_SKILLTREE_RESPEC    = (byte) 127;

    // NB: bytes 124-127 are the skill-tree C2S packets on this season2 branch.
    // Public master assigns 124-128 to the PV-extract / loot packets below; to
    // avoid a collision they are renumbered to 128-132 here, matching the
    // season2 plugin (PrisonsModChannel). Public master uses 124-128.
    /**
     * Player clicked a tile in the PV terminal view: pull from a specific
     * vault slot into the player's inventory. Server reads the slot, computes
     * the amount based on click mode, moves what fits into the player's
     * inventory, leaves the remainder in the PV, and pushes a fresh
     * {@link #PKT_PV_BUNDLE}.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   byte   vault       (1..PV_MAX_VAULTS)
     *   short  slot        (0..PV_MAX_SLOTS-1)
     *   byte   amountMode  (PV_EXTRACT_*)
     *   byte   target      (PV_TARGET_*)
     * </pre>
     */
    public static final byte PKT_PV_EXTRACT_REQ = (byte) 128;

    /** Extract one item from the stack. */
    public static final byte PV_EXTRACT_ONE  = 0;
    /** Extract half the stack, rounded up. Non-stackable items pull as 1. */
    public static final byte PV_EXTRACT_HALF = 1;
    /** Extract the entire stack. */
    public static final byte PV_EXTRACT_ALL  = 2;
    /** Extract a single full stack (maxStackSize). Used by Shift+L on an
     *  aggregated {@link #PKT_PV_EXTRACT_ITEM} tile so each shift-click pulls one
     *  stack into the inventory, not the whole merged item. */
    public static final byte PV_EXTRACT_STACK = 3;

    /** Pull onto the player's cursor (ME-terminal pickup). */
    public static final byte PV_TARGET_CURSOR = 0;
    /** Pull straight into the player's inventory (vanilla shift-click bulk move). */
    public static final byte PV_TARGET_INV    = 1;

    /**
     * Player clicked a tile in the PV terminal that aggregates the same item
     * across several PV slots into a single tile. Unlike {@link #PKT_PV_EXTRACT_REQ}
     * (one slot), the server pulls the mode-determined amount across <b>every</b>
     * matching slot in all the owner's PVs in one atomic transaction, then pushes
     * a single fresh {@link #PKT_PV_BUNDLE} — so grabbing a merged stack is one
     * packet + one bundle, not N. The reference {@code (vault, slot)} only names
     * which item to match; identity is never trusted from the client.
     *
     * <p>Wire format after the type byte:
     * <pre>
     *   byte   refVault    (1..PV_MAX_VAULTS)
     *   short  refSlot     (0..PV_MAX_SLOTS-1)
     *   byte   amountMode  (PV_EXTRACT_*)
     *   byte   target      (PV_TARGET_*)
     * </pre>
     *
     * <p>Reuses C2S byte 114 (one of the freed PV affinity ids); free on both
     * the master and season2 schemes — keep it that way on merge.
     */
    public static final byte PKT_PV_EXTRACT_ITEM = (byte) 114;

    /**
     * Place the player's cursor stack into a specific player-inventory slot
     * (ME-terminal style: pick up from a tile, click a slot to drop). Server
     * swaps if the target slot is occupied by a different item, merges if same.
     * Wire: {@code byte invSlot} (0..35, Bukkit ordering).
     */
    public static final byte PKT_PV_CURSOR_PLACE_INV = (byte) 129;

    /**
     * Return whatever is on the player's cursor back into a PV (or their
     * inventory) — sent when the terminal screen closes with a non-empty
     * cursor so picked-up items are never lost. No payload.
     */
    public static final byte PKT_PV_CURSOR_RETURN = (byte) 130;

    /**
     * The admin closed the {@code /pvsee} terminal — end the server-side session
     * so subsequent PV packets act on the admin's own vaults again. No payload.
     * Byte 134 is free on both the master and season2 schemes.
     */
    public static final byte PKT_PV_PVSEE_CLOSE = (byte) 134;

    /**
     * Player ran {@code /loottables} (or its {@code /loot} alias) on a modded
     * client. The mod intercepts the command and sends this (no payload — the
     * server identifies the player from the connection); the server registers
     * the player as a loot-browser viewer and replies with a chunked
     * {@link #PKT_LOOT_SNAPSHOT_CHUNK} catalog.
     */
    public static final byte PKT_LOOT_REQ = (byte) 131;
    /** Player closed the mod loot browser. Server deregisters them so reload /
     *  discovery pushes stop. No payload. */
    public static final byte PKT_LOOT_CLOSE = (byte) 132;

    /** C2S — report whether the mod renders Powerball client-side (1=on, 0=off).
     *  When on, the server suppresses the server-side ItemDisplay + trail for
     *  this player's balls and sends {@link #PKT_POWERBALL} hints instead. Sent
     *  on join after the handshake and on every toggle flip. Byte 133 is free on
     *  both the master and season2 server schemes. */
    public static final byte PKT_POWERBALL_STATE = (byte) 133;

    /** C2S — report the client's local (OS-default) timezone so the server can render
     *  all player-facing clock times (event schedules, daily resets, payout/expiry
     *  timestamps, the raid-protection window) in this player's own zone. Sent once
     *  right after the handshake on join. Wire: varint+string IANA zone id (e.g.
     *  {@code "America/New_York"}, capped at {@link #CLIENT_TIMEZONE_MAX_CHARS}). Byte 135
     *  is free on both the master and season2 server schemes — keep it that way on merge. */
    public static final byte PKT_CLIENT_TIMEZONE = (byte) 135;
    /** Max chars for the IANA zone id carried by {@link #PKT_CLIENT_TIMEZONE}. */
    public static final int CLIENT_TIMEZONE_MAX_CHARS = 64;

    // ── Cell Vault Terminal C2S (intent-only; the server resolves the sender
    //    from the connection — payloads carry indices only, never item identity
    //    or UUIDs; every mutating op is answered with a fresh PKT_CELLTERM_BUNDLE).
    //    Bytes 137-144 are free on ALL THREE schemes (mod master, PrisonsCore
    //    dev, PrisonsCore season2) — keep them that way on merge. ──────────────

    /**
     * Pull from a specific cell-container slot. Wire after the type byte:
     * {@code varint containerId; short slot; byte mode; byte target}. Mode and
     * target bytes share the PV values ({@link #PV_EXTRACT_ONE} /
     * {@link #PV_EXTRACT_HALF} / {@link #PV_EXTRACT_ALL} /
     * {@link #PV_EXTRACT_STACK}; {@link #PV_TARGET_CURSOR} /
     * {@link #PV_TARGET_INV}) with identical semantics.
     */
    public static final byte PKT_CELLTERM_EXTRACT = (byte) 137;

    /**
     * Aggregated ME-style pull: the reference {@code (containerId, slot)} only
     * names which item to match — the server matches all isSimilar stacks
     * across ALL session containers, caps by destination space BEFORE mutating,
     * and drains ascending (containerId, slot) in one tick. Wire:
     * {@code varint refContainerId; short refSlot; byte mode; byte target}.
     */
    public static final byte PKT_CELLTERM_EXTRACT_ITEM = (byte) 138;

    /**
     * Deposit a player-inventory stack into the cell: vault (container 0)
     * first, then other containers in containerId order; merge-into-similar
     * then empty slots. Wire: {@code int playerInvSlot} (0..35, Bukkit
     * ordering — hotbar 0..8, main inv 9..35).
     */
    public static final byte PKT_CELLTERM_DEPOSIT = (byte) 139;

    /**
     * Vanilla click semantics cursor ↔ player-inv slot (mirror of the PV
     * terminal's {@link #PKT_PV_CURSOR_PLACE_INV}). Wire:
     * {@code byte invSlot} (0..35).
     */
    public static final byte PKT_CELLTERM_CURSOR_PLACE_INV = (byte) 140;

    /**
     * Return the cursor stack: cell containers first, then player inventory,
     * then drop at feet. Sent when the cell-terminal closes (or a tile is
     * clicked) with a non-empty cursor. No payload.
     */
    public static final byte PKT_CELLTERM_CURSOR_RETURN = (byte) 141;

    /**
     * Client closed the cell-terminal screen — end the server-side session. No
     * payload. NOT sent when the close was server-initiated via the S2C
     * {@link #PKT_CELLTERM_CLOSE}. (Spec name on both sides is
     * PKT_CELLTERM_CLOSE = 142 C2S / 44 S2C; suffixed here to avoid the Java
     * name collision.)
     */
    public static final byte PKT_CELLTERM_CLOSE_C2S = (byte) 142;

    /** Ask for a fresh cell-terminal bundle (server-side ~500ms cooldown class). No payload. */
    public static final byte PKT_CELLTERM_REFRESH_REQ = (byte) 143;

    /**
     * Feature-toggle report: {@code byte enabled} (1/0). Sent once after the
     * handshake on join and on every toggle flip (mirror of the
     * {@link #PKT_MINING_HUD_STATE} pattern). When a client reported disabled,
     * the server must NOT intercept vault-chest opens for it.
     */
    public static final byte PKT_CELLTERM_STATE = (byte) 144;

    // --- Hard size caps (wire-level) ---
    /** Maximum bytes for any single cosmetic S2C payload. Larger packets are dropped. */
    public static final int MAX_PAYLOAD_BYTES = 256;
    /** Larger cap reserved for {@link #PKT_BUFF_SNAPSHOT}, which carries every layer with a label. */
    public static final int MAX_SNAPSHOT_PAYLOAD_BYTES = 16_384;
    /** Max bytes for a single {@link #PKT_PV_BUNDLE} packet (the legacy one-shot path).
     *  Also the {@link RawPayload} outer read cap. Bundles larger than this arrive split
     *  across {@link #PKT_PV_BUNDLE_CHUNK} packets and are reassembled up to
     *  {@link #MAX_PV_BUNDLE_TOTAL_BYTES}. Must match server-side PrisonsModChannel.MAX_PV_BUNDLE_BYTES. */
    public static final int MAX_PV_BUNDLE_BYTES = 262_144;
    /** Hard cap on a reassembled chunked PV bundle body — guards against a malicious server
     *  claiming a huge chunk count to OOM the client. Must match server-side
     *  PrisonsModChannel.MAX_PV_BUNDLE_TOTAL_BYTES. */
    public static final int MAX_PV_BUNDLE_TOTAL_BYTES = 1_048_576;
    /** Max bytes for a single {@link #PKT_PV_BUNDLE_CHUNK} packet (the server sends 24 KiB
     *  bodies; this read cap leaves header headroom). */
    public static final int MAX_PV_BUNDLE_CHUNK_BYTES = 32_768;
    /** Max chunks a single PV bundle may declare (1 MiB / 24 KiB, with headroom). */
    public static final int PV_BUNDLE_MAX_CHUNKS = 80;
    /** Max bytes for a single {@link #PKT_LOOT_SNAPSHOT_CHUNK} packet. */
    public static final int MAX_LOOT_CHUNK_BYTES = 32_768;
    /** Hard cap on the reassembled loot snapshot body — guards against a
     *  malicious server claiming a huge chunk count to OOM the client. */
    public static final int MAX_LOOT_SNAPSHOT_BYTES = 1_048_576;

    // --- Loot browser bounds (validated post-decode) ---
    /** Max chunks a single snapshot may declare (1 MB / 16 KB, with headroom). */
    public static final int LOOT_MAX_CHUNKS = 80;
    public static final int LOOT_MAX_STRINGS = 8_192;
    public static final int LOOT_MAX_STRING_CHARS = 256;
    public static final int LOOT_MAX_CATEGORIES = 32;
    public static final int LOOT_MAX_TABLES = 512;
    public static final int LOOT_MAX_ENTRIES_PER_TABLE = 256;

    // --- PV bundle bounds ---
    // Set well above the current server-side cap so future bumps don't break
    // older jars. The decoder rejects bundles only when the server claims
    // MORE vaults than this — a tighter cap would force a coordinated
    // re-release of the mod every time VAULTS_PER_PLAYER goes up.
    public static final int PV_MAX_VAULTS = 64;
    public static final int PV_MAX_SLOTS = 162; // 6 rows × 9 cols × multiple rows of extras
    public static final int PV_MAX_MATERIAL_KEY_CHARS = 48;
    /** Must stay in lockstep with PrisonsCore PrisonsModChannel.PV_MAX_DISPLAY_NAME_CHARS.
     *  Raised from 64 so colour/hex-coded names survive without truncation — a single
     *  hex colour is a 14-char §x§r§r§g§g§b§b run, so coloured names need the headroom.
     *  If this is lower than what the server sends, readString throws and the whole PV
     *  bundle is dropped (the terminal goes blank), so the two move together. */
    public static final int PV_MAX_DISPLAY_NAME_CHARS = 1024;
    public static final int PV_MAX_AFFINITY_CSV_CHARS = 256;
    /** Max lore lines accepted per PV slot. A maxed pickaxe can reach ~140 lines
     *  (103 enchants + prestige + energy + ore tracking). A server that sends MORE
     *  than this trips the decode guard and the whole bundle is dropped — keep this
     *  ≥ server-side PrisonsModChannel.PV_MAX_LORE_LINES (currently 200). */
    public static final int PV_MAX_LORE_LINES = 200;
    public static final int PV_MAX_LORE_LINE_CHARS = 128;

    // --- Cell terminal bounds ---
    // Per-slot fields reuse the PV slot caps above (identical codec). Bundle /
    // chunk byte sizes reuse MAX_PV_BUNDLE_BYTES / MAX_PV_BUNDLE_CHUNK_BYTES /
    // MAX_PV_BUNDLE_TOTAL_BYTES / PV_BUNDLE_MAX_CHUNKS — same caps by spec.
    /** Hard cap on containers per {@link #PKT_CELLTERM_BUNDLE}. */
    public static final int CELLTERM_MAX_CONTAINERS = 64;
    /** Hard cap on a single cell container's slot count (double chest = 54). */
    public static final int CELLTERM_MAX_SLOTS_PER_CONTAINER = 54;
    /** Max chars for a per-container label ("Vault", "Chest 2", "Barrel 1"). */
    public static final int CELLTERM_MAX_CONTAINER_LABEL_CHARS = 24;
    /** Max chars for the cell label in {@link #PKT_CELLTERM_OPEN} (e.g. "Cell A-12"). */
    public static final int CELLTERM_MAX_CELL_LABEL_CHARS = 32;
    /** Max chars for the S2C {@link #PKT_CELLTERM_CLOSE} reason string. */
    public static final int CELLTERM_MAX_CLOSE_REASON_CHARS = 64;

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
    /** Max inbound mining-rush pings per second. At most one per tier per spawn cycle. */
    public static final int RATE_MINING_RUSH_PING_PER_SEC = 5;
    /** Roster + duel state are low-frequency keepalives — a handful per second is the ceiling. */
    public static final int RATE_GANG_ROSTER_PER_SEC = 4;
    public static final int RATE_DUEL_STATE_PER_SEC = 4;
    /** Booster heartbeat is 1 Hz from the server; ceiling at 5 absorbs jitter without letting a misbehaving server flood the renderer. */
    public static final int RATE_BOOSTER_UPDATE_PER_SEC = 5;
    /** Event timer heartbeat — same shape as boosters. */
    public static final int RATE_EVENT_TIMERS_PER_SEC = 5;
    public static final int RATE_RIFT_BUDGET_PER_SEC = 5;
    /** Cooldowns heartbeat — same shape as boosters. */
    public static final int RATE_COOLDOWNS_PER_SEC = 5;
    /** PvE stats heartbeat — same shape. */
    public static final int RATE_PVE_STATS_PER_SEC = 5;
    /** Mining stats heartbeat — same shape. */
    public static final int RATE_MINING_STATS_PER_SEC = 5;
    /** Mining block-counts heartbeat — same shape as mining stats. */
    public static final int RATE_MINING_BLOCKS_PER_SEC = 5;
    /** Mining-session heartbeat — same 1 Hz shape as mining stats. */
    public static final int RATE_MINING_SESSION_PER_SEC = 5;
    /** Per-block-break + right-click. A meteorite is 200–300 blocks; cap at theoretical max mining cadence. */
    public static final int RATE_METEORITE_HUD_PER_SEC = 40;
    /** Buff snapshot is on-demand (only on /pickbuffs or refresh-button). */
    public static final int RATE_BUFF_SNAPSHOT_PER_SEC = 5;
    /** Bug-report inbound packets are user-driven; a handful per second absorbs jitter. */
    public static final int RATE_BUGREPORT_PER_SEC = 5;
    /** Suggest inbound packets are user-driven; a handful per second is plenty. */
    public static final int RATE_SUGGEST_PER_SEC = 5;
    /** PV bundle pushes — one per /pv intercept, plus one per terminal
     *  extract/deposit refresh. Fast clicking can legitimately fire many per
     *  second; keep this comfortably above human click rate so refresh bundles
     *  aren't dropped (a dropped bundle leaves the terminal grid looking like
     *  items vanished until reopen). Still bounded against a misbehaving server. */
    public static final int RATE_PV_BUNDLE_PER_SEC = 20;
    /** Fullbright blacklist is one-shot per handshake — tight cap. */
    public static final int RATE_FULLBRIGHT_BLACKLIST_PER_SEC = 2;
    /** Skill tree layout pushed only when the screen opens — tight cap. */
    public static final int RATE_SKILLTREE_OPEN_PER_SEC  = 2;
    /** State pushed after every chisel / mod allocation — 1 Hz typical, allow burst. */
    public static final int RATE_SKILLTREE_STATE_PER_SEC = 10;
    /** Ack arrives at most once per mod action — burst limit is the player's click rate. */
    public static final int RATE_SKILLTREE_ACK_PER_SEC   = 10;
    /** Loot snapshot is on-demand but multi-chunk; allow a burst for the chunks
     *  of one snapshot to arrive back-to-back without tripping the limiter. */
    public static final int RATE_LOOT_CHUNK_PER_SEC = 80;
    /** PV bundle chunks (for oversized vaults) arrive as a back-to-back burst, same as
     *  the loot snapshot — allow the whole bundle's chunks through in one window. */
    public static final int RATE_PV_CHUNK_PER_SEC = 80;
    /** Cell-terminal bundle pushes — one per server-side open, plus one per
     *  extract/deposit refresh. Same human-click-rate reasoning as the PV bundle. */
    public static final int RATE_CELLTERM_BUNDLE_PER_SEC = 20;
    /** Cell-terminal bundle chunks arrive as a back-to-back burst — same as PV chunks. */
    public static final int RATE_CELLTERM_CHUNK_PER_SEC = 80;
    /** Cell-terminal open / force-close pushes are user-driven (one per chest open). */
    public static final int RATE_CELLTERM_OPEN_PER_SEC = 5;
    /** Powerball is burst-prone: a stacked proc sends a spawn per ball plus a
     *  bounce update per ball-bounce. Generous so legitimate bursts aren't dropped. */
    public static final int RATE_POWERBALL_PER_SEC = 200;

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
