package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.render.MinePredictRenderer;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds authoritative server block updates into {@link MinePredictRenderer} so
 * swing-time predictions can be confirmed (server broke the block — drop the
 * ghost) or corrected (server kept/changed it differently — adopt + learn).
 * Both handlers force main-thread re-dispatch before reaching TAIL, so the
 * callbacks run on the render thread after the state is applied to the world.
 */
@Mixin(ClientPlayNetworkHandler.class)
abstract class ClientPlayNetworkHandlerBlockUpdateMixin {

    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void ancientsmod$onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        MinePredictRenderer.onServerBlockUpdate(packet.getPos(), packet.getState());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void ancientsmod$onChunkDeltaUpdate(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        packet.visitUpdates((pos, state) -> MinePredictRenderer.onServerBlockUpdate(pos.toImmutable(), state));
    }
}
