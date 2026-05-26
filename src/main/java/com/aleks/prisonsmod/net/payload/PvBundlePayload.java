package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded {@code PKT_PV_BUNDLE}: a snapshot of every PV's contents, used by
 * the mod's custom {@code /pv} overview screen.
 *
 * <p>Bundles {@link Vault} entries each containing slot count, affinity csv,
 * and a list of {@link Slot} stubs. Each stub holds the material id, the
 * custom display name (or empty for vanilla), and the stack amount — no NBT,
 * so the screen renders vanilla item icons.
 */
public final class PvBundlePayload {

    public final List<Vault> vaults;

    private PvBundlePayload(List<Vault> vaults) {
        this.vaults = vaults;
    }

    public static PvBundlePayload decode(PacketByteBuf buf) {
        int pvCount = buf.readByte() & 0xFF;
        if (pvCount > Protocol.PV_MAX_VAULTS) {
            throw new IllegalArgumentException("pv bundle: vault count " + pvCount + " > max");
        }
        List<Vault> vaults = new ArrayList<>(pvCount);
        for (int v = 0; v < pvCount; v++) {
            int vaultNumber = buf.readByte() & 0xFF;
            int slotCount = buf.readShort() & 0xFFFF;
            if (slotCount > Protocol.PV_MAX_SLOTS) {
                throw new IllegalArgumentException("pv bundle: slot count " + slotCount + " > max");
            }
            int nonEmptyCount = buf.readShort() & 0xFFFF;
            if (nonEmptyCount > slotCount) {
                throw new IllegalArgumentException("pv bundle: non-empty " + nonEmptyCount + " > slotCount " + slotCount);
            }
            List<Slot> slots = new ArrayList<>(nonEmptyCount);
            for (int i = 0; i < nonEmptyCount; i++) {
                int slotIndex = buf.readShort() & 0xFFFF;
                if (slotIndex >= slotCount) {
                    throw new IllegalArgumentException("pv bundle: slotIndex " + slotIndex + " >= slotCount " + slotCount);
                }
                String materialKey = clamp(buf.readString(Protocol.PV_MAX_MATERIAL_KEY_CHARS),
                        Protocol.PV_MAX_MATERIAL_KEY_CHARS);
                String displayName = clamp(buf.readString(Protocol.PV_MAX_DISPLAY_NAME_CHARS),
                        Protocol.PV_MAX_DISPLAY_NAME_CHARS);
                int amount = buf.readInt();
                if (amount < 0) amount = 0;
                slots.add(new Slot(slotIndex, materialKey, displayName, amount));
            }
            String affinityCsv = clamp(buf.readString(Protocol.PV_MAX_AFFINITY_CSV_CHARS),
                    Protocol.PV_MAX_AFFINITY_CSV_CHARS);
            vaults.add(new Vault(vaultNumber, slotCount, slots, affinityCsv));
        }
        return new PvBundlePayload(vaults);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    public static final class Vault {
        public final int vaultNumber;
        public final int slotCount;
        public final List<Slot> slots;
        public final String affinityCsv;

        public Vault(int vaultNumber, int slotCount, List<Slot> slots, String affinityCsv) {
            this.vaultNumber = vaultNumber;
            this.slotCount = slotCount;
            this.slots = slots;
            this.affinityCsv = affinityCsv;
        }

        public boolean isAccessible() {
            return slotCount > 0;
        }
    }

    public static final class Slot {
        public final int slotIndex;
        public final String materialKey;
        public final String displayName;
        public final int amount;

        public Slot(int slotIndex, String materialKey, String displayName, int amount) {
            this.slotIndex = slotIndex;
            this.materialKey = materialKey;
            this.displayName = displayName;
            this.amount = amount;
        }
    }
}
