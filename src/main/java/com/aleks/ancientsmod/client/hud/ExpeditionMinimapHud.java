package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.net.payload.ExpeditionMapPayload;
import com.aleks.ancientsmod.net.payload.ExpeditionMarkersPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Expedition minimap, Path of Exile map-overlay style: the whole map as the server drew it from
 * above, turned so the way you face is up, with the packs still to clear (red, yellow while a Rare
 * lives) and the boss arena (a skull, dim once the boss is dead). Only while the server says we
 * are in an Expedition ({@link ExpeditionMapState}).
 */
public final class ExpeditionMinimapHud extends HudElement {
    public static final ExpeditionMinimapHud INSTANCE = new ExpeditionMinimapHud();

    public static final String KEY_SIZE = "size";
    /** 100 = {@link #BASE_BLOCKS_PER_PX} blocks per screen pixel; higher is closer. */
    public static final String KEY_ZOOM = "zoom";
    private static final int DEFAULT_SIZE = 110;
    private static final float BASE_BLOCKS_PER_PX = 1.5f;
    private static final int INSET = 2;

    private static final int PACK = 0xFFE5483B;
    private static final int PACK_UNSPAWNED = 0xB0E5483B;
    private static final int PACK_RARE = 0xFFFFD84A;
    private static final int OUTLINE = 0xC0101010;
    private static final int BONE = 0xFFEDE4D0;
    private static final int BONE_DEAD = 0x90807A70;
    private static final int EYES_ALIVE = 0xFFFF3B2B;

    private static final String[] SKULL = {
            ".#####.",
            "#######",
            "#..#..#",
            "#######",
            ".##.##.",
            ".#####.",
            ".#.#.#.",
    };
    private static final String[] ARROW = {
            "...#...",
            "..###..",
            "..###..",
            ".#####.",
            ".#####.",
            "###.###",
            "##...##",
    };

    private ExpeditionMinimapHud() {}

    @Override public String id() { return "expedition_map"; }
    @Override public String displayName() { return "Expedition Map"; }
    @Override public boolean isVisible() {
        return FeatureToggles.isExpeditionMinimapEnabled() && ExpeditionMapState.layout() != null;
    }
    @Override public String editorPlaceholder() { return "Expedition Map"; }
    @Override public int width() { return size(); }
    @Override public int height() { return size(); }
    @Override public int defaultX(int screenWidth) { return screenWidth - size() - 6; }
    /** Below the first row of status effect icons, which also sit top right. */
    @Override public int defaultY(int screenHeight) { return 30; }

    public int size() { return Math.max(60, Math.min(240, HudSettings.getInt(id(), KEY_SIZE, DEFAULT_SIZE))); }

    /** World blocks per screen pixel at scale 1. */
    public float blocksPerPx() {
        int pct = Math.max(25, Math.min(400, HudSettings.getInt(id(), KEY_ZOOM, 100)));
        return BASE_BLOCKS_PER_PX * 100f / pct;
    }

    @Override public Screen openSettings(Screen parent) {
        return new WidgetSettingsScreen(parent, this) {
            @Override protected void addRows() {
                addToggle("Show this HUD", FeatureToggles::isExpeditionMinimapEnabled, FeatureToggles::setExpeditionMinimap);
                addSlider("Size (px)", 60, 240, ExpeditionMinimapHud.this::size,
                        v -> HudSettings.setInt(id(), KEY_SIZE, v));
                addSlider("Zoom %", 25, 400, () -> HudSettings.getInt(id(), KEY_ZOOM, 100),
                        v -> HudSettings.setInt(id(), KEY_ZOOM, v));
                addSlider("Background opacity %", 0, 100,
                        () -> HudSettings.getInt(id(), HudStyle.KEY_BG_OPACITY, HudStyle.DEFAULT_OPACITY),
                        v -> HudSettings.setInt(id(), HudStyle.KEY_BG_OPACITY, v));
            }
        };
    }

    @Override public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        int s = size();
        int op = HudStyle.bgOpacity(id());
        int r = Math.min(GlassRender.RADIUS, s / 2);
        GlassRender.roundedRect(ctx, 0, 0, s, s, r, GlassTheme.scaleAlpha(GlassTheme.hudTop(), op));
        GlassRender.roundedBorder(ctx, 0, 0, s, s, r, GlassTheme.scaleAlpha(GlassTheme.rim(), Math.max(op, 60)));

        MinecraftClient mc = MinecraftClient.getInstance();
        PlayerEntity player = mc == null ? null : mc.player;
        ExpeditionMapPayload layout = ExpeditionMapState.layout();
        if (player == null || layout == null) {
            String label = "Expedition Map";
            ctx.drawText(fr, label, (s - fr.getWidth(label)) / 2, (s - fr.fontHeight) / 2, GlassTheme.textMuted(), true);
            return;
        }

        Vec3d pos = player.getLerpedPos(tickDelta);
        double px = pos.x, pz = pos.z;
        float yaw = player.getYaw(tickDelta);
        double theta = Math.toRadians(180.0 - yaw);
        float cos = (float) Math.cos(theta), sin = (float) Math.sin(theta);
        float zoom = blocksPerPx();
        float cx = s / 2f, cy = s / 2f;

