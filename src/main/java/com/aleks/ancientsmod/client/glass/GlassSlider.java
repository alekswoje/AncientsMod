package com.aleks.ancientsmod.client.glass;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

/**
 * Glass integer slider — replaces the anonymous {@code SliderWidget} in the settings
 * base. Keeps vanilla drag/keyboard behavior; only the look (frosted rail + accent fill
 * + glossy knob + centered label) is overridden.
 */
public class GlassSlider extends SliderWidget {

    private final int min;
    private final int max;
    private final String label;
    private final IntConsumer setter;

    public GlassSlider(int x, int y, int width, int height, String label,
                       int min, int max, int initial, IntConsumer setter) {
        super(x, y, width, height, Text.literal(label + ": " + initial),
                max == min ? 0.0 : (double) (clamp(initial, min, max) - min) / (max - min));
        this.min = min;
        this.max = max;
        this.label = label;
        this.setter = setter;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    private int currentValue() { return min + (int) Math.round(this.value * (max - min)); }

    @Override
    protected void updateMessage() {
        setMessage(Text.literal(label + ": " + currentValue()));
    }

    @Override
    protected void applyValue() {
        if (setter != null) setter.accept(currentValue());
    }

    @Override
    public void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
        GlassRender.row(ctx, x1, y1, x2, y2, isHovered());

        int ty = y1 + getHeight() / 2;
        int tx1 = x1 + 10, tx2 = x2 - 10;
        GlassRender.sliderTrack(ctx, tx1, ty - 2, tx2, ty + 2, (float) this.value);

        int kx = tx1 + (int) Math.round(this.value * (tx2 - tx1));
        GlassRender.roundedRect(ctx, kx - 4, y1 + 4, kx + 4, y2 - 4, 4, 0xFFFFFFFF);

        TextRenderer fr = MinecraftClient.getInstance().textRenderer;
        Text m = getMessage();
        int tw = fr.getWidth(m);
        ctx.drawText(fr, m, x1 + (getWidth() - tw) / 2, y1 + (getHeight() - fr.fontHeight) / 2, GlassTheme.text(), true);
    }
}
