package com.aleks.prisonsmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;

/**
 * Gate for the "peaceful mining" fade. While the local player holds a
 * pickaxe, any player-shaped entity (real players + PrisonsCore NPCs)
 * within {@link #RADIUS_BLOCKS} blocks is rendered translucent so it
 * doesn't visually block the mine face.
 *
 * <p>Pure visual — no collision or raycast change. Vanilla block-mining
 * raycast already passes through entities, so the translucency is the
 * whole win.
 */
public final class PeacefulMining {

    /** Spherical radius (in blocks) around the local player. */
    private static final double RADIUS_BLOCKS = 2.0;
    private static final double RADIUS_SQ = RADIUS_BLOCKS * RADIUS_BLOCKS;

    public static boolean shouldFade(LivingEntityRenderState state) {
        if (!isFeatureActive()) return false;
        if (!(state instanceof PlayerEntityRenderState pers)) return false;

        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (self == null) return false;
        if (pers.id == self.getId()) return false;

        double dx = state.x - self.getX();
        double dy = state.y - self.getY();
        double dz = state.z - self.getZ();
        return (dx * dx + dy * dy + dz * dz) <= RADIUS_SQ;
    }

    /**
     * Same predicate but takes a live Entity — used by the attack-raycast
     * mixin so the crosshair pick skips nearby players/NPCs while you're
     * mining. Without this the player's hitbox steals the crosshair from
     * the block behind them (their hitbox is closer than the block) and
     * left-click attacks instead of mining.
     */
    public static boolean shouldPhaseThrough(Entity candidate) {
        if (!isFeatureActive()) return false;
        if (!(candidate instanceof PlayerEntity other)) return false;

        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (self == null) return false;
        if (other.getId() == self.getId()) return false;

        double distSq = candidate.squaredDistanceTo(self);
        return distSq <= RADIUS_SQ;
    }

    private static boolean isFeatureActive() {
        if (!ServerAllowlist.isAllowed()) return false;
        if (!FeatureToggles.isPeacefulMiningEnabled()) return false;

        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (self == null) return false;

        ItemStack held = self.getMainHandStack();
        return held.isIn(ItemTags.PICKAXES);
    }

    private PeacefulMining() {}
}
