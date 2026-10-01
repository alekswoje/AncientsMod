package com.aleks.ancientsmod.client.glass;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * Glass ON/OFF toggle row, replacing the vanilla {@code CyclingButtonWidget.onOffBuilder}
 * used by the settings screens. Draws a left-aligned label and a right-aligned switch on a
 * row that tints on hover; clicking anywhere on the row flips the value and fires
 * the change callback (which persists via FeatureToggles).
 */
public class GlassToggle extends PressableWidget {

    private boolean value;
    private final Consumer<Boolean> onChange;
    private final String labelText;

    public GlassToggle(int x, int y, int width, int height, String label, boolean initial, Consumer<Boolean> onChange) {
        super(x, y, width, height, Text.literal(label));
        this.labelText = label;
        this.value = initial;
        this.onChange = onChange;
    }

    public boolean value() { return value; }

    @Override
    public void onPress(AbstractInput input) {
        value = !value;
        if (onChange != null) onChange.accept(value);
    }

    @Override
    protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
        GlassRender.row(ctx, x1, y1, x2, y2, isHovered());

        TextRenderer fr = MinecraftClient.getInstance().textRenderer;
        int midV = y1 + (getHeight() - fr.fontHeight) / 2;

        int sw = 16, sh = 9;
        int tx2 = x2 - 6, tx1 = tx2 - sw;
        int ty1 = y1 + (getHeight() - sh) / 2, ty2 = ty1 + sh;

        // Clip the label so it can never run under the switch.
        int labelMax = tx1 - 8 - (x1 + 6);
        String label = labelMax > 8 ? fr.trimToWidth(labelText, labelMax) : "";
        int color = !this.active ? GlassTheme.textMuted() : isHovered() ? GlassTheme.text() : GlassTheme.textDim();
        ctx.drawText(fr, Text.literal(label), x1 + 6, midV, color, false);

        GlassRender.glassSwitch(ctx, tx1, ty1, tx2, ty2, value);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
