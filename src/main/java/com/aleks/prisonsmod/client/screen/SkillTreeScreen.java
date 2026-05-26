package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.skilltree.SkillTreeClient;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.SkillTreeOpenPayload;
import com.aleks.prisonsmod.net.payload.SkillTreeStatePayload;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fullscreen Tartarus Vision skill tree. Drag to pan, scroll to zoom,
 * left-click to allocate, right-click to refund, button bottom-right to
 * respec all (charges money).
 *
 * <p>Rendered with axis-aligned rectangles — every edge in the live tree
 * connects orthogonally-adjacent grid cells, so the renderer doesn't need
 * rotated-line support. Branch colours mirror the in-world bossbar
 * (assault=red, endurance=gold, agility=aqua, fortune=green, gate=amethyst).
 *
 * <p>State + layout come from {@link SkillTreeClient}; the screen polls
 * its version counter each frame so a chisel-driven allocation (or a
 * mod-driven one from a different session) re-renders live.
 */
public final class SkillTreeScreen extends Screen {

    // ── Layout tunables ─────────────────────────────────────────────────────
    private static final float GRID_PITCH = 56f;
    private static final float ZOOM_MIN = 0.30f;
    private static final float ZOOM_MAX = 2.50f;
    private static final float ZOOM_DEFAULT = 0.65f;

    private static final int NODE_BASE_RADIUS = 11;
    private static final int NODE_NOTABLE_RADIUS = 16;
    private static final int NODE_GATE_RADIUS = 20;

    // ── Tartarus palette ────────────────────────────────────────────────────
    private static final int COL_BG_TOP     = 0xFF1A0A2A;
    private static final int COL_BG_BOTTOM  = 0xFF0A0518;
    private static final int COL_HUD_BG     = 0xCC1A0A2A;
    private static final int COL_HUD_BORDER = 0xFF6B3EAA;
    private static final int COL_AMETHYST   = 0xFFB892D9;
    private static final int COL_AMETHYST_DIM = 0x4DB892D9;

    private static final int COL_EDGE_LOCKED   = 0x553A2D55;
    private static final int COL_EDGE_REACHABLE = 0xAA8B6FCC;
    private static final int COL_EDGE_UNLOCKED = 0xFFD8B4FF;

    private static final int COL_BRANCH_ASSAULT   = 0xFFFF5C5C;
    private static final int COL_BRANCH_ENDURANCE = 0xFFFFC857;
    private static final int COL_BRANCH_AGILITY   = 0xFF5CD0FF;
    private static final int COL_BRANCH_FORTUNE   = 0xFF6BE39A;
    private static final int COL_BRANCH_GATE      = 0xFFB892D9;

    private static final DecimalFormat MONEY_FMT = new DecimalFormat("#,###");

    // ── Camera state ────────────────────────────────────────────────────────
    private float panX = 0f;
    private float panY = 0f;
    private float zoom = ZOOM_DEFAULT;

    // ── Drag state ──────────────────────────────────────────────────────────
    private boolean dragging = false;
    private double dragStartMouseX, dragStartMouseY;
    private float dragStartPanX, dragStartPanY;
    /** Cumulative drag distance — used to distinguish "drag" from "click" on mouseReleased. */
    private double dragMovePx = 0;

    // ── Hover state ─────────────────────────────────────────────────────────
    private String hoveredNodeId = null;
    /** Cached "path to here" set of node ids — recomputed when hover changes. */
    private Set<String> pathHighlight = java.util.Collections.emptySet();

    // ── Search state ────────────────────────────────────────────────────────
    private TextFieldWidget searchField;
    private String searchLower = "";

    // ── Respec confirmation ─────────────────────────────────────────────────
    private ButtonWidget respecButton;
    private long respecArmedAtMs = 0L;
    private static final long RESPEC_CONFIRM_WINDOW_MS = 5_000L;

    private long lastSeenVersion = -1L;

    public SkillTreeScreen() {
        super(Text.literal("Tartarus Vision"));
    }

