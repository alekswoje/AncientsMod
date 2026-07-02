package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.ServerAllowlist;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Properties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Forces redstone ore to render UNLIT on the client while connected to the
 * Prisons server, removing the lit-glow "flicker" players reported while
 * mining the Redstone Mine (ANCT-345).
 *
 * <p>Vanilla redstone ore flips {@code LIT=true} whenever it is hit, walked
 * on, or has a neighbour broken, then decays back to {@code LIT=false} a tick
 * later. Across a wall of redstone ore in the mine that on/off glow reads as
 * constant flashing. The server already tries to suppress it, but the only way
 * to guarantee a clean client is to never let the client hold the lit state.
 *
 * <p>Every block state applied to the client world passes through one of two
 * sinks: {@link ClientWorld#handleBlockUpdate} (server-sent
 * {@code BlockUpdateS2CPacket} / {@code ChunkDeltaUpdateS2CPacket}) and
 * {@link ClientWorld#setBlockState} (client-local prediction). We rewrite the
 * incoming state's {@code LIT} property to {@code false} at the head of both,
 * so the client never renders the lit model or its {@code randomDisplayTick}
 * particles.
 *
 * <p>Purely visual and client-only: it changes nothing the server sees and is
 * a strict no-op off the Prisons server (gated on {@link ServerAllowlist}).
 * Redstone ore is the only mineable block with a {@code LIT} state, so no other
 * block is affected.
 */
@Mixin(ClientWorld.class)
public class ClientWorldRedstoneUnlitMixin {

    @ModifyVariable(method = "handleBlockUpdate", at = @At("HEAD"), argsOnly = true)
    private BlockState ancientsmod$unlitOnUpdate(BlockState state) {
        return ancientsmod$unlitRedstone(state);
    }

    @ModifyVariable(method = "setBlockState", at = @At("HEAD"), argsOnly = true)
    private BlockState ancientsmod$unlitOnSet(BlockState state) {
        return ancientsmod$unlitRedstone(state);
    }

    private static BlockState ancientsmod$unlitRedstone(BlockState state) {
        if (state == null || !ServerAllowlist.isAllowed()) return state;
        if ((state.isOf(Blocks.REDSTONE_ORE) || state.isOf(Blocks.DEEPSLATE_REDSTONE_ORE))
                && state.contains(Properties.LIT) && state.get(Properties.LIT)) {
            return state.with(Properties.LIT, false);
        }
        return state;
    }
}
