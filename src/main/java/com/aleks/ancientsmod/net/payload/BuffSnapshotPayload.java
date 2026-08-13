package com.aleks.ancientsmod.net.payload;

import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoded form of the server's {@code PKT_BUFF_SNAPSHOT} packet — the data
 * source for the in-game {@code /pickbuffs} breakdown screen.
 *
 * <p>Wire format (matches plugin {@code BuffLayerGraph}):
 * <pre>
 *   byte    PKT_BUFF_SNAPSHOT
 *   byte    flags          (bit 0 inMines, bit 1 inTutorialWorld, bit 2 atLevelCap)
 *   varint  channelCount
 *   for each channel:
 *     byte    channelId
 *     double  serverFinalValue
 *     varint  layerCount
 *     for each layer:
 *       varintString  label
 *       varintString  detail
 *       byte    category
 *       byte    kind
 *       byte    state
 *       double  value
 * </pre>
 */
public final class BuffSnapshotPayload {

    // Channel ids (must match plugin BuffLayerGraph constants).
    public static final byte CH_MINING_XP      = 1;
    public static final byte CH_MINING_ENERGY  = 2;
    public static final byte CH_MINING_MONEY   = 3;
    public static final byte CH_MINING_SHARDS  = 4;
    public static final byte CH_MINING_ORE     = 5;
    public static final byte CH_MINING_METEOR  = 6;
    public static final byte CH_COMBAT_OUT     = 7;
    public static final byte CH_COMBAT_IN      = 8;
    public static final byte CH_BOSS_OUT       = 9;
    public static final byte CH_BOSS_IN        = 10;
    public static final byte CH_CRIT_CHANCE    = 11;
    public static final byte CH_CRIT_DAMAGE    = 12;
    public static final byte CH_MINING_SPEED   = 13;  // pickaxe break-speed multiplier (×N)

    // Layer categories.
    public static final byte CAT_BASE      = 0;
    public static final byte CAT_BOOSTER   = 1;
    public static final byte CAT_OUTPOST   = 2;
    public static final byte CAT_GANG      = 3;
    public static final byte CAT_PRESTIGE  = 4;
    public static final byte CAT_ENCHANT   = 5;
    public static final byte CAT_FATE_CARD = 6;
    public static final byte CAT_TUTORIAL  = 7;
    public static final byte CAT_TRIM      = 8;
    public static final byte CAT_ARMOR     = 9;
    public static final byte CAT_BOSS      = 10;
    public static final byte CAT_GEAR      = 11;
    public static final byte CAT_LEVEL     = 12;
    public static final byte CAT_OTHER     = 13;

    // Layer kinds — how the value combines in the stacker.
    public static final byte KIND_ADDITIVE       = 0;
    public static final byte KIND_MULTIPLICATIVE = 1;
    public static final byte KIND_FLAT_BONUS     = 2;
    public static final byte KIND_BASE           = 3;
    /** Info-only proc damage row. value = base avg/hit (no Lucky, no multipliers).
     *  Mod renders without checkbox; display value scales with active multipliers
     *  and the active KIND_LUCKY_PROC factor. */
    public static final byte KIND_PROC_DAMAGE    = 4;
    /** Toggleable multiplier applied ONLY to KIND_PROC_DAMAGE rows. value = lucky mult.
     *  Does NOT multiply the channel's base hit damage. */
    public static final byte KIND_LUCKY_PROC     = 5;

    // Layer state.
    public static final byte STATE_ACTIVE    = 0;
    public static final byte STATE_POTENTIAL = 1;

    /** Hard cap on layer-label characters; longer strings are truncated to bound memory. */
    public static final int MAX_LABEL_CHARS = 64;
    public static final int MAX_DETAIL_CHARS = 96;
    /** Hard cap on layers per channel; if the server somehow sends more, we drop the rest. */
    public static final int MAX_LAYERS_PER_CHANNEL = 64;
    public static final int MAX_CHANNELS = 32;
    /** Hard cap on per-ore yield rows in a snapshot. 9 ores in practice, leave headroom. */
    public static final int MAX_ORE_YIELDS = 32;

    public final boolean inMines;
    public final boolean inTutorialWorld;
    public final boolean atLevelCap;
    /** Channels in server-declared order — keyed by channel id byte. */
    public final Map<Byte, Channel> channels;
    /** Per-ore concrete yields after all currently-active multipliers. May be empty. */
    public final List<OreYield> oreYields;

    private BuffSnapshotPayload(boolean inMines, boolean inTutorialWorld, boolean atLevelCap,
                                 Map<Byte, Channel> channels, List<OreYield> oreYields) {
        this.inMines = inMines;
        this.inTutorialWorld = inTutorialWorld;
        this.atLevelCap = atLevelCap;
        this.channels = channels;
        this.oreYields = oreYields;
    }

