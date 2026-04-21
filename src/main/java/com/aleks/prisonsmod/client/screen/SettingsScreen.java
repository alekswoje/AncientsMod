package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.FeatureToggles;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;

/**
 * In-game options screen for PrisonsMod client toggles. Each row is a
 * CyclingButtonWidget in ON/OFF mode wired to a {@link FeatureToggles} setter,
 * so edits persist to {@code config/prisonsmod.properties} immediately.
 */
public final class SettingsScreen extends Screen {

    private static final int BUTTON_W = 240;
    private static final int BUTTON_H = 20;
    private static final int ROW_GAP = 24;

    private final Screen parent;

    public SettingsScreen(Screen parent) {
        super(Text.literal("PrisonsMod Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - (ROW_GAP * 3) / 2 - 8;

        addToggle(centerX, y, "Mine-crack prediction",
                FeatureToggles.isMinePredictEnabled(), FeatureToggles::setMinePredict);
        y += ROW_GAP;

        addToggle(centerX, y, "Collapse enchants on gear",
                FeatureToggles.isEnchantCollapseEnabled(), FeatureToggles::setEnchantCollapse);
        y += ROW_GAP;

        addToggle(centerX, y, "Scrollable tooltips",
                FeatureToggles.isScrollableTooltipsEnabled(), FeatureToggles::setScrollableTooltips);
        y += ROW_GAP + 20;

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> this.close())
                .dimensions(centerX - 50, y, 100, BUTTON_H)
                .build());
    }

    private void addToggle(int centerX, int y, String label, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        addDrawableChild(CyclingButtonWidget.onOffBuilder(initial)
                .build(centerX - BUTTON_W / 2, y, BUTTON_W, BUTTON_H,
                        Text.literal(label),
                        (button, value) -> onChange.accept(value)));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title,
                this.width / 2, 24, 0xFFFFFF);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(this.parent);
    }
}
