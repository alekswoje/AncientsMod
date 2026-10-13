package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.net.payload.ExpeditionMapPayload;
import com.aleks.ancientsmod.net.payload.ExpeditionMarkersPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/**
 * Expedition minimap, Path of Exile map-overlay style: a round map of the whole Expedition as the
 * server drew it from above, turned so the way you face is up, with the packs still to clear (red,
 * yellow while a Rare lives, hollow where nobody has been yet) and the boss arena (a skull, dim once
 * the boss is dead, pinned to the ring while off the map). Only while the server says we are in an
 * Expedition ({@link ExpeditionMapState}).
 *
 * <p>Drawn on the CPU into one small texture at the screen's real pixel density (GUI scale times the
 * HUD editor scale), then blitted as an ordinary unrotated quad. Nothing is rotated on the GPU and
 * nothing relies on scissoring, so the map can never draw outside its circle, at any HUD position
 * or scale. The texture is redrawn only when the player moves or turns, or the markers change.
 */
public final class ExpeditionMinimapHud extends HudElement {
    public static final ExpeditionMinimapHud INSTANCE = new ExpeditionMinimapHud();

    public static final String KEY_SIZE = "size";
    /** 100 = {@link #BASE_BLOCKS_PER_PX} blocks per GUI pixel; higher is closer. */
    public static final String KEY_ZOOM = "zoom";
    private static final int DEFAULT_SIZE = 110;
    private static final float BASE_BLOCKS_PER_PX = 1.5f;
    /** Texture side cap in real pixels (a 240 px HUD at GUI scale 4 would otherwise be 960). */
    private static final int MAX_RES = 384;
    private static final Identifier TEXTURE = Identifier.of("ancientsmod", "dynamic/expedition_map");

    private static final int BG = 0xFF12100E;
    private static final int RIM = GlassTheme.BRONZE;
    private static final int DARK = 0xFF0B0908;
    private static final int PACK = 0xFFE5483B;
    private static final int PACK_RARE = 0xFFFFD84A;
    private static final int BONE = 0xFFEDE4D0;
    private static final int EYES_ALIVE = 0xFFFF3B2B;

    private NativeImageBackedTexture texture;
    private int textureRes;
    private Raster raster;
    /** What the texture last showed; redraw when any of it changes. */
    private double lastX = Double.NaN, lastZ, lastYaw;
    private Object lastMarkers, lastLayout;
    private int lastSettings;

    private ExpeditionMinimapHud() {}

    @Override public String id() { return "expedition_map"; }
    @Override public String displayName() { return "Expedition Map"; }
    @Override public boolean isVisible() {
        return FeatureToggles.isExpeditionMinimapEnabled() && ExpeditionMapState.layout() != null;
    }
    @Override public String editorPlaceholder() { return "Expedition Map"; }
    @Override public int width() { return size(); }
    @Override public int height() { return size(); }
    @Override public int defaultX(int screenWidth) { return screenWidth - size() - 5; }
    @Override public int defaultY(int screenHeight) { return 5; }

    public int size() { return Math.max(60, Math.min(240, HudSettings.getInt(id(), KEY_SIZE, DEFAULT_SIZE))); }

    /** World blocks per GUI pixel at HUD scale 1. */
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

    /** The picture changed: redraw on the next frame. */
    void invalidate() { lastX = Double.NaN; }

    /** Frees the GPU texture (left the Expedition, disconnected). */
    void release() {
        invalidate();
        if (texture == null) return;
        texture = null;
        textureRes = 0;
        raster = null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.getTextureManager().destroyTexture(TEXTURE);
    }

