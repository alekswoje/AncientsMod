package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.mixin.client.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;

/**
 * Small "Combine energy" button sitting just above the top-left corner of the survival
 * inventory panel. Clicking it runs the server's {@code /combine}, which merges every
 * Ancient Energy stack in the inventory into one.
 *
 * <p>The left edge is the free side: the jewel sockets and the status-effect icons both live
 * to the right of the panel. The button is re-anchored before every frame because opening the
 * recipe book slides the panel sideways without re-initialising the screen.
 */
public final class CombineButton {

    private static final int W = 74;
    private static final int H = 14;
    private static final int GAP = 2;

    private CombineButton() {}

    public static void attach(Screen screen) {
        if (!(screen instanceof InventoryScreen)) return;
        if (!ServerAllowlist.isAllowed() || !FeatureToggles.isCombineButtonEnabled()) return;
        HandledScreenAccessor panel = (HandledScreenAccessor) screen;

        GlassButton button = new GlassButton(0, 0, W, H, Text.literal("Combine energy"), CombineButton::run);
        button.setTooltip(Tooltip.of(Text.literal("Merge every Ancient Energy stack in your inventory into one (/combine)")));
        place(button, panel);
        Screens.getButtons(screen).add(button);
        ScreenEvents.beforeRender(screen).register((s, ctx, mouseX, mouseY, delta) -> place(button, panel));
    }

    private static void place(GlassButton button, HandledScreenAccessor panel) {
        button.setX(panel.ancientsmod$panelX());
        button.setY(Math.max(0, panel.ancientsmod$panelY() - H - GAP));
    }

    private static void run() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) client.getNetworkHandler().sendChatCommand("combine");
    }
}
