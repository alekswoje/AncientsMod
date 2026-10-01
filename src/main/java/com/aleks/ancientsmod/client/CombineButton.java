package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.mixin.client.HandledScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

/**
 * Icon button inside the survival inventory panel, just right of the recipe-book button,
 * showing the Ancient Energy item. Clicking it runs the server's {@code /combine}, which merges
 * every Ancient Energy stack in the inventory into one.
 *
 * <p>It sits on the vanilla grey panel next to the recipe-book button, so it copies that
 * button's 20x18 frame pixel for pixel (normal and highlighted) rather than using the mod's
 * glass theme. It is re-anchored before every frame because opening the recipe
 * book slides the panel sideways without re-initialising the screen.
 */
public final class CombineButton {

    private static final int W = 20;
    private static final int H = 18;
    /** Panel-relative position: the recipe-book button is 20x18 at (104, 61). */
    private static final int OFF_X = 104 + 20 + 2;
    private static final int OFF_Y = 61;

    private CombineButton() {}

    public static void attach(Screen screen) {
        if (!(screen instanceof InventoryScreen)) return;
        if (!ServerAllowlist.isAllowed() || !FeatureToggles.isCombineButtonEnabled()) return;
        HandledScreenAccessor panel = (HandledScreenAccessor) screen;

        Tile tile = new Tile();
        tile.setTooltip(Tooltip.of(Text.literal("Combine Ancient Energy\n")
                .append(Text.literal("Merge every energy stack into one").withColor(0xFFA8A8A8))));
        place(tile, panel);
        Screens.getButtons(screen).add(tile);
        ScreenEvents.beforeRender(screen).register((s, ctx, mouseX, mouseY, delta) -> place(tile, panel));
    }

    private static void place(Tile tile, HandledScreenAccessor panel) {
        tile.setX(panel.ancientsmod$panelX() + OFF_X);
        tile.setY(panel.ancientsmod$panelY() + OFF_Y);
    }

    /** Light-blue dye with the server pack's {@code ancient_energy} model: the real energy icon. */
    private static ItemStack energyIcon() {
        return IconResolver.resolve("minecraft:light_blue_dye#mancient_energy", Items.LIGHT_BLUE_DYE, 1);
    }

    private static final class Tile extends PressableWidget {

        private final ItemStack icon = energyIcon();

        Tile() {
            super(0, 0, W, H, Text.literal("Combine Ancient Energy"));
        }

        @Override
        public void onPress(AbstractInput input) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getNetworkHandler() != null) client.getNetworkHandler().sendChatCommand("combine");
        }

        @Override
        protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY();
            boolean hot = isHovered() || isFocused();
            int line = hot ? 0xFF00073E : 0xFF000000;
            int face = hot ? 0xFF8892C9 : 0xFFC6C6C6;
            int shade = hot ? 0xFF343E75 : 0xFF555555;
            // Same frame as vanilla's recipe_book/button sprite: outline with 2px diagonal
            // corner cuts, white top/left highlight, dark bottom/right edge.
            ctx.fill(x + 2, y, x + W - 2, y + 1, line);
            ctx.fill(x + 2, y + H - 1, x + W - 2, y + H, line);
            ctx.fill(x, y + 2, x + 1, y + H - 2, line);
            ctx.fill(x + W - 1, y + 2, x + W, y + H - 2, line);
            ctx.fill(x + 1, y + 1, x + 2, y + 2, line);
            ctx.fill(x + W - 2, y + 1, x + W - 1, y + 2, line);
            ctx.fill(x + 1, y + H - 2, x + 2, y + H - 1, line);
            ctx.fill(x + W - 2, y + H - 2, x + W - 1, y + H - 1, line);
            ctx.fill(x + 2, y + 2, x + W - 2, y + H - 2, face);
            ctx.fill(x + 2, y + 1, x + W - 2, y + 2, 0xFFFFFFFF);
            ctx.fill(x + 1, y + 2, x + 2, y + H - 2, 0xFFFFFFFF);
            ctx.fill(x + 2, y + H - 2, x + W - 2, y + H - 1, shade);
            ctx.fill(x + W - 2, y + 2, x + W - 1, y + H - 2, shade);
            ctx.drawItem(icon, x + 2, y + 1);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }
}