    public static BuffSnapshotPayload decode(PacketByteBuf buf) {
        int flags = buf.readUnsignedByte();
        boolean inMines = (flags & 0x1) != 0;
        boolean inTutorial = (flags & 0x2) != 0;
        boolean atCap = (flags & 0x4) != 0;

        int channelCount = buf.readVarInt();
        if (channelCount < 0 || channelCount > MAX_CHANNELS) {
            throw new IllegalArgumentException("buff snapshot: too many channels (" + channelCount + ")");
        }

        Map<Byte, Channel> channels = new LinkedHashMap<>(channelCount);
        for (int i = 0; i < channelCount; i++) {
            byte id = buf.readByte();
            double serverFinal = buf.readDouble();
            int layerCount = buf.readVarInt();
            if (layerCount < 0 || layerCount > MAX_LAYERS_PER_CHANNEL) {
                throw new IllegalArgumentException("buff snapshot: too many layers in channel " + id + " (" + layerCount + ")");
            }
            List<Layer> layers = new ArrayList<>(layerCount);
            for (int j = 0; j < layerCount; j++) {
                String label  = clampString(buf.readString(MAX_LABEL_CHARS), MAX_LABEL_CHARS);
                String detail = clampString(buf.readString(MAX_DETAIL_CHARS), MAX_DETAIL_CHARS);
                byte category = buf.readByte();
                byte kind     = buf.readByte();
                byte state    = buf.readByte();
                double value  = buf.readDouble();
                if (!Double.isFinite(value)) value = 0.0;
                layers.add(new Layer(label, detail, category, kind, state, value));
            }
            channels.put(id, new Channel(id, serverFinal, layers));
        }

        // Per-ore yields — added after channels. Older servers won't write
        // these; treat the missing-trailer case as an empty list so older
        // servers still work.
        List<OreYield> oreYields = new ArrayList<>();
        if (buf.readableBytes() > 0) {
            int oreCount = buf.readVarInt();
            if (oreCount < 0 || oreCount > MAX_ORE_YIELDS) {
                throw new IllegalArgumentException("buff snapshot: too many ore yields (" + oreCount + ")");
            }
            for (int i = 0; i < oreCount; i++) {
                String name = clampString(buf.readString(MAX_LABEL_CHARS), MAX_LABEL_CHARS);
                double baseXp     = buf.readDouble();
                double baseEnergy = buf.readDouble();
                double baseMoney  = buf.readDouble();
                double baseShard  = buf.readDouble();
                double xp     = buf.readDouble();
                double energy = buf.readDouble();
                double money  = buf.readDouble();
                double shard  = buf.readDouble();
                if (!Double.isFinite(baseXp)) baseXp = 0;
                if (!Double.isFinite(baseEnergy)) baseEnergy = 0;
                if (!Double.isFinite(baseMoney)) baseMoney = 0;
                if (!Double.isFinite(baseShard)) baseShard = 0;
                if (!Double.isFinite(xp)) xp = 0;
                if (!Double.isFinite(energy)) energy = 0;
                if (!Double.isFinite(money)) money = 0;
                if (!Double.isFinite(shard)) shard = 0;
                oreYields.add(new OreYield(name,
                        baseXp, baseEnergy, baseMoney, baseShard,
                        xp, energy, money, shard));
            }
        }

        // Per-ore break times — a second trailing block, aligned by index with
        // oreYields. Older servers don't write it, so guard on remaining bytes.
        if (buf.readableBytes() > 0) {
            int breakCount = buf.readVarInt();
            if (breakCount < 0 || breakCount > MAX_ORE_YIELDS) {
                throw new IllegalArgumentException("buff snapshot: too many ore break times (" + breakCount + ")");
            }
            for (int i = 0; i < breakCount; i++) {
                double bms = buf.readDouble();
                if (!Double.isFinite(bms)) bms = -1.0;
                if (i < oreYields.size()) oreYields.get(i).breakMs = bms;
            }
        }
        return new BuffSnapshotPayload(inMines, inTutorial, atCap, channels, oreYields);
    }

