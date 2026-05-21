package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.TooltipScroll;
import net.minecraft.client.gui.tooltip.HoveredTooltipPositioner;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shifts oversized tooltips vertically by the current
 * {@link TooltipScroll} offset.
 *
 * <p>The vanilla {@link HoveredTooltipPositioner#getPosition} computes a
 * position that keeps the tooltip on-screen, which for taller-than-screen
 * tooltips means pinning the bottom (at {@code screenHeight - height - 3})
 * and letting the top spill off-screen above. We record the tooltip/screen
 * height for scroll-bounds calculation, then add the (non-negative) offset
 * to the returned Y so the tooltip slides downward, revealing the top.
 * Normal-sized tooltips pass through untouched.
 */
@Mixin(HoveredTooltipPositioner.class)
public class HoveredTooltipPositionerMixin {

    @Inject(method = "getPosition(IIIIII)Lorg/joml/Vector2ic;", at = @At("RETURN"), cancellable = true)
    private void prisonsmod$applyScroll(int screenWidth, int screenHeight, int x, int y,
                                        int width, int height,
                                        CallbackInfoReturnable<Vector2ic> cir) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!FeatureToggles.isScrollableTooltipsEnabled()) return;

        TooltipScroll.captureRender(screenHeight, height);
        if (!TooltipScroll.isOversized()) return;

        int offset = TooltipScroll.getOffset();
        if (offset == 0) return;

        Vector2ic orig = cir.getReturnValue();
        cir.setReturnValue(new Vector2i(orig.x(), orig.y() + offset));
    }
}
