package com.aleks.ancientsmod.client.glass;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

/**
 * Glass-styled action button. Drop-in for vanilla {@code ButtonWidget}: same
 * dimensions / message / press behavior, but drawn as a frosted rounded plate.
 * Mark it {@link #primary()} for the accent-filled "confirm" variant (always on
 * the right of a button row per the project's confirm-right rule).
 */
public class GlassButton extends PressableWidget {

    private final Runnable action;
    private boolean primary;

    public GlassButton(int x, int y, int width, int height, Text message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    /** Accent-filled variant (confirm / submit / done). */
    public GlassButton primary() { this.primary = true; return this; }

    @Override
    public void onPress(AbstractInput input) {
        if (action != null) action.run();
    }

    @Override
    protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
        GlassRender.button(ctx, x1, y1, x2, y2, isHovered(), this.active, primary);
        TextRenderer fr = MinecraftClient.getInstance().textRenderer;
        Text m = getMessage();
        int tw = fr.getWidth(m);
        int color = !this.active ? GlassTheme.textMuted() : primary ? 0xFFFFFFFF : GlassTheme.text();
        ctx.drawText(fr, m, x1 + (getWidth() - tw) / 2, y1 + (getHeight() - fr.fontHeight) / 2, color, false);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
