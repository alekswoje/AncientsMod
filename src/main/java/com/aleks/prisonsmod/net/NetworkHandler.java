package com.aleks.prisonsmod.net;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.DuelState;
import com.aleks.prisonsmod.client.GangRoster;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.bugreport.BugReportClient;
import com.aleks.prisonsmod.client.cellterm.CellTermClient;
import com.aleks.prisonsmod.client.loot.LootClient;
import com.aleks.prisonsmod.client.pv.PvClient;
import com.aleks.prisonsmod.client.suggest.SuggestClient;
import com.aleks.prisonsmod.client.gangping.GangPingManager;
import com.aleks.prisonsmod.client.hud.BoosterState;
import com.aleks.prisonsmod.client.hud.CooldownState;
import com.aleks.prisonsmod.client.hud.EventState;
import com.aleks.prisonsmod.client.hud.MeteoriteState;
import com.aleks.prisonsmod.client.hud.MiningStatsState;
import com.aleks.prisonsmod.client.hud.OutpostState;
import com.aleks.prisonsmod.client.hud.PveStatsState;
import com.aleks.prisonsmod.net.payload.OutpostStatePayload;
import com.aleks.prisonsmod.net.payload.BoosterUpdatePayload;
import com.aleks.prisonsmod.net.payload.BugReportAiReplyPayload;
import com.aleks.prisonsmod.net.payload.BugReportErrorPayload;
import com.aleks.prisonsmod.net.payload.BugReportFiledPayload;
import com.aleks.prisonsmod.net.payload.BugReportOpenPayload;
import com.aleks.prisonsmod.net.payload.SuggestErrorPayload;
import com.aleks.prisonsmod.net.payload.SuggestFiledPayload;
import com.aleks.prisonsmod.net.payload.SuggestOpenPayload;
import com.aleks.prisonsmod.net.payload.CooldownsPayload;
import com.aleks.prisonsmod.net.payload.EventTimersPayload;
import com.aleks.prisonsmod.net.payload.MeteoriteHudPayload;
import com.aleks.prisonsmod.net.payload.MiningStatsPayload;
import com.aleks.prisonsmod.net.payload.PveStatsPayload;
import com.aleks.prisonsmod.net.payload.DuelStatePayload;
import com.aleks.prisonsmod.net.payload.GangPingPayload;
import com.aleks.prisonsmod.net.payload.GangRosterPayload;
import com.aleks.prisonsmod.net.payload.HudUpdatePayload;
import com.aleks.prisonsmod.net.payload.MeteorPingPayload;
import com.aleks.prisonsmod.net.payload.MiningRushPingPayload;
import com.aleks.prisonsmod.net.payload.MineCancelPayload;
import com.aleks.prisonsmod.net.payload.MineStartPayload;
import com.aleks.prisonsmod.net.payload.CellTermBundlePayload;
import com.aleks.prisonsmod.net.payload.PointGainPayload;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import com.aleks.prisonsmod.render.FloatingNumberRenderer;
import com.aleks.prisonsmod.render.MinePredictRenderer;
import com.aleks.prisonsmod.render.PowerballRenderer;
import com.aleks.prisonsmod.render.RiftHud;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;

/**
 * Dispatches inbound server packets on {@link Protocol#CHANNEL_V1} to renderers.
 *
 * <p>The handler is paranoid on purpose — every step (size check, type id,
 * rate limit, payload decode) can abort the packet. Nothing throws out to the
 * Minecraft netty pipeline; anything malformed is dropped silently.
 */
public final class NetworkHandler {

    private static final RateLimiter RATE_LIMITER = new RateLimiter();

    /** Register with Fabric's client networking API. Call once from client init. */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(RawPayload.ID, RawPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RawPayload.ID, RawPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(RawPayload.ID, (payload, context) -> {
            // Server allowlist is the first gate — if we're not on RooPrisons, do nothing.
            if (!ServerAllowlist.isAllowed()) return;
            // Dispatch on the render thread. Fabric already invokes the handler on
            // the client thread, so we can call straight through.
            onPayload(payload.data());
        });

        PrisonsMod.LOGGER.info("PrisonsMod receivers registered on channel {}", Protocol.CHANNEL_V1);
    }

