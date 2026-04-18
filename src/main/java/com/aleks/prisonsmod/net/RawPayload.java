package com.aleks.prisonsmod.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * Opaque byte-array wrapper for the {@code prisonsmod:v1} custom payload
 * channel. The wire format inside the byte array is owned by
 * {@link NetworkHandler} — this class just enforces the hard size cap and
 * hands the bytes off.
 *
 * <p>Using a raw byte[] rather than typed per-packet records means the server
 * (Paper / Bukkit plugin messaging) can speak the same channel identifier
 * without Fabric-specific codec machinery.
 */
public record RawPayload(byte[] data) implements CustomPayload {

    public static final CustomPayload.Id<RawPayload> ID = new CustomPayload.Id<>(Protocol.CHANNEL_V1);

    public static final PacketCodec<PacketByteBuf, RawPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeBytes(payload.data),
            buf -> {
                int available = buf.readableBytes();
                if (available <= 0 || available > Protocol.MAX_PAYLOAD_BYTES) {
                    // Drain the buffer so the netty pipeline doesn't complain about unread bytes,
                    // then surface as an empty payload (handler drops it).
                    buf.skipBytes(Math.max(0, available));
                    return new RawPayload(new byte[0]);
                }
                byte[] data = new byte[available];
                buf.readBytes(data);
                return new RawPayload(data);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
