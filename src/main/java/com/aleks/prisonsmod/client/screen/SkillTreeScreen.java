package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.skilltree.SkillTreeClient;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.SkillTreeOpenPayload;
import com.aleks.prisonsmod.net.payload.SkillTreeStatePayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

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

    // ── Client-side organic transform tunables ──────────────────────────────
    /** Global rotation applied to every node position (degrees). Breaks the
     *  horizontal/vertical axis lock the server's grid has. */
    private static final float GLOBAL_ROTATION_DEG = 25f;
    /** Per-cluster small-node ring radius (in grid units). Server places the
     *  4 small nodes in a + shape at 1 grid unit from the notable; we move
     *  them to a smoother ring at this radius and at 45° offsets. */
    private static final float CLUSTER_SMALL_RADIUS = 1.05f;
    /** Each cluster gets a small per-id rotation jitter so they don't all
     *  align — kills the "every cluster is a plus" feel. (degrees max) */
    private static final float CLUSTER_JITTER_DEG = 22f;

    // ── Tartarus palette ────────────────────────────────────────────────────
    // Deep cosmic backdrop — three-stop vertical with a darker center where
    // the tree sits so nodes pop against it.
    private static final int COL_BG_TOP        = 0xFF1B0E36;
    private static final int COL_BG_MID        = 0xFF0A0418;
    private static final int COL_BG_BOTTOM     = 0xFF1B0E36;
    private static final int COL_VIGNETTE      = 0xC8000000;
    private static final int COL_HUD_BG        = 0xE61A0A2A;
    private static final int COL_HUD_BORDER    = 0xFF6B3EAA;
    private static final int COL_HUD_BORDER_HI = 0xFFA070E0;
    private static final int COL_AMETHYST      = 0xFFD4B0F0;
    private static final int COL_AMETHYST_DIM  = 0x55D4B0F0;
    private static final int COL_STAR          = 0xFFFFFFFF;

    private static final int COL_EDGE_LOCKED    = 0x40231840;
    private static final int COL_EDGE_REACHABLE = 0x806B4FAA;
    private static final int COL_EDGE_UNLOCKED  = 0xFFE9C8FF;
    private static final int COL_EDGE_SPARK     = 0xFFFFFFFF;

    // Cores (the solid node fill) — saturated but not blinding.
    private static final int COL_BRANCH_ASSAULT      = 0xFFFF6B6B;
    private static final int COL_BRANCH_ENDURANCE    = 0xFFFFD24A;
    private static final int COL_BRANCH_AGILITY      = 0xFF66E6FF;
    private static final int COL_BRANCH_FORTUNE      = 0xFF7BFFAA;
    private static final int COL_BRANCH_GATE         = 0xFFD4B0F0;

    // Glow colours — pure, higher-luminance hue used for halos so the
    // additive-feeling stacked alpha layers look like real bloom.
    private static final int COL_BRANCH_ASSAULT_GLOW   = 0xFFFF3030;
    private static final int COL_BRANCH_ENDURANCE_GLOW = 0xFFFFAA00;
    private static final int COL_BRANCH_AGILITY_GLOW   = 0xFF22BBFF;
    private static final int COL_BRANCH_FORTUNE_GLOW   = 0xFF22DD66;
    private static final int COL_BRANCH_GATE_GLOW      = 0xFFB892F5;

    private static final int COL_SEARCH_MATCH       = 0xFFFFD854;
    private static final int COL_SEARCH_MATCH_GLOW  = 0xFFFFAA22;

    // ── Sprite assets ───────────────────────────────────────────────────────
    /** Grayscale template — branch colour is multiplied in at draw time.
     *  If missing the renderer silently falls back to procedural rectangles. */
    private static final Identifier SPRITE_NODE_TEMPLATE =
            Identifier.of(PrisonsMod.MOD_ID, "textures/gui/skilltree/node_base_template.png");
    /** Source dimensions of the template — used for the drawTexture call so
     *  Minecraft's scaler doesn't have to guess. */
    private static final int SPRITE_TEMPLATE_PX = 1024;

    /** Per-Identifier cache for the resource-manager existence check.
     *  Populated lazily so the first frame doesn't stall on disk I/O. */
    private final java.util.Map<Identifier, Boolean> textureExistsCache = new java.util.HashMap<>();

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

        // Reclamp on window resize so a smaller window doesn't leave the
        // pan offset out of bounds from a previous wider layout.
        clampPan();
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
    //
    // The server's SkillTreeLayout puts nodes on a strict integer grid with
    // cardinal arms — visually that reads as a "square Manhattan grid"
    // (notables connect to small nodes in a +, all connectors are 90° axis-
    // aligned). To get a PoE-style organic feel without changing the server
    // layout, we apply a client-only transform here:
    //   1. Global rotation by GLOBAL_ROTATION_DEG, so the cardinal arms no
    //      longer align to the screen axes.
    //   2. Cluster smalls (the 4 "+ shape" nodes around every cluster
    //      notable) are repositioned into a ring around their notable at
    //      diagonal offsets, with a small per-cluster jitter so no two
    //      clusters land at exactly the same orientation.
    // After transform, edges become smooth diagonal lines and the tree
    // reads as a tilted radial structure instead of an axis grid.

    /** Cached transformed (post-rotation, post-reshape) world positions per
     *  node id. Rebuilt only when the layout changes (very rare). */
    private final java.util.Map<String, float[]> transformedPositions = new java.util.HashMap<>();
    private long transformLayoutVersion = -1L;

    private void rebuildTransformIfNeeded() {
        SkillTreeOpenPayload layout = SkillTreeClient.layout();
        if (layout == null) return;
        long v = SkillTreeClient.version();
        if (v == transformLayoutVersion && !transformedPositions.isEmpty()) return;
        transformLayoutVersion = v;
        transformedPositions.clear();

        final double rot = Math.toRadians(GLOBAL_ROTATION_DEG);
        final double cosR = Math.cos(rot);
        final double sinR = Math.sin(rot);

        // Pass 1: apply global rotation to every node's grid position.
        java.util.Map<String, float[]> base = new java.util.HashMap<>(layout.nodes.size() * 2);
        for (SkillTreeOpenPayload.Node n : layout.nodes) {
            float fx = n.gx;
            float fy = n.gy;
            float rx = (float) (fx * cosR - fy * sinR);
            float ry = (float) (fx * sinR + fy * cosR);
            base.put(n.id, new float[] { rx, ry });
        }

        // Pass 2: cluster smalls — replace the + shape with a ring of 4 nodes
        // at diagonal offsets around the notable. Each cluster gets a small
        // deterministic jitter so the ring isn't identically aligned everywhere.
        for (SkillTreeOpenPayload.Node n : layout.nodes) {
            int smallIdx = clusterSmallIndex(n.id);
            if (smallIdx >= 0) {
                String clusterPrefix = n.id.substring(0, n.id.lastIndexOf("_s"));
                String notableId = clusterPrefix + "_notable";
                float[] notablePos = base.get(notableId);
                if (notablePos != null) {
                    float jitter = (clusterPrefix.hashCode() & 0xFFFF) / 65535f; // 0..1
                    double clusterRotDeg = (jitter - 0.5f) * 2f * CLUSTER_JITTER_DEG;
                    double ringAngle = Math.toRadians(45 + 90 * smallIdx + clusterRotDeg);
                    double ox = CLUSTER_SMALL_RADIUS * Math.cos(ringAngle);
                    double oy = CLUSTER_SMALL_RADIUS * Math.sin(ringAngle);
                    transformedPositions.put(n.id, new float[] {
                            notablePos[0] + (float) ox,
                            notablePos[1] + (float) oy
                    });
                    continue;
                }
            }
            transformedPositions.put(n.id, base.get(n.id));
        }
    }

    /** Returns the 0..3 small index if the id matches {@code <prefix>_s<idx>},
     *  else -1. Used to detect cluster small nodes for ring-reshape. */
    private static int clusterSmallIndex(String id) {
        int sIdx = id.lastIndexOf("_s");
        if (sIdx <= 0) return -1;
        String suffix = id.substring(sIdx + 2);
        if (suffix.isEmpty() || suffix.length() > 2) return -1;
        try {
            int idx = Integer.parseInt(suffix);
            return (idx >= 0 && idx <= 3) ? idx : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private float screenXOf(SkillTreeOpenPayload.Node n) {
        float[] p = transformedPositions.get(n.id);
        float fx = p != null ? p[0] : n.gx;
        return width * 0.5f + fx * GRID_PITCH * zoom + panX;
    }

    private float screenYOf(SkillTreeOpenPayload.Node n) {
        float[] p = transformedPositions.get(n.id);
        float fy = p != null ? p[1] : n.gy;
        return height * 0.5f + fy * GRID_PITCH * zoom + panY;
    }

    // ── Render ──────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();

        // Backdrop — vertical 3-stop gradient (dark cosmic above, deeper purple-
        // black in the middle where the tree sits, fade back up at the bottom).
        ctx.fillGradient(0, 0, width, height / 2, COL_BG_TOP, COL_BG_MID);
        ctx.fillGradient(0, height / 2, width, height, COL_BG_MID, COL_BG_BOTTOM);

        // Twinkling stars — deterministic positions so they don't shimmer
        // randomly between frames, but each star's brightness drifts on its
        // own phase so the field feels alive.
        renderStars(ctx, now);

        // Vignette — darken top and bottom strips so the eye centres on the
        // tree. Horizontal gradients aren't directly supported, so we accept
        // a slight asymmetry (corners get the most darkening from the overlap).
        ctx.fillGradient(0, 0, width, 120, COL_VIGNETTE, withAlpha(0xFF000000, 0));
        ctx.fillGradient(0, height - 120, width, height, withAlpha(0xFF000000, 0), COL_VIGNETTE);

        SkillTreeOpenPayload layout = SkillTreeClient.layout();
        SkillTreeStatePayload state = SkillTreeClient.state();

        if (layout == null || state == null) {
            renderLoadingOverlay(ctx);
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        // Make sure the client-side organic transform map is built for the
        // current layout. Cheap no-op if already up-to-date.
        rebuildTransformIfNeeded();

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
        renderEdges(ctx, layout, state, now);
        renderNodes(ctx, layout, state, mouseX, mouseY, now);

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

    private void renderEdges(DrawContext ctx, SkillTreeOpenPayload layout, SkillTreeStatePayload state, long now) {
        int coreThickness = Math.max(1, Math.round(2f * zoom));
        int glowThickness = coreThickness + Math.max(2, Math.round(3f * zoom));

        for (int[] e : layout.edges) {
            SkillTreeOpenPayload.Node a = layout.nodes.get(e[0]);
            SkillTreeOpenPayload.Node b = layout.nodes.get(e[1]);
            boolean aUn = state.unlocked.contains(a.id) || a.autoUnlocked;
            boolean bUn = state.unlocked.contains(b.id) || b.autoUnlocked;

            int sx1 = (int) screenXOf(a);
            int sy1 = (int) screenYOf(a);
            int sx2 = (int) screenXOf(b);
            int sy2 = (int) screenYOf(b);

            if (aUn && bUn) {
                // Active unlocked edge — gradient core blending each endpoint's
                // branch hue, surrounded by 3 stacked alpha layers that fake
                // additive bloom on overlap.
                int colA = branchGlowColor(a.branch);
                int colB = branchGlowColor(b.branch);
                int midGlow = blend(colA, colB, 0.5f);
                drawDiagonalLine(ctx, sx1, sy1, sx2, sy2, glowThickness + 2, withAlpha(midGlow, 0x22));
                drawDiagonalLine(ctx, sx1, sy1, sx2, sy2, glowThickness,     withAlpha(midGlow, 0x55));
                drawDiagonalGradient(ctx, sx1, sy1, sx2, sy2, coreThickness + 1,
                        withAlpha(colA, 0xDD), withAlpha(colB, 0xDD));
                drawDiagonalGradient(ctx, sx1, sy1, sx2, sy2, coreThickness,
                        blend(COL_EDGE_UNLOCKED, colA, 0.35f),
                        blend(COL_EDGE_UNLOCKED, colB, 0.35f));

                // Flowing spark along the edge — only at decent zoom, otherwise
                // it's noise. Travels at constant on-screen speed.
                if (zoom >= 0.45f) {
                    float len = (float) Math.hypot(sx2 - sx1, sy2 - sy1);
                    if (len > 1f) {
                        float t = ((now * 0.06f) % len) / len;
                        int fx = (int) (sx1 + (sx2 - sx1) * t);
                        int fy = (int) (sy1 + (sy2 - sy1) * t);
                        int sparkR = Math.max(2, Math.round(3.5f * zoom));
                        drawSoftGlow(ctx, fx, fy, sparkR * 3, withAlpha(midGlow, 0x60));
                        drawSoftGlow(ctx, fx, fy, sparkR, COL_EDGE_SPARK);
                    }
                }
            } else if (aUn || bUn) {
                // One side connected — dim solid line, no glow.
                drawDiagonalLine(ctx, sx1, sy1, sx2, sy2, coreThickness, COL_EDGE_REACHABLE);
            } else {
                // Both locked — barely visible.
                drawDiagonalLine(ctx, sx1, sy1, sx2, sy2, coreThickness, COL_EDGE_LOCKED);
            }
        }
    }

    /**
     * Thick diagonal line via per-step square stamping. Steps along the
     * longer axis and stamps a {@code thickness × thickness} filled square
     * at every step; consecutive stamps overlap so the line reads as solid.
     */
    private static void drawDiagonalLine(DrawContext ctx, int x1, int y1, int x2, int y2,
                                         int thickness, int color) {
        if ((color >>> 24) == 0 || thickness <= 0) return;
        int dx = x2 - x1, dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            int half = thickness / 2;
            ctx.fill(x1 - half, y1 - half, x1 + thickness - half, y1 + thickness - half, color);
            return;
        }
        int half = Math.max(1, thickness / 2);
        int rem  = thickness - half;
        // Step every pixel for a smooth line. ~300 edges × ~60 steps × 4 layers
        // = ~70k fills/frame — fine on modern hardware.
        for (int i = 0; i <= steps; i++) {
            int x = x1 + dx * i / steps;
            int y = y1 + dy * i / steps;
            ctx.fill(x - half, y - half, x + rem, y + rem, color);
        }
    }

    /**
     * Same as {@link #drawDiagonalLine} but the colour smoothly blends from
     * {@code colorA} at the start to {@code colorB} at the end — gives each
     * edge a branch-flavoured gradient.
     */
    private static void drawDiagonalGradient(DrawContext ctx, int x1, int y1, int x2, int y2,
                                              int thickness, int colorA, int colorB) {
        if (thickness <= 0) return;
        int dx = x2 - x1, dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            int half = thickness / 2;
            ctx.fill(x1 - half, y1 - half, x1 + thickness - half, y1 + thickness - half, colorA);
            return;
        }
        int half = Math.max(1, thickness / 2);
        int rem  = thickness - half;
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / (float) steps;
            int color = blend(colorA, colorB, t);
            int x = x1 + dx * i / steps;
            int y = y1 + dy * i / steps;
            ctx.fill(x - half, y - half, x + rem, y + rem, color);
        }
    }

    private void renderNodes(DrawContext ctx, SkillTreeOpenPayload layout, SkillTreeStatePayload state,
                             int mouseX, int mouseY, long now) {
        for (SkillTreeOpenPayload.Node n : layout.nodes) {
            int sx = (int) screenXOf(n);
            int sy = (int) screenYOf(n);
            int baseRadius;
            if (n.autoUnlocked) baseRadius = NODE_GATE_RADIUS;
            else if (n.notable) baseRadius = NODE_NOTABLE_RADIUS;
            else baseRadius = NODE_BASE_RADIUS;
            int r = Math.max(3, Math.round(baseRadius * zoom));

            boolean unlocked = state.unlocked.contains(n.id) || n.autoUnlocked;
            boolean allocatable = !unlocked && SkillTreeClient.isAllocatable(n.id);
            boolean searchActive = !searchLower.isEmpty();
            boolean matchesSearch = searchActive && nodeMatchesSearch(n);
            boolean dimmedBySearch = searchActive && !matchesSearch;
            boolean onPath = pathHighlight.contains(n.id);
            boolean isHovered = n.id.equals(hoveredNodeId);

            int branchCore = branchColor(n.branch);
            int branchGlow = branchGlowColor(n.branch);

            // ── Layer 0: path-to-here amethyst halo (behind everything)
            if (onPath && !unlocked && !dimmedBySearch) {
                int haloR = r + Math.max(4, Math.round(6 * zoom));
                drawSoftGlow(ctx, sx, sy, haloR, withAlpha(COL_AMETHYST, 0x70));
            }

            // ── Layer 1: search-match golden halo with slow breathing pulse.
            //    Uses the same drawSoftGlow stack as everything else so the
            //    falloff matches the rest of the screen instead of being a
            //    flat opaque rectangle (the old version's "jarring yellow").
            if (matchesSearch && !unlocked) {
                float pulse = 0.55f + 0.45f * (float) Math.sin(now * 0.0035 + n.gx * 0.13 + n.gy * 0.07);
                int matchR = r + Math.max(6, Math.round((8 + 4 * pulse) * zoom));
                drawSoftGlow(ctx, sx, sy, matchR, withAlpha(COL_SEARCH_MATCH_GLOW, (int) (0xA0 * (0.55f + 0.45f * pulse))));
                drawSoftGlow(ctx, sx, sy, matchR / 2, withAlpha(COL_SEARCH_MATCH, (int) (0x90 * (0.55f + 0.45f * pulse))));
            }

            // ── Layer 2: persistent branch-colour halo for unlocked nodes,
            //    with a subtle breathing animation so the tree feels alive.
            if (unlocked && !dimmedBySearch) {
                float breathe = 0.85f + 0.15f * (float) Math.sin(now * 0.0018 + n.gx * 0.05 + n.gy * 0.05);
                int glowR = (int) (r * 2.0f * breathe);
                drawSoftGlow(ctx, sx, sy, glowR, withAlpha(branchGlow, (int) (0x70 * breathe)));
            } else if (allocatable && !dimmedBySearch) {
                // Allocatable: pulse to invite the click.
                float pulse = 0.5f + 0.5f * (float) Math.sin(now * 0.003 + n.gx * 0.07);
                int glowR = (int) (r * 1.45f);
                drawSoftGlow(ctx, sx, sy, glowR, withAlpha(branchGlow, (int) (0x50 + 0x30 * pulse)));
            }

            // ── Layer 3: just-changed unlock / refund pulse (fades over PULSE_LIFETIME_MS).
            float upulse  = SkillTreeClient.unlockPulse(n.id);
            float refund  = SkillTreeClient.refundPulse(n.id);
            float pStr    = Math.max(upulse, refund);
            if (pStr > 0f) {
                int pColor = upulse > refund ? COL_AMETHYST : 0xFF7AA5FF;
                int extra  = Math.round((6 + 14 * (1f - pStr)) * zoom);
                int hr     = r + Math.max(2, extra);
                drawSoftGlow(ctx, sx, sy, hr, withAlpha(pColor, (int) (0xD0 * pStr)));
            }

            // ── Layer 4: hover ring.
            if (isHovered && !dimmedBySearch) {
                int hoverR = r + Math.max(3, Math.round(4 * zoom));
                drawSoftGlow(ctx, sx, sy, hoverR, withAlpha(COL_AMETHYST, 0x80));
            }

            // ── Body: either a sprite (preferred — branch-tinted grayscale
            //    template) or procedural rectangles as graceful fallback.
            boolean useSprite = textureExists(SPRITE_NODE_TEMPLATE);

            if (useSprite) {
                // Sprite mode — render a tinted PNG centered on the node.
                int spriteR = r + Math.max(2, Math.round(2 * zoom)); // slight pad for the glow rim
                int tint = branchSpriteTint(n.branch, unlocked, allocatable);
                if (dimmedBySearch) {
                    tint = withAlpha(tint, 0x30);
                } else {
                    // Slight breathing on allocated nodes — modulate alpha.
                    if (unlocked) {
                        float breathe = 0.92f + 0.08f * (float) Math.sin(now * 0.0022 + n.gx * 0.05);
                        int a = Math.round(255 * breathe);
                        tint = withAlpha(tint, a);
                    }
                }
                drawSpriteTinted(ctx, SPRITE_NODE_TEMPLATE, sx, sy, spriteR, tint);

                // Notable inner pip overlaid on top so visually-important
                // nodes still stand out at distance.
                if (n.notable && !n.autoUnlocked && r > 4 && !dimmedBySearch) {
                    int p = Math.max(2, r / 4);
                    ctx.fill(sx - p, sy - p, sx + p, sy + p, COL_AMETHYST);
                    ctx.fill(sx - p + 1, sy - p + 1, sx + p - 1, sy - p + 2,
                            withAlpha(0xFFFFFFFF, 0x90));
                }

                // Gate marker overlay — a small radiant pulse on top of the sprite.
                if (n.autoUnlocked && r > 6 && !dimmedBySearch) {
                    float gpulse = 0.7f + 0.3f * (float) Math.sin(now * 0.002);
                    int cp = Math.max(2, Math.round(r * 0.25f * gpulse));
                    ctx.fill(sx - cp, sy - cp, sx + cp, sy + cp, 0xFFFFFFFF);
                }
            } else {
                // ── Procedural fallback path ───────────────────────────────
                // Drop shadow
                if (!dimmedBySearch) {
                    int shadowOff = Math.max(1, Math.round(2 * zoom));
                    ctx.fill(sx - r + shadowOff, sy + r - 1,
                             sx + r + shadowOff, sy + r + shadowOff + 1,
                             withAlpha(0xFF000000, 0x55));
                }
                // Body
                int bodyCore;
                if (unlocked)         bodyCore = blend(branchCore, 0xFFFFFFFF, 0.15f);
                else if (allocatable) bodyCore = withAlpha(branchCore, 0xD0);
                else                  bodyCore = withAlpha(0xFF2A1A48, 0xE0);
                if (dimmedBySearch)   bodyCore = withAlpha(bodyCore, 0x12);
                ctx.fill(sx - r, sy - r, sx + r, sy + r, bodyCore);
                if (!dimmedBySearch) {
                    int topH = Math.max(1, r / 2);
                    ctx.fillGradient(sx - r, sy - r, sx + r, sy - r + topH,
                            withAlpha(0xFFFFFFFF, 0x30), withAlpha(0xFFFFFFFF, 0));
                    int botH = Math.max(1, r / 3);
                    ctx.fillGradient(sx - r, sy + r - botH, sx + r, sy + r,
                            withAlpha(0xFF000000, 0), withAlpha(0xFF000000, 0x40));
                }
                // Border ring
                int borderCol;
                if (unlocked)         borderCol = COL_AMETHYST;
                else if (allocatable) borderCol = blend(branchCore, COL_AMETHYST, 0.55f);
                else                  borderCol = withAlpha(branchCore, 0x55);
                if (dimmedBySearch)   borderCol = withAlpha(borderCol, 0x18);
                int bt = Math.max(1, Math.round(1.5f * zoom));
                drawRect(ctx, sx - r, sy - r, sx + r, sy + r, bt, borderCol);
                // Notable pip
                if (n.notable && !n.autoUnlocked && r > 4) {
                    int p = Math.max(2, r / 3);
                    int pipCore = withAlpha(COL_AMETHYST, dimmedBySearch ? 0x20 : 0xFF);
                    ctx.fill(sx - p, sy - p, sx + p, sy + p, pipCore);
                    if (!dimmedBySearch) {
                        ctx.fill(sx - p + 1, sy - p + 1, sx + p - 1, sy - p + 2,
                                withAlpha(0xFFFFFFFF, 0x90));
                    }
                }
                // Gate cross
                if (n.autoUnlocked && r > 6) {
                    float gpulse = 0.7f + 0.3f * (float) Math.sin(now * 0.002);
                    int p = (int) (r * 0.5f * gpulse);
                    int gateCol = withAlpha(COL_AMETHYST, dimmedBySearch ? 0x20 : 0xFF);
                    ctx.fill(sx - p, sy - p, sx + p, sy + p, gateCol);
                    int armW = Math.max(2, Math.round(2.5f * zoom));
                    ctx.fill(sx - armW / 2, sy - r, sx + armW - armW / 2, sy + r, gateCol);
                    ctx.fill(sx - r, sy - armW / 2, sx + r, sy + armW - armW / 2, gateCol);
                    if (!dimmedBySearch) {
                        int cp = Math.max(1, p / 3);
                        ctx.fill(sx - cp, sy - cp, sx + cp, sy + cp, 0xFFFFFFFF);
                    }
                }
            }

            // ── Layer 10: search-match thin gold ring on top.
            if (matchesSearch && !unlocked) {
                int ringR = r + Math.max(2, Math.round(2 * zoom));
                int ringT = Math.max(1, Math.round(1.5f * zoom));
                drawRect(ctx, sx - ringR, sy - ringR, sx + ringR, sy + ringR, ringT, COL_SEARCH_MATCH);
            }
        }
    }

    /** Lazy resource-manager check — true if a texture file is actually
     *  bundled with the mod. False positives are impossible; false negatives
     *  only on the first call before the resource manager is ready (which
     *  should never happen by the time render() fires). */
    private boolean textureExists(Identifier id) {
        Boolean cached = textureExistsCache.get(id);
        if (cached != null) return cached;
        boolean exists;
        try {
            exists = MinecraftClient.getInstance().getResourceManager().getResource(id).isPresent();
        } catch (Throwable t) {
            exists = false;
        }
        textureExistsCache.put(id, exists);
        return exists;
    }

    /** Branch tint applied to the grayscale template — multiplied with the
     *  sprite's luminance to produce a branch-coloured node. Allocatable +
     *  unlocked variants brighten the tint; locked dims it. */
    private static int branchSpriteTint(byte branch, boolean unlocked, boolean allocatable) {
        int hue = switch (branch) {
            case Protocol.BRANCH_ASSAULT   -> 0xFFFF6B6B;
            case Protocol.BRANCH_ENDURANCE -> 0xFFFFD24A;
            case Protocol.BRANCH_AGILITY   -> 0xFF66E6FF;
            case Protocol.BRANCH_FORTUNE   -> 0xFF7BFFAA;
            default                         -> 0xFFD4B0F0;
        };
        if (unlocked)         return brighten(hue, 1.10f);
        if (allocatable)      return hue;
        // Locked: dim and slightly desaturate.
        return blend(hue, 0xFF3A2A5A, 0.55f);
    }

    private static int brighten(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * factor));
        int g = Math.min(255, Math.round(((argb >> 8)  & 0xFF) * factor));
        int b = Math.min(255, Math.round((argb         & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Draw a square-cropped sprite centered at (cx, cy) with the given
     *  radius (half-size in pixels) and ARGB tint multiplier (modulates
     *  the sprite's per-channel intensity). */
    private void drawSpriteTinted(DrawContext ctx, Identifier id, int cx, int cy, int radius, int tint) {
        int size = Math.max(2, radius * 2);
        int x = cx - radius;
        int y = cy - radius;
        try {
            ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, id,
                    x, y, 0f, 0f, size, size,
                    SPRITE_TEMPLATE_PX, SPRITE_TEMPLATE_PX, tint);
        } catch (Throwable t) {
            textureExistsCache.put(id, false);
            PrisonsMod.LOGGER.debug("drawSpriteTinted failed for {}: {}", id, t.toString());
        }
    }

    /**
     * Multi-layer radial glow approximation. Stacks 3-4 concentric squares
     * with decreasing alpha so overlapping nodes blend into a brighter
     * core — fakes additive bloom without dropping into RenderLayer plumbing.
     */
    private static void drawSoftGlow(DrawContext ctx, int cx, int cy, int radius, int color) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0 || radius < 1) return;
        int rgb = color & 0x00FFFFFF;
        // 4 stacked layers, each tighter and brighter than the last.
        int[] sizes  = { radius, (radius * 3) / 4, radius / 2, Math.max(1, radius / 3) };
        int[] alphas = { alpha / 6, alpha / 3, (alpha * 2) / 3, alpha };
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            int a = alphas[i] & 0xFF;
            if (a == 0 || s <= 0) continue;
            ctx.fill(cx - s, cy - s, cx + s, cy + s, rgb | (a << 24));
        }
    }

    /** Outline rect with given thickness — 4 thin fills. */
    private static void drawRect(DrawContext ctx, int x1, int y1, int x2, int y2, int thickness, int color) {
        if ((color >>> 24) == 0 || thickness <= 0) return;
        ctx.fill(x1, y1, x2, y1 + thickness, color);                 // top
        ctx.fill(x1, y2 - thickness, x2, y2, color);                 // bottom
        ctx.fill(x1, y1, x1 + thickness, y2, color);                 // left
        ctx.fill(x2 - thickness, y1, x2, y2, color);                 // right
    }

    /** Deterministic starfield drawn behind the tree. ~70 stars, each with
     *  its own sine-driven twinkle phase. Cheap (one fill per star). */
    private void renderStars(DrawContext ctx, long now) {
        java.util.Random r = new java.util.Random(0xA72ECL ^ (long) width * 131L ^ (long) height * 1733L);
        int count = 70;
        for (int i = 0; i < count; i++) {
            int x = r.nextInt(width);
            int y = r.nextInt(height);
            int size = r.nextInt(100) < 12 ? 2 : 1;
            float phase = r.nextFloat() * (float) Math.PI * 2f;
            float speed = 0.0008f + r.nextFloat() * 0.0022f;
            float t = 0.4f + 0.6f * (0.5f + 0.5f * (float) Math.sin(now * speed + phase));
            int alpha = (int) (0x70 * t);
            ctx.fill(x, y, x + size, y + size, withAlpha(COL_STAR, alpha));
        }
    }

    private static int branchGlowColor(byte branch) {
        return switch (branch) {
            case Protocol.BRANCH_ASSAULT   -> COL_BRANCH_ASSAULT_GLOW;
            case Protocol.BRANCH_ENDURANCE -> COL_BRANCH_ENDURANCE_GLOW;
            case Protocol.BRANCH_AGILITY   -> COL_BRANCH_AGILITY_GLOW;
            case Protocol.BRANCH_FORTUNE   -> COL_BRANCH_FORTUNE_GLOW;
            default                         -> COL_BRANCH_GATE_GLOW;
        };
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
            clampPan();
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
        clampPan();
        return true;
    }

    /**
     * Stop the player from panning the tree completely off-screen. Always
     * keeps at least {@link #PAN_KEEP_MARGIN_PX} of the node bounding box
     * visible on every edge.
     */
    private static final float PAN_KEEP_MARGIN_PX = 80f;

    private void clampPan() {
        SkillTreeOpenPayload layout = SkillTreeClient.layout();
        if (layout == null || layout.nodes.isEmpty()) return;
        // Use transformed positions so the bbox follows the rotated tree.
        rebuildTransformIfNeeded();
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (SkillTreeOpenPayload.Node n : layout.nodes) {
            float[] p = transformedPositions.get(n.id);
            float fx = p != null ? p[0] : n.gx;
            float fy = p != null ? p[1] : n.gy;
            if (fx < minX) minX = fx;
            if (fx > maxX) maxX = fx;
            if (fy < minY) minY = fy;
            if (fy > maxY) maxY = fy;
        }
        float leftPx  = minX * GRID_PITCH * zoom;
        float rightPx = maxX * GRID_PITCH * zoom;
        float topPx   = minY * GRID_PITCH * zoom;
        float botPx   = maxY * GRID_PITCH * zoom;
        // Require the tree's screen rect to overlap [margin, screen - margin].
        //   screenLeft  = width/2  + leftPx  + panX  must be <= width - margin
        //   screenRight = width/2  + rightPx + panX  must be >= margin
        float panXMin = PAN_KEEP_MARGIN_PX - width * 0.5f - rightPx;
        float panXMax = width - PAN_KEEP_MARGIN_PX - width * 0.5f - leftPx;
        panX = Math.max(panXMin, Math.min(panXMax, panX));
        float panYMin = PAN_KEEP_MARGIN_PX - height * 0.5f - botPx;
        float panYMax = height - PAN_KEEP_MARGIN_PX - height * 0.5f - topPx;
        panY = Math.max(panYMin, Math.min(panYMax, panY));
    }

    /**
     * Match a node against the current search query. Matches on display
     * name (e.g. "Phase Step"), the effect description text (so "speed"
     * finds every {@code DUNGEON_MOVE_SPEED_PCT} node), and a handful of
     * intuitive aliases ("damage" → DUNGEON_DAMAGE_PCT etc.).
     */
    private boolean nodeMatchesSearch(SkillTreeOpenPayload.Node n) {
        if (searchLower.isEmpty()) return true;
        if (n.name.toLowerCase(Locale.ROOT).contains(searchLower)) return true;
        String effect = formatEffect(n).toLowerCase(Locale.ROOT);
        if (effect.contains(searchLower)) return true;
        return false;
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
            double sx = screenXOf(n);
            double sy = screenYOf(n);
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