    /**
     * Entry point from the Fabric client networking receiver. Bounds-checks,
     * rate-limits, decodes, dispatches. Never throws to the caller.
     */
    public static void onPayload(byte[] raw) {
        try {
            // Snapshot packets can be larger than cosmetic packets, so use the
            // largest known cap (PV bundle) as the outer bound — type-specific
            // decoders enforce their own bounds further in.
            int outerCap = Math.max(Protocol.MAX_SNAPSHOT_PAYLOAD_BYTES, Protocol.MAX_PV_BUNDLE_BYTES);
            if (raw == null || raw.length == 0 || raw.length > outerCap) {
                if (raw != null) PrisonsMod.LOGGER.info("onPayload: dropped len={}", raw.length);
                return; // drop oversized or empty
            }
            PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(raw));
            byte typeId = buf.readByte();
            // Trace snapshot only — cosmetic packets are spammy.
            if (typeId == Protocol.PKT_BUFF_SNAPSHOT) {
                PrisonsMod.LOGGER.info("onPayload: type=PKT_BUFF_SNAPSHOT raw len={}", raw.length);
            }
            switch (typeId) {
                case Protocol.PKT_POINT_GAIN -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.POINT_GAIN)) return;
                    PointGainPayload p = PointGainPayload.decode(buf);
                    FloatingNumberRenderer.enqueue(p);
                }
                case Protocol.PKT_HUD_UPDATE -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.HUD_UPDATE)) return;
                    HudUpdatePayload p = HudUpdatePayload.decode(buf);
                    RiftHud.update(p);
                }
                case Protocol.PKT_MINE_START -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.MINE_START)) return;
                    MineStartPayload p = MineStartPayload.decode(buf);
                    MinePredictRenderer.onMineStart(p);
                }
                case Protocol.PKT_MINE_CANCEL -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.MINE_CANCEL)) return;
                    MineCancelPayload p = MineCancelPayload.decode(buf);
                    MinePredictRenderer.onMineCancel(p.pos());
                }
                case Protocol.PKT_MINE_SPEEDS -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.MINE_SPEEDS)) return;
                    com.aleks.prisonsmod.net.payload.MineSpeedsPayload p =
                            com.aleks.prisonsmod.net.payload.MineSpeedsPayload.decode(buf);
                    MinePredictRenderer.onSpeedTable(p);
                }
                case Protocol.PKT_CLICKLOCK_STATE -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.CLICKLOCK_STATE)) return;
                    MinePredictRenderer.onClickLockState(buf.readByte() != 0);
                }
                case Protocol.PKT_GANG_PING -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.GANG_PING)) return;
                    GangPingPayload p = GangPingPayload.decode(buf);
                    GangPingManager.onPing(p);
                }
                case Protocol.PKT_METEOR_PING -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.METEOR_PING)) return;
                    MeteorPingPayload p = MeteorPingPayload.decode(buf);
                    GangPingManager.onMeteorPing(p);
                }
                case Protocol.PKT_MINING_RUSH_PING -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.MINING_RUSH_PING)) return;
                    MiningRushPingPayload p = MiningRushPingPayload.decode(buf);
                    GangPingManager.onMiningRushPing(p);
                }
                case Protocol.PKT_GANG_ROSTER -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.GANG_ROSTER)) return;
                    GangRosterPayload p = GangRosterPayload.decode(buf);
                    GangRoster.update(p);
                }
                case Protocol.PKT_DUEL_STATE -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.DUEL_STATE)) return;
                    DuelStatePayload p = DuelStatePayload.decode(buf);
                    DuelState.set(p.inDuel());
                }
                case Protocol.PKT_BOOSTER_UPDATE -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.BOOSTER_UPDATE)) return;
                    BoosterUpdatePayload p = BoosterUpdatePayload.decode(buf);
                    BoosterState.update(p);
                }
                case Protocol.PKT_EVENT_TIMERS -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.EVENT_TIMERS)) return;
                    EventTimersPayload p = EventTimersPayload.decode(buf);
                    EventState.update(p);
                }
                case Protocol.PKT_METEORITE_HUD -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.METEORITE_HUD)) return;
                    MeteoriteHudPayload p = MeteoriteHudPayload.decode(buf);
                    MeteoriteState.update(p);
                }
                case Protocol.PKT_COOLDOWNS -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.COOLDOWNS)) return;
                    CooldownsPayload p = CooldownsPayload.decode(buf);
                    CooldownState.update(p);
                }
                case Protocol.PKT_PVE_STATS -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.PVE_STATS)) return;
                    PveStatsPayload p = PveStatsPayload.decode(buf);
                    PveStatsState.update(p);
                }
                case Protocol.PKT_MINING_STATS -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.MINING_STATS)) return;
                    MiningStatsPayload p = MiningStatsPayload.decode(buf);
                    MiningStatsState.update(p);
                }
                case Protocol.PKT_MINING_BLOCKS -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.MINING_BLOCKS)) return;
                    com.aleks.prisonsmod.net.payload.MiningBlocksPayload p =
                            com.aleks.prisonsmod.net.payload.MiningBlocksPayload.decode(buf);
                    com.aleks.prisonsmod.client.hud.MiningBlocksState.update(p);
                }
                case Protocol.PKT_MINING_SESSION -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.MINING_SESSION)) return;
                    com.aleks.prisonsmod.net.payload.MiningSessionPayload p =
                            com.aleks.prisonsmod.net.payload.MiningSessionPayload.decode(buf);
                    com.aleks.prisonsmod.client.hud.MiningSessionState.update(p);
                }
                case Protocol.PKT_BUFF_SNAPSHOT -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.BUFF_SNAPSHOT)) {
                        PrisonsMod.LOGGER.info("BUFF_SNAPSHOT rate-limited");
                        return;
                    }
                    com.aleks.prisonsmod.net.payload.BuffSnapshotPayload p =
                            com.aleks.prisonsmod.net.payload.BuffSnapshotPayload.decode(buf);
                    PrisonsMod.LOGGER.info("BUFF_SNAPSHOT received: {} channels", p.channels.size());
                    com.aleks.prisonsmod.client.buffs.BuffSnapshotState.update(p);
                    // If the screen isn't open yet, open it; if it is, refresh it.
                    com.aleks.prisonsmod.client.screen.BuffBreakdownScreen.openNow(null);
                }
                case Protocol.PKT_BUGREPORT_OPEN -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.BUGREPORT)) return;
                    BugReportOpenPayload p = BugReportOpenPayload.decode(buf);
                    BugReportClient.onOpen(p);
                }
                case Protocol.PKT_BUGREPORT_AI_REPLY -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.BUGREPORT)) return;
                    BugReportAiReplyPayload p = BugReportAiReplyPayload.decode(buf);
                    BugReportClient.onAiReply(p);
                }
                case Protocol.PKT_BUGREPORT_FILED -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.BUGREPORT)) return;
                    BugReportFiledPayload p = BugReportFiledPayload.decode(buf);
                    BugReportClient.onFiled(p);
                }
                case Protocol.PKT_BUGREPORT_ERROR -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.BUGREPORT)) return;
                    BugReportErrorPayload p = BugReportErrorPayload.decode(buf);
                    BugReportClient.onError(p);
                }
                case Protocol.PKT_SUGGEST_OPEN -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.SUGGEST)) return;
                    SuggestOpenPayload p = SuggestOpenPayload.decode(buf);
                    SuggestClient.onOpen(p);
                }
                case Protocol.PKT_SUGGEST_FILED -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.SUGGEST)) return;
                    SuggestFiledPayload p = SuggestFiledPayload.decode(buf);
                    SuggestClient.onFiled(p);
                }
                case Protocol.PKT_SUGGEST_ERROR -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.SUGGEST)) return;
                    SuggestErrorPayload p = SuggestErrorPayload.decode(buf);
                    SuggestClient.onError(p);
                }
                case Protocol.PKT_PV_BUNDLE -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.PV_BUNDLE)) return;
                    PvBundlePayload p = PvBundlePayload.decode(buf);
                    PvClient.onBundle(p);
                }
                case Protocol.PKT_PV_BUNDLE_CHUNK -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.PV_CHUNK)) return;
                    int version = buf.readInt();
                    int chunkIndex = buf.readVarInt();
                    int chunkCount = buf.readVarInt();
                    int len = buf.readVarInt();
                    if (chunkCount < 1 || chunkCount > Protocol.PV_BUNDLE_MAX_CHUNKS) return;
                    if (chunkIndex < 0 || chunkIndex >= chunkCount) return;
                    if (len < 0 || len > Protocol.MAX_PV_BUNDLE_CHUNK_BYTES) return;
                    if (buf.readableBytes() < len) return;
                    byte[] chunk = new byte[len];
                    buf.readBytes(chunk);
                    PvClient.onBundleChunk(version, chunkIndex, chunkCount, chunk);
                }
                case Protocol.PKT_PV_OPEN_TERMINAL -> {
                    // Server-initiated (admin /pvsee). Not client-spammable, so
                    // no rate-limit gate — the target bundle follows immediately.
                    String tName = buf.readString(Protocol.PV_OPEN_TERMINAL_MAX_NAME_CHARS);
                    boolean editable = buf.readByte() != 0;
                    PvClient.onOpenTerminal(tName, editable);
                }
                case Protocol.PKT_CELLTERM_OPEN -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.CELLTERM_OPEN)) return;
                    String cellLabel = buf.readString(Protocol.CELLTERM_MAX_CELL_LABEL_CHARS);
                    byte flags = buf.readByte();
                    CellTermClient.onOpen(cellLabel, (flags & 0x01) != 0);
                }
                case Protocol.PKT_CELLTERM_BUNDLE -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.CELLTERM_BUNDLE)) return;
                    CellTermBundlePayload p = CellTermBundlePayload.decode(buf);
                    CellTermClient.onBundle(p);
                }
                case Protocol.PKT_CELLTERM_BUNDLE_CHUNK -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.CELLTERM_CHUNK)) return;
                    int version = buf.readInt();
                    int chunkIndex = buf.readVarInt();
                    int chunkCount = buf.readVarInt();
                    int len = buf.readVarInt();
                    if (chunkCount < 1 || chunkCount > Protocol.PV_BUNDLE_MAX_CHUNKS) return;
                    if (chunkIndex < 0 || chunkIndex >= chunkCount) return;
                    if (len < 0 || len > Protocol.MAX_PV_BUNDLE_CHUNK_BYTES) return;
                    if (buf.readableBytes() < len) return;
                    byte[] chunk = new byte[len];
                    buf.readBytes(chunk);
                    CellTermClient.onBundleChunk(version, chunkIndex, chunkCount, chunk);
                }
                case Protocol.PKT_CELLTERM_CLOSE -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.CELLTERM_OPEN)) return;
                    String reason = buf.readString(Protocol.CELLTERM_MAX_CLOSE_REASON_CHARS);
                    CellTermClient.onForceClose(reason);
                }
                case Protocol.PKT_OUTPOST_STATE -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.OUTPOST_STATE)) return;
                    OutpostStatePayload p = OutpostStatePayload.decode(buf);
                    OutpostState.update(p);
                }
                case Protocol.PKT_LOOT_SNAPSHOT_CHUNK -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.LOOT_CHUNK)) return;
                    int version = buf.readInt();
                    int chunkIndex = buf.readVarInt();
                    int chunkCount = buf.readVarInt();
                    int len = buf.readVarInt();
                    if (chunkCount < 1 || chunkCount > Protocol.LOOT_MAX_CHUNKS) return;
                    if (chunkIndex < 0 || chunkIndex >= chunkCount) return;
                    if (len < 0 || len > Protocol.MAX_LOOT_CHUNK_BYTES) return;
                    if (buf.readableBytes() < len) return;
                    byte[] chunk = new byte[len];
                    buf.readBytes(chunk);
                    LootClient.onSnapshotChunk(version, chunkIndex, chunkCount, chunk);
                }
                case Protocol.PKT_LOOT_LUCK -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.LOOT_LUCK)) return;
                    int milli = buf.readVarInt();
                    // Clamp defensively; the value is purely cosmetic (drives the
                    // "with luck" drop-rate display in the loot browser).
                    if (milli < 0) milli = 0;
                    if (milli > 10_000_000) milli = 10_000_000;
                    LootClient.onLuck(milli / 1000.0);
                }
                case Protocol.PKT_POWERBALL -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.POWERBALL)) return;
                    byte op = buf.readByte();
                    long now = System.currentTimeMillis();
                    switch (op) {
                        case Protocol.POWERBALL_OP_SPAWN -> {
                            int id = buf.readVarInt();
                            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
                            float vx = buf.readFloat(), vy = buf.readFloat(), vz = buf.readFloat();
                            int life = buf.readVarInt();
                            PowerballRenderer.onSpawn(id, x, y, z, vx, vy, vz, life, now);
                        }
                        case Protocol.POWERBALL_OP_BOUNCE -> {
                            int id = buf.readVarInt();
                            double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
                            float vx = buf.readFloat(), vy = buf.readFloat(), vz = buf.readFloat();
                            PowerballRenderer.onBounce(id, x, y, z, vx, vy, vz);
                        }
                        case Protocol.POWERBALL_OP_DESPAWN -> {
                            int id = buf.readVarInt();
                            boolean fizzle = buf.readByte() != 0;
                            PowerballRenderer.onDespawn(id, fizzle);
                        }
                        default -> { /* unknown sub-op — ignore */ }
                    }
                }
                case Protocol.PKT_DISABLED_TEXTURES -> {
                    com.aleks.prisonsmod.net.payload.DisabledTexturesPayload p =
                            com.aleks.prisonsmod.net.payload.DisabledTexturesPayload.decode(buf);
                    com.aleks.prisonsmod.client.DisabledTextures.update(p.keys());
                }
                default -> {
                    // Unknown type — silently ignore. A future server may emit
                    // newer packet types that older clients don't recognize; we
                    // never want that to be fatal.
                }
            }
            // Any bytes we didn't consume are discarded; we don't care if the
            // server appended fields we don't know about yet.
        } catch (IllegalArgumentException bounds) {
            // Validation failure — drop the packet. In debug builds we could log.
        } catch (Throwable unexpected) {
            // Catch-all: we never want a malformed packet to crash the client.
            PrisonsMod.LOGGER.debug("dropped malformed packet", unexpected);
        }
    }

    /**
     * Send the one-shot mod-presence handshake. Called on
     * {@code ClientPlayConnectionEvents.JOIN} once the server is allowlisted,
     * so the server can flag this player as modded and route
     * {@code /pickbuffs} to the rich snapshot path.
     */
    public static void sendHandshake() {
        if (!ServerAllowlist.isAllowed()) {
            PrisonsMod.LOGGER.info("sendHandshake: server not allowlisted, skipping");
            return;
        }
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) {
            PrisonsMod.LOGGER.info("sendHandshake: channel not registered server-side, skipping");
            return;
        }
        try {
            // Trailing minor-version byte lets the server gate same-major feature additions
            // (e.g. chunked PV bundles). Older servers stop at the type byte and ignore it.
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_HANDSHAKE, (byte) Protocol.PROTOCOL_MINOR }));
            PrisonsMod.LOGGER.info("sendHandshake: sent");
        } catch (Throwable t) {
            PrisonsMod.LOGGER.warn("send handshake failed", t);
        }
    }

    /**
     * Report whether the client-side booster HUD is enabled. Server uses this
     * to default the action-bar booster line off while the widget is rendering
     * the same info; players can still flip it back on via {@code /toggles}.
     * Sent right after the handshake on join and on every toggle change.
     */
    public static void sendBoosterHudState(boolean on) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] {
                    Protocol.PKT_BOOSTER_HUD_STATE, (byte) (on ? 1 : 0)
            }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send booster hud state failed", t);
        }
    }

    /**
     * Report whether the Stats HUD mining section is enabled. Server uses this
     * to default the action-bar XP/h / Energy/h / $/h rates off while the
     * widget is rendering the same info; players can still flip them back on
     * via {@code /toggles} → Action Bar → Show Rates.
     * Sent right after the handshake on join and on every toggle change.
     */
    public static void sendMiningHudState(boolean on) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] {
                    Protocol.PKT_MINING_HUD_STATE, (byte) (on ? 1 : 0)
            }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send mining hud state failed", t);
        }
    }

    /**
     * Report whether the mod renders Powerball client-side. When on, the server
     * stops spawning the server-side ItemDisplay + trail for this player's balls
     * and sends {@link Protocol#PKT_POWERBALL} hints instead — eliminating the
     * per-tick entity-move packet flood. Sent after the handshake on join and on
     * every toggle change.
     */
    public static void sendPowerballState(boolean on) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] {
                    Protocol.PKT_POWERBALL_STATE, (byte) (on ? 1 : 0)
            }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send powerball state failed", t);
        }
    }

    /**
     * Buff-screen refresh request. Single-byte payload — server identifies the
     * sender from the channel connection. Server enforces a 1Hz rate limit.
     */
    /**
     * Report whether the mod runs swing-time mine prediction. When on, the
     * server streams {@link Protocol#PKT_MINE_SPEEDS}, suppresses its own
     * crack-stage stream + break particle/sound/fragment for this player's own
     * breaks, and grants a ping-bounded completion grace on early retarget.
     * Sent after the handshake on join and on every toggle change.
     */
    public static void sendMinePredictState(boolean on) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] {
                    Protocol.PKT_MINE_PREDICT_STATE, (byte) (on ? 1 : 0)
            }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send mine predict state failed", t);
        }
    }

    public static void sendBuffRefreshRequest() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_BUFF_REFRESH_REQ }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send buff refresh failed", t);
        }
    }

    /**
     * Send a gang-ping request to the server. Payload contains only target
     * coordinates + hold-flag — the server authenticates the sender from the
     * connection itself and ignores any identity fields a malicious client
     * could try to forge.
     */
    public static void sendGangPingRequest(double x, double y, double z, boolean isHeld) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            io.netty.buffer.ByteBuf buf = Unpooled.buffer(26);
            buf.writeByte(Protocol.PKT_GANG_PING_REQ);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeByte(isHeld ? 1 : 0);
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            ClientPlayNetworking.send(new RawPayload(data));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send gang ping failed", t);
        }
    }

    // ── Bug-report sends ─────────────────────────────────────────────────────

    /**
     * "Player ran /bugreport — open the UI." Server replies with
     * {@link Protocol#PKT_BUGREPORT_OPEN} (token + sanitized snapshot) on
     * success, or with {@link Protocol#PKT_BUGREPORT_ERROR} if rate-limited.
     */
    public static void sendBugReportIntent(String prefillDescription) {
        sendString(Protocol.PKT_BUGREPORT_INTENT,
                clamp(prefillDescription, Protocol.BUGREPORT_MAX_PREFILL_CHARS));
    }

    /**
     * "Submit the report." Server validates token + persists the report +
     * fires AI investigation. Categories is the OR'd BR_CAT_* bitmask.
     */
    public static void sendBugReportSubmit(String token, int categoryMask, String description) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(32));
            buf.writeByte(Protocol.PKT_BUGREPORT_SUBMIT);
            buf.writeString(clamp(token, Protocol.BUGREPORT_MAX_TOKEN_CHARS));
            buf.writeVarInt(categoryMask & 0x1FF);
            buf.writeString(clamp(description, Protocol.BUGREPORT_MAX_DESCRIPTION_CHARS));
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send bugreport submit failed", t);
        }
    }

    /** Send a player follow-up in the AI chat thread. */
    public static void sendBugReportFollowup(String token, String message) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(32));
            buf.writeByte(Protocol.PKT_BUGREPORT_FOLLOWUP);
            buf.writeString(clamp(token, Protocol.BUGREPORT_MAX_TOKEN_CHARS));
            buf.writeString(clamp(message, Protocol.BUGREPORT_MAX_FOLLOWUP_CHARS));
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send bugreport followup failed", t);
        }
    }

    /** Player clicked "Talk to staff": ask server to open a Discord ticket. */
    public static void sendBugReportEscalate(String token) {
        sendString(Protocol.PKT_BUGREPORT_ESCALATE, clamp(token, Protocol.BUGREPORT_MAX_TOKEN_CHARS));
    }

    /** Player closed the UI; tell server to free the preview / mark resolved. */
    public static void sendBugReportClose(String token, boolean resolved) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(32));
            buf.writeByte(Protocol.PKT_BUGREPORT_CLOSE);
            buf.writeString(clamp(token, Protocol.BUGREPORT_MAX_TOKEN_CHARS));
            buf.writeByte(resolved ? 1 : 0);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send bugreport close failed", t);
        }
    }

    // ── Suggest sends ────────────────────────────────────────────────────────

    /** "Player ran /suggest — open the GUI." Server replies with PKT_SUGGEST_OPEN. */
    public static void sendSuggestIntent() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_SUGGEST_INTENT }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send suggest intent failed", t);
        }
    }

    /** "Submit this suggestion." Server validates the token and forwards to Discord. */
    public static void sendSuggestSubmit(String token, byte category, String body) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(32));
            buf.writeByte(Protocol.PKT_SUGGEST_SUBMIT);
            buf.writeString(clamp(token, Protocol.SUGGEST_MAX_TOKEN_CHARS));
            buf.writeByte(category);
            buf.writeString(clamp(body, Protocol.SUGGEST_MAX_BODY_CHARS));
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send suggest submit failed", t);
        }
    }

    /** Player dismissed the suggest UI; tell server to free the session. */
    public static void sendSuggestClose(String token) {
        sendString(Protocol.PKT_SUGGEST_CLOSE, clamp(token, Protocol.SUGGEST_MAX_TOKEN_CHARS));
    }

    // ── PV overview sends ────────────────────────────────────────────────────

    /**
     * "Player ran /pv — bundle all 7 PVs for me." Single-byte payload. Server
     * identifies the sender from the connection and rate-limits (2s per player).
     */
    public static void sendPvBundleRequest() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_PV_BUNDLE_REQ }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send pv bundle req failed", t);
        }
    }

    /**
     * "Open this vault for me — and reopen the menu on close." Server
     * responds with the standard chest GUI; close returns to the
     * PersonalVaultMenu chest GUI, which the mod intercepts and replaces
     * with a fresh overview.
     */
    public static void sendPvOpenRequest(int vaultNumber) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        if (vaultNumber < 1 || vaultNumber > Protocol.PV_MAX_VAULTS) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] {
                    Protocol.PKT_PV_OPEN_REQ, (byte) (vaultNumber & 0xFF) }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send pv open req failed", t);
        }
    }

    /** "I closed the /pvsee terminal — end my admin session." Tells the server
     *  to stop treating my PV packets as acting on the inspected player's
     *  vaults. No payload. */
    public static void sendPvSeeClose() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_PV_PVSEE_CLOSE }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send pvsee close failed", t);
        }
    }

    /** "Swap PV from ↔ PV to." Triggered by drag-drop in the overview. Server
     *  validates accessibility + slot capacity, swaps contents atomically, then
     *  pushes a fresh PKT_PV_BUNDLE. */
    public static void sendPvSwapRequest(int from, int to) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        if (from < 1 || from > Protocol.PV_MAX_VAULTS) return;
        if (to < 1 || to > Protocol.PV_MAX_VAULTS) return;
        if (from == to) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] {
                    Protocol.PKT_PV_SWAP_REQ, (byte) (from & 0xFF), (byte) (to & 0xFF) }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send pv swap req failed", t);
        }
    }

    /** "Extract from PV terminal." Server pulls (mode-determined amount) from
     *  vault N slot M into the player's inventory and pushes a fresh bundle.
     *  {@code mode} is one of {@link Protocol#PV_EXTRACT_ONE},
     *  {@link Protocol#PV_EXTRACT_HALF}, {@link Protocol#PV_EXTRACT_ALL}. */
    public static void sendPvExtract(int vaultNumber, int slotIndex, byte mode, byte target) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        if (vaultNumber < 1 || vaultNumber > Protocol.PV_MAX_VAULTS) return;
        if (slotIndex < 0 || slotIndex >= Protocol.PV_MAX_SLOTS) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(8));
            buf.writeByte(Protocol.PKT_PV_EXTRACT_REQ);
            buf.writeByte(vaultNumber & 0xFF);
            buf.writeShort(slotIndex & 0xFFFF);
            buf.writeByte(mode);
            buf.writeByte(target);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send pv extract failed", t);
        }
    }

    /** "Extract this item across all my PVs." For an aggregated terminal tile
     *  (the same item summed across several PV slots): the server pulls the
     *  mode-determined amount from every matching slot in one transaction and
     *  pushes a single fresh bundle. {@code refVault}/{@code refSlot} only name
     *  which item to match (one of the tile's source slots). */
    public static void sendPvExtractItem(int refVault, int refSlot, byte mode, byte target) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        if (refVault < 1 || refVault > Protocol.PV_MAX_VAULTS) return;
        if (refSlot < 0 || refSlot >= Protocol.PV_MAX_SLOTS) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(8));
            buf.writeByte(Protocol.PKT_PV_EXTRACT_ITEM);
            buf.writeByte(refVault & 0xFF);
            buf.writeShort(refSlot & 0xFFFF);
            buf.writeByte(mode);
            buf.writeByte(target);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send pv extract item failed", t);
        }
    }

    /** "Place my cursor stack into player-inventory slot N." Server swaps /
     *  merges as appropriate and syncs the cursor + slot back. */
    public static void sendPvCursorPlaceInv(int playerInvSlot) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        if (playerInvSlot < 0 || playerInvSlot > 35) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(4));
            buf.writeByte(Protocol.PKT_PV_CURSOR_PLACE_INV);
            buf.writeByte(playerInvSlot & 0xFF);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send pv cursor place failed", t);
        }
    }

    /** "Return my cursor stack to a PV/inv." Sent on terminal close so a
     *  picked-up stack is never left dangling. */
    public static void sendPvCursorReturn() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_PV_CURSOR_RETURN }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send pv cursor return failed", t);
        }
    }

    /** Terminal deposit: shift-clicking a player-inventory slot while the PV
     *  terminal is open asks the server to push that stack into the first vault
     *  with space (merge-then-fill, no affinity). During a /pvsee session the
     *  items land in the inspected player's vaults. */
    public static void sendPvShiftClick(int playerInvSlot) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(8));
            buf.writeByte(Protocol.PKT_PV_SHIFT_CLICK_REQ);
            buf.writeInt(playerInvSlot);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send pv shift-click failed", t);
        }
    }

    // ── Loot browser sends ───────────────────────────────────────────────────

    /** "Player ran /loottables — send me the catalog." Single-byte payload; the
     *  server registers the player as a viewer and replies with chunked
     *  {@link Protocol#PKT_LOOT_SNAPSHOT_CHUNK} packets. */
    public static void sendLootRequest() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_LOOT_REQ }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send loot request failed", t);
        }
    }

    /** "I closed the loot browser." Server stops pushing reload/discovery refreshes. */
    public static void sendLootClose() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_LOOT_CLOSE }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send loot close failed", t);
        }
    }

    // ── Cell-terminal sends ──────────────────────────────────────────────────

    /** "Extract from this cell container slot." Server pulls the mode-determined
     *  amount from container N slot M (cursor or inventory target) and pushes a
     *  fresh {@link Protocol#PKT_CELLTERM_BUNDLE}. Mode/target bytes share the
     *  PV values ({@link Protocol#PV_EXTRACT_ONE} etc.). */
    public static void sendCellTermExtract(int containerId, int slotIndex, byte mode, byte target) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        if (containerId < 0 || containerId > 0xFFFF) return;
        if (slotIndex < 0 || slotIndex >= Protocol.CELLTERM_MAX_SLOTS_PER_CONTAINER) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(8));
            buf.writeByte(Protocol.PKT_CELLTERM_EXTRACT);
            buf.writeVarInt(containerId);
            buf.writeShort(slotIndex & 0xFFFF);
            buf.writeByte(mode);
            buf.writeByte(target);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send cellterm extract failed", t);
        }
    }

    /** "Extract this item across all my cell containers." The reference
     *  {@code (containerId, slot)} only names which item to match — the server
     *  matches all isSimilar stacks across the session's containers, caps by
     *  destination space before mutating, drains ascending (containerId, slot)
     *  in one tick, then pushes a single fresh bundle. */
    public static void sendCellTermExtractItem(int refContainerId, int refSlot, byte mode, byte target) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        if (refContainerId < 0 || refContainerId > 0xFFFF) return;
        if (refSlot < 0 || refSlot >= Protocol.CELLTERM_MAX_SLOTS_PER_CONTAINER) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(8));
            buf.writeByte(Protocol.PKT_CELLTERM_EXTRACT_ITEM);
            buf.writeVarInt(refContainerId);
            buf.writeShort(refSlot & 0xFFFF);
            buf.writeByte(mode);
            buf.writeByte(target);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send cellterm extract item failed", t);
        }
    }

    /** Cell-terminal deposit: push the given player-inventory stack into the
     *  cell — vault (container 0) first, then other containers in id order;
     *  merge-into-similar then empty slots. Server replies with a fresh bundle. */
    public static void sendCellTermDeposit(int playerInvSlot) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        if (playerInvSlot < 0 || playerInvSlot > 35) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(8));
            buf.writeByte(Protocol.PKT_CELLTERM_DEPOSIT);
            buf.writeInt(playerInvSlot);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send cellterm deposit failed", t);
        }
    }

    /** "Place my cursor stack into player-inventory slot N" (cell-terminal
     *  session). Vanilla click semantics — server swaps / merges and syncs back. */
    public static void sendCellTermCursorPlaceInv(int playerInvSlot) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        if (playerInvSlot < 0 || playerInvSlot > 35) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(4));
            buf.writeByte(Protocol.PKT_CELLTERM_CURSOR_PLACE_INV);
            buf.writeByte(playerInvSlot & 0xFF);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send cellterm cursor place failed", t);
        }
    }

    /** "Return my cursor stack" — cell containers first, then player inventory,
     *  then drop at feet. Sent on cell-terminal close so a picked-up stack is
     *  never left dangling. No payload. */
    public static void sendCellTermCursorReturn() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_CELLTERM_CURSOR_RETURN }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send cellterm cursor return failed", t);
        }
    }

    /** "I closed the cell terminal — end my session." No payload. Not sent when
     *  the close was server-initiated (S2C {@link Protocol#PKT_CELLTERM_CLOSE}). */
    public static void sendCellTermClose() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_CELLTERM_CLOSE_C2S }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send cellterm close failed", t);
        }
    }

    /** Ask the server for a fresh cell-terminal bundle (server enforces a
     *  ~500ms cooldown). No payload. */
    public static void sendCellTermRefreshRequest() {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] { Protocol.PKT_CELLTERM_REFRESH_REQ }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send cellterm refresh failed", t);
        }
    }

    /**
     * Report the cell-terminal feature-toggle state. When disabled, the server
     * must NOT intercept vault-chest opens for this client (the vanilla chest
     * opens instead). Sent once after the handshake on join and on every toggle
     * flip — mirror of the {@link Protocol#PKT_MINING_HUD_STATE} pattern.
     */
    public static void sendCellTermState(boolean on) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            ClientPlayNetworking.send(new RawPayload(new byte[] {
                    Protocol.PKT_CELLTERM_STATE, (byte) (on ? 1 : 0)
            }));
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send cellterm state failed", t);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Report this client's local timezone to the server so it renders all
     * player-facing clock times in the player's own zone. Sent once on join after
     * the handshake. The server validates the IANA id and ignores anything it can't
     * parse, falling back to its default display zone.
     */
    public static void sendClientTimezone() {
        String zoneId;
        try {
            zoneId = java.time.ZoneId.systemDefault().getId();
        } catch (Throwable t) {
            return; // no resolvable zone → leave the server on its default
        }
        sendString(Protocol.PKT_CLIENT_TIMEZONE, clamp(zoneId, Protocol.CLIENT_TIMEZONE_MAX_CHARS));
    }

    private static void sendString(byte typeId, String s) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!ClientPlayNetworking.canSend(RawPayload.ID)) return;
        try {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer(32));
            buf.writeByte(typeId);
            buf.writeString(s == null ? "" : s);
            sendBuf(buf);
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("send string packet failed", t);
        }
    }

    private static void sendBuf(PacketByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        ClientPlayNetworking.send(new RawPayload(data));
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    private NetworkHandler() {}
}
