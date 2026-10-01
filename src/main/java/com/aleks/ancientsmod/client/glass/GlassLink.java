package com.aleks.ancientsmod.client.glass;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

/**
 * Settings-list action row: candle label on the left, a chevron on the right, a soft tint on
 * hover. Used for rows that open another screen or run a one-off action, so they read as
 * navigation rather than as a big button in the middle of a list of switches.
 */
public class GlassLink extends PressableWidget {

    private final Runnable action;

    public GlassLink(int x, int y, int width, int height, Text message, Runnable action) {
        super(x, y, width, height, message);
        this.action = action;
    }

    @Override
    public void onPress(AbstractInput input) {
        if (action != null) action.run();
    }

    @Override
    protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
        GlassRender.row(ctx, x1, y1, x2, y2, isHovered());
        TextRenderer fr = MinecraftClient.getInstance().textRenderer;
        int midV = y1 + (getHeight() - fr.fontHeight) / 2;
        int color = !this.active ? GlassTheme.textMuted() : GlassTheme.ACCENT_SOFT;
        String label = fr.trimToWidth(getMessage().getString(), getWidth() - 24);
        ctx.drawText(fr, Text.literal(label), x1 + 6, midV, color, false);
        ctx.drawText(fr, Text.literal(">"), x2 - 6 - fr.getWidth(">") - (isHovered() ? 0 : 1), midV, color, false);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
