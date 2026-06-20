package com.aleks.prisonsmod.client.glass;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Glass-styled text field — a {@code TextFieldWidget} with its own vanilla box
 * suppressed ({@code setDrawsBackground(false)}) and a frosted glass plate drawn
 * behind the text/cursor instead. All editing behavior is inherited unchanged.
 */
public class GlassTextField extends TextFieldWidget {

    public GlassTextField(TextRenderer textRenderer, int x, int y, int width, int height, Text message) {
        super(textRenderer, x, y, width, height, message);
        setDrawsBackground(false);
    }

    @Override
    public void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        GlassRender.field(ctx, getX(), getY(), getX() + getWidth(), getY() + getHeight(), isFocused());
        super.renderWidget(ctx, mouseX, mouseY, delta);
    }
}
