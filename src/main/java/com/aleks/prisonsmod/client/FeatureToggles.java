package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.PrisonsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side feature toggles. Persists to {@code config/prisonsmod.properties}
 * so A/B tests survive restarts. Each toggle has a sane default so fresh
 * installs behave predictably.
 *
 * <p>Toggles here only gate rendering/display — they never affect what the
 * server emits or does. Turning everything off = mod is effectively invisible.
 */
public final class FeatureToggles {

    private static final String FILE_NAME = "prisonsmod.properties";

    // ── Toggles (defaults below) ─────────────────────────────────────────────

    /** Client-side break-crack prediction. When off, the real server packets drive the crack animation (lag returns, for A/B comparison). */
    private static volatile boolean minePredict = true;

    /** Collapse marked enchant tooltip lines behind Shift. Off by default — full enchant list always visible. */
    private static volatile boolean enchantCollapse = false;

    /** Scroll oversized tooltips with the mouse wheel. Off = vanilla bottom-pin (top spills off-screen). */
    private static volatile boolean scrollableTooltips = true;

    // ─────────────────────────────────────────────────────────────────────────

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            save(); // write defaults so the user can hand-edit
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
            minePredict = parseBool(props.getProperty("minePredict"), minePredict);
            enchantCollapse = parseBool(props.getProperty("enchantCollapse"), enchantCollapse);
            scrollableTooltips = parseBool(props.getProperty("scrollableTooltips"), scrollableTooltips);
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to load {}: {}", FILE_NAME, e.getMessage());
        }
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("minePredict", Boolean.toString(minePredict));
        props.setProperty("enchantCollapse", Boolean.toString(enchantCollapse));
        props.setProperty("scrollableTooltips", Boolean.toString(scrollableTooltips));
        try {
            Files.createDirectories(configPath().getParent());
            try (var out = Files.newOutputStream(configPath())) {
                props.store(out, "PrisonsMod client feature toggles");
            }
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to save {}: {}", FILE_NAME, e.getMessage());
        }
    }

    public static boolean isMinePredictEnabled() { return minePredict; }

    public static boolean toggleMinePredict() {
        minePredict = !minePredict;
        save();
        return minePredict;
    }

    public static void setMinePredict(boolean value) {
        if (minePredict == value) return;
        minePredict = value;
        save();
    }

    public static boolean isEnchantCollapseEnabled() { return enchantCollapse; }

    public static boolean toggleEnchantCollapse() {
        enchantCollapse = !enchantCollapse;
        save();
        return enchantCollapse;
    }

    public static void setEnchantCollapse(boolean value) {
        if (enchantCollapse == value) return;
        enchantCollapse = value;
        save();
    }

    public static boolean isScrollableTooltipsEnabled() { return scrollableTooltips; }

    public static boolean toggleScrollableTooltips() {
        scrollableTooltips = !scrollableTooltips;
        save();
        return scrollableTooltips;
    }

    public static void setScrollableTooltips(boolean value) {
        if (scrollableTooltips == value) return;
        scrollableTooltips = value;
        save();
    }

    private static boolean parseBool(String s, boolean fallback) {
        if (s == null) return fallback;
        s = s.trim().toLowerCase();
        if (s.equals("true") || s.equals("yes") || s.equals("on") || s.equals("1")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("off") || s.equals("0")) return false;
        return fallback;
    }

    private FeatureToggles() {}
}
