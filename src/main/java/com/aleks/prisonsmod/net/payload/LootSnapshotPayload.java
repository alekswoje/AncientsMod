package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded loot-browser catalog (reassembled from {@code PKT_LOOT_SNAPSHOT_CHUNK}).
 * Mirrors what the server's {@code /loottables} chest GUI shows: the home
 * categories, their tables, and per-entry drop chance / rarity / amount, with
 * cluster-wide discovery masking already applied server-side.
 *
 * <p>The wire body is built by {@code com.aleks.prisons.loot.LootBrowserService}
 * with a shared string-intern pool — decode resolves indices back to strings so
 * the model holds plain values.
 */
public final class LootSnapshotPayload {

    public final List<Category> categories;
    public final List<Table> tables;

    private LootSnapshotPayload(List<Category> categories, List<Table> tables) {
        this.categories = categories;
        this.tables = tables;
    }

    public static LootSnapshotPayload decode(byte[] body) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(body));

        int wireFormat = buf.readVarInt();
        if (wireFormat != 1) {
            // Unknown schema — refuse rather than mis-parse. Caller treats a
            // null/throw as "drop snapshot".
            throw new IllegalArgumentException("loot snapshot wire format " + wireFormat);
        }

        int stringCount = buf.readVarInt();
        if (stringCount < 0 || stringCount > Protocol.LOOT_MAX_STRINGS) {
            throw new IllegalArgumentException("loot strings " + stringCount);
        }
        List<String> pool = new ArrayList<>(stringCount);
        for (int i = 0; i < stringCount; i++) {
            pool.add(buf.readString(Protocol.LOOT_MAX_STRING_CHARS));
        }

        int catCount = buf.readVarInt();
        if (catCount < 0 || catCount > Protocol.LOOT_MAX_CATEGORIES) {
            throw new IllegalArgumentException("loot categories " + catCount);
        }
        List<Category> categories = new ArrayList<>(catCount);
        for (int i = 0; i < catCount; i++) {
            String key = str(pool, buf.readVarInt());
            String label = str(pool, buf.readVarInt());
            String iconKey = str(pool, buf.readVarInt());
            categories.add(new Category(key, label, iconKey));
        }

        int tableCount = buf.readVarInt();
        if (tableCount < 0 || tableCount > Protocol.LOOT_MAX_TABLES) {
            throw new IllegalArgumentException("loot tables " + tableCount);
        }
        List<Table> tables = new ArrayList<>(tableCount);
        for (int t = 0; t < tableCount; t++) {
            int catIdx = buf.readVarInt();
            String tableId = str(pool, buf.readVarInt());
            String name = str(pool, buf.readVarInt());
            String iconKey = str(pool, buf.readVarInt());
            int rollsMin = buf.readVarInt();
            int rollsMax = buf.readVarInt();
            boolean luck = buf.readByte() != 0;

            int entryCount = buf.readVarInt();
            if (entryCount < 0 || entryCount > Protocol.LOOT_MAX_ENTRIES_PER_TABLE) {
                throw new IllegalArgumentException("loot entries " + entryCount);
            }
            List<Entry> entries = new ArrayList<>(entryCount);
            for (int e = 0; e < entryCount; e++) {
                boolean masked = buf.readByte() != 0;
                int rarity = buf.readByte() & 0xFF;
                int chanceMilli = buf.readVarInt();
                if (masked) {
                    entries.add(new Entry(true, rarity, chanceMilli, (byte) 0, "", "", ""));
                } else {
                    byte type = buf.readByte();
                    String eName = str(pool, buf.readVarInt());
                    String eIcon = str(pool, buf.readVarInt());
                    String amount = str(pool, buf.readVarInt());
                    entries.add(new Entry(false, rarity, chanceMilli, type, eName, eIcon, amount));
                }
            }
            int safeCatIdx = (catIdx >= 0 && catIdx < categories.size()) ? catIdx : -1;
            tables.add(new Table(safeCatIdx, tableId, name, iconKey, rollsMin, rollsMax, luck, entries));
        }
        return new LootSnapshotPayload(categories, tables);
    }

    private static String str(List<String> pool, int idx) {
        return (idx >= 0 && idx < pool.size()) ? pool.get(idx) : "";
    }

    public static final class Category {
        public final String key;
        public final String label;
        public final String iconKey;

        public Category(String key, String label, String iconKey) {
            this.key = key;
            this.label = label;
            this.iconKey = iconKey;
        }
    }

    public static final class Table {
        public final int categoryIndex;
        public final String tableId;
        public final String name;
        public final String iconKey;
        public final int rollsMin;
        public final int rollsMax;
        public final boolean luck;
        public final List<Entry> entries;

        public Table(int categoryIndex, String tableId, String name, String iconKey,
                     int rollsMin, int rollsMax, boolean luck, List<Entry> entries) {
            this.categoryIndex = categoryIndex;
            this.tableId = tableId;
            this.name = name;
            this.iconKey = iconKey;
            this.rollsMin = rollsMin;
            this.rollsMax = rollsMax;
            this.luck = luck;
            this.entries = entries;
        }

        public String rollsText() {
            return rollsMin == rollsMax ? String.valueOf(rollsMin) : (rollsMin + "-" + rollsMax);
        }
    }

    /** A single drop entry. {@code rarity} is a {@code LootRarity} level 0..5,
     *  or 255 for "no rarity". When {@code masked}, identity fields are empty
     *  (the server hid an undiscovered in-scope drop) but rarity + chance show. */
    public static final class Entry {
        public final boolean masked;
        public final int rarity;
        public final int chanceMilli;
        public final byte type;
        public final String name;
        public final String iconKey;
        public final String amountText;

        public Entry(boolean masked, int rarity, int chanceMilli, byte type,
                     String name, String iconKey, String amountText) {
            this.masked = masked;
            this.rarity = rarity;
            this.chanceMilli = chanceMilli;
            this.type = type;
            this.name = name;
            this.iconKey = iconKey;
            this.amountText = amountText;
        }

        /** Drop chance as a percentage (0..100). */
        public double chancePct() {
            return chanceMilli / 1000.0;
        }

        public String chanceText() {
            return String.format(java.util.Locale.US, "%.2f%%", chancePct());
        }
    }
}
