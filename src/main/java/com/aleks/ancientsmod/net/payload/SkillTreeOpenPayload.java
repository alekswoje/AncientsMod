package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decoded {@code PKT_SKILLTREE_OPEN}: the full dungeon skill-tree layout.
 *
 * <p>Server emits this once per Tartarus Vision open. Nodes are indexed by
 * their position in the {@link #nodes} list; edges reference those indices
 * to keep the wire compact (~13 KB for the live 193-node tree).
 */
public final class SkillTreeOpenPayload {

    public final List<Node> nodes;
    public final List<int[]> edges; // each entry: { fromIndex, toIndex }
    public final Map<String, Integer> indexById;

    private SkillTreeOpenPayload(List<Node> nodes, List<int[]> edges, Map<String, Integer> indexById) {
        this.nodes = nodes;
        this.edges = edges;
        this.indexById = indexById;
    }

    public Node nodeById(String id) {
        Integer i = indexById.get(id);
        return (i == null || i < 0 || i >= nodes.size()) ? null : nodes.get(i);
    }

    public static SkillTreeOpenPayload decode(PacketByteBuf buf) {
        int nodeCount = buf.readShort() & 0xFFFF;
        if (nodeCount > Protocol.SKILLTREE_MAX_NODES) {
            throw new IllegalArgumentException("skill tree: node count " + nodeCount + " > max");
        }
        List<Node> nodes = new ArrayList<>(nodeCount);
        Map<String, Integer> indexById = new HashMap<>(nodeCount * 2);
        for (int i = 0; i < nodeCount; i++) {
            String id = clamp(buf.readString(Protocol.SKILLTREE_MAX_NODE_ID_CHARS),
                    Protocol.SKILLTREE_MAX_NODE_ID_CHARS);
            short gx = buf.readShort();
            short gy = buf.readShort();
            String name = clamp(buf.readString(Protocol.SKILLTREE_MAX_NODE_NAME_CHARS),
                    Protocol.SKILLTREE_MAX_NODE_NAME_CHARS);
            byte effect = buf.readByte();
            float value = buf.readFloat();
            int cost = buf.readByte() & 0xFF;
            byte branch = buf.readByte();
            int flags = buf.readByte() & 0xFF;
            boolean notable = (flags & 0x1) != 0;
            boolean autoUnlocked = (flags & 0x2) != 0;
            nodes.add(new Node(id, gx, gy, name, effect, value, cost, branch, notable, autoUnlocked));
            indexById.put(id, i);
        }
        int edgeCount = buf.readShort() & 0xFFFF;
        if (edgeCount > Protocol.SKILLTREE_MAX_EDGES) {
            throw new IllegalArgumentException("skill tree: edge count " + edgeCount + " > max");
        }
        List<int[]> edges = new ArrayList<>(edgeCount);
        for (int i = 0; i < edgeCount; i++) {
            int from = buf.readShort() & 0xFFFF;
            int to = buf.readShort() & 0xFFFF;
            if (from >= nodeCount || to >= nodeCount) {
                throw new IllegalArgumentException("skill tree: edge index out of range");
            }
            edges.add(new int[] { from, to });
        }
        return new SkillTreeOpenPayload(nodes, edges, indexById);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    /** Immutable per-node bundle. */
    public static final class Node {
        public final String id;
        public final int gx;
        public final int gy;
        public final String name;
        public final byte effect;
        public final float value;
        public final int cost;
        public final byte branch;
        public final boolean notable;
        public final boolean autoUnlocked;

        public Node(String id, int gx, int gy, String name, byte effect, float value,
                    int cost, byte branch, boolean notable, boolean autoUnlocked) {
            this.id = id;
            this.gx = gx;
            this.gy = gy;
            this.name = name;
            this.effect = effect;
            this.value = value;
            this.cost = cost;
            this.branch = branch;
            this.notable = notable;
            this.autoUnlocked = autoUnlocked;
        }
    }
}
