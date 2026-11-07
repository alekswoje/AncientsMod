package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.mining.MiningTreeClient;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.payload.HunterTreeLayoutPayload;
import com.aleks.ancientsmod.net.payload.HunterTreeStatePayload;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The mining web and six ascendancy trees on one screen, PoE style: drag to move, scroll to zoom. The server
 * sends where each node goes; links inside a wheel draw as arcs around it, the rest straight.
 * No names are drawn on the tree (owner 2026-10-15). Drawn with
 * the Hunter screen's shared frame art (dusk sky, bronze frame and gold links), exported by
 * {@code _draw_hunter_gui.py --mod-out}, so the two views look the same; the node icons are
 * {@code gui/mining/nodes.png} from {@code _draw_mining_mod_icons.py}, in the Hunter icon style. The server stays the
 * authority: clicks go out as {@link NetworkHandler#sendMiningTreeAction} and the screen
 * redraws from whatever state comes back.
 */
public final class MiningTreeScreen extends Screen {

    private static final Identifier NODES = Identifier.of("ancientsmod", "textures/gui/mining/nodes.png");
    private static final Identifier GLOW = Identifier.of("ancientsmod", "textures/gui/mining/nodes_glow.png");
    private static final Identifier SKY = tex("sky");
    private static final Identifier FRAME = tex("frame");
    private static final Identifier PLAQUES = tex("plaques");
    private static final Identifier TITLE = tex("title");

    /** nodes.png rows, drawn by {@code _draw_mining_mod_icons.py}: one per symbol, columns taken/available/locked. */
    private static final int ICON_W = 21, ICON_H = 17, ROWS = 51;
    private static final int ROW_SEAM = 0, ROW_SMALL = 1, ROW_MAJOR = 12, ROW_EFFECT = 23, ROW_KEYSTONE = 28,
            ROW_CREST = 33, ROW_CLASS_MINOR = 39, ROW_CLASS_MAJOR = 45, STAT_COUNT = 11;
    /** The effect rows nodes.png draws, in order. */
    private static final String[] EFFECTS = {"Insight", "Ripple", "Ore Wisps", "Rockfall", "Echo Swing"};
    /**
     * Effects with no sprite of their own borrow the one the server's dialog draws
     * (PrisonsCore MiningEffect.artGlyph), so the mod, the dialog and the site agree.
     */
    private static final java.util.Map<String, String> EFFECT_ART = java.util.Map.of(
            "Hot Rubble", "Ripple", "Jewel Burst", "Ripple", "Lesson in the Rock", "Insight", "Shardfall", "Rockfall");
    /**
     * The keystone rows nodes.png draws, in order, then the generic row. Only Heavy Hand is still
     * on the tree; the other three rows are the removed Charged Core, Gem Seeker and Haste, so
     * Bottomless, Kiln Heart and Deep Cut draw the generic row until they get art of their own.
     */
    private static final String[] KEYSTONES = {"Heavy Hand", "Charged Core", "Gem Seeker", "Haste"};
    private static final int COL_W = 104, COL_X = 12, PLAQUE_W = 80, PLAQUE_H = 13;
    private static final int P_TAB = 0, P_TAB_SEL = 1, P_RESPEC = 2, P_RESPEC_ARMED = 3, P_OPEN = 4;
    private static final int ACTION_TAKE = 0, ACTION_REFUND = 1, ACTION_RESPEC = 2, ACTION_PRESET = 3, ACTION_CLOSED = 4, ACTION_AUTO = 6;
    private static final float MIN_ZOOM = 0.3f, MAX_ZOOM = 3f;
    private static final long CONFIRM_MS = 5000;

    private static final int CREAM = 0xFFF2E6CE, DIM = 0xFF9C94A8, GOLD = 0xFFFFD06A, TITLE_TEXT = 0xFFFFE8C8;
    private static final int CU_LT = 0xFFD89848, CU_DK = 0xFF4A220C;
    private static final int[] EDGE_OUTER = {0x96FFA836, 0xAA0A0808, 0x8206070C};
    private static final int[] EDGE_CORE = {0xFFFFE286, 0xFFE8CCA4, 0xFF50586C};

    /** Fit each selected tree initially; drag and zoom then remain local to this screen. */
    private float camX = 0, camY = 0, zoom = 0.6f;
    private static boolean statsOpen = true;
    /** The auto-spend choices are open in place of the stat rows: one click per preset, no cycling. */
    private boolean autoMenu;

    private final @Nullable Screen parent;
    /** The search text survives closing and reopening within a session, like the view. */
    private static String lastQuery = "";
    private final TreeSearch search = new TreeSearch("The Seam");
    private HunterTreeLayoutPayload adjFor;
    private int[][] adj;
    private HunterTreeLayoutPayload rowsFor;
    private int rowsView = -1;
    private int[] rows;

    private boolean pressed, dragging;
    private double pressX, pressY;
    private float pressCamX, pressCamY;
    private int armedRefund = -1;
    private long armedRefundUntil, armedRespecUntil;

    public MiningTreeScreen(@Nullable Screen parent) {
        super(Text.literal("Mining & Ascendancy"));
        this.parent = parent instanceof ChatScreen ? null : parent;
    }

    private static final String[] VIEWS = {"Mining", "Kobaloi", "Horologist", "Telchine", "Prospector", "Reservoir", "Overdrive"};
    @Override protected void init() { resetView(); addSearchField(); }

    public void resetView() {
        camX = camY = 0;
        zoom = 1f;
        var layout = MiningTreeClient.layout();
        if (layout != null && !layout.nodes().isEmpty()) {
            float x0=Float.MAX_VALUE,y0=Float.MAX_VALUE,x1=-Float.MAX_VALUE,y1=-Float.MAX_VALUE;
            for (var n : layout.nodes()) { x0=Math.min(x0,n.px());y0=Math.min(y0,n.py());x1=Math.max(x1,n.px());y1=Math.max(y1,n.py()); }
            camX=(x0+x1)/2;camY=(y0+y1)/2;
            zoom=Math.max(MIN_ZOOM,Math.min(1.5f,Math.min((width-COL_W-24f)/(x1-x0+40),(height-48f)/(y1-y0+40))));
        }
        armedRefund = -1; armedRefundUntil = armedRespecUntil = 0;
        pressed = dragging = false;
    }

    private static Identifier tex(String name) {
        return Identifier.of("ancientsmod", "textures/gui/hunter/" + name + ".png");
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        NetworkHandler.sendMiningTreeAction(ACTION_CLOSED, 0);
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void removed() {
        NetworkHandler.sendMiningTreeAction(ACTION_CLOSED, 0);
        super.removed();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // The sky covers the whole screen; no world blur behind it.
    }

    private void addSearchField() {
        this.addDrawableChild(search.createField(this.textRenderer, COL_X, this.height - 52, PLAQUE_W, lastQuery,
                q -> lastQuery = q));
    }

    // ── Geometry ───────────────────────────────────────────────────────────

    private float originX() { return COL_W + (this.width - COL_W) / 2f; }
    private float originY() { return this.height / 2f; }
    private float toWorldX(double mx) { return (float) ((mx - originX()) / zoom + camX); }
    private float toWorldY(double my) { return (float) ((my - originY()) / zoom + camY); }
    private boolean inTree(double mx, double my) { return mx > COL_W + 2 && mx < this.width - 6 && my > 6 && my < this.height - 6; }

    /** Half the drawn icon's width for a nodes.png row: small 11, major 13, notable 17, keystone 21. */
    private static int halfW(int row) {
        if (row >= ROW_KEYSTONE && row < ROW_CREST) return 10;
        if (row == ROW_SEAM || row >= ROW_EFFECT && row < ROW_CLASS_MINOR) return 8;
        if (row >= ROW_MAJOR && row < ROW_EFFECT || row >= ROW_CLASS_MAJOR) return 6;
        return 5;
    }

    private static int halfH(int row) { return Math.min(8, halfW(row)); }

    /** Each node's nodes.png row for this view, worked out once per layout. */
    private int[] rows(HunterTreeLayoutPayload l, int view) {
        if (rowsFor == l && rowsView == view) return rows;
        int[] out = new int[l.nodes().size()];
        for (int i = 0; i < out.length; i++) {
            var n = l.nodes().get(i);
            String name = n.ability() >= 0 && n.ability() < l.abilities().size() ? l.abilities().get(n.ability()).name() : "";
            int stat = Math.max(0, Math.min(STAT_COUNT - 1, n.stat()));
            int cls = Math.max(0, Math.min(5, view - 1));
            out[i] = switch (n.kind()) {
                case 0 -> view == 0 ? ROW_SEAM : ROW_CREST + cls;
                case 1 -> ROW_SMALL + stat;
                case 2 -> ROW_MAJOR + stat;
                case 4 -> ROW_KEYSTONE + indexOf(KEYSTONES, name, KEYSTONES.length);
                default -> view > 0 ? (n.icon() == 1 ? ROW_CLASS_MINOR : ROW_CLASS_MAJOR) + cls
                        : ROW_EFFECT + Math.max(0, indexOf(EFFECTS, EFFECT_ART.getOrDefault(name, name), 0));
            };
            out[i] = Math.max(0, Math.min(ROWS - 1, out[i]));
        }
        rowsFor = l;
        rowsView = view;
        rows = out;
        return out;
    }

    private static int indexOf(String[] names, String name, int missing) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        return missing;
    }

    // ── State ──────────────────────────────────────────────────────────────

    private int[][] adjacency(HunterTreeLayoutPayload l) {
        if (adjFor == l) return adj;
        int n = l.nodes().size();
        int[] deg = new int[n];
        for (int i = 0; i < l.edgeA().length; i++) { deg[l.edgeA()[i]]++; deg[l.edgeB()[i]]++; }
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) out[i] = new int[deg[i]];
        int[] fill = new int[n];
        for (int i = 0; i < l.edgeA().length; i++) {
            int a = l.edgeA()[i], b = l.edgeB()[i];
            out[a][fill[a]++] = b;
            out[b][fill[b]++] = a;
        }
        adjFor = l;
        adj = out;
        return out;
    }

    private static boolean taken(HunterTreeLayoutPayload l, HunterTreeStatePayload s, int i) {
        return l.nodes().get(i).kind() == 0 || s.taken().get(i);
    }

    /** 0 taken, 1 available, 2 locked: the column of nodes.png. */
    private int nodeState(HunterTreeLayoutPayload l, HunterTreeStatePayload s, int i) {
        if (taken(l, s, i)) return 0;
        for (int j : adjacency(l)[i]) if (taken(l, s, j)) return 1;
        return 2;
    }

    // ── Render ─────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        HunterTreeLayoutPayload l = MiningTreeClient.layout();
        HunterTreeStatePayload s = MiningTreeClient.state();
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, SKY, 0, 0, 0f, 0f, this.width, this.height, 480, 270, 480, 270);
        if (l == null || s == null) {
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }
        int[] rows = rows(l, s.activePreset());
        int hovered = inTree(mouseX, mouseY) && !dragging ? nodeAt(l, rows, toWorldX(mouseX), toWorldY(mouseY)) : -1;
        float vx0 = toWorldX(COL_W) - 12, vy0 = toWorldY(0) - 12, vx1 = toWorldX(this.width) + 12, vy1 = toWorldY(this.height) + 12;

        ctx.enableScissor(COL_W + 2, 6, this.width - 6, this.height - 6);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(originX(), originY());
        ctx.getMatrices().scale(zoom, zoom);
        ctx.getMatrices().translate(-camX, -camY);
        try {
            drawEdges(ctx, l, s, vx0, vy0, vx1, vy1);
            drawNodes(ctx, l, s, rows, hovered, vx0, vy0, vx1, vy1);
        } finally {
            ctx.getMatrices().popMatrix();
            ctx.disableScissor();
        }

        List<Text> tip = drawColumn(ctx, l, s, mouseX, mouseY);
        drawFrame(ctx);
        drawTitle(ctx, s);
        if (hovered >= 0) tip = nodeTooltip(l, s, hovered);
        super.render(ctx, mouseX, mouseY, delta);
        if (tip != null && !tip.isEmpty()) ctx.drawTooltip(this.textRenderer, tip, mouseX, mouseY);
    }

    private void drawEdges(DrawContext ctx, HunterTreeLayoutPayload l, HunterTreeStatePayload s,
                           float vx0, float vy0, float vx1, float vy1) {
        boolean frontier = s.points() > 0 && !s.readOnly();
        for (int i = 0; i < l.edgeA().length; i++) {
            int a = l.edgeA()[i], b = l.edgeB()[i];
            boolean ta = taken(l, s, a), tb = taken(l, s, b);
            int st = ta && tb ? 0 : (ta || tb) && frontier ? 1 : 2;
            var na = l.nodes().get(a);
            var nb = l.nodes().get(b);
            boolean arc = na.wheel() && nb.wheel() && Math.abs(na.cx() - nb.cx()) < 0.5 && Math.abs(na.cy() - nb.cy()) < 0.5;
            // Off-screen links cost as much to draw as visible ones; an arc stays inside its wheel's circle.
            float r = arc ? (float) Math.hypot(na.px() - na.cx(), na.py() - na.cy()) : 0;
            float ex0 = arc ? na.cx() - r : Math.min(na.px(), nb.px()), ex1 = arc ? na.cx() + r : Math.max(na.px(), nb.px());
            float ey0 = arc ? na.cy() - r : Math.min(na.py(), nb.py()), ey1 = arc ? na.cy() + r : Math.max(na.py(), nb.py());
            if (ex1 < vx0 || ex0 > vx1 || ey1 < vy0 || ey0 > vy1) continue;
            if (arc) {
                // Same wheel: an arc around its centre, the short way.
                float cx = na.cx(), cy = na.cy();
                double ra = Math.hypot(na.px() - cx, na.py() - cy);
                double angA = Math.atan2(na.py() - cy, na.px() - cx), angB = Math.atan2(nb.py() - cy, nb.px() - cx);
                double dt = angB - angA;
                while (dt > Math.PI) dt -= 2 * Math.PI;
                while (dt < -Math.PI) dt += 2 * Math.PI;
                int steps = Math.max(1, (int) Math.ceil(Math.abs(dt) * ra / 6.0));
                float px = na.px(), py = na.py();
                for (int k = 1; k <= steps; k++) {
                    double t = angA + dt * k / steps;
                    float qx = (float) (cx + ra * Math.cos(t)), qy = (float) (cy + ra * Math.sin(t));
                    segment(ctx, px, py, qx, qy, st);
                    px = qx;
                    py = qy;
                }
            } else {
                segment(ctx, na.px(), na.py(), nb.px(), nb.py(), st);
            }
        }
    }

    /** A 3 px link from one point to another: a dark or glowing rim around a 1 px core. */
    private static void segment(DrawContext ctx, float x0, float y0, float x1, float y1, int st) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x0, y0);
        ctx.getMatrices().rotate((float) Math.atan2(dy, dx));
        int l = (int) Math.ceil(len) + 1;
        ctx.fill(0, -1, l, 2, EDGE_OUTER[st]);
        ctx.fill(0, 0, l, 1, EDGE_CORE[st]);
        ctx.getMatrices().popMatrix();
    }

    private void drawNodes(DrawContext ctx, HunterTreeLayoutPayload l, HunterTreeStatePayload s, int[] rows, int hovered,
                           float vx0, float vy0, float vx1, float vy1) {
        boolean[] found = search.active() ? search.matches(l) : null;
        for (int i = 0; i < l.nodes().size(); i++) {
            var n = l.nodes().get(i);
            if (n.px() < vx0 || n.px() > vx1 || n.py() < vy0 || n.py() > vy1) continue;
            int row = rows[i];
            int st = nodeState(l, s, i);
            int cx = Math.round(n.px()), cy = Math.round(n.py());
            int x = cx - ICON_W / 2, y = cy - ICON_H / 2;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, NODES, x, y, st * ICON_W, row * ICON_H,
                    ICON_W, ICON_H, ICON_W * 3, ICON_H * ROWS);
            if (i == hovered) TreeSearch.glow(ctx, GLOW, ROWS, row, cx, cy, TreeSearch.HOVER);
            else if (found != null && i < found.length && found[i]) TreeSearch.glow(ctx, GLOW, ROWS, row, cx, cy, TreeSearch.matchColor());
            if (i == armedRefund && armedRefundUntil > System.currentTimeMillis()) {
                int hw = halfW(row) + 2;
                ctx.fill(cx - hw, cy + halfH(row) + 2, cx + hw + 1, cy + halfH(row) + 3, 0xFFFF6A5A);
            }
        }
    }

    private int nodeAt(HunterTreeLayoutPayload l, int[] rows, float wx, float wy) {
        for (int i = 0; i < l.nodes().size(); i++) {
            var n = l.nodes().get(i);
            if (Math.abs(wx - Math.round(n.px())) <= halfW(rows[i]) + 0.5f && Math.abs(wy - Math.round(n.py())) <= halfH(rows[i]) + 0.5f) return i;
        }
        return -1;
    }

    // ── Column ─────────────────────────────────────────────────────────────

    private static final int PRESET_Y = 62, STATS_Y = 174, RESPEC_Y = 190, AUTO_Y = 206, STAT_ROWS_Y = 226;
    /** Auto-spend presets in server order (MiningTreeAutoSpend.Preset); the plaque cycles Off then these. */
    private static final String[] AUTO = {"Energy", "XP", "Money", "Mixed"};
    private static final String[] AUTO_BLURB = {"Most energy on your pickaxe.", "Level up faster.",
            "More ore to sell.", "A bit of everything: ore, energy and XP."};

    /** The Auto plaque shows on the mining view when the server sent a preset. */
    private static boolean autoShown(HunterTreeStatePayload s) {
        return !s.readOnly() && s.activePreset() == 0 && MiningTreeClient.autoPreset() != MiningTreeClient.AUTO_UNKNOWN;
    }

    /** Draws the left column; returns the tooltip for whatever in it is hovered. */
    private List<Text> drawColumn(DrawContext ctx, HunterTreeLayoutPayload l, HunterTreeStatePayload s, int mx, int my) {
        ctx.fill(4, 6, COL_W, this.height - 6, 0x480C0406);
        ctx.fill(COL_W, 6, COL_W + 1, this.height - 6, CU_LT);
        ctx.fill(COL_W + 1, 6, COL_W + 2, this.height - 6, CU_DK);
        List<Text> tip = null;

        String k = s.activePreset() == 0 ? "Mining: " : "Ascendancy: ";
        ctx.drawText(this.textRenderer, k, COL_X + 2, 12, CREAM, true);
        ctx.drawText(this.textRenderer, String.valueOf(s.points()), COL_X + 2 + this.textRenderer.getWidth(k), 12,
                s.points() > 0 ? GOLD : DIM, true);
        ctx.drawText(this.textRenderer, "Level " + s.level(), COL_X + 2, 24, CREAM, true);
        int y = 37;
        for (OrderedText line : this.textRenderer.wrapLines(Text.literal(s.xpLine()), PLAQUE_W)) {
            if (y > PRESET_Y - 10) break;
            ctx.drawText(this.textRenderer, line, COL_X + 2, y, DIM, true);
            y += 10;
        }

        boolean ro = s.readOnly();
        for (int v = 0; v < VIEWS.length; v++) {
            plaque(ctx, PRESET_Y + v * 16, s.activePreset() == v ? P_TAB_SEL : P_TAB,
                VIEWS[v], s.activePreset() == v ? GOLD : CREAM);
            if (over(mx,my,PRESET_Y + v * 16)) tip = List.of(Text.literal(
                v == 0 ? "One connected tree. Uses mining points." : "Uses ascendancy points. " + s.presetHint()).formatted(Formatting.GRAY));
        }
        plaque(ctx, STATS_Y, statsOpen ? P_OPEN : P_TAB, statsOpen ? "Hide Stats" : "Show Stats", statsOpen ? 0xFFFFECCC : 0xFFB4A088);
        if (!ro) {
            boolean armed = armedRespecUntil > System.currentTimeMillis();
            plaque(ctx, RESPEC_Y, armed ? P_RESPEC_ARMED : P_RESPEC, armed ? "Confirm?" : "Respec", armed ? 0xFFFFFFFF : CREAM);
            if (over(mx, my, RESPEC_Y)) {
                String cost = s.respecCost() <= 0 ? "free" : "$" + money(s.respecCost());
                tip = armed
                        ? List.of(Text.literal("Click again to refund " + (s.activePreset() == 0 ? "ALL mining and ascendancy points (" : "your ascendancy points (") + cost + ").").formatted(Formatting.RED))
                        : List.of(Text.literal("Refund " + (s.activePreset() == 0 ? "ALL mining and ascendancy points (" : "your ascendancy points (") + cost + ").").formatted(Formatting.GOLD),
                                  Text.literal(s.activePreset() == 0 ? "To change class only, use Respec on a class page."
                                          : "Then you can pick a different class.").formatted(Formatting.GRAY));
            }
        }

        if (autoShown(s)) {
            int a = MiningTreeClient.autoPreset();
            boolean on = a >= 0 && a < AUTO.length;
            plaque(ctx, AUTO_Y, on || autoMenu ? P_TAB_SEL : P_TAB, "Auto: " + (on ? AUTO[a] : "Off"), on ? GOLD : CREAM);
            if (over(mx, my, AUTO_Y)) {
                tip = new ArrayList<>();
                tip.add(Text.literal("Auto-spend").formatted(Formatting.AQUA));
                tip.add(Text.literal(on ? AUTO[a] + ": " + AUTO_BLURB[a] : "Off. You spend every point yourself.").formatted(Formatting.GRAY));
                tip.add(Text.literal("Your points spend themselves on the preset's path, now and as you level.").formatted(Formatting.GRAY));
                tip.add(Text.literal(autoMenu ? "Click to close the choices." : "Click to choose.").formatted(Formatting.YELLOW));
                tip.add(Text.literal("Taking or refunding a node yourself turns it off.").formatted(Formatting.DARK_GRAY));
            }
            if (autoMenu) {
                for (int c = 0; c <= AUTO.length; c++) {
                    int cy = STAT_ROWS_Y + c * 16;
                    boolean cur = c == 0 ? !on : a == c - 1;
                    plaque(ctx, cy, cur ? P_TAB_SEL : P_TAB, c == 0 ? "Off" : AUTO[c - 1], cur ? GOLD : CREAM);
                    if (over(mx, my, cy)) {
                        tip = new ArrayList<>();
                        if (c == 0) {
                            tip.add(Text.literal("Off").formatted(Formatting.AQUA));
                            tip.add(Text.literal("Keep your nodes. New points wait for you.").formatted(Formatting.GRAY));
                        } else {
                            tip.add(Text.literal(AUTO[c - 1]).formatted(Formatting.AQUA));
                            tip.add(Text.literal(AUTO_BLURB[c - 1]).formatted(Formatting.GRAY));
                            tip.add(cur ? Text.literal("Your preset now.").formatted(Formatting.GREEN)
                                    : Text.literal("Rebuilds your mining tree on this path, free. Your class stays.").formatted(Formatting.YELLOW));
                        }
                    }
                }
            }
        }

        if (over(mx,my,STATS_Y)) {
            tip = new ArrayList<>();
            for (int i=0;i<l.stats().size() && i<s.statValue().length;i++)
                tip.add(Text.literal(l.stats().get(i).name() + ": " + percent(s.statValue()[i])).formatted(Formatting.GRAY));
        }
        if (statsOpen && !(autoMenu && autoShown(s))) {
            int ry = STAT_ROWS_Y;
            for (int i = 0; i < l.stats().size() && i < s.statValue().length; i++) {
                if (ry > this.height - 64) break;
                var stat = l.stats().get(i);
                float v = s.statValue()[i], cap = s.statCap()[i];
                ctx.fill(COL_X + 3, ry + 2, COL_X + 10, ry + 9, 0xFF000000 | stat.rgb());
                // The server sends cap 0: the mining tree has no cap since 2026-11-03.
                boolean capped = cap > 0 && v >= cap - 1e-6f;
                int col = v <= 0 ? DIM : capped ? GOLD : CREAM;
                ctx.drawText(this.textRenderer, percent(v), COL_X + 14, ry + 1, col, true);
                if (mx >= COL_X && mx < COL_X + PLAQUE_W && my >= ry && my < ry + 11) {
                    tip = new ArrayList<>();
                    tip.add(Text.literal(stat.name()).styled(st -> st.withColor(stat.rgb())));
                    tip.add(Text.literal(percent(v) + " " + stat.description()).formatted(Formatting.GRAY));
                    if (cap > 0) tip.add(Text.literal("Base tree cap " + percent(cap) + (capped ? " (reached)" : ""))
                            .formatted(Formatting.DARK_GRAY));
                }
                ry += 11;
            }
        }
        if (search.active()) {
            int found = search.count(l);
            ctx.drawText(this.textRenderer, found == 0 ? "No matches" : found + (found == 1 ? " match" : " matches"),
                    COL_X + 2, this.height - 34, found == 0 ? 0xFFFF8A7A : 0xFF7CF0FF, true);
            if (found > 0) ctx.drawText(this.textRenderer, "Enter: next", COL_X + 2, this.height - 22, DIM, true);
        } else {
            ctx.drawText(this.textRenderer, "Drag to move", COL_X + 2, this.height - 34, DIM, true);
            ctx.drawText(this.textRenderer, "Scroll to zoom", COL_X + 2, this.height - 22, DIM, true);
        }
        return tip;
    }

    private void plaque(DrawContext ctx, int y, int row, String label, int color) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, PLAQUES, COL_X, y, 0, row * PLAQUE_H, PLAQUE_W, PLAQUE_H,
                PLAQUE_W, PLAQUE_H * 5);
        ctx.drawText(this.textRenderer, label, COL_X + (PLAQUE_W - this.textRenderer.getWidth(label)) / 2, y + 3, color, true);
    }

    private static boolean over(double mx, double my, int y) {
        return mx >= COL_X && mx < COL_X + PLAQUE_W && my >= y && my < y + PLAQUE_H;
    }

    // ── Frame and title ────────────────────────────────────────────────────

    private void drawFrame(DrawContext ctx) {
        int w = this.width, h = this.height, c = 26;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FRAME, c, 0, 30, 0, w - 2 * c, 6, 1, 6, 64, 64);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FRAME, c, h - 6, 30, 58, w - 2 * c, 6, 1, 6, 64, 64);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FRAME, 0, c, 0, 40, 6, h - 2 * c, 6, 1, 64, 64);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FRAME, w - 6, c, 58, 40, 6, h - 2 * c, 6, 1, 64, 64);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FRAME, 0, 0, 0, 0, c, c, 64, 64);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FRAME, w - c, 0, 64 - c, 0, c, c, 64, 64);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FRAME, 0, h - c, 0, 64 - c, c, c, 64, 64);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, FRAME, w - c, h - c, 64 - c, 64 - c, c, c, 64, 64);
    }

    private void drawTitle(DrawContext ctx, HunterTreeStatePayload s) {
        String label = s.owner();
        int pw = 112, x = (int) originX() - pw / 2, y = 4;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, TITLE, x, y, 0, 0, pw, 24, pw, 24);
        String t = this.textRenderer.trimToWidth(label, 80);
        ctx.drawText(this.textRenderer, t, x + (pw - this.textRenderer.getWidth(t)) / 2, y + 8, TITLE_TEXT, true);
    }

    // ── Tooltips ───────────────────────────────────────────────────────────

    private List<Text> nodeTooltip(HunterTreeLayoutPayload l, HunterTreeStatePayload s, int i) {
        var n = l.nodes().get(i);
        List<Text> tip = new ArrayList<>();
        HunterTreeLayoutPayload.Stat stat = n.stat() >= 0 && n.stat() < l.stats().size() ? l.stats().get(n.stat()) : null;
        HunterTreeLayoutPayload.Ability ability = n.ability() >= 0 && n.ability() < l.abilities().size() ? l.abilities().get(n.ability()) : null;
        switch (n.kind()) {
            case 0 -> {
                tip.add(Text.literal(ability == null ? "The Seam" : ability.name()).styled(st -> st.withColor(0xF28C28)));
                if (ability != null) for (String line : ability.lines()) tip.add(Text.literal(line).formatted(Formatting.GRAY));
                return tip;
            }
            case 1, 2 -> {
                if (stat == null) return tip;
                tip.add(Text.literal((n.kind() == 2 ? "Greater " : "") + stat.name()).styled(st -> st.withColor(stat.rgb())));
                float v = n.kind() == 2 ? stat.major() : stat.small();
                MutableText line = Text.literal(percent(v) + " ").styled(st -> st.withColor(stat.rgb()));
                tip.add(line.append(Text.literal(stat.description()).formatted(Formatting.GRAY)));
            }
            default -> {
                if (ability == null) return tip;
                boolean key = n.kind() == 4;
                tip.add(Text.literal(ability.name()).formatted(key ? Formatting.LIGHT_PURPLE : Formatting.GOLD, Formatting.BOLD));
                if (key) tip.add(Text.literal("Keystone").formatted(Formatting.LIGHT_PURPLE));
                for (String line : ability.lines()) tip.add(Text.literal(line).formatted(Formatting.GRAY));
            }
        }
        tip.add(Text.empty());
        int st = nodeState(l, s, i);
        if (s.readOnly()) {
            tip.add(st == 0 ? Text.literal("Taken").formatted(Formatting.GREEN) : Text.literal("Not taken").formatted(Formatting.GRAY));
        } else if (st == 0 && ability != null && "Soulforge".equals(ability.name())) {
            tip.add(Text.literal("Click to choose a perk or refund Soulforge.").formatted(Formatting.YELLOW));
        } else if (st == 0) {
            tip.add(Text.literal("Taken").formatted(Formatting.GREEN));
            String cost = s.refundCost() <= 0 ? "free" : "$" + money(s.refundCost());
            boolean armed = armedRefund == i && armedRefundUntil > System.currentTimeMillis();
            tip.add(Text.literal((armed ? "Click again to refund 1 point (" : "Click to refund 1 point (") + cost + ").")
                    .formatted(Formatting.YELLOW));
        } else if (classLocked(s)) {
            // Another class's page: the one-class rule locks it, not a missing path.
            String line = s.xpLine();
            String cls = line.substring("Refund ".length(), line.length() - " to switch class".length());
            tip.add(Text.literal("Your ascendancy points are in " + cls + ". Refund them to switch.").formatted(Formatting.DARK_GRAY));
        } else if (st == 1) {
            tip.add(s.points() > 0 ? Text.literal(s.activePreset() > 0 ? "Click to take (1 ascendancy point)" : "Click to take (1 point)")
                            .formatted(Formatting.YELLOW)
                    : Text.literal("No points left").formatted(Formatting.RED));
        } else {
            tip.add(Text.literal("Take a connected node first").formatted(Formatting.DARK_GRAY));
        }
        return tip;
    }

    /**
     * A class page whose class the player's points are not in. The server's header line for that
     * page reads "Refund &lt;class&gt; to switch class" (PrisonsCore MiningTreeModBridge.stateBody).
     */
    private static boolean classLocked(HunterTreeStatePayload s) {
        return s.activePreset() > 0 && s.xpLine() != null && s.xpLine().startsWith("Refund ")
                && s.xpLine().endsWith("to switch class");
    }

    static String percent(float fraction) {
        double pct = fraction * 100.0;
        long whole = Math.round(pct);
        // No leading "+": "+N%" reads as adding to the base value, not an increased/more modifier.
        return Math.abs(pct - whole) < 0.05 ? whole + "%" : String.format(Locale.US, "%.1f%%", pct);
    }

    static String money(long v) {
        String[] units = {"", "k", "M", "B", "T"};
        double d = v;
        int u = 0;
        while (d >= 1000 && u < units.length - 1) { d /= 1000; u++; }
        String s = u == 0 ? String.valueOf(v) : String.format(Locale.US, "%.1f", d);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + units[u];
    }

    // ── Input ──────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        HunterTreeStatePayload s = MiningTreeClient.state();
        double mx = click.x(), my = click.y();
        if (click.button() == 0 && s != null) {
            if (over(mx, my, STATS_Y)) { statsOpen = !statsOpen; return true; }
            for (int v = 0; v < VIEWS.length; v++) if (over(mx,my,PRESET_Y + v * 16)) {
                if (v != s.activePreset()) NetworkHandler.sendMiningTreeAction(ACTION_PRESET,v);
                return true;
            }
            if (autoShown(s) && over(mx, my, AUTO_Y)) {
                autoMenu = !autoMenu;
                return true;
            }
            if (autoShown(s) && autoMenu) {
                for (int k = 0; k <= AUTO.length; k++) {
                    if (!over(mx, my, STAT_ROWS_Y + k * 16)) continue;
                    int a = MiningTreeClient.autoPreset();
                    int current = a >= 0 && a < AUTO.length ? a + 1 : 0;
                    // Server arg: 0 off, 1..4 a preset (the wire varint cannot be negative). One
                    // click is one rebuild: no stepping through every preset on the way.
                    if (k != current) NetworkHandler.sendMiningTreeAction(ACTION_AUTO, k);
                    autoMenu = false;
                    return true;
                }
            }
            if (!s.readOnly() && over(mx, my, RESPEC_Y)) {
                long now = System.currentTimeMillis();
                if (armedRespecUntil > now) {
                    armedRespecUntil = 0;
                    NetworkHandler.sendMiningTreeAction(ACTION_RESPEC, 0);
                } else {
                    armedRespecUntil = now + CONFIRM_MS;
                }
                return true;
            }
        }
        if (inTree(mx, my)) {
            if (search.focused()) {
                search.field.setFocused(false);
                this.setFocused(null);
            }
            pressed = true;
            dragging = false;
            pressX = mx;
            pressY = my;
            pressCamX = camX;
            pressCamY = camY;
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (!pressed) return super.mouseDragged(click, offsetX, offsetY);
        double dx = click.x() - pressX, dy = click.y() - pressY;
        if (!dragging && dx * dx + dy * dy > 9) dragging = true;
        if (dragging) {
            camX = (float) (pressCamX - dx / zoom);
            camY = (float) (pressCamY - dy / zoom);
            clampCamera();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (!pressed) return super.mouseReleased(click);
        pressed = false;
        if (dragging) { dragging = false; return true; }
        if (click.button() != 0) return true;
        HunterTreeLayoutPayload l = MiningTreeClient.layout();
        HunterTreeStatePayload s = MiningTreeClient.state();
        if (l == null || s == null || s.readOnly()) return true;
        int i = nodeAt(l, rows(l, s.activePreset()), toWorldX(click.x()), toWorldY(click.y()));
        if (i < 0 || l.nodes().get(i).kind() == 0) return true;
        int st = nodeState(l, s, i);
        var node = l.nodes().get(i);
        if (st == 0 && node.ability() >= 0 && "Soulforge".equals(l.abilities().get(node.ability()).name())) {
            NetworkHandler.sendMiningTreeAction(5,i);
            return true;
        }
        long now = System.currentTimeMillis();
        if (st == 1 && s.points() > 0) {
            NetworkHandler.sendMiningTreeAction(ACTION_TAKE, i);
        } else if (st == 0) {
            if (armedRefund == i && armedRefundUntil > now) {
                armedRefund = -1;
                NetworkHandler.sendMiningTreeAction(ACTION_REFUND, i);
            } else {
                armedRefund = i;
                armedRefundUntil = now + CONFIRM_MS;
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!inTree(mouseX, mouseY) || verticalAmount == 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        float wx = toWorldX(mouseX), wy = toWorldY(mouseY);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, (float) (zoom * Math.pow(1.15, verticalAmount))));
        camX = (float) (wx - (mouseX - originX()) / zoom);
        camY = (float) (wy - (mouseY - originY()) / zoom);
        clampCamera();
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_F && input.hasCtrlOrCmd()) {
            focusSearch();
            search.field.setCursorToEnd(false);
            search.field.setSelectionStart(0);
            return true;
        }
        if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && search.active()) {
            jumpToNextMatch();
            return true;
        }
        if (search.focused()) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE && !search.field.getText().isEmpty()) {
                search.field.setText("");
                return true;
            }
            return super.keyPressed(input);
        }
        if (input.key() == GLFW.GLFW_KEY_SPACE || input.key() == GLFW.GLFW_KEY_HOME) {
            camX = 0;
            camY = 0;
            zoom = 1f;
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        // Typing a letter anywhere on the screen starts a search, as in PoE.
        if (!search.focused() && Character.isLetterOrDigit(input.codepoint()) && (input.modifiers() & GLFW.GLFW_MOD_CONTROL) == 0) focusSearch();
        return super.charTyped(input);
    }

    private void focusSearch() {
        this.setFocused(search.field);
        search.field.setFocused(true);
    }

    /** Centre the view on the next node the search matches, zooming in if the view is far out. */
    private void jumpToNextMatch() {
        HunterTreeLayoutPayload l = MiningTreeClient.layout();
        if (l == null) return;
        int i = search.next(l);
        if (i < 0) return;
        var n = l.nodes().get(i);
        camX = n.px();
        camY = n.py();
        zoom = Math.max(zoom, 1f);
        clampCamera();
    }

    /** Keep at least part of the tree in view. */
    private void clampCamera() {
        HunterTreeLayoutPayload l = MiningTreeClient.layout();
        if (l == null) return;
        float minX = 0, maxX = 0, minY = 0, maxY = 0;
        for (var n : l.nodes()) {
            minX = Math.min(minX, n.px()); maxX = Math.max(maxX, n.px());
            minY = Math.min(minY, n.py()); maxY = Math.max(maxY, n.py());
        }
        camX = Math.max(minX, Math.min(maxX, camX));
        camY = Math.max(minY, Math.min(maxY, camY));
    }
}
