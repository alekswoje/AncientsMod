package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * The Hunter tree definition the server sends on {@code /hunter} (reassembled from
 * {@link Protocol#PKT_HUNTER_TREE_LAYOUT_CHUNK}). {@code x, y} are the server's grid cells;
 * {@code px, py} are where this screen draws the node, in world pixels with the start at 0,0
 * (the server bends the grid into a round PoE-style disk). {@code icon} indexes the rows of
 * {@code textures/gui/hunter/nodes.png}.
 */
public record HunterTreeLayoutPayload(int rings, List<Node> nodes, int[] edgeA, int[] edgeB,
                                      List<Stat> stats, List<Ability> abilities, List<Label> labels) {

    /** {@code kind}: 0 start, 1 small, 2 major, 3 notable, 4 keystone. */
    public record Node(int x, int y, float px, float py, int kind, int icon, int stat, int ability) {}
    public record Stat(String name, String description, int rgb, float small, float major) {}
    public record Ability(String name, boolean keystone, List<String> lines) {}
    public record Label(String name, float px, float py) {}

    public static HunterTreeLayoutPayload decode(byte[] body) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(body));
        int format = buf.readVarInt();
        if (format != 2) throw new IllegalArgumentException("hunter tree format " + format);
        int rings = buf.readVarInt();
        int n = bounded(buf.readVarInt(), Protocol.MAX_HUNTER_TREE_NODES);
        List<Node> nodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            nodes.add(new Node(buf.readShort(), buf.readShort(), buf.readFloat(), buf.readFloat(),
                    buf.readByte(), buf.readByte(), buf.readByte(), buf.readByte()));
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
        return new HunterTreeLayoutPayload(rings, nodes, a, b, stats, abilities, labels);
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
