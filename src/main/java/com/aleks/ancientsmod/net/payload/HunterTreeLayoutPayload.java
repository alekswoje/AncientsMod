package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * The Hunter tree definition the server sends on {@code /hunter} (reassembled from
 * {@link Protocol#PKT_HUNTER_TREE_LAYOUT_CHUNK}). {@code x, y} are the server's grid cells;
 * {@code px, py} are where this screen draws the node, in world pixels with the start at 0,0;
 * a node on a wheel also carries the wheel's centre, and a link between two nodes of one wheel
 * is drawn as an arc around it. {@code icon} indexes the rows of
 * {@code textures/gui/hunter/nodes.png}.
 */
public record HunterTreeLayoutPayload(int rings, List<Node> nodes, int[] edgeA, int[] edgeB,
                                      List<Stat> stats, List<Ability> abilities, List<Label> labels) {

    /** {@code kind}: 0 start, 1 small, 2 major, 3 notable, 4 keystone. */
    public record Node(int x, int y, float px, float py, boolean wheel, float cx, float cy,
                       int kind, int icon, int stat, int ability) {}
    /**
     * {@code scale} and {@code unit} say how a value is shown: {@code value x scale + unit}. A server
     * that predates the display section sends neither, so a stat reads as a percent (100, "%").
     */
    public record Stat(String name, String description, int rgb, float small, float major, float scale, String unit) {
        public Stat(String name, String description, int rgb, float small, float major) {
            this(name, description, rgb, small, major, 100f, "%");
        }

        /** "7.5", "20%": a whole number when it is one, else one decimal, then the unit. No leading "+". */
        public String format(float value) {
            double v = value * (double) scale;
            long whole = Math.round(v);
            return (Math.abs(v - whole) < 0.05 ? String.valueOf(whole) : String.format(java.util.Locale.US, "%.1f", v)) + unit;
        }
    }
    public record Ability(String name, boolean keystone, List<String> lines) {}
    public record Label(String name, float px, float py) {}

    public static HunterTreeLayoutPayload decode(byte[] body) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(body));
        int format = buf.readVarInt();
        if (format != 3) throw new IllegalArgumentException("hunter tree format " + format);
        int rings = buf.readVarInt();
        int n = bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_NODES);
        List<Node> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int x = buf.readShort(), y = buf.readShort();
            float px = buf.readFloat(), py = buf.readFloat();
            boolean wheel = buf.readBoolean();
            float cx = wheel ? buf.readFloat() : 0f, cy = wheel ? buf.readFloat() : 0f;
            nodes.add(new Node(x, y, px, py, wheel, cx, cy, buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte()));
        }
        int e = bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_EDGES);
        int[] a = new int[e], b = new int[e];
        for (int i = 0; i < e; i++) {
            a[i] = index(buf.readVarInt(), n);
            b[i] = index(buf.readVarInt(), n);
        }
        int s = bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_NAMES);
        List<Stat> stats = new ArrayList<>(s);
        for (int i = 0; i < s; i++) {
            stats.add(new Stat(str(buf), str(buf), buf.readInt(), buf.readFloat(), buf.readFloat()));
        }
        int ab = bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_NAMES);
        List<Ability> abilities = new ArrayList<>(ab);
        for (int i = 0; i < ab; i++) {
            String name = str(buf);
            boolean keystone = buf.readBoolean();
            int lines = bounded(buf.readVarInt(), 16);
            List<String> text = new ArrayList<>(lines);
            for (int k = 0; k < lines; k++) text.add(str(buf));
            abilities.add(new Ability(name, keystone, text));
        }
        int l = bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_NAMES);
        List<Label> labels = new ArrayList<>(l);
        for (int i = 0; i < l; i++) labels.add(new Label(str(buf), buf.readFloat(), buf.readFloat()));
        stats = readDisplay(buf, stats);
        return new HunterTreeLayoutPayload(rings, nodes, a, b, stats, abilities, labels);
    }

    /**
     * Optional trailing section (servers from 2026-11-11 on): per stat the name and description
     * to show, the display scale and unit. The format number stays 3: a mod that predates this
     * stops reading after the labels and keeps the legacy name/description fields above, which
     * the server still fills with text that reads right next to a percent. A missing or broken
     * section leaves the stats as they were.
     */
    private static List<Stat> readDisplay(PacketByteBuf buf, List<Stat> stats) {
        if (!buf.isReadable()) return stats;
        try {
            int d = bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_NAMES);
            List<Stat> out = new ArrayList<>(stats);
            for (int i = 0; i < d; i++) {
                String name = str(buf), desc = str(buf);
                float scale = buf.readFloat();
                String unit = buf.readString(8);
                if (i >= out.size() || !Float.isFinite(scale)) continue;
                Stat o = out.get(i);
                out.set(i, new Stat(name, desc, o.rgb(), o.small(), o.major(), scale, unit));
            }
            return out;
        } catch (RuntimeException e) {
            return stats;
        }
    }

    static String str(PacketByteBuf buf) {
        return buf.readString(Protocol.MAX_HUNTER_TREE_TEXT_CHARS);
    }

    static int bounded(int v, int max) {
        if (v < 0 || v > max) throw new IllegalArgumentException("count " + v + " > " + max);
        return v;
    }

    private static int index(int v, int n) {
        if (v < 0 || v >= n) throw new IllegalArgumentException("node index " + v);
        return v;
    }
}
