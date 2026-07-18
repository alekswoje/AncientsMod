package com.aleks.ancientsmod.client;

/**
 * Hold-to-zoom state shared between {@link KeyBinds#ZOOM} and the
 * {@code GameRendererZoomMixin} FOV hook.
 *
 * <p>Deliberately NOT gated on {@link ServerAllowlist} — zoom is a pure
 * client convenience (like the F9 settings screen and muffler keybinds)
 * and works on any server.
 */
public final class Zoom {

    /** Smoothed FOV multiplier, eased toward the target every frame. */
    private static float currentMult = 1.0f;

    public static boolean isHeld() {
        return FeatureToggles.isZoomEnabled() && KeyBinds.ZOOM.isPressed();
    }

    /**
     * Per-frame smoothed FOV multiplier — 1.0 when idle, easing toward the
     * configured zoom level while the key is held. Called from the
     * GameRenderer FOV mixin every frame, so a simple exponential ease reads
     * as a quick, smooth zoom-in/out.
     */
    public static float smoothedMultiplier() {
        float target = isHeld() ? FeatureToggles.getZoomFovPercent() / 100.0f : 1.0f;
        currentMult += (target - currentMult) * 0.4f;
        if (Math.abs(target - currentMult) < 0.005f) currentMult = target;
        return currentMult;
    }

    private Zoom() {}
}
