package com.aleks.ancientsmod.client.hud;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

/**
 * A moveable on-screen widget rendered through {@link HudRenderer}. Each element
 * has a stable {@code id} (used as the key for position persistence) and is
 * drawn through {@link #render} with its top-left corner already translated to
 * the player-chosen position. Implementations only need to draw their own
 * content; positioning, scaling and the edit-mode overlay are handled by the
 * framework.
 *
 * <p>Width and height are dynamic (queried each frame) so widgets whose
 * content changes — e.g. a booster panel that grows when new boosters start —
 * still drag correctly in the editor.
 */
public abstract class HudElement {

    /** Stable id used in config and the registry. Lowercase, no spaces. */
    public abstract String id();

    /** Human-readable label shown in the editor. */
    public abstract String displayName();

    /** Pixel width of the widget at scale 1.0 right now. Must be &gt; 0. */
    public abstract int width();

    /** Pixel height of the widget at scale 1.0 right now. Must be &gt; 0. */
    public abstract int height();

    /** Default x for a fresh install, given the current screen width. */
    public abstract int defaultX(int screenWidth);

    /** Default y for a fresh install, given the current screen height. */
    public abstract int defaultY(int screenHeight);

    /**
     * Draw the widget. The {@link DrawContext} has already been translated and
     * scaled so {@code (0, 0)} is the widget's top-left at scale 1.0. Do not
     * apply your own translate/scale here.
     */
    public abstract void render(DrawContext ctx, TextRenderer fr, float tickDelta);

    /**
     * Whether the widget should render in normal play. Returning {@code false}
     * hides the widget while leaving its slot reserved in the editor (the
     * editor always shows every registered element so the player can position
     * it before it's relevant). Defaults to {@code true}.
     */
    public boolean isVisible() {
        return true;
    }

    /**
     * Placeholder text shown in the editor when {@link #isVisible()} returns
     * {@code false} (e.g. "No active boosters"). Null = no placeholder.
     */
    public String editorPlaceholder() {
        return null;
    }

    /**
     * Optional per-widget settings screen, opened from the HUD editor via
     * gear-icon click or right-click on this widget. Return {@code null} (the
     * default) if this widget has no settings.
     */
    public Screen openSettings(Screen parent) {
        return null;
    }
}