    /** Open / refocus the screen — used when the server pushes a fresh layout. */
    public static void openNow() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (!(mc.currentScreen instanceof SkillTreeScreen)) {
                mc.setScreen(new SkillTreeScreen());
            }
        });
    }

    @Override
    protected void init() {
        super.init();
        // Center the tree on first open.
        if (lastSeenVersion < 0) {
            panX = 0;
            panY = 0;
        }

        int searchW = Math.min(220, width / 4);
        int searchX = (width - searchW) / 2;
        searchField = new TextFieldWidget(this.textRenderer, searchX, 6,
                searchW, 18, Text.literal("Search…"));
        searchField.setPlaceholder(Text.literal("Search nodes…").formatted(Formatting.DARK_GRAY));
        searchField.setMaxLength(48);
        searchField.setChangedListener(this::onSearchChanged);
        addDrawableChild(searchField);

        respecButton = ButtonWidget.builder(
                Text.literal("Respec All").formatted(Formatting.LIGHT_PURPLE),
                btn -> onRespecClicked())
                .dimensions(width - 130, height - 30, 120, 20)
                .build();
        addDrawableChild(respecButton);
    }

    private void onSearchChanged(String s) {
        searchLower = s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        // No explicit "close" packet needed — server doesn't track open
        // screens, it just pushes state on changes.
        super.close();
    }

    // ── Coord transforms ────────────────────────────────────────────────────

    private float screenX(int gx) {
        return width * 0.5f + gx * GRID_PITCH * zoom + panX;
    }

    private float screenY(int gy) {
        return height * 0.5f + gy * GRID_PITCH * zoom + panY;
    }

    // ── Render ──────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Backdrop — vertical gradient.
        ctx.fillGradient(0, 0, width, height, COL_BG_TOP, COL_BG_BOTTOM);

        SkillTreeOpenPayload layout = SkillTreeClient.layout();
        SkillTreeStatePayload state = SkillTreeClient.state();

        if (layout == null || state == null) {
            renderLoadingOverlay(ctx);
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        // Recompute hover + path on every frame — cheap (193 nodes max).
        String nextHover = pickNodeAt(layout, mouseX, mouseY);
        if (!java.util.Objects.equals(nextHover, hoveredNodeId)) {
            hoveredNodeId = nextHover;
            pathHighlight = computePathToHere(layout, state, nextHover);
        } else if (SkillTreeClient.version() != lastSeenVersion) {
            // State changed underneath us — recompute path against fresh state.
            pathHighlight = computePathToHere(layout, state, nextHover);
            lastSeenVersion = SkillTreeClient.version();
        }

        // Edges first so nodes draw on top.
        renderEdges(ctx, layout, state);
        renderNodes(ctx, layout, state, mouseX, mouseY);

        renderPointsHud(ctx, state);
        renderHelpFooter(ctx);

        // Tooltip on hover.
        if (hoveredNodeId != null) {
            SkillTreeOpenPayload.Node n = layout.nodeById(hoveredNodeId);
            if (n != null) renderTooltip(ctx, n, state, mouseX, mouseY);
        }

        // Search + respec button (also widgets, drawn by super) — render after
        // our content so they sit on top.
        super.render(ctx, mouseX, mouseY, delta);

        // Respec arming hint — drawn over the button.
        if (respecArmedAtMs > 0 && System.currentTimeMillis() - respecArmedAtMs < RESPEC_CONFIRM_WINDOW_MS) {
            String hint = "Click again to confirm";
            int tw = textRenderer.getWidth(hint);
            ctx.fill(width - tw - 16, height - 50, width - 6, height - 38, 0xCC1A0A2A);
            ctx.drawText(textRenderer, hint, width - tw - 11, height - 47, 0xFFFFAA00, false);
        }
    }

    private void renderEdges(DrawContext ctx, SkillTreeOpenPayload layout, SkillTreeStatePayload state) {
        for (int[] e : layout.edges) {
            SkillTreeOpenPayload.Node a = layout.nodes.get(e[0]);
            SkillTreeOpenPayload.Node b = layout.nodes.get(e[1]);
            boolean aUn = state.unlocked.contains(a.id) || a.autoUnlocked;
            boolean bUn = state.unlocked.contains(b.id) || b.autoUnlocked;
            int color;
            if (aUn && bUn) color = COL_EDGE_UNLOCKED;
            else if (aUn || bUn) color = COL_EDGE_REACHABLE;
            else color = COL_EDGE_LOCKED;

            int sx1 = (int) screenX(a.gx);
            int sy1 = (int) screenY(a.gy);
            int sx2 = (int) screenX(b.gx);
            int sy2 = (int) screenY(b.gy);
            // Axis-aligned only (all edges in the live tree are 1-cell orthogonal).
            int thickness = Math.max(2, Math.round(3 * zoom));
            if (sx1 == sx2) {
                int yMin = Math.min(sy1, sy2);
                int yMax = Math.max(sy1, sy2);
                ctx.fill(sx1 - thickness / 2, yMin, sx1 + (thickness - thickness / 2), yMax, color);
            } else if (sy1 == sy2) {
                int xMin = Math.min(sx1, sx2);
                int xMax = Math.max(sx1, sx2);
                ctx.fill(xMin, sy1 - thickness / 2, xMax, sy1 + (thickness - thickness / 2), color);
            } else {
                // Diagonal — shouldn't happen in the current layout but fall
                // back to a low-cost dotted approximation.
                drawDiagonal(ctx, sx1, sy1, sx2, sy2, color);
            }
        }
    }

    private static void drawDiagonal(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps <= 0) return;
        for (int i = 0; i <= steps; i += 2) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            ctx.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    private void renderNodes(DrawContext ctx, SkillTreeOpenPayload layout, SkillTreeStatePayload state,
                             int mouseX, int mouseY) {
        for (SkillTreeOpenPayload.Node n : layout.nodes) {
            int sx = (int) screenX(n.gx);
            int sy = (int) screenY(n.gy);
            int baseRadius;
            if (n.autoUnlocked) baseRadius = NODE_GATE_RADIUS;
            else if (n.notable) baseRadius = NODE_NOTABLE_RADIUS;
            else baseRadius = NODE_BASE_RADIUS;
            int r = Math.max(3, Math.round(baseRadius * zoom));

            boolean unlocked = state.unlocked.contains(n.id) || n.autoUnlocked;
            boolean allocatable = !unlocked && SkillTreeClient.isAllocatable(n.id);
            boolean dimmedBySearch = !searchLower.isEmpty()
                    && !n.name.toLowerCase(Locale.ROOT).contains(searchLower);
            boolean onPath = pathHighlight.contains(n.id);

            int branchCol = branchColor(n.branch);
            int fill;
            int border;
            if (unlocked) {
                fill = blend(branchCol, 0xFFFFFFFF, 0.10f);
                border = COL_AMETHYST;
            } else if (allocatable) {
                fill = withAlpha(branchCol, 0xAA);
                border = blend(branchCol, COL_AMETHYST, 0.50f);
            } else {
                fill = withAlpha(0xFF3A2A5A, 0xC0);
                border = withAlpha(branchCol, 0x66);
            }

            if (dimmedBySearch) {
                fill = withAlpha(fill, 0x30);
                border = withAlpha(border, 0x40);
            }
            if (onPath && !unlocked) {
                // Light a faint amethyst halo behind the node.
                int halo = r + Math.max(3, Math.round(4 * zoom));
                ctx.fill(sx - halo, sy - halo, sx + halo, sy + halo, COL_AMETHYST_DIM);
            }

            // Just-changed pulse halo — fades over PULSE_LIFETIME_MS.
            float pulse = SkillTreeClient.unlockPulse(n.id);
            float refund = SkillTreeClient.refundPulse(n.id);
            float ringStrength = Math.max(pulse, refund);
            if (ringStrength > 0f) {
                int alpha = (int) (0xCC * ringStrength) & 0xFF;
                int color = pulse > refund ? withAlpha(COL_AMETHYST, alpha)
                                            : withAlpha(0xFF7AA5FF, alpha);
                int extra = Math.round((4 + 8 * (1f - ringStrength)) * zoom);
                int hr = r + Math.max(2, extra);
                ctx.fill(sx - hr, sy - hr, sx + hr, sy + hr, color);
            }

            // Hover ring drawn first so the node sits on top.
            if (n.id.equals(hoveredNodeId)) {
                int hr = r + Math.max(2, Math.round(3 * zoom));
                ctx.fill(sx - hr, sy - hr, sx + hr, sy + hr, withAlpha(COL_AMETHYST, 0x70));
            }

            // Node body — filled square (cheap circle approximation).
            ctx.fill(sx - r, sy - r, sx + r, sy + r, fill);
            // Border — outline by drawing 4 thin rects.
            int bt = Math.max(1, Math.round(2 * zoom));
            ctx.fill(sx - r, sy - r, sx + r, sy - r + bt, border);              // top
            ctx.fill(sx - r, sy + r - bt, sx + r, sy + r, border);              // bottom
            ctx.fill(sx - r, sy - r, sx - r + bt, sy + r, border);              // left
            ctx.fill(sx + r - bt, sy - r, sx + r, sy + r, border);              // right

            // Notable inner pip.
            if (n.notable && !n.autoUnlocked && r > 6) {
                int p = Math.max(2, r / 3);
                ctx.fill(sx - p, sy - p, sx + p, sy + p, COL_AMETHYST);
            }

            // Gate diamond marker.
            if (n.autoUnlocked && r > 8) {
                int p = r / 2;
                ctx.fill(sx - p, sy - p, sx + p, sy + p, COL_AMETHYST);
                ctx.fill(sx - 2, sy - r, sx + 2, sy + r, COL_AMETHYST);
                ctx.fill(sx - r, sy - 2, sx + r, sy + 2, COL_AMETHYST);
            }
        }
    }

    private void renderPointsHud(DrawContext ctx, SkillTreeStatePayload state) {
        TextRenderer tr = textRenderer;
        int x = 10, y = 30;
        int w = 178, h = 78;
        ctx.fill(x, y, x + w, y + h, COL_HUD_BG);
        ctx.fill(x, y, x + w, y + 1, COL_HUD_BORDER);
        ctx.fill(x, y + h - 1, x + w, y + h, COL_HUD_BORDER);
        ctx.fill(x, y, x + 1, y + h, COL_HUD_BORDER);
        ctx.fill(x + w - 1, y, x + w, y + h, COL_HUD_BORDER);

        ctx.drawText(tr, Text.literal("Tartarus Vision").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD),
                x + 8, y + 6, 0xFFFFFFFF, false);
        ctx.drawText(tr, line("Banked",    Integer.toString(state.banked),    0xFFB892D9), x + 8, y + 22, 0xFFFFFFFF, false);
        ctx.drawText(tr, line("Available", Integer.toString(state.available), state.available > 0 ? 0xFF55FF55 : 0xFFAAAAAA),
                x + 8, y + 34, 0xFFFFFFFF, false);
        ctx.drawText(tr, line("Spent",     Integer.toString(state.spent),     0xFFAAAAAA), x + 8, y + 46, 0xFFFFFFFF, false);
        ctx.drawText(tr, line("Respec",    "$" + MONEY_FMT.format(state.respecCost), 0xFFFFCC44),
                x + 8, y + 58, 0xFFFFFFFF, false);
    }

    private static Text line(String label, String value, int valueColor) {
        return Text.literal(label + ": ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(value).styled(s -> s.withColor(valueColor)));
    }

    private void renderHelpFooter(DrawContext ctx) {
        TextRenderer tr = textRenderer;
        String help = "drag to pan · scroll to zoom · left-click allocate · right-click refund · esc close";
        int tw = tr.getWidth(help);
        ctx.drawText(tr, help, (width - tw) / 2, height - 12, 0x88FFFFFF, false);
    }

    private void renderLoadingOverlay(DrawContext ctx) {
        TextRenderer tr = textRenderer;
        String msg = "Awaiting the Oracle's vision…";
        int tw = tr.getWidth(msg);
        ctx.drawText(tr, Text.literal(msg).formatted(Formatting.LIGHT_PURPLE),
                (width - tw) / 2, height / 2, 0xFFB892D9, false);
    }

    private void renderTooltip(DrawContext ctx, SkillTreeOpenPayload.Node n,
                                SkillTreeStatePayload state, int mouseX, int mouseY) {
        TextRenderer tr = textRenderer;
        boolean unlocked = state.unlocked.contains(n.id) || n.autoUnlocked;
        boolean allocatable = !unlocked && SkillTreeClient.isAllocatable(n.id);

        List<Text> lines = new ArrayList<>(5);
        String prefix = n.notable ? "★ " : "";
        Formatting nameColor = unlocked ? Formatting.LIGHT_PURPLE
                : allocatable ? branchFormatting(n.branch) : Formatting.GRAY;
        lines.add(Text.literal(prefix + n.name).formatted(nameColor, Formatting.BOLD));
        if (!n.autoUnlocked) {
            lines.add(Text.literal(formatEffect(n)).formatted(Formatting.GRAY));
        } else {
            lines.add(Text.literal("Start of the tree").formatted(Formatting.AQUA));
        }
        String status;
        Formatting statusColor;
        if (n.autoUnlocked) {
            status = "START";
            statusColor = Formatting.AQUA;
        } else if (unlocked) {
            status = "ALLOCATED";
            statusColor = Formatting.GREEN;
        } else if (allocatable) {
            status = "Click to allocate · " + n.cost + " pt"
                    + (state.available >= n.cost ? "" : " (not enough)");
            statusColor = state.available >= n.cost ? Formatting.YELLOW : Formatting.RED;
        } else {
            status = "Locked — allocate an adjacent node first";
            statusColor = Formatting.DARK_GRAY;
        }
        lines.add(Text.literal(status).formatted(statusColor));

        int tipW = 0;
        for (Text t : lines) tipW = Math.max(tipW, tr.getWidth(t));
        int tipH = lines.size() * (tr.fontHeight + 2) + 6;
        int tx = Math.min(mouseX + 12, width - tipW - 8);
        int ty = Math.max(6, mouseY + 12);
        if (ty + tipH > height - 24) ty = mouseY - tipH - 4;
        ctx.fill(tx - 4, ty - 4, tx + tipW + 8, ty + tipH, COL_HUD_BG);
        ctx.fill(tx - 4, ty - 4, tx + tipW + 8, ty - 3, COL_HUD_BORDER);
        ctx.fill(tx - 4, ty + tipH - 1, tx + tipW + 8, ty + tipH, COL_HUD_BORDER);
        ctx.fill(tx - 4, ty - 4, tx - 3, ty + tipH, COL_HUD_BORDER);
        ctx.fill(tx + tipW + 7, ty - 4, tx + tipW + 8, ty + tipH, COL_HUD_BORDER);
        int row = 0;
        for (Text t : lines) {
            ctx.drawText(tr, t, tx, ty + row * (tr.fontHeight + 2), 0xFFFFFFFF, false);
            row++;
        }
    }

    private static String formatEffect(SkillTreeOpenPayload.Node n) {
        float v = n.value;
        String num = v == Math.floor(v)
                ? Integer.toString((int) v)
                : String.format(Locale.US, "%.1f", v);
        return switch (n.effect) {
            case Protocol.SKILL_EFFECT_DUNGEON_DAMAGE_PCT          -> num + "% increased Damage to Dungeon Mobs";
            case Protocol.SKILL_EFFECT_DUNGEON_BOSS_DAMAGE_PCT     -> num + "% increased Damage to Dungeon Bosses";
            case Protocol.SKILL_EFFECT_DUNGEON_DAMAGE_REDUCTION_PCT -> num + "% decreased Damage Taken (Dungeons)";
            case Protocol.SKILL_EFFECT_DUNGEON_MAX_HP_FLAT         -> "+" + num + " Max HP (Dungeons)";
            case Protocol.SKILL_EFFECT_DUNGEON_MOVE_SPEED_PCT      -> num + "% increased Move Speed (Dungeons)";
            case Protocol.SKILL_EFFECT_DUNGEON_JUMP_BOOST_FLAT     -> "+" + num + " Jump Boost (Dungeons)";
            case Protocol.SKILL_EFFECT_CHEST_COST_REDUCTION_PCT    -> num + "% decreased Chest Cost";
            case Protocol.SKILL_EFFECT_RUNE_RARITY_UPGRADE_CHANCE  -> "+" + num + "% Rune Rarity Upgrade Chance";
            case Protocol.SKILL_EFFECT_BONUS_RUNE_DROP_CHANCE      -> "+" + num + "% Bonus Rune Drop Chance";
            case Protocol.SKILL_EFFECT_DUNGEON_LIFESTEAL_PCT       -> num + "% Damage Dealt Healed (Dungeon Mobs)";
            case Protocol.SKILL_EFFECT_DUNGEON_CULLING_THRESHOLD_PCT -> "Culling Strike: kill below " + num + "% HP";
            case Protocol.SKILL_EFFECT_DUNGEON_DOUBLE_JUMP_FLAT    -> "Unlocks Double Jump (Dungeons)";
            default -> "+" + num + " (unknown effect)";
        };
    }

    // ── Mouse input (1.21.11 Click API) ────────────────────────────────────

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        // Auto-defocus the search field if the click missed it. Otherwise
        // typing while panning the tree silently lands in the search box.
        if (searchField != null && searchField.isFocused() && !isInSearchBounds(mouseX, mouseY)) {
            searchField.setFocused(false);
            setFocused(null);
        }

        // Widget hits (search field, respec button) take precedence.
        if (super.mouseClicked(click, doubleClick)) return true;

        SkillTreeOpenPayload layout = SkillTreeClient.layout();
        if (layout == null) return false;

        String nodeId = pickNodeAt(layout, (int) mouseX, (int) mouseY);
        if (nodeId != null) {
            if (button == 0) {
                SkillTreeClient.requestAllocate(nodeId);
                return true;
            } else if (button == 1) {
                SkillTreeClient.requestRefund(nodeId);
                return true;
            }
        }

        // Empty space — start a pan drag with the left button.
        if (button == 0) {
            dragging = true;
            dragMovePx = 0;
            dragStartMouseX = mouseX;
            dragStartMouseY = mouseY;
            dragStartPanX = panX;
            dragStartPanY = panY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double offsetX, double offsetY) {
        if (dragging && click.button() == 0) {
            panX = dragStartPanX + (float) (click.x() - dragStartMouseX);
            panY = dragStartPanY + (float) (click.y() - dragStartMouseY);
            dragMovePx += Math.abs(offsetX) + Math.abs(offsetY);
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        dragging = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horiz, double vert) {
        float old = zoom;
        float next = old * (vert > 0 ? 1.15f : 0.87f);
        next = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, next));
        if (Math.abs(next - old) < 0.001f) return true;
        // Zoom around the cursor: keep the world point under the cursor stationary.
        float cx = (float) (mouseX - width * 0.5);
        float cy = (float) (mouseY - height * 0.5);
        panX = cx - (cx - panX) * (next / old);
        panY = cy - (cy - panY) * (next / old);
        zoom = next;
        return true;
    }

    private boolean isInSearchBounds(double x, double y) {
        if (searchField == null) return false;
        return x >= searchField.getX() && x < searchField.getX() + searchField.getWidth()
                && y >= searchField.getY() && y < searchField.getY() + searchField.getHeight();
    }

    // ── Picking + path BFS ──────────────────────────────────────────────────

    private String pickNodeAt(SkillTreeOpenPayload layout, int mx, int my) {
        String best = null;
        double bestD = Double.MAX_VALUE;
        for (SkillTreeOpenPayload.Node n : layout.nodes) {
            double sx = screenX(n.gx);
            double sy = screenY(n.gy);
            int baseR = n.autoUnlocked ? NODE_GATE_RADIUS
                    : n.notable ? NODE_NOTABLE_RADIUS : NODE_BASE_RADIUS;
            double r = Math.max(4, baseR * zoom);
            double dx = mx - sx;
            double dy = my - sy;
            double d = Math.max(Math.abs(dx), Math.abs(dy));
            if (d <= r && d < bestD) {
                bestD = d;
                best = n.id;
            }
        }
        return best;
    }

    /**
     * Shortest path from any currently-allocated node (or the gate) to the
     * hovered unallocated node, expanding through still-locked nodes. The
     * resulting set is the chain of node-ids the player would need to
     * allocate. Used to render a soft amethyst halo behind those nodes.
     *
     * <p>Returns an empty set if the hover target is allocated, is null,
     * or no path exists in the layout (shouldn't happen — the tree is
     * fully connected).
     */
    private Set<String> computePathToHere(SkillTreeOpenPayload layout, SkillTreeStatePayload state,
                                           String targetId) {
        if (targetId == null || layout == null || state == null) return java.util.Collections.emptySet();
        if (state.unlocked.contains(targetId)) return java.util.Collections.emptySet();
        Integer targetIdx = layout.indexById.get(targetId);
        if (targetIdx == null) return java.util.Collections.emptySet();

        // Build adjacency lookup once.
        Map<Integer, List<Integer>> adj = new HashMap<>(layout.nodes.size() * 2);
        for (int[] e : layout.edges) {
            adj.computeIfAbsent(e[0], k -> new ArrayList<>()).add(e[1]);
            adj.computeIfAbsent(e[1], k -> new ArrayList<>()).add(e[0]);
        }

        // BFS sources: every unlocked node + every auto-unlocked (gate) node.
        Deque<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> parent = new HashMap<>();
        for (int i = 0; i < layout.nodes.size(); i++) {
            SkillTreeOpenPayload.Node n = layout.nodes.get(i);
            if (n.autoUnlocked || state.unlocked.contains(n.id)) {
                queue.add(i);
                parent.put(i, -1);
            }
        }
        if (queue.isEmpty()) return java.util.Collections.emptySet();

        boolean found = false;
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (cur == targetIdx) { found = true; break; }
            List<Integer> nbs = adj.get(cur);
            if (nbs == null) continue;
            for (int nb : nbs) {
                if (parent.containsKey(nb)) continue;
                parent.put(nb, cur);
                queue.add(nb);
            }
        }
        if (!found) return java.util.Collections.emptySet();

        Set<String> path = new HashSet<>();
        int cur = targetIdx;
        while (cur >= 0) {
            SkillTreeOpenPayload.Node n = layout.nodes.get(cur);
            // Only include nodes the player still needs to allocate — already
            // unlocked nodes don't need the highlight.
            if (!state.unlocked.contains(n.id) && !n.autoUnlocked) {
                path.add(n.id);
            }
            Integer next = parent.get(cur);
            if (next == null || next < 0) break;
            cur = next;
        }
        return path;
    }

    // ── Respec button (two-click confirmation) ─────────────────────────────

    private void onRespecClicked() {
        long now = System.currentTimeMillis();
        if (respecArmedAtMs == 0 || now - respecArmedAtMs > RESPEC_CONFIRM_WINDOW_MS) {
            respecArmedAtMs = now;
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.8f, 0.8f);
            }
            respecButton.setMessage(Text.literal("Confirm Respec").formatted(Formatting.YELLOW));
            return;
        }
        respecArmedAtMs = 0L;
        respecButton.setMessage(Text.literal("Respec All").formatted(Formatting.LIGHT_PURPLE));
        SkillTreeClient.requestRespec();
    }

    // ── Colour helpers ──────────────────────────────────────────────────────

    private static int branchColor(byte branch) {
        return switch (branch) {
            case Protocol.BRANCH_ASSAULT   -> COL_BRANCH_ASSAULT;
            case Protocol.BRANCH_ENDURANCE -> COL_BRANCH_ENDURANCE;
            case Protocol.BRANCH_AGILITY   -> COL_BRANCH_AGILITY;
            case Protocol.BRANCH_FORTUNE   -> COL_BRANCH_FORTUNE;
            default                         -> COL_BRANCH_GATE;
        };
    }

    private static Formatting branchFormatting(byte branch) {
        return switch (branch) {
            case Protocol.BRANCH_ASSAULT   -> Formatting.RED;
            case Protocol.BRANCH_ENDURANCE -> Formatting.GOLD;
            case Protocol.BRANCH_AGILITY   -> Formatting.AQUA;
            case Protocol.BRANCH_FORTUNE   -> Formatting.GREEN;
            default                         -> Formatting.LIGHT_PURPLE;
        };
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF, aa = (a >>> 24) & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF, ba = (b >>> 24) & 0xFF;
        int rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t);
        int rb = (int) (ab + (bb - ab) * t);
        int ra = (int) (aa + (ba - aa) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

}
