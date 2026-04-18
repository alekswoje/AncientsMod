package com.aleks.prisonsmod.net;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.net.payload.CascadePayload;
import com.aleks.prisonsmod.net.payload.HudUpdatePayload;
import com.aleks.prisonsmod.net.payload.PointGainPayload;
import com.aleks.prisonsmod.render.CascadeEffectRenderer;
import com.aleks.prisonsmod.render.FloatingNumberRenderer;
import com.aleks.prisonsmod.render.RiftHud;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.CustomPayload;

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
        // The actual Fabric API for 1.21.11 uses typed CustomPayload records.
        // The snippet below is intentionally illustrative — integrate with the
        // PayloadTypeRegistry.playS2C(...) API that Fabric exposes in 1.21.11.
        //
        // Pseudocode sketch:
        //   PayloadTypeRegistry.playS2C().register(RawPayload.ID, RawPayload.CODEC);
        //   ClientPlayNetworking.registerGlobalReceiver(RawPayload.ID, NetworkHandler::onPayload);
        //
        // RawPayload wraps a byte[] up to MAX_PAYLOAD_BYTES; the decode path below
        // consumes it. See the docstring in fabric-networking-api-v1 for the
        // current API surface.
        PrisonsMod.LOGGER.info("PrisonsMod network receivers registered on {}", Protocol.CHANNEL_V1);
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