    @Override public void render(DrawContext ctx, TextRenderer fr, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        int s = size();
        float pxPerGui = (float) mc.getWindow().getScaleFactor() * HudPositions.getScale(this);
        int res = Math.max(32, Math.min(MAX_RES, Math.round(s * Math.max(1f, pxPerGui))));
        float k = res / (float) s;

        PlayerEntity player = mc.player;
        ExpeditionMapPayload layout = ExpeditionMapState.layout();
        ExpeditionMarkersPayload markers = ExpeditionMapState.markers();
        double px = 0, pz = 0, yaw = 180;
        if (player != null) {
            Vec3d pos = player.getLerpedPos(tickDelta);
            px = pos.x;
            pz = pos.z;
            yaw = player.getYaw(tickDelta);
        }
        int settings = s * 31 + HudSettings.getInt(id(), KEY_ZOOM, 100) * 7 + HudStyle.bgOpacity(id());
        if (!ensureTexture(mc, res)) return;
        boolean moved = Double.isNaN(lastX) || Math.abs(px - lastX) > 0.01 || Math.abs(pz - lastZ) > 0.01
                || Math.abs(yaw - lastYaw) > 0.05;
        if (moved || markers != lastMarkers || layout != lastLayout || settings != lastSettings) {
            lastX = px; lastZ = pz; lastYaw = yaw;
            lastMarkers = markers; lastLayout = layout; lastSettings = settings;
            paint(layout, markers, px, pz, yaw, s, k);
            upload();
        }
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0f, 0f, s, s, res, res, res, res);

