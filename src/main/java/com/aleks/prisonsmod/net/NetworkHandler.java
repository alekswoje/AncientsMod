package com.aleks.prisonsmod.net;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.net.payload.CascadePayload;
import com.aleks.prisonsmod.net.payload.HudUpdatePayload;
import com.aleks.prisonsmod.net.payload.MineCancelPayload;
import com.aleks.prisonsmod.net.payload.MineStartPayload;
import com.aleks.prisonsmod.net.payload.PointGainPayload;
import com.aleks.prisonsmod.render.CascadeEffectRenderer;
import com.aleks.prisonsmod.render.FloatingNumberRenderer;
import com.aleks.prisonsmod.render.MinePredictRenderer;
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
            if (raw == null || raw.length == 0 || raw.length > Protocol.MAX_PAYLOAD_BYTES) {
                return; // drop oversized or empty
            }
            PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(raw));
            byte typeId = buf.readByte();
            switch (typeId) {
                case Protocol.PKT_POINT_GAIN -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.POINT_GAIN)) return;
                    PointGainPayload p = PointGainPayload.decode(buf);
                    FloatingNumberRenderer.enqueue(p);
                }
                case Protocol.PKT_CASCADE -> {
                    if (!RATE_LIMITER.tryAcquire(RateLimiter.Kind.CASCADE)) return;
                    CascadePayload p = CascadePayload.decode(buf);
                    CascadeEffectRenderer.enqueue(p);
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

    private NetworkHandler() {}
}
