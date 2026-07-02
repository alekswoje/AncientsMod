package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.ServerAllowlist;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes a vanilla quirk that strands the server's custom mining (meteorites,
 * ores) in a paused state when the player briefly looks off a block and back
 * while holding attack.
 *
 * <p><b>The vanilla bug.</b> Holding attack and moving the crosshair off the
 * block makes {@code MinecraftClient.handleBlockBreaking} call
 * {@code cancelBlockBreaking()}, which sends {@code ABORT_DESTROY_BLOCK} (the
 * server pauses the mining session) and clears {@code breakingBlock} /
 * {@code currentBreakingProgress} — but it leaves {@code currentBreakingPos}
 * pointing at the same block. Looking back at that <em>same</em> block then
 * enters {@code updateBlockBreakingProgress}, where {@code isCurrentlyBreaking(pos)}
 * is still {@code true} (pos still equals {@code currentBreakingPos}). So the
 * client takes the "continue breaking" branch and advances progress
 * <em>locally only</em> — it never re-sends {@code START_DESTROY_BLOCK}. The
 * server, paused on the abort, gets no resume signal, so mining appears stuck
 * until the player releases and re-presses attack (which routes through
 * {@code attackBlock} and finally sends a fresh START).
 *
 * <p>It's intermittent because it only triggers when the crosshair returns to
 * the exact same block; landing on a different block sends a new START and
 * resumes normally.
 *
 * <p><b>The fix.</b> Detect the "ghost breaking" state at the head of
 * {@code updateBlockBreakingProgress} — not on cooldown, {@code breakingBlock}
 * cleared, yet {@code currentBreakingPos} still equals the targeted block — and
 * re-issue {@code attackBlock(pos, direction)}, which sends a fresh START and
 * re-arms the breaking state. The server resumes the paused session from its
 * persisted progress, exactly as if attack had been re-pressed. Gated to the
 * Prisons server (the only place custom mining pauses on abort); vanilla servers
 * are untouched.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class MiningResumeMixin {

    @Shadow private boolean breakingBlock;
    @Shadow private BlockPos currentBreakingPos;
    @Shadow private int blockBreakingCooldown;

    @Shadow public abstract boolean attackBlock(BlockPos pos, Direction direction);

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void ancientsmod$resumeAfterLookAway(BlockPos pos, Direction direction,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (!ServerAllowlist.isAllowed()) return;

        // Let vanilla own the cooldown window (post-break grace) and the genuine
        // continuous-break case — only the ghost state needs a nudge.
        if (blockBreakingCooldown > 0) return;
        if (breakingBlock) return;

        // breakingBlock cleared by a prior cancelBlockBreaking() (look-away abort)
        // but the crosshair is back on the same block: vanilla would silently
        // continue without notifying the server. Re-send START so it resumes.
        if (currentBreakingPos != null && currentBreakingPos.equals(pos)) {
            cir.setReturnValue(attackBlock(pos, direction));
        }
    }
}
