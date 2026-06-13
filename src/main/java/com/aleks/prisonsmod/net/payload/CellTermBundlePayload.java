package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded {@code PKT_CELLTERM_BUNDLE}: a full snapshot of every container in
 * the player's cell (vault + chests + barrels), used by the mod's
 * cell-terminal screen.
 *
 * <p>Bundles {@link Container} entries each carrying a stable per-session id
 * (the vault is always id 0), a server-chosen display label, the slot count,
 * and a list of non-empty slot stubs. The per-slot codec is IDENTICAL to the
 * PV bundle's — both decode through
 * {@link PvBundlePayload#readSlot(PacketByteBuf, int)} so the two can never
 * drift.
 *
 * <p>Defensive decoding, same posture as {@link PvBundlePayload#decode}: any
 * bounds violation throws {@link IllegalArgumentException} and the dispatcher
 * drops the whole packet silently.
 */
public final class CellTermBundlePayload {

    public final List<Container> containers;

    private CellTermBundlePayload(List<Container> containers) {
        this.containers = containers;
    }

    public static CellTermBundlePayload decode(PacketByteBuf buf) {
        int containerCount = buf.readVarInt();
        if (containerCount < 0 || containerCount > Protocol.CELLTERM_MAX_CONTAINERS) {
            throw new IllegalArgumentException("cellterm bundle: container count " + containerCount + " > max");
        }
        List<Container> containers = new ArrayList<>(containerCount);
        for (int c = 0; c < containerCount; c++) {
            int containerId = buf.readVarInt();
            // Ids only need to be stable within a session, but bound them so the
            // screen's (containerId << 16 | slot) optimistic-override key packing
            // stays collision-free no matter what a misbehaving server sends.
            if (containerId < 0 || containerId > 0xFFFF) {
                throw new IllegalArgumentException("cellterm bundle: container id " + containerId + " out of range");
            }
            String label = clamp(buf.readString(Protocol.CELLTERM_MAX_CONTAINER_LABEL_CHARS),
                    Protocol.CELLTERM_MAX_CONTAINER_LABEL_CHARS);
            int slotCount = buf.readShort() & 0xFFFF;
            if (slotCount > Protocol.CELLTERM_MAX_SLOTS_PER_CONTAINER) {
                throw new IllegalArgumentException("cellterm bundle: slot count " + slotCount + " > max");
            }
            int nonEmptyCount = buf.readShort() & 0xFFFF;
            if (nonEmptyCount > slotCount) {
                throw new IllegalArgumentException("cellterm bundle: non-empty " + nonEmptyCount + " > slotCount " + slotCount);
            }
            List<PvBundlePayload.Slot> slots = new ArrayList<>(nonEmptyCount);
            for (int i = 0; i < nonEmptyCount; i++) {
                slots.add(PvBundlePayload.readSlot(buf, slotCount));
            }
            containers.add(new Container(containerId, label, slotCount, slots));
        }
        return new CellTermBundlePayload(containers);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    /** One cell container: stable id (vault = 0), display label, capacity, and
     *  its non-empty slot stubs (shared {@link PvBundlePayload.Slot} shape). */
    public static final class Container {
        public final int containerId;
        public final String label;
        public final int slotCount;
        public final List<PvBundlePayload.Slot> slots;

        public Container(int containerId, String label, int slotCount,
                         List<PvBundlePayload.Slot> slots) {
            this.containerId = containerId;
            this.label = label == null ? "" : label;
            this.slotCount = slotCount;
            this.slots = slots;
        }
    }
}
