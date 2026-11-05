package com.aleks.ancientsmod.client.hud;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.minecraft.util.Identifier;

/**
 * Whether the vanilla XP bar currently holds the Hunter level. The server shows the Hunter
 * level in PvE worlds and the mining level everywhere else, and says which with
 * {@code PKT_XP_BAR_STYLE}. While it is the Hunter level the bar and its number draw purple.
 */
public final class XpBarStyle {

    public static final Identifier HUNTER_BAR =
            Identifier.of("ancientsmod", "textures/gui/hud/hunter_xp_bar.png");
    /** Number colour for the Hunter level (vanilla's mining level is 0xFF80FF20). */
    public static final int HUNTER_LEVEL_COLOR = 0xFFC07CFF;

    private static volatile boolean hunter = false;

    public static void set(boolean value) { hunter = value; }

    public static boolean isHunter() { return hunter; }

    public static void reset() { hunter = false; }

    /** Same layout as vanilla {@code Bar.drawExperienceLevel}, purple instead of green. */
    public static void drawHunterLevel(DrawContext context, TextRenderer textRenderer, int level) {
        Text text = Text.translatable("gui.experience.level", level);
        int x = (context.getScaledWindowWidth() - textRenderer.getWidth(text)) / 2;
        int y = context.getScaledWindowHeight() - 24 - 9 - 2;
        context.drawText(textRenderer, text, x + 1, y, Colors.BLACK, false);
        context.drawText(textRenderer, text, x - 1, y, Colors.BLACK, false);
        context.drawText(textRenderer, text, x, y + 1, Colors.BLACK, false);
        context.drawText(textRenderer, text, x, y - 1, Colors.BLACK, false);
        context.drawText(textRenderer, text, x, y, HUNTER_LEVEL_COLOR, false);
    }

    private XpBarStyle() {}
}
