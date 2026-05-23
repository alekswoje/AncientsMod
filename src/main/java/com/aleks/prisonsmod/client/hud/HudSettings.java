package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.PrisonsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-widget settings persistence — separate from {@link HudPositions} so the
 * position file stays compact and the settings file can grow as new widgets
 * and toggles are added without invalidating each other.
 *
 * <p>All values are stored as strings under {@code {widgetId}.{key}} so the
 * properties file is human-readable. Typed accessors handle the
 * boolean / int / string-set serialization.
 *
 * <p>Example file ({@code config/prisonsmod-hud-settings.properties}):
 * <pre>
 *   boosters.collapse=true
 *   events.enabled=koth,bah,meteor,rift
 *   cooldowns.categories=commands,combat,enchant
 * </pre>
 */
public final class HudSettings {

    private static final String FILE_NAME = "prisonsmod-hud-settings.properties";

    /** Backing store; keys are exactly the on-disk format ({widgetId}.{key}). */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private static volatile boolean loaded = false;

    private HudSettings() {}

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
            CACHE.put(key, props.getProperty(key));
        }
    }

    public static synchronized void save() {
        Properties props = new Properties();
        for (Map.Entry<String, String> e : CACHE.entrySet()) {
            props.setProperty(e.getKey(), e.getValue());
        }
        try {
            Files.createDirectories(configPath().getParent());
            try (var out = Files.newOutputStream(configPath())) {
                props.store(out, "PrisonsMod HUD widget settings");
            }
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to save {}: {}", FILE_NAME, e.getMessage());
        }
    }

    private static String fullKey(String widgetId, String key) {
        return widgetId + "." + key;
    }

    // ── Boolean ──────────────────────────────────────────────────────────────

    public static boolean getBoolean(String widgetId, String key, boolean def) {
        String v = CACHE.get(fullKey(widgetId, key));
        if (v == null) return def;
        return Boolean.parseBoolean(v.trim());
    }

    public static void setBoolean(String widgetId, String key, boolean value) {
        CACHE.put(fullKey(widgetId, key), Boolean.toString(value));
        save();
    }

    // ── Int ──────────────────────────────────────────────────────────────────

    public static int getInt(String widgetId, String key, int def) {
        String v = CACHE.get(fullKey(widgetId, key));
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public static void setInt(String widgetId, String key, int value) {
        CACHE.put(fullKey(widgetId, key), Integer.toString(value));
        save();
    }

    // ── String set (csv on disk) ─────────────────────────────────────────────

    /**
     * Read a comma-separated string set. Empty string and missing key both
     * return {@code def} so a brand-new install picks up sensible defaults.
     */
    public static Set<String> getStringSet(String widgetId, String key, Set<String> def) {
        String v = CACHE.get(fullKey(widgetId, key));
        if (v == null) return def;
        String trimmed = v.trim();
        if (trimmed.isEmpty()) return def;
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static void setStringSet(String widgetId, String key, Set<String> value) {
        String joined = value == null ? "" : String.join(",", value);
        CACHE.put(fullKey(widgetId, key), joined);
        save();
    }

    /** Atomically toggle one value's membership in a string set. */
    public static void toggleSetMember(String widgetId, String key, String member, Set<String> def) {
        Set<String> current = new LinkedHashSet<>(getStringSet(widgetId, key, def));
        if (!current.remove(member)) current.add(member);
        setStringSet(widgetId, key, current);
    }
}
