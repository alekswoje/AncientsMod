package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded {@code PKT_NAMETAG_OPEN}: everything the rename GUI needs to show the
 * player exactly what they are editing.
 *
 * <p>The icon key uses the same codec as PV / cell-terminal slots, so
 * {@link com.aleks.ancientsmod.client.IconResolver} reconstructs custom art from
 * its {@code #m} item-model token rather than falling back to the base material.
 * The lore comes along so the preview can render the real tooltip the new name
 * sits above.
 */
public final class NametagOpenPayload {

    public final String token;
    public final String iconKey;
    /** The item's current name in legacy {@code &}-form. May be empty (unnamed item). */
    public final String currentName;
    /** Server's cap on VISIBLE characters (colour codes are free). */
    public final int maxNameChars;
    /** Existing lore lines, legacy section-coded, as the server sees them. */
    public final List<String> lore;

    private NametagOpenPayload(String token, String iconKey, String currentName,
                               int maxNameChars, List<String> lore) {
        this.token = token;
        this.iconKey = iconKey;
        this.currentName = currentName;
        this.maxNameChars = maxNameChars;
        this.lore = lore;
    }

    public static NametagOpenPayload decode(PacketByteBuf buf) {
        String token = clamp(buf.readString(Protocol.NAMETAG_MAX_TOKEN_CHARS),
                Protocol.NAMETAG_MAX_TOKEN_CHARS);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("nametag open: empty token");
        }
        String iconKey = clamp(buf.readString(Protocol.PV_MAX_MATERIAL_KEY_CHARS),
                Protocol.PV_MAX_MATERIAL_KEY_CHARS);
        String currentName = clamp(buf.readString(Protocol.NAMETAG_MAX_INPUT_CHARS),
                Protocol.NAMETAG_MAX_INPUT_CHARS);

        int maxNameChars = buf.readVarInt();
        // A hostile or buggy server must not be able to hand us a cap of 0 (nothing
        // is ever submittable) or a huge one (the field would outrun the panel).
        if (maxNameChars < 1 || maxNameChars > Protocol.NAMETAG_MAX_NAME_CHARS) {
            maxNameChars = 32;
        }

        int loreCount = buf.readUnsignedByte();
        if (loreCount > Protocol.NAMETAG_MAX_LORE_LINES) {
            throw new IllegalArgumentException("nametag open: lore count " + loreCount);
        }
        List<String> lore = new ArrayList<>(loreCount);
        for (int i = 0; i < loreCount; i++) {
            lore.add(clamp(buf.readString(Protocol.NAMETAG_MAX_LORE_LINE_CHARS),
                    Protocol.NAMETAG_MAX_LORE_LINE_CHARS));
        }
        return new NametagOpenPayload(token, iconKey, currentName, maxNameChars, List.copyOf(lore));
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
