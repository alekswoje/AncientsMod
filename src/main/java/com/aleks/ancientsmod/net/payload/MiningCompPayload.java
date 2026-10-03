package com.aleks.ancientsmod.net.payload;

import net.minecraft.network.PacketByteBuf;
import java.util.ArrayList;
import java.util.List;

public record MiningCompPayload(int seconds, String tier, int rank, int blocks, List<Entry> entries) {
    public record Entry(String name, int blocks) {}

    public static MiningCompPayload decode(PacketByteBuf buf) {
        int seconds = buf.readInt();
        String tier = buf.readString(16);
        int rank = buf.readInt();
        int blocks = buf.readInt();
        int count = buf.readUnsignedByte();
        if (seconds < 0 || rank < 0 || blocks < 0 || count > 5)
            throw new IllegalArgumentException("Invalid mining competition snapshot");
        List<Entry> entries = new ArrayList<>(count);
        int previous = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            String name = buf.readString(16);
            int score = buf.readInt();
            if (score < 0 || score > previous) throw new IllegalArgumentException("Invalid standings");
            entries.add(new Entry(name, score));
            previous = score;
        }
        return new MiningCompPayload(seconds, tier, rank, blocks, List.copyOf(entries));
    }
}
