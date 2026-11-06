package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.net.payload.HunterTreeLayoutPayload;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * PoE-style search for the Hunter and mining tree screens. The player types into a box at the
 * bottom of the left column; every node whose name or description contains each typed word gets
 * a pulsing ring, and Enter centres the view on the next match. Matching is done on the same
 * text the node tooltip shows, so whatever a player can read on hover they can search for.
 */
final class TreeSearch {

    private final String rootName;
    private HunterTreeLayoutPayload textFor;
    private String[] text;
    private HunterTreeLayoutPayload matchFor;
    private String matchQuery = "";
    private boolean[] match = new boolean[0];
    private int count;
    private int cursor = -1;

    TextFieldWidget field;

    /** @param rootName what the start node is called when the layout gives it no ability (e.g. "The Hunt"). */
    TreeSearch(String rootName) {
        this.rootName = rootName;
    }

    TextFieldWidget createField(TextRenderer tr, int x, int y, int w, String keep, Consumer<String> onChange) {
        field = new TextFieldWidget(tr, x, y, w, 14, Text.literal("Search"));
        field.setMaxLength(48);
        field.setPlaceholder(Text.literal("Search (Ctrl+F)").withColor(0xFF7C7488));
        field.setText(keep);
        field.setChangedListener(s -> {
            cursor = -1;
            onChange.accept(s);
        });
        return field;
    }

    String query() {
        return field == null ? "" : field.getText().trim().toLowerCase(Locale.ROOT);
    }

    boolean active() {
        return !query().isEmpty();
    }

    boolean focused() {
        return field != null && field.isFocused();
    }

    /** Which nodes match the current query; recomputed only when the layout or query changes. */
    boolean[] matches(HunterTreeLayoutPayload l) {
        String q = query();
        if (matchFor == l && matchQuery.equals(q)) return match;
        String[] texts = texts(l);
        String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
        boolean[] out = new boolean[texts.length];
        int n = 0;
        if (words.length > 0) {
            for (int i = 0; i < texts.length; i++) {
                boolean all = true;
                for (String w : words) if (!texts[i].contains(w)) { all = false; break; }
                if (all) { out[i] = true; n++; }
            }
        }
        matchFor = l;
        matchQuery = q;
        match = out;
        count = n;
        return out;
    }

    int count(HunterTreeLayoutPayload l) {
        matches(l);
        return count;
    }

    /** The next matching node after the last one jumped to, wrapping; -1 when nothing matches. */
    int next(HunterTreeLayoutPayload l) {
        boolean[] m = matches(l);
        if (count == 0) return -1;
        for (int k = 1; k <= m.length; k++) {
            int i = Math.floorMod(cursor + k, m.length);
            if (m[i]) { cursor = i; return i; }
        }
        return -1;
    }

    /** Search matches: cyan, pulsing. */
    static int matchColor() {
        double phase = (System.currentTimeMillis() % 1200L) / 1200.0 * Math.PI * 2;
        int alpha = 150 + (int) (105 * (0.5 + 0.5 * Math.sin(phase)));
        return (alpha << 24) | 0x7CF0FF;
    }

    /** Hovered node: cream, steady. */
    static final int HOVER = 0xFFFFF6C8;

    private static final int CELL_W = 21, CELL_H = 17, PAD = 2, GLOW_W = CELL_W + 2 * PAD, GLOW_H = CELL_H + 2 * PAD;

    /**
     * An outline that hugs the icon drawn at row {@code row} of a node atlas, in tree space. {@code tex}
     * is that atlas's outline sheet from {@code scripts/node_glow.py}: one white ring per row, which
     * {@code color} tints.
     */
    static void glow(DrawContext ctx, Identifier tex, int rows, int row, int cx, int cy, int color) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, cx - CELL_W / 2 - PAD, cy - CELL_H / 2 - PAD,
                0, row * GLOW_H, GLOW_W, GLOW_H, GLOW_W, GLOW_H * rows, color);
    }

    /** The same outline around a drawn disc (or diamond) node of {@code radius}, for screens without icon art. */
    static void discGlow(DrawContext ctx, int cx, int cy, int radius, boolean diamond, int color) {
        int size = radius + 3, span = 2 * size + 1;
        boolean[] in = new boolean[span * span];
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = diamond ? radius - Math.abs(dy) : (int) Math.sqrt(radius * radius - dy * dy);
            for (int x = -dx; x <= dx; x++) in[(dy + size) * span + x + size] = true;
        }
        boolean[] ring1 = grow(in, span, false);
        boolean[] inner = new boolean[in.length];
        for (int i = 0; i < in.length; i++) inner[i] = in[i] || ring1[i];
        boolean[] ring2 = grow(inner, span, true);
        int faint = (((color >>> 24) * 90 / 255) << 24) | (color & 0xFFFFFF);
        for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) {
            int i = y * span + x;
            if (ring1[i]) ctx.fill(cx + x - size, cy + y - size, cx + x - size + 1, cy + y - size + 1, color);
            else if (ring2[i]) ctx.fill(cx + x - size, cy + y - size, cx + x - size + 1, cy + y - size + 1, faint);
        }
    }

    /** Cells just outside {@code m}: 4-neighbour, or 8-neighbour with {@code diag}. */
    private static boolean[] grow(boolean[] m, int span, boolean diag) {
        boolean[] out = new boolean[m.length];
        for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) {
            if (m[y * span + x]) continue;
            search:
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                if ((dx == 0 && dy == 0) || (!diag && dx != 0 && dy != 0)) continue;
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && ny >= 0 && nx < span && ny < span && m[ny * span + nx]) { out[y * span + x] = true; break search; }
            }
        }
        return out;
    }

    private String[] texts(HunterTreeLayoutPayload l) {
        if (textFor == l) return text;
        String[] out = new String[l.nodes().size()];
        for (int i = 0; i < out.length; i++) {
            var n = l.nodes().get(i);
            HunterTreeLayoutPayload.Stat stat = n.stat() >= 0 && n.stat() < l.stats().size() ? l.stats().get(n.stat()) : null;
            HunterTreeLayoutPayload.Ability ability = n.ability() >= 0 && n.ability() < l.abilities().size() ? l.abilities().get(n.ability()) : null;
            StringBuilder sb = new StringBuilder();
            switch (n.kind()) {
                case 0 -> {
                    sb.append(ability == null ? rootName : ability.name());
                    if (ability != null) for (String line : ability.lines()) sb.append(' ').append(line);
                }
                case 1, 2 -> {
                    if (stat != null) {
                        if (n.kind() == 2) sb.append("Greater ");
                        sb.append(stat.name()).append(' ').append(stat.description());
                    }
                }
                default -> {
                    if (ability != null) {
                        sb.append(ability.name());
                        if (n.kind() == 4) sb.append(" Keystone");
                        for (String line : ability.lines()) sb.append(' ').append(line);
                    }
                }
            }
            out[i] = strip(sb.toString()).toLowerCase(Locale.ROOT);
        }
        textFor = l;
        text = out;
        return out;
    }

    /** Drops legacy colour codes so "&7Speed" still matches "speed". */
    private static String strip(String s) {
        return s.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");
    }
}
