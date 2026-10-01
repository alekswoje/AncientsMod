package com.aleks.ancientsmod.client.glass;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

/**
 * Glass integer slider — replaces the anonymous {@code SliderWidget} in the settings
 * base. Keeps vanilla drag/keyboard behavior; only the look (label left, thin ember rail,
 * value in flame on the right) is overridden.
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

        TextRenderer fr = MinecraftClient.getInstance().textRenderer;
        int midV = y1 + (getHeight() - fr.fontHeight) / 2;

        // Label on the left, value in flame on the right, rail between them.
        String val = String.valueOf(currentValue());
        int vw = fr.getWidth(val);
        ctx.drawText(fr, Text.literal(val), x2 - 6 - vw, midV, GlassTheme.VALUE, false);
        int lw = Math.min(fr.getWidth(label), getWidth() / 2 - 12);
        ctx.drawText(fr, Text.literal(fr.trimToWidth(label, lw)), x1 + 6, midV,
                isHovered() ? GlassTheme.text() : GlassTheme.textDim(), false);

        int tx1 = x1 + 6 + lw + 10, tx2 = x2 - 6 - Math.max(vw, fr.getWidth("00")) - 10;
        if (tx2 - tx1 < 20) tx1 = tx2 - 20;
        int ty = y1 + getHeight() / 2;
        GlassRender.sliderTrack(ctx, tx1, ty - 1, tx2, ty + 1, (float) this.value);
        int kx = tx1 + (int) Math.round(this.value * (tx2 - tx1));
        GlassRender.roundedRect(ctx, kx - 2, ty - 4, kx + 3, ty + 5, 1, 0xFFFFF4E2);
    }
}
