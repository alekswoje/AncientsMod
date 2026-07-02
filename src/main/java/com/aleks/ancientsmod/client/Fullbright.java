package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.AncientsMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Client-side fullbright state. The gamma override mixin
 * ({@code mixin/client/MixinOptionInstanceGamma}) calls {@link #isActive()} per
 * gamma lookup, so this needs to be cheap and lock-free.
 *
 * <p>Blacklist is pushed by the server over the {@code prisonsmod:v1} channel
 * right after the handshake (see {@code AncientsModChannel.sendFullbrightBlacklist}
 * on the plugin side). Default = empty set = fullbright everywhere. Names are
 * Bukkit world names matched against the current client world's registry path.
 */
public final class Fullbright {

    private static volatile Set<String> blacklistedWorlds = Collections.emptySet();

    private Fullbright() {}

    /**
     * Replace the blacklist with a snapshot from the server. Defensive copy so
     * the caller can't mutate after pushing.
     */
    public static void setBlacklist(Set<String> worlds) {
        if (worlds == null || worlds.isEmpty()) {
            blacklistedWorlds = Collections.emptySet();
            return;
        }
        blacklistedWorlds = Collections.unmodifiableSet(new HashSet<>(worlds));
    }

    /** Server cleared the blacklist (or we disconnected). */
    public static void clear() {
        blacklistedWorlds = Collections.emptySet();
    }

    /**
     * True when fullbright should currently apply: the feature toggle is on,
     * the player is in-world, and the current world isn't blacklisted.
     * Called on every gamma getter invocation — keep it allocation-free.
     */
    public static boolean isActive() {
        if (!FeatureToggles.isFullbrightEnabled()) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return false;
        Set<String> bl = blacklistedWorlds;
        if (bl.isEmpty()) return true;
        ClientWorld world = mc.world;
        if (world == null) return true;
        RegistryKey<World> key = world.getRegistryKey();
        if (key == null) return true;
        // Bukkit world names map to the dimension's registry path
        // (e.g. "world", "tartarus_rift", "forgotten_polis"). Compare on path
        // only — namespace is always "minecraft" for custom dimensions
        // registered by the server, so it doesn't disambiguate.
        return !bl.contains(key.getValue().getPath());
    }

    /** Logging hook for the network handler — confirms what arrived from the server. */
    public static void logReceived(Set<String> worlds) {
        if (worlds.isEmpty()) {
            AncientsMod.LOGGER.info("[fullbright] blacklist cleared by server");
        } else {
            AncientsMod.LOGGER.info("[fullbright] blacklist from server ({} worlds): {}",
                    worlds.size(), worlds);
        }
    }
}
