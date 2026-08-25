package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The player's jewel loadouts (server → mod), for the loadout tabs.
 *
 * <p>Wire: {@code activePage}, {@code count}, then per page {@code name},
 * {@code unlocked}, and exactly {@link Protocol#MAX_JEWEL_SLOTS} pairs of
 * {@code jewelName} / {@code modelPath}. The pairs are always written, empty
 * sockets included, so the decode reads a fixed width per page.
 *
 * <p>Names and model paths are server-authored for the same reason
 * {@link JewelSlotsPayload} carries them: the client cannot name a unique, and
 * reconstructing one here produced "Divine Aetheric Jewel".
 */
public record JewelLoadoutsPayload(int activePage, List<Page> pages) {

    public static JewelLoadoutsPayload decode(PacketByteBuf buf) {
        int activePage = buf.readByte() & 0xFF;
        int count = Math.min(buf.readByte() & 0xFF, Protocol.MAX_JEWEL_LOADOUTS);
        List<Page> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = buf.readString(Protocol.JEWEL_LOADOUT_MAX_NAME_CHARS);
            boolean unlocked = (buf.readByte() & 0xFF) != 0;
            List<String> jewelNames = new ArrayList<>(Protocol.MAX_JEWEL_SLOTS);
            List<String> modelPaths = new ArrayList<>(Protocol.MAX_JEWEL_SLOTS);
            for (int slot = 0; slot < Protocol.MAX_JEWEL_SLOTS; slot++) {
                jewelNames.add(buf.readString(Protocol.JEWEL_MAX_NAME_CHARS));
                modelPaths.add(buf.readString(Protocol.JEWEL_MAX_MODEL_CHARS));
            }
            out.add(new Page(name,
                    unlocked,
                    Collections.unmodifiableList(jewelNames),
                    Collections.unmodifiableList(modelPaths)));
        }
        if (activePage >= out.size()) activePage = 0;
        return new JewelLoadoutsPayload(activePage, Collections.unmodifiableList(out));
    }

    public record Page(String name, boolean unlocked,
                       List<String> jewelNames, List<String> modelPaths) {

        /** Sockets on this page holding a jewel. */
        public int filled() {
            int n = 0;
            for (String jewelName : jewelNames) {
                if (jewelName != null && !jewelName.isEmpty()) n++;
            }
            return n;
        }
    }
}
