package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.PrisonsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence layer for HUD element positions. Kept in its own properties file
 * ({@code config/prisonsmod-hud.properties}) instead of mixed into the main
 * feature toggles — positions are a different concern than on/off toggles, and
 * they grow as new widgets are added without diluting the toggle list.
 *
 * <p>Per element three keys are stored:
 * <pre>
 *   {id}.x      = int (top-left x in pixels)
 *   {id}.y      = int (top-left y in pixels)
 *   {id}.scale  = float (1.0 = native size)
 * </pre>
 *
 * <p>Positions are stored as absolute pixels rather than percent-of-screen
 * because the editor lets the player drag to an exact pixel and we don't want
 * a window resize to nudge them off by a half-pixel. On a screen-size change
 * we just clamp to keep elements visible (see {@link #getX}).
 */
public final class HudPositions {

    private static final String FILE_NAME = "prisonsmod-hud.properties";

    /** Cache so we don't hit disk every frame. */
    private static final Map<String, Integer> X_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> Y_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Float> SCALE_CACHE = new ConcurrentHashMap<>();

    private static volatile boolean loaded = false;

    private HudPositions() {}

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path path = configPath();
        if (!Files.isRegularFile(path)) return;
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to load {}: {}", FILE_NAME, e.getMessage());
            return;
        }
        for (String key : props.stringPropertyNames()) {
            int dot = key.lastIndexOf('.');
            if (dot < 0) continue;
            String id = key.substring(0, dot);
            String field = key.substring(dot + 1);
            String value = props.getProperty(key);
            try {
                switch (field) {
                    case "x" -> X_CACHE.put(id, Integer.parseInt(value));
                    case "y" -> Y_CACHE.put(id, Integer.parseInt(value));
                    case "scale" -> SCALE_CACHE.put(id, Float.parseFloat(value));
                }
            } catch (NumberFormatException ignored) {
                // Corrupt entry — fall back to default.
            }
        }
    }

    public static synchronized void save() {
        Properties props = new Properties();
        for (Map.Entry<String, Integer> e : X_CACHE.entrySet()) {
            props.setProperty(e.getKey() + ".x", Integer.toString(e.getValue()));
        }
        for (Map.Entry<String, Integer> e : Y_CACHE.entrySet()) {
            props.setProperty(e.getKey() + ".y", Integer.toString(e.getValue()));
        }
        for (Map.Entry<String, Float> e : SCALE_CACHE.entrySet()) {
            props.setProperty(e.getKey() + ".scale", Float.toString(e.getValue()));
        }
        try {
            Files.createDirectories(configPath().getParent());
            try (var out = Files.newOutputStream(configPath())) {
                props.store(out, "PrisonsMod HUD element positions");
            }
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to save {}: {}", FILE_NAME, e.getMessage());
        }
    }

    /** Stored x, or the element's default. Clamped so widget stays at least partially on screen. */
    public static int getX(HudElement el, int screenWidth) {
        Integer stored = X_CACHE.get(el.id());
        int raw = stored != null ? stored : el.defaultX(screenWidth);
        return clamp(raw, screenWidth, el.width(), getScale(el));
    }

    /** Stored y, or the element's default. */
    public static int getY(HudElement el, int screenHeight) {
        Integer stored = Y_CACHE.get(el.id());
        int raw = stored != null ? stored : el.defaultY(screenHeight);
        return clamp(raw, screenHeight, el.height(), getScale(el));
    }

    /** Stored scale, or 1.0. Clamped 0.5..2.0 to keep widgets readable. */
    public static float getScale(HudElement el) {
        Float stored = SCALE_CACHE.get(el.id());
        if (stored == null) return 1.0f;
        return Math.max(0.5f, Math.min(2.0f, stored));
    }

    public static void setX(HudElement el, int x) {
        X_CACHE.put(el.id(), x);
    }

    public static void setY(HudElement el, int y) {
        Y_CACHE.put(el.id(), y);
    }

    public static void setScale(HudElement el, float scale) {
        SCALE_CACHE.put(el.id(), Math.max(0.5f, Math.min(2.0f, scale)));
    }

    /** Reset one element back to defaults (writes nothing to disk until {@link #save} is called). */
    public static void reset(HudElement el) {
        X_CACHE.remove(el.id());
        Y_CACHE.remove(el.id());
        SCALE_CACHE.remove(el.id());
    }

    /** Resets every element back to defaults. */
    public static void resetAll() {
        X_CACHE.clear();
        Y_CACHE.clear();
        SCALE_CACHE.clear();
    }

    /** Snapshot for diagnostics. */
    public static Map<String, int[]> snapshot() {
        Map<String, int[]> out = new HashMap<>();
        for (String id : X_CACHE.keySet()) {
            out.put(id, new int[] { X_CACHE.getOrDefault(id, 0), Y_CACHE.getOrDefault(id, 0) });
        }
        return out;
    }

    private static int clamp(int value, int screenSize, int widgetSize, float scale) {
        int scaled = Math.max(1, Math.round(widgetSize * scale));
        // Keep at least 8 px visible on the trailing edge so a half-off-screen
        // widget after a resize can still be grabbed and dragged back.
        int min = -(scaled - 8);
        int max = screenSize - 8;
        return Math.max(min, Math.min(max, value));
    }
}