        if (layout == null) {
            String label = "Expedition Map";
            ctx.drawText(fr, label, (s - fr.getWidth(label)) / 2, (s - fr.fontHeight) / 2, GlassTheme.textMuted(), true);
            return;
        }
        // North on the ring, turning with the map.
        double theta = Math.toRadians(180.0 - yaw);
        float rN = s / 2f - 5.5f;
        float nx = s / 2f + (float) Math.sin(theta) * rN, ny = s / 2f - (float) Math.cos(theta) * rN;
        ctx.drawText(fr, "N", Math.round(nx - fr.getWidth("N") / 2f), Math.round(ny - 3.5f), GlassTheme.ACCENT_SOFT, false);
    }

    private boolean ensureTexture(MinecraftClient mc, int res) {
        if (texture != null && textureRes == res) return true;
        release();
        try {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, res, res, false);
            texture = new NativeImageBackedTexture(() -> "ancientsmod expedition map", image);
            mc.getTextureManager().registerTexture(TEXTURE, texture);
            textureRes = res;
            raster = new Raster(res);
            return true;
        } catch (Throwable t) {
            texture = null;
            AncientsMod.LOGGER.warn("[ExpeditionMap] texture create failed: {}", t.toString());
            return false;
        }
    }

    private void upload() {
        NativeImage image = texture.getImage();
        if (image == null) return;
        for (int y = 0; y < raster.n; y++)
            for (int x = 0; x < raster.n; x++) image.setColorArgb(x, y, raster.argb(y * raster.n + x));
        texture.upload();
    }

    /** Draws the whole minimap into {@link #raster}. Units: {@code s} GUI px across, {@code k} real px per GUI px. */
    private void paint(ExpeditionMapPayload layout, ExpeditionMarkersPayload markers,
                       double px, double pz, double yaw, int s, float k) {
        Raster r = raster;
        r.clear();
        int n = r.n;
        float c = n / 2f;                 // centre, real px
        float rad = c - 0.5f;             // circle radius, real px
        float zoom = blocksPerPx() / k;   // blocks per real px
        double theta = Math.toRadians(180.0 - yaw);
        float cos = (float) Math.cos(theta), sin = (float) Math.sin(theta);
        float bgAlpha = 0.55f + 0.4f * HudStyle.bgOpacity(id()) / 100f;

        // Background, the map (bilinear), vignette.
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float dx = i + 0.5f - c, dy = j + 0.5f - c;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > rad + 1) continue;
                int idx = j * n + i;
                r.blend(idx, BG, bgAlpha);
                if (layout != null) {
                    double wx = px + zoom * (cos * dx + sin * dy);
                    double wz = pz + zoom * (-sin * dx + cos * dy);
                    r.blendPremul(idx, sample(layout, wx, wz), 0.94f);
                }
                float edge = smooth(0.62f, 1.0f, dist / rad);
                r.darken(idx, edge * 0.55f);
            }
        }

        if (layout != null && markers != null) {
            for (ExpeditionMarkersPayload.Pack p : markers.packs()) {
                float[] at = toLocal(p.x() + 0.5, p.z() + 0.5, px, pz, zoom, cos, sin);
                if (Math.hypot(at[0], at[1]) > rad - 3 * k) continue;
                float x = c + at[0], y = c + at[1];
                int color = p.rare() ? PACK_RARE : PACK;
                if (p.unspawned()) {
                    r.ring(x, y, 1.5f * k, 2.0f * k, DARK, 0.65f);
                    r.ring(x, y, 1.5f * k, 0.9f * k, color, 0.7f);
                } else {
                    r.disc(x, y, 2.6f * k, DARK, 0.85f);
                    r.disc(x, y, 1.7f * k, color, 1f);
                }
            }
            float[] at = toLocal(markers.bossX() + 0.5, markers.bossZ() + 0.5, px, pz, zoom, cos, sin);
            float d = (float) Math.hypot(at[0], at[1]);
            float pin = rad - 5.5f * k;
            if (d > pin) { at[0] *= pin / d; at[1] *= pin / d; }
            skull(r, c + at[0], c + at[1], k, markers.bossState());
        }
        if (layout != null) arrow(r, c, c, k);

        // Ring.
        r.ring(c, c, rad - 2.2f * k, 0.8f * k, DARK, 0.45f);
        r.ring(c, c, rad - 0.9f * k, 1.6f * k, RIM, 0.85f);
        // North marker backing on the ring.
        if (layout != null) {
            float rN = c - 5.5f * k;
            r.disc(c + sin * rN, c - cos * rN, 4.4f * k, DARK, 0.8f);
            r.ring(c + sin * rN, c - cos * rN, 4.4f * k, 0.8f * k, RIM, 0.7f);
        }
        r.mask(c, c, rad);
    }

    /** World point relative to the player, in real px, turned like the map. */
    private static float[] toLocal(double wx, double wz, double px, double pz, float zoom, float cos, float sin) {
        float dx = (float) ((wx - px) / zoom), dz = (float) ((wz - pz) / zoom);
        return new float[]{cos * dx - sin * dz, sin * dx + cos * dz};
    }

    /** Premultiplied ARGB of the picture at world (wx, wz), bilinear; 0 outside it or over void. */
    private static int sample(ExpeditionMapPayload l, double wx, double wz) {
        double fu = (wx - l.originX()) / l.blocksPerPixel() - 0.5, fv = (wz - l.originZ()) / l.blocksPerPixel() - 0.5;
        int u0 = (int) Math.floor(fu), v0 = (int) Math.floor(fv);
        float tu = (float) (fu - u0), tv = (float) (fv - v0);
        float a = 0, rr = 0, g = 0, b = 0;
        for (int q = 0; q < 4; q++) {
            int u = u0 + (q & 1), v = v0 + (q >> 1);
            if (u < 0 || v < 0 || u >= l.width() || v >= l.height()) continue;
            int argb = l.argb()[v * l.width() + u];
            float w = ((q & 1) == 0 ? 1 - tu : tu) * ((q >> 1) == 0 ? 1 - tv : tv);
            float ca = (argb >>> 24) / 255f * w;
            a += ca;
            rr += ((argb >> 16) & 0xFF) * ca;
            g += ((argb >> 8) & 0xFF) * ca;
            b += (argb & 0xFF) * ca;
        }
        if (a <= 0.001f) return 0;
        return ((int) (Math.min(1, a) * 255) << 24) | ((int) Math.min(255, rr) << 16) | ((int) Math.min(255, g) << 8) | (int) Math.min(255, b);
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** White arrow pointing up (your facing), dark outline. */
    private static void arrow(Raster r, float cx, float cy, float k) {
        float[][] right = {{0, -4.2f}, {3.2f, 3.6f}, {0, 1.6f}};
        float[][] left = {{0, -4.2f}, {0, 1.6f}, {-3.2f, 3.6f}};
        r.polys(cx, cy, k, new float[][][]{right, left}, 1.0f, DARK, 0.9f);
        r.polys(cx, cy, k, new float[][][]{right, left}, 0f, 0xFFFFFFFF, 1f);
    }

    /** A small skull: dome, jaw, two eyes (red while the boss lives), a tooth gap. Dim once dead. */
    private static void skull(Raster r, float cx, float cy, float k, int state) {
        boolean dead = state == ExpeditionMarkersPayload.BOSS_DEAD;
        float a = dead ? 0.5f : 1f;
        int bone = dead ? 0xFF8A847A : BONE;
        float[][] jaw = {{-1.6f, 0.4f}, {1.6f, 0.4f}, {1.6f, 2.6f}, {-1.6f, 2.6f}};
        r.disc(cx, cy - 0.6f * k, 3.6f * k, DARK, 0.9f * a);
        r.polys(cx, cy, k, new float[][][]{jaw}, 1.0f, DARK, 0.9f * a);
        r.disc(cx, cy - 0.6f * k, 2.6f * k, bone, a);
        r.polys(cx, cy, k, new float[][][]{jaw}, 0f, bone, a);
        int eyes = state == ExpeditionMarkersPayload.BOSS_ALIVE ? EYES_ALIVE : DARK;
        r.disc(cx - 1.05f * k, cy - 0.5f * k, 0.8f * k, eyes, a);
        r.disc(cx + 1.05f * k, cy - 0.5f * k, 0.8f * k, eyes, a);
        float[][] gap = {{-0.2f, 1.5f}, {0.2f, 1.5f}, {0.2f, 2.6f}, {-0.2f, 2.6f}};
        r.polys(cx, cy, k, new float[][][]{gap}, 0f, DARK, 0.8f * a);
    }

    /** A small premultiplied RGBA canvas with anti-aliased discs, rings and polygons. */
    static final class Raster {
        final int n;
        final float[] r, g, b, a;

        Raster(int n) {
            this.n = n;
            r = new float[n * n]; g = new float[n * n]; b = new float[n * n]; a = new float[n * n];
        }

        void clear() {
            java.util.Arrays.fill(r, 0); java.util.Arrays.fill(g, 0);
            java.util.Arrays.fill(b, 0); java.util.Arrays.fill(a, 0);
        }

        /** Straight ARGB {@code color} over pixel {@code i} at coverage {@code cov}. */
        void blend(int i, int color, float cov) {
            float sa = ((color >>> 24) / 255f) * cov;
            if (sa <= 0) return;
            float k = 1 - sa;
            r[i] = ((color >> 16) & 0xFF) / 255f * sa + r[i] * k;
            g[i] = ((color >> 8) & 0xFF) / 255f * sa + g[i] * k;
            b[i] = (color & 0xFF) / 255f * sa + b[i] * k;
            a[i] = sa + a[i] * k;
        }

        /** Premultiplied ARGB over pixel {@code i}, scaled by {@code f}. */
        void blendPremul(int i, int color, float f) {
            float sa = (color >>> 24) / 255f * f;
            if (sa <= 0) return;
            float k = 1 - sa;
            r[i] = ((color >> 16) & 0xFF) / 255f * f + r[i] * k;
            g[i] = ((color >> 8) & 0xFF) / 255f * f + g[i] * k;
            b[i] = (color & 0xFF) / 255f * f + b[i] * k;
            a[i] = sa + a[i] * k;
        }

        void darken(int i, float f) {
            float k = 1 - f;
            r[i] *= k; g[i] *= k; b[i] *= k;
        }

        void disc(float cx, float cy, float rad, int color, float alpha) {
            int x0 = Math.max(0, (int) (cx - rad - 1)), x1 = Math.min(n - 1, (int) (cx + rad + 1));
            int y0 = Math.max(0, (int) (cy - rad - 1)), y1 = Math.min(n - 1, (int) (cy + rad + 1));
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float cov = clamp(rad + 0.5f - (float) Math.sqrt(dx * dx + dy * dy));
                if (cov > 0) blend(y * n + x, color, cov * alpha);
            }
        }

        void ring(float cx, float cy, float rad, float width, int color, float alpha) {
            float out = rad + width / 2 + 1;
            int x0 = Math.max(0, (int) (cx - out)), x1 = Math.min(n - 1, (int) (cx + out));
            int y0 = Math.max(0, (int) (cy - out)), y1 = Math.min(n - 1, (int) (cy + out));
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float cov = clamp(width / 2 + 0.5f - Math.abs((float) Math.sqrt(dx * dx + dy * dy) - rad));
                if (cov > 0) blend(y * n + x, color, cov * alpha);
            }
        }

        /**
         * The union of convex polygons (points in GUI px around (cx, cy), scaled by {@code k}),
         * grown by {@code grow} GUI px, 4x4 supersampled.
         */
        void polys(float cx, float cy, float k, float[][][] shapes, float grow, int color, float alpha) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[][] sh : shapes) for (float[] p : sh) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            int x0 = Math.max(0, (int) (cx + (minX - grow) * k - 1)), x1 = Math.min(n - 1, (int) (cx + (maxX + grow) * k + 1));
            int y0 = Math.max(0, (int) (cy + (minY - grow) * k - 1)), y1 = Math.min(n - 1, (int) (cy + (maxY + grow) * k + 1));
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                int hits = 0;
                for (int sy = 0; sy < 4; sy++) for (int sx = 0; sx < 4; sx++) {
                    float gx = (x + (sx + 0.5f) / 4 - cx) / k, gy = (y + (sy + 0.5f) / 4 - cy) / k;
                    for (float[][] sh : shapes) if (inside(sh, gx, gy, grow)) { hits++; break; }
                }
                if (hits > 0) blend(y * n + x, color, hits / 16f * alpha);
            }
        }

        /** True when (x, y) lies within {@code grow} of the convex polygon {@code p}. */
        private static boolean inside(float[][] p, float x, float y, float grow) {
            float area = 0;
            for (int i = 0; i < p.length; i++) {
                float[] u = p[i], v = p[(i + 1) % p.length];
                area += u[0] * v[1] - v[0] * u[1];
            }
            float sign = area >= 0 ? 1 : -1;
            for (int i = 0; i < p.length; i++) {
                float[] u = p[i], v = p[(i + 1) % p.length];
                float ex = v[0] - u[0], ey = v[1] - u[1];
                float len = (float) Math.sqrt(ex * ex + ey * ey);
                if (len == 0) continue;
                float d = sign * (ex * (y - u[1]) - ey * (x - u[0])) / len;
                if (d < -grow) return false;
            }
            return true;
        }

        /** Keeps only the circle of radius {@code rad}, anti-aliased. */
        void mask(float cx, float cy, float rad) {
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                float cov = clamp(rad + 0.5f - (float) Math.sqrt(dx * dx + dy * dy));
                if (cov >= 1) continue;
                int i = y * n + x;
                r[i] *= cov; g[i] *= cov; b[i] *= cov; a[i] *= cov;
            }
        }

        /** Straight (non-premultiplied) ARGB of pixel {@code i}, for NativeImage. */
        int argb(int i) {
            float al = a[i];
            if (al <= 0.002f) return 0;
            int A = Math.round(Math.min(1, al) * 255);
            int R = Math.round(Math.min(1, r[i] / al) * 255), G = Math.round(Math.min(1, g[i] / al) * 255),
                    B = Math.round(Math.min(1, b[i] / al) * 255);
            return (A << 24) | (R << 16) | (G << 8) | B;
        }

        private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    }
}
