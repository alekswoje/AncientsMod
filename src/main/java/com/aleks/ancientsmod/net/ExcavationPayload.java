package com.aleks.ancientsmod.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Small, server-authoritative visual hints; never sends client gameplay actions. */
public record ExcavationPayload(byte[] data) implements CustomPayload {
    public static final Id<ExcavationPayload> ID = new Id<>(Identifier.of("ancients", "excavation_v1"));
    public static final PacketCodec<PacketByteBuf, ExcavationPayload> CODEC = PacketCodec.of(
            (payload, buf) -> buf.writeBytes(payload.data), buf -> {
                int size = buf.readableBytes();
                if (size < 1 || size > 96) {
                    buf.skipBytes(size); return new ExcavationPayload(new byte[0]);
                }
                byte[] data = new byte[size]; buf.readBytes(data); return new ExcavationPayload(data);
            });
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
