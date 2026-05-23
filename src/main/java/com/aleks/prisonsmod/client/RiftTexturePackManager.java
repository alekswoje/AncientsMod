package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.PrisonsMod;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Manages the bundled "Ancients Rift Pack" — a small (~16 KB) resource pack
 * that tints stone and the seven vanilla ores white, so the four rift special
 * blocks (deepslate_gold_ore / crying_obsidian / gilded_blackstone /
 * amethyst_block) keep their natural look and pop visually by contrast.
 *
 * <p><b>Scope</b> is the rift world only. Activation is driven from the
 * world-change tick in {@link PrisonsModClient}: enter {@code tartarus_rift} +
 * toggle on → pack enabled + {@link MinecraftClient#reloadResources()}. Leave
 * (or toggle off mid-rift) → pack disabled + reload. The reload is a 5-15 s
 * stutter; that's the accepted trade-off for keeping the recoloured ores
 * scoped to the rift instead of bleeding across the whole Ancients server.
 *
 * <p>Pack files live in the mod jar under
 * {@code resourcepacks/ancients_rift_pack/}.
 */
public final class RiftTexturePackManager {

    /** Mod-namespaced ID — Fabric resolves jar path {@code resourcepacks/ancients_rift_pack/}. */
    public static final Identifier PACK_ID = Identifier.of(PrisonsMod.MOD_ID, "ancients_rift_pack");

    /** Server dimension key for the rift; matches PrisonsCore's {@code tartarus_rift} world. */
    public static final String RIFT_WORLD_NAME = "tartarus_rift";

    private static volatile boolean registered = false;
    private static volatile boolean wantActive = false;
    private static volatile boolean currentlyActive = false;
    /**
     * Set when the player joins the rift queue (chat-message trigger). Lets us
     * fold the pack reload into the queue wait (typically 60s of idle countdown)
     * instead of stalling the player the moment the rift starts. Cleared when
     * the player actually enters the rift world (we're "in"), explicitly leaves
     * the queue, or the queue gets cancelled for lack of players.
     */
    private static volatile boolean queuedForRift = false;
    /** Resolved at runtime — Fabric prefixes built-in pack names (e.g. {@code fabric/prisonsmod/ancients_rift_pack}); we look it up by suffix. */
    private static volatile String cachedPackName = null;

    /** Register the built-in pack with Fabric so it's visible to MC's resource manager. Call once during client init. */
    public static void register() {
        if (registered) return;
        FabricLoader.getInstance().getModContainer(PrisonsMod.MOD_ID).ifPresent(container -> {
            boolean ok = ResourceManagerHelper.registerBuiltinResourcePack(
                    PACK_ID, container,
                    Text.literal("Ancients Rift Pack"),
                    ResourcePackActivationType.NORMAL);
            if (ok) {
                registered = true;
                PrisonsMod.LOGGER.info("Registered built-in resource pack: {}", PACK_ID);
            } else {
                PrisonsMod.LOGGER.warn("Failed to register built-in resource pack: {}", PACK_ID);
            }
        });
    }

    /**
     * Re-evaluate target state from the current world + toggle. Cheap on every
     * tick — only triggers a reload when the target actually changes.
     *
     * <p>Once {@code inRift} flips true, {@link #queuedForRift} is cleared:
     * we're now in the rift the queue was waiting for, and the world transition
     * is the new "should be on" reason.
     */
    public static void update(boolean inRift, boolean toggleOn) {
        if (inRift) queuedForRift = false;
        boolean should = toggleOn && (inRift || queuedForRift);
        if (should == wantActive) return;
        wantActive = should;
        applyAsync(should);
    }

    /** Called when a chat message announces the local player has joined the rift queue. */
    public static void onQueueJoined() {
        queuedForRift = true;
        // Pump update immediately so the reload starts now, during the queue wait,
        // not at the next world tick.
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean inRift = mc != null && mc.world != null
                && RIFT_WORLD_NAME.equals(mc.world.getRegistryKey().getValue().getPath());
        update(inRift, FeatureToggles.isRiftTexturePackEnabled());
    }

    /** Called when the player explicitly leaves the queue or the queue is cancelled. */
    public static void onQueueLeft() {
        if (!queuedForRift) return;
        queuedForRift = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean inRift = mc != null && mc.world != null
                && RIFT_WORLD_NAME.equals(mc.world.getRegistryKey().getValue().getPath());
        update(inRift, FeatureToggles.isRiftTexturePackEnabled());
    }

    private static void applyAsync(boolean enable) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> apply(mc, enable));
    }

    private static void apply(MinecraftClient mc, boolean enable) {
        ResourcePackManager mgr = mc.getResourcePackManager();
        if (mgr == null) return;
        String packName = findPackName(mgr);
        if (packName == null) {
            PrisonsMod.LOGGER.warn("Rift pack not found in pack manager (have: {})", mgr.getIds());
            return;
        }
        Collection<String> currentEnabled = mgr.getEnabledIds();
        boolean isEnabled = currentEnabled.contains(packName);
        if (isEnabled == enable) {
            currentlyActive = enable;
            return;
        }
        // Preserve order/contents of currently-enabled packs; just add or remove ours.
        LinkedHashSet<String> next = new LinkedHashSet<>(currentEnabled);
        if (enable) next.add(packName); else next.remove(packName);
        mgr.setEnabledProfiles(new ArrayList<>(next));
        currentlyActive = enable;
        mc.reloadResources();
        PrisonsMod.LOGGER.info("Rift pack {} ({})", enable ? "enabled" : "disabled", packName);
    }

    private static String findPackName(ResourcePackManager mgr) {
        String cached = cachedPackName;
        Collection<String> ids = mgr.getIds();
        if (cached != null && ids.contains(cached)) return cached;
        String idPath = PACK_ID.getPath();
        String suffix = "/" + idPath;
        String fullId = PACK_ID.toString();
        for (String n : ids) {
            if (n.equals(fullId) || n.equals(idPath) || n.endsWith(suffix)) {
                cachedPackName = n;
                return n;
            }
        }
        return null;
    }

    public static boolean isCurrentlyActive() { return currentlyActive; }

    private RiftTexturePackManager() {}
}
