package com.aleks.prisonsmod.client;

import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.Entity;

/**
 * Unifier for client-side "fade this player so the user can see past them"
 * decisions. Two independent features ({@link PeacefulMining} and
 * {@link PeacefulPvp}) feed into a single rendering path so the
 * {@code LivingEntityRendererMixin} / {@code EquipmentRendererMixin} pair
 * only needs to check one predicate.
 *
 * <p>Holds a {@link ThreadLocal} "currently rendering a faded entity"
 * flag — set at the HEAD of every {@code LivingEntityRenderer.render}
 * call we want to fade, cleared at RETURN. The equipment-feature
 * renderer reads this flag to decide whether to swap the armor render
 * layer to a translucent variant.
 *
 * <p>Body alpha is fixed at {@link #BODY_ALPHA} (50%). That value
 * replaces the vanilla 0x26 (15%) constant inside the
 * {@code LivingEntityRenderer.render} hot path.
 */
public final class EntityFade {

    /** High byte of the colour int passed to the model render — 0x80 = 50% alpha, full white RGB. */
    public static final int BODY_COLOR = 0x80FFFFFF;

    /** Same alpha applied to armor via the EquipmentRenderer mixin. */
    public static final int BODY_ALPHA = 0x80;

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * True if either feature wants this entity faded right now. Cheap —
     * each branch short-circuits on the toggle and the allowlist.
     */
    public static boolean shouldFade(LivingEntityRenderState state) {
        return PeacefulMining.shouldFade(state) || PeacefulPvp.shouldFade(state);
    }

    /**
     * Combined raycast-skip predicate used by {@code GameRendererTargetMixin}.
     * If either feature would phase through this entity, the targeting
     * raycast should ignore it so the block (or enemy) behind becomes the
     * new crosshair target.
     */
    public static boolean shouldPhaseThrough(Entity candidate) {
        return PeacefulMining.shouldPhaseThrough(candidate) || PeacefulPvp.shouldPhaseThrough(candidate);
    }

    /** Called from {@code LivingEntityRendererMixin} HEAD when {@link #shouldFade} matches. */
    public static void beginRender() {
        ACTIVE.set(Boolean.TRUE);
    }

    /** Called unconditionally from {@code LivingEntityRendererMixin} RETURN. */
    public static void endRender() {
        ACTIVE.set(Boolean.FALSE);
    }

    /** True only while we are in the body+features render pass of a faded entity. */
    public static boolean isRenderingFaded() {
        return ACTIVE.get();
    }

    private EntityFade() {}
}