    private static String clampString(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    /** A single contributing source within a channel. */
    public static final class Layer {
        public final String label;
        public final String detail;
        public final byte category;
        public final byte kind;
        public final byte state;
        public final double value;

        public Layer(String label, String detail, byte category, byte kind, byte state, double value) {
            this.label = label;
            this.detail = detail;
            this.category = category;
            this.kind = kind;
            this.state = state;
            this.value = value;
        }
    }

    /**
     * Per-ore concrete yield row — informational, not toggleable. Carries both
     * the base values (raw config) and the multiplied values (final) so the UI
     * can render "base × mult = result" inline.
     */
    public static final class OreYield {
        public final String displayName;
        public final double baseXp;
        public final double baseEnergy;
        public final double baseMoney;
        public final double baseShard;
        public final double xpPerOre;
        public final double energyPerOre;
        public final double moneyPerOre;
        public final double shardChancePerOre;
        /** Predicted break time for this ore in ms (incl. tier-up penalty + floor); -1 if the server didn't send it. */
        public double breakMs = -1.0;
        public OreYield(String displayName,
                        double baseXp, double baseEnergy, double baseMoney, double baseShard,
                        double xpPerOre, double energyPerOre, double moneyPerOre, double shardChancePerOre) {
            this.displayName = displayName;
            this.baseXp = baseXp;
            this.baseEnergy = baseEnergy;
            this.baseMoney = baseMoney;
            this.baseShard = baseShard;
            this.xpPerOre = xpPerOre;
            this.energyPerOre = energyPerOre;
            this.moneyPerOre = moneyPerOre;
            this.shardChancePerOre = shardChancePerOre;
        }
    }

    /** One channel's worth of layers — XP, Energy, Combat Out, etc. */
    public static final class Channel {
        public final byte id;
        /** The "what /pickbuffs would show right now" value, computed server-side. */
        public final double serverFinalValue;
        public final List<Layer> layers;

        public Channel(byte id, double serverFinalValue, List<Layer> layers) {
            this.id = id;
            this.serverFinalValue = serverFinalValue;
            this.layers = layers;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Channel + category display helpers (kept here so screen + tooltips share)
    // ─────────────────────────────────────────────────────────────────────────

    public static String channelDisplayName(byte id) {
        return switch (id) {
            case CH_MINING_XP     -> "Mining XP";
            case CH_MINING_ENERGY -> "Mining Energy";
            case CH_MINING_MONEY  -> "Mining Money";
            case CH_MINING_SHARDS -> "Mining Shards";
            case CH_MINING_ORE    -> "Mining Ore";
            case CH_MINING_METEOR -> "Meteorite Ore";
            case CH_COMBAT_OUT    -> "Damage Dealt";
            case CH_COMBAT_IN     -> "Damage Taken";
            case CH_CRIT_CHANCE   -> "Crit Chance";
            // "Crit Multi", not "Crit Damage": the channel carries the crit
            // MULTIPLIER, and every source feeding it now reads "+X% to Critical
            // Strike Multiplier" server-side (jewels, Brutality). Kept short to
            // sit alongside "Crit Chance". The constant keeps its wire name —
            // the id is the protocol, the string is only the label.
            case CH_CRIT_DAMAGE   -> "Crit Multi";
            case CH_BOSS_OUT      -> "Boss Damage Dealt";
            case CH_BOSS_IN       -> "Boss Damage Taken";
            case CH_MINING_SPEED  -> "Mining Speed";
            default -> "?";
        };
    }

    public static String categoryDisplayName(byte cat) {
        return switch (cat) {
            case CAT_BASE      -> "Base";
            case CAT_BOOSTER   -> "Booster";
            case CAT_OUTPOST   -> "Outpost";
            case CAT_GANG      -> "Gang";
            case CAT_PRESTIGE  -> "Prestige";
            case CAT_ENCHANT   -> "Enchant";
            case CAT_FATE_CARD -> "Fate Card";
            case CAT_TUTORIAL  -> "Tutorial";
            case CAT_TRIM      -> "Trim";
            case CAT_ARMOR     -> "Armor";
            case CAT_BOSS      -> "Boss";
            case CAT_GEAR      -> "Gear";
            case CAT_LEVEL     -> "Level";
            default            -> "Other";
        };
    }

    /** Theme color per category — matches BoosterHud accent palette where it makes sense. */
    public static int categoryColor(byte cat) {
        return switch (cat) {
            case CAT_BASE      -> 0xFFBFC4CC;
            case CAT_BOOSTER   -> 0xFFFFC857;
            case CAT_OUTPOST   -> 0xFF8AE08A;
            case CAT_GANG      -> 0xFF8AC2FF;
            case CAT_PRESTIGE  -> 0xFFE6B05A;
            case CAT_ENCHANT   -> 0xFFD37CFF;
            case CAT_FATE_CARD -> 0xFF6FE8C9;
            case CAT_TUTORIAL  -> 0xFFFFD68A;
            case CAT_TRIM      -> 0xFFE68AE0;
            case CAT_ARMOR     -> 0xFF9DD2FF;
            case CAT_BOSS      -> 0xFFFF6E6E;
            case CAT_GEAR      -> 0xFFFFE8B0;
            case CAT_LEVEL     -> 0xFFCFB8FF;
            default            -> 0xFFE6E8EE;
        };
    }
}
