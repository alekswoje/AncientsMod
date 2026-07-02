package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.AncientsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a config-file path, transparently migrating the pre-rename
 * PrisonsMod file on first use. The mod was renamed PrisonsMod → AncientsMod
 * (2026-07); every persisted file kept its format but changed its
 * {@code prisonsmod*} name prefix to {@code ancientsmod*}. If the new file
 * doesn't exist yet and the legacy one does, the legacy file is copied (not
 * moved, so rolling back to an older jar keeps its settings) and the new path
 * is returned.
 */
public final class ConfigPaths {

    /** Maps e.g. {@code ancientsmod-hud.properties} → {@code prisonsmod-hud.properties}. */
    private static String legacyName(String fileName) {
        return fileName.replace("ancientsmod", "prisonsmod");
    }

    public static Path resolve(String fileName) {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path path = dir.resolve(fileName);
        if (Files.notExists(path)) {
            Path legacy = dir.resolve(legacyName(fileName));
            if (Files.exists(legacy)) {
                try {
                    Files.copy(legacy, path);
                    AncientsMod.LOGGER.info("Migrated legacy config {} -> {}",
                            legacy.getFileName(), path.getFileName());
                } catch (IOException e) {
                    AncientsMod.LOGGER.warn("Could not migrate legacy config {}: {}",
                            legacy.getFileName(), e.toString());
                    return legacy; // keep reading/writing the old file rather than losing settings
                }
            }
        }
        return path;
    }

    private ConfigPaths() {}
}