        ctx.enableScissor(INSET, INSET, s - INSET, s - INSET);
        try {
            // The picture: offset so the player's block sits at the origin, scaled to screen pixels,
            // turned so their facing points up, then moved to the middle of the square.
            ctx.createNewRootLayer();
            var m = ctx.getMatrices();
            m.pushMatrix();
            m.translate(cx, cy);
            m.rotate((float) theta);
            float k = layout.blocksPerPixel() / zoom;
            m.scale(k, k);
            m.translate((float) (-(px - layout.originX()) / layout.blocksPerPixel()),
                    (float) (-(pz - layout.originZ()) / layout.blocksPerPixel()));
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, ExpeditionMapState.TEXTURE, 0, 0, 0f, 0f,
                    layout.width(), layout.height(), layout.width(), layout.height());
            m.popMatrix();

            ctx.createNewRootLayer();
            ExpeditionMarkersPayload markers = ExpeditionMapState.markers();
            if (markers != null) {
                for (ExpeditionMarkersPayload.Pack p : markers.packs()) {
                    float[] at = toScreen(p.x() + 0.5, p.z() + 0.5, px, pz, zoom, cos, sin, cx, cy);
                    if (at[0] < 0 || at[1] < 0 || at[0] > s || at[1] > s) continue;
                    int color = p.rare() ? PACK_RARE : p.unspawned() ? PACK_UNSPAWNED : PACK;
                    int half = p.alive() >= 6 ? 2 : 1;
                    int x = Math.round(at[0]), y = Math.round(at[1]);
                    ctx.fill(x - half - 1, y - half - 1, x + half + 2, y + half + 2, OUTLINE);
                    ctx.fill(x - half, y - half, x + half + 1, y + half + 1, color);
                }
                float[] at = toScreen(markers.bossX() + 0.5, markers.bossZ() + 0.5, px, pz, zoom, cos, sin, cx, cy);
                // The boss arena stays on the rim as a heading when it is off the map.
                int bx = Math.round(Math.max(INSET + 5, Math.min(s - INSET - 5, at[0])));
                int by = Math.round(Math.max(INSET + 5, Math.min(s - INSET - 5, at[1])));
                drawSkull(ctx, bx - 3, by - 3, markers.bossState());
            }
            drawSprite(ctx, ARROW, Math.round(cx) - 3, Math.round(cy) - 3, 0xFFFFFFFF, OUTLINE);
        } finally {
            ctx.disableScissor();
        }
        // North on the rim, so a turning map still reads.
        int nx = Math.round(cx + sin * (s / 2f - 7)) - fr.getWidth("N") / 2 + 1;
        int ny = Math.round(cy - cos * (s / 2f - 7)) - fr.fontHeight / 2 + 1;
        ctx.drawText(fr, "N", nx, ny, GlassTheme.ACCENT_SOFT, true);
    }

    /** World (x, z) to a point in the square: relative to the player, scaled, turned like the picture. */
    static float[] toScreen(double wx, double wz, double px, double pz, float zoom, float cos, float sin, float cx, float cy) {
        float dx = (float) ((wx - px) / zoom), dz = (float) ((wz - pz) / zoom);
        return new float[]{cx + cos * dx - sin * dz, cy + sin * dx + cos * dz};
    }

    private static void drawSkull(DrawContext ctx, int x, int y, int bossState) {
        boolean dead = bossState == ExpeditionMarkersPayload.BOSS_DEAD;
        ctx.fill(x - 1, y - 1, x + 8, y + 8, dead ? 0x60101010 : OUTLINE);
        drawSprite(ctx, SKULL, x, y, dead ? BONE_DEAD : BONE, 0);
        if (bossState == ExpeditionMarkersPayload.BOSS_ALIVE) {
            ctx.fill(x + 1, y + 2, x + 3, y + 3, EYES_ALIVE);
            ctx.fill(x + 4, y + 2, x + 6, y + 3, EYES_ALIVE);
        }
    }

    /** A 7x7 pixel sprite ('#' filled), with an optional one-pixel outline. */
    private static void drawSprite(DrawContext ctx, String[] rows, int x, int y, int color, int outline) {
        if (outline != 0) {
            for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                for (int row = 0; row < rows.length; row++)
                    for (int col = 0; col < rows[row].length(); col++)
                        if (rows[row].charAt(col) == '#')
                            ctx.fill(x + col + d[0], y + row + d[1], x + col + d[0] + 1, y + row + d[1] + 1, outline);
            }
        }
        for (int row = 0; row < rows.length; row++) {
            int start = -1;
            String line = rows[row];
            for (int col = 0; col <= line.length(); col++) {
                boolean on = col < line.length() && line.charAt(col) == '#';
                if (on && start < 0) start = col;
                if (!on && start >= 0) {
                    ctx.fill(x + start, y + row, x + col, y + row + 1, color);
                    start = -1;
                }
            }
        }
    }
}
