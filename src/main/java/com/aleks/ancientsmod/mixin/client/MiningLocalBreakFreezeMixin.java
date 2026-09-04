package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.render.MinePredictRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops vanilla's own block breaking from completing underneath a mine
 * prediction, which cost a predict-on player ~250ms and a visible pop-back on
 * every block.
 *
 * <p><b>The bug.</b> Mining here is server-authoritative: the server decides the
 * break from {@code START_DESTROY_BLOCK} and a scheduled task, and
 * {@code MiningPacketListener} cancels any client {@code STOP_DESTROY_BLOCK} on
 * an ore, answering it with a {@code sendBlockChange} of the block's real state.
 * The client's own break loop runs anyway, and nothing in the mod used to touch
 * it — there was no {@code ClientPlayerInteractionManager} mixin on this path.
 * {@code updateBlockBreakingProgress} recomputes its per-tick delta from
 * whatever block state sits at the position it is breaking
 * ({@code speed / hardness / (canHarvest ? 30 : 100)}), and
 * {@code isCurrentlyBreaking} only compares position and held item — never the
 * state. So when {@link MinePredictRenderer} ghost-swaps the ore to its
 * replacement, vanilla silently carries its accumulated progress onto a
 * <em>softer</em> block (stone 1.5 vs ore 3.0; netherrack 0.4 vs quartz ore 3.0;
 * nether bricks 2.0 vs ancient debris 30) and can also flip {@code canHarvest}
 * false→true for another 3.3x.
 *
 * <p>Mid-gear players were already ~70% through vanilla's own break when the
 * ghost swap landed, so local progress crossed 1.0 within ~80-200ms of it —
 * faster than anyone retargets. That fired {@code STOP_DESTROY_BLOCK}, broke the
 * block to air locally, and set {@code blockBreakingCooldown = 5}: five ticks in
 * which {@code updateBlockBreakingProgress} returns immediately, so <b>no START
 * goes out for the next block for 250ms</b>. The server meanwhile cancelled the
 * STOP and re-asserted the ore, which the renderer reads as a "reassert"
 * rollback — the ore visibly pops back and the position is blacklisted for 10s.
 * The server-side task itself survives (its {@code BlockDamageEvent} handler
 * ignores a repeat START on the block it is already mining), so no progress is
 * destroyed; the cost is the stall and the flicker.
 *
 * <p>Endgame builds never saw it: at 100-150ms breaks the crosshair has moved on
 * long before local progress can complete. It scaled with how slow the player's
 * custom break was, which is why it read as "mining got very slow" to a mid-gear
 * player and as nothing at all to everyone advising them.
 *
 * <p><b>The fix.</b> While the block under the crosshair is our own ghost
 * replacement awaiting confirmation, hold vanilla's local break at zero: reset
 * {@code currentBreakingProgress}, drop the local crack overlay it would draw,
 * and return {@code true} so the caller still spawns break particles and swings
 * the hand (the player IS still mining — the server is finishing the block). No
 * completion, so no spurious STOP, no 250ms cooldown, and no re-assert rollback.
 * The freeze ends the moment the entry does, and vanilla resumes from zero on
 * whatever the server actually put there.
 *
 * <p>Scoped to positions where the server has sent a {@code PKT_MINE_START} and
 * the renderer is holding a ghost swap, so it can only ever apply to a break the
 * server owns and will finish itself. Cell-region and Skywars blocks bypass
 * custom mining and never get that START, so they keep breaking with plain
 * vanilla rules even if the speed table happened to predict one (walking from
 * the mine into a cell leaves the engine armed for 60s). With prediction off
 * there are no entries at all and this never fires.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class MiningLocalBreakFreezeMixin {

    @Shadow private boolean breakingBlock;
    @Shadow private BlockPos currentBreakingPos;
    @Shadow private int blockBreakingCooldown;
    @Shadow private float currentBreakingProgress;
    @Shadow private ItemStack selectedStack;

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void ancientsmod$freezeLocalBreakUnderGhostSwap(BlockPos pos, Direction direction,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (!ServerAllowlist.isAllowed()) return;

        // Let vanilla drain a cooldown it already started, and leave the
        // not-yet-breaking case to MiningResumeMixin (which re-sends START).
        if (blockBreakingCooldown > 0) return;
        if (!breakingBlock) return;

        // Same condition vanilla's isCurrentlyBreaking uses for the "continue
        // breaking" branch: position plus held item. If the player swapped tools
        // mid-break, vanilla re-attacks and we must not swallow that.
        if (currentBreakingPos == null || !currentBreakingPos.equals(pos)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        if (!ItemStack.areItemsAndComponentsEqual(client.player.getMainHandStack(), selectedStack)) return;

        if (!MinePredictRenderer.isServerOwnedGhostAt(pos)) return;

        // The block here is our prediction, not the server's state — vanilla has
        // no business finishing a break on it.
        currentBreakingProgress = 0.0F;
        // Progress 0 means vanilla would have pushed stage -1 anyway; do it
        // explicitly so the crack it drew before the swap does not stick.
        client.world.setBlockBreakingInfo(client.player.getId(), pos, -1);
        MinePredictRenderer.noteLocalBreakFrozen();
        cir.setReturnValue(true);
    }
}
