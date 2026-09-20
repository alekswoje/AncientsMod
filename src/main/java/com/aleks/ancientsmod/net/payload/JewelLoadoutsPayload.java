package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.client.hud.JewelState;
import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The player's jewel loadouts (server → mod), for the loadout tabs.
 *
 * <p>Wire: {@code activePage}, {@code count}, then per page {@code name},
 * {@code unlocked}, and a fixed number of pairs of {@code jewelName} /
 * {@code modelPath}. The pairs are always written, empty sockets included, so
 * the decode reads a fixed width per page.
 *
 * <p>That width is NOT on the wire, and it is not a constant either: a server
 * writes as many pairs as it believes this client reads, so one that predates
 * the fourth socket writes {@link Protocol#LEGACY_MAX_JEWEL_SLOTS} to a client
 * that can handle {@link Protocol#MAX_JEWEL_SLOTS}. Reading one pair too many
 * or too few does not just lose a jewel — the next page's name is read out of
 * the middle of this one, so the whole tab strip comes out as garbage. Hence
 * the trial decode below rather than a hardcoded bound.
 *
 * <p>Names and model paths are server-authored for the same reason
 * {@link JewelSlotsPayload} carries them: the client cannot name a unique, and
 * reconstructing one here produced "Divine Aetheric Jewel".
 */
public record JewelLoadoutsPayload(int activePage, List<Page> pages) {

    /**
     * Reads the pages at the width the server is most likely using, and falls
     * back to the other one when that read doesn't land exactly on the end of
     * the packet.
     *
     * <p>The socket packet states the width the server picked for this client,
     * so it is the first guess — but the two packets can arrive in either
     * order (a loadout load that finishes before the socket load pushes tabs on
     * its own), so the guess has to be checked rather than trusted. A wrong
     * width either runs off the end of the buffer or leaves most of a page
     * behind; the right one consumes the packet exactly.
     *
     * <p>Only a clean read overrides the first guess. A future server appending
     * a field would leave bytes over at the correct width too, and the wrong
     * width will not also happen to finish on the last byte.
     */
    public static JewelLoadoutsPayload decode(PacketByteBuf buf) {
        int start = buf.readerIndex();
        int width = JewelState.socketWireWidth();
        Decoded best = attempt(buf, start, width);
        if (best == null || best.leftover() > 0) {
            int fallback = width == Protocol.MAX_JEWEL_SLOTS
                    ? Protocol.LEGACY_MAX_JEWEL_SLOTS : Protocol.MAX_JEWEL_SLOTS;
            Decoded other = attempt(buf, start, fallback);
            if (other != null && other.leftover() == 0) best = other;
        }
        if (best == null) {
            // Unreadable either way. Throwing leaves the tabs showing what the
            // last good push said, which beats blanking them on a bad packet;
            // NetworkHandler drops it on the way out.
            throw new IllegalArgumentException("jewel loadouts: unreadable per-page width");
        }
        return best.payload();
    }

    /** One decode attempt at a given per-page width, or null if it didn't parse. */
    private static Decoded attempt(PacketByteBuf buf, int start, int slotsPerPage) {
        buf.readerIndex(start);
        try {
            int activePage = buf.readByte() & 0xFF;
            int count = Math.min(buf.readByte() & 0xFF, Protocol.MAX_JEWEL_LOADOUTS);
            List<Page> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String name = buf.readString(Protocol.JEWEL_LOADOUT_MAX_NAME_CHARS);
                boolean unlocked = (buf.readByte() & 0xFF) != 0;
                List<String> jewelNames = new ArrayList<>(slotsPerPage);
                List<String> modelPaths = new ArrayList<>(slotsPerPage);
                for (int slot = 0; slot < slotsPerPage; slot++) {
                    jewelNames.add(buf.readString(Protocol.JEWEL_MAX_NAME_CHARS));
                    modelPaths.add(buf.readString(Protocol.JEWEL_MAX_MODEL_CHARS));
                }
                out.add(new Page(name,
                        unlocked,
                        Collections.unmodifiableList(jewelNames),
                        Collections.unmodifiableList(modelPaths)));
            }
            if (activePage >= out.size()) activePage = 0;
            return new Decoded(
                    new JewelLoadoutsPayload(activePage, Collections.unmodifiableList(out)),
                    buf.readableBytes());
        } catch (RuntimeException wrongWidth) {
            // Ran off the end, or a string length that can only come from
            // reading a byte that wasn't one. Either way: not this width.
            return null;
        }
    }

    /** A parsed packet plus the bytes that attempt left behind. */
    private record Decoded(JewelLoadoutsPayload payload, int leftover) {}

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
