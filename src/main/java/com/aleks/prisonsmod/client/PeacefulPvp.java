package com.aleks.prisonsmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;

/**
 * Gate for the "peaceful PvP" fade + raycast-skip. While the local player
 * holds a sword or axe, any gang teammate (per {@link GangRoster}) within
 * {@link #RADIUS_BLOCKS} blocks is rendered translucent AND skipped by
 * the attack raycast — so you can hit the enemy standing behind your gang
 * teammate instead of accidentally swinging at the teammate.
 *
 * <p>Disabled while the local player is in a duel ({@link DuelState}):
 * during a duel the teammate may BE the opponent, and we never want to
 * silently hide whatever the duel resolves to.
 *
 * <p>Off by default — opt-in via the settings screen.
 */
public final class PeacefulPvp {

    /** Spherical radius (in blocks) around the local player. */
    private static final double RADIUS_BLOCKS = 5.0;
    private static final double RADIUS_SQ = RADIUS_BLOCKS * RADIUS_BLOCKS;

    /**
     * Per-frame decision used by the render mixin. Cheap and pure.
     */
    public static boolean shouldFade(LivingEntityRenderState state) {
        if (!isFeatureActive()) return false;
        if (!(state instanceof PlayerEntityRenderState pers)) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity self = mc.player;
        if (self == null) return false;
        if (pers.id == self.getId()) return false;

        // PlayerEntityRenderState doesn't snapshot the UUID, only the entity id.
        // Resolve via the world's id→entity map (HashMap lookup, fine per frame).
        ClientWorld world = mc.world;
        if (world == null) return false;
        Entity entity = world.getEntityById(pers.id);
        if (!(entity instanceof PlayerEntity teammate)) return false;
        if (!GangRoster.contains(teammate.getUuid())) return false;

        double dx = state.x - self.getX();
        double dy = state.y - self.getY();
        double dz = state.z - self.getZ();
        return (dx * dx + dy * dy + dz * dz) <= RADIUS_SQ;
    }

    /**
     * Per-entity decision used by the attack-raycast mixin. Mirrors
     * {@link #shouldFade} but takes a live Entity instead of a render
     * state, because the raycast hands us the entity directly.
     */
    public static boolean shouldPhaseThrough(Entity candidate) {
        if (!isFeatureActive()) return false;
        if (!(candidate instanceof PlayerEntity other)) return false;

        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (self == null) return false;
        if (other.getId() == self.getId()) return false;
        if (!GangRoster.contains(other.getUuid())) return false;

        double distSq = candidate.squaredDistanceTo(self);
        return distSq <= RADIUS_SQ;
    }

    private static boolean isFeatureActive() {
        if (!ServerAllowlist.isAllowed()) return false;
        if (!FeatureToggles.isPeacefulPvpEnabled()) return false;
        if (DuelState.isInDuel()) return false;
        if (GangRoster.isEmpty()) return false;

        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (self == null) return false;

        ItemStack held = self.getMainHandStack();
        return held.isIn(ItemTags.SWORDS) || held.isIn(ItemTags.AXES);
    }

    private PeacefulPvp() {}
}
