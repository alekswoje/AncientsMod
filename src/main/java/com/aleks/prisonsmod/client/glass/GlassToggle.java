package com.aleks.prisonsmod.client.glass;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * Glass ON/OFF toggle row — replaces the vanilla {@code CyclingButtonWidget.onOffBuilder}
 * used by the settings screens. Draws a left-aligned label and a right-aligned iOS-style
 * switch on a frosted row plate; clicking anywhere on the row flips the value and fires
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

        int sw = 32, sh = 16;
        int tx2 = x2 - 10, tx1 = tx2 - sw;
        int ty1 = y1 + (getHeight() - sh) / 2, ty2 = ty1 + sh;

        Text state = Text.literal(value ? "ON" : "OFF");
        int stateX = tx1 - 6 - fr.getWidth(state);

        // Clip the label so it can never run under the ON/OFF text or the switch.
        int labelMax = stateX - 8 - (x1 + 10);
        String label = labelMax > 8 ? fr.trimToWidth(labelText, labelMax) : "";
        ctx.drawText(fr, Text.literal(label), x1 + 10, midV,
                this.active ? GlassTheme.text() : GlassTheme.textMuted(), false);

        GlassRender.glassSwitch(ctx, tx1, ty1, tx2, ty2, value);
        int sc = value ? GlassTheme.ACCENT_SOFT : GlassTheme.textMuted();
        ctx.drawText(fr, state, stateX, midV, sc, false);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }
}
