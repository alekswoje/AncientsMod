package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.EntityFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Post-processes the vanilla crosshair pick so that, when either fade
 * feature would phase through the picked entity, the targeting flips to
 * whatever is behind it.
 *
 * <p>Two cases:
 * <ul>
 *   <li><b>Peaceful mining</b> (pickaxe held, player within 2 blocks): the
 *       player's hitbox is closer than the block they're standing in
 *       front of, so vanilla picks the player and left-click attacks them.
 *       Without this mixin, translucent rendering alone doesn't help —
 *       the user reported the original "visual only" promise was wrong.</li>
 *   <li><b>Peaceful PvP</b> (sword/axe held, gang teammate within 5 blocks,
 *       not in a duel): same problem, but the goal is to swing at the
 *       enemy behind your teammate instead of the teammate.</li>
 * </ul>
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Check {@link EntityFade#shouldPhaseThrough} on the current
 *       {@code targetedEntity}; bail if not phase-through.</li>
 *   <li>Redo the entity raycast using the local player's
 *       {@code getEntityInteractionRange()} and a predicate that excludes
 *       phase-through targets.</li>
 *   <li>Redo the block raycast at the player's {@code getBlockInteractionRange()}
 *       — vanilla's result was thrown away when the entity was picked, and
 *       the block behind the skipped player is the new desired target.</li>
 *   <li>Whichever is closer to the eye wins. Block always wins ties (so
 *       mining beats attacking when the user clearly aimed at a face).</li>
 * </ol>
 *
 * <p>Pure client-side: the server still validates whatever attack/break we
 * send. If the rewritten target is past the server-known reach the action
 * is rejected exactly as if the player had aimed there directly. No reach
 * extension is possible — we only swap one in-range target for another.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererTargetMixin {

    @Shadow @Final private MinecraftClient client;

    @Inject(method = "updateCrosshairTarget", at = @At("TAIL"))
    private void prisonsmod$skipPhaseThroughTargets(float tickDelta, CallbackInfo ci) {
        Entity current = client.targetedEntity;
        if (current == null) return;
        if (!EntityFade.shouldPhaseThrough(current)) return;

        Entity camera = client.getCameraEntity();
        ClientWorld world = client.world;
        ClientPlayerEntity self = client.player;
        if (camera == null || world == null || self == null) return;

        Vec3d eye = camera.getCameraPosVec(tickDelta);
        Vec3d look = camera.getRotationVec(tickDelta);
        double blockReach = self.getBlockInteractionRange();
        double entityReach = self.getEntityInteractionRange();

        Vec3d entityEnd = eye.add(look.x * entityReach, look.y * entityReach, look.z * entityReach);
        Vec3d blockEnd  = eye.add(look.x * blockReach,  look.y * blockReach,  look.z * blockReach);

        Box searchBox = camera.getBoundingBox().stretch(look.multiply(entityReach)).expand(1.0);
        EntityHitResult entHit = ProjectileUtil.raycast(
                camera, eye, entityEnd, searchBox,
                e -> !e.isSpectator()
                        && e.canHit()
                        && e != camera
                        && !EntityFade.shouldPhaseThrough(e),
                entityReach * entityReach);

        BlockHitResult blockHit = world.raycast(new RaycastContext(
                eye, blockEnd,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                camera));

        double entDist = entHit != null ? eye.squaredDistanceTo(entHit.getPos()) : Double.POSITIVE_INFINITY;
        double blockDist = eye.squaredDistanceTo(blockHit.getPos());

        // Strict < so a tie goes to the block — mining beats attacking when
        // the player clearly aimed at a face that an entity is grazing.
        if (entHit != null && entDist < blockDist) {
            client.crosshairTarget = entHit;
            client.targetedEntity = entHit.getEntity();
        } else {
            // World.raycast never returns null — it returns a "miss" hit
            // result when nothing is hit, which is what vanilla sets too.
            client.crosshairTarget = blockHit;
            client.targetedEntity = null;
        }
    }
}
