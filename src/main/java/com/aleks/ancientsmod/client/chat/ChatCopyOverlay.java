package com.aleks.ancientsmod.client.chat;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.mixin.client.ChatHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.DrawnTextConsumer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.network.message.ChatVisibility;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * Hover-to-copy affordance for chat, drawn on top of the open chat screen.
 *
 * <p>The server marks each player chat line with a {@code copy_to_clipboard}
 * click event carrying {@code <name>: <message>}, but only for players running
 * this mod. Vanilla already performs the copy on click, so all this class does
 * is make it <i>visible</i>: the message under the cursor gets a soft
 * highlight, an accent bar down its left edge, and a copy icon in the chat
 * box's right padding strip. Nothing is drawn on lines the server did not mark
 * (system broadcasts, other plugins' output).
 *
 * <h2>Geometry</h2>
 * Mirrors {@code ChatHud#render}, because the chat is laid out in its own
 * scaled space. The chat pose is {@code scale(f)} then {@code translate(4, 0)},
 * so screen <em>x</em> = {@code f * (chatX + 4)} and screen <em>y</em> =
 * {@code f * chatY}. Row {@code k} (0 = bottom) spans
 * {@code baseline - k*lineH - lineH} to {@code baseline - k*lineH} in chat
 * space, where {@code baseline = floor((windowHeight - 40) / f)}. Inverting
 * that gives the row under the cursor.
 *
 * <p>Everything is recomputed from the same vanilla options ChatHud reads
 * rather than cached, so chat scale / width / line-spacing changes take effect
 * immediately.
 */
public final class ChatCopyOverlay {

    /** Chat sits this many scaled pixels above the bottom of the window. */
    private static final int OFFSET_FROM_BOTTOM = 40;

    private static final int HIGHLIGHT_COLOR = 0x1AFFFFFF;
    private static final int ACCENT_COLOR = 0xCCA78BFA;
    private static final int ICON_COLOR = 0xFFC9C9C9;
    private static final int ICON_COPIED_COLOR = 0xFF7BE38A;
    /** Painted behind the front sheet so it reads as overlapping the back one. */
    private static final int ICON_OCCLUDE_COLOR = 0xE0000000;

    /** How long the icon stays green after a copy. */
    private static final long COPIED_FLASH_MS = 700L;

    private static long copiedFlashUntil;

    private ChatCopyOverlay() {}

    /** Where the hovered message sits on screen, in real (unscaled) pixels. */
    private record Hovered(int xLeft, int xRight, int yTop, int yBottom, float scale) {}

    /** Draw the highlight + icon for the message under the cursor, if any. */
    public static void render(DrawContext ctx, int mouseX, int mouseY) {
        if (!isEnabled()) return;
        Hovered hovered = hoveredMessage(ctx.getScaledWindowHeight(), mouseX, mouseY);
        if (hovered == null) return;

        ctx.fill(hovered.xLeft(), hovered.yTop(), hovered.xRight(), hovered.yBottom(), HIGHLIGHT_COLOR);
        int accentW = Math.max(1, Math.round(hovered.scale()));
        ctx.fill(hovered.xLeft(), hovered.yTop(), hovered.xLeft() + accentW, hovered.yBottom(), ACCENT_COLOR);

        // The icon lives in the padding strip between the text column and the
        // background right edge - exactly 8 chat-pixels wide, so at chat scale
        // 1 an 8px icon fits it without ever covering message text.
        int size = MathHelper.clamp(Math.round(8f * hovered.scale()), 6, 12);
        int iconX = hovered.xRight() - Math.round(12f * hovered.scale());
        int iconY = (hovered.yTop() + hovered.yBottom()) / 2 - size / 2;
        boolean flashing = System.currentTimeMillis() < copiedFlashUntil;
        drawCopyGlyph(ctx, iconX, iconY, size, flashing ? ICON_COPIED_COLOR : ICON_COLOR);
    }

    /**
     * Called before the chat screen handles a click. Never swallows it, since
     * vanilla's own {@code ChatScreen#mouseClicked} does the actual copying -
     * it only arms the copied flash, and only when the click really landed on
     * a copyable style. Using vanilla's own hit-test here, rather than the row
     * geometry above, means an embedded {@code [brag]} / {@code [ah]} token,
     * which keeps its own click action, cannot flash a false confirm.
     */
    public static void onMouseClick(Click click) {
        if (!isEnabled()) return;
        if (click.button() != 0) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        // GLFW_MOD_SHIFT - shift-click is vanilla's "insert into the chat box"
        // gesture and does not copy.
        if ((click.modifiers() & 0x0001) != 0) return;

        DrawnTextConsumer.ClickHandler handler =
                new DrawnTextConsumer.ClickHandler(mc.textRenderer, (int) click.x(), (int) click.y());
        mc.inGameHud.getChatHud().render(
                handler, mc.getWindow().getScaledHeight(), mc.inGameHud.getTicks(), true);
        Style style = handler.getStyle();
        if (style != null && style.getClickEvent() instanceof ClickEvent.CopyToClipboard) {
            copiedFlashUntil = System.currentTimeMillis() + COPIED_FLASH_MS;
        }
    }

    /** Clear transient state so a flash cannot survive into the next session. */
    public static void reset() {
        copiedFlashUntil = 0L;
    }

    private static boolean isEnabled() {
        return ServerAllowlist.isAllowed() && FeatureToggles.isChatCopyEnabled();
    }

    /**
     * Resolve the cursor to a whole chat message and return its screen rect,
     * or null when the cursor is not over a copyable one.
     */
    private static Hovered hoveredMessage(int windowHeight, int mouseX, int mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.getChatVisibility().getValue() == ChatVisibility.HIDDEN) return null;

        ChatHud hud = mc.inGameHud.getChatHud();
        List<ChatHudLine.Visible> lines = ((ChatHudAccessor) hud).ancientsmod$visibleMessages();
        if (lines.isEmpty()) return null;

        float scale = mc.options.getChatScale().getValue().floatValue();
        if (scale <= 0f) return null;
        int lineH = (int) (9.0 * (mc.options.getChatLineSpacing().getValue() + 1.0));
        if (lineH <= 0) return null;

        int scrolled = ((ChatHudAccessor) hud).ancientsmod$scrolledLines();
        int drawnRows = Math.min(lines.size() - scrolled, hud.getVisibleLineCount());
        if (drawnRows <= 0) return null;

        int chatWidth = MathHelper.ceil(ChatHud.getWidth(mc.options.getChatWidth().getValue()) / scale);
        int baseline = MathHelper.floor((windowHeight - OFFSET_FROM_BOTTOM) / scale);

        double chatX = mouseX / scale - 4.0;
        double chatY = mouseY / scale;
        // The background spans chatX -4 .. chatWidth+8; outside it there is no row.
        if (chatX < -4.0 || chatX > chatWidth + 8.0) return null;

        int row = MathHelper.floor((baseline - chatY) / (double) lineH);
        if (row < 0 || row >= drawnRows) return null;

        int index = row + scrolled;
        if (index < 0 || index >= lines.size()) return null;
        if (findCopyText(lines.get(index).content()) == null) return null;

        // Walk out to the whole message: down to the endOfEntry row (its lowest
        // index, the bottom row on screen), then up over its wrapped rows.
        int low = index;
        while (low > 0 && !lines.get(low).endOfEntry()) low--;
        int high = low;
        while (high + 1 < lines.size() && !lines.get(high + 1).endOfEntry()) high++;

        int rowLow = MathHelper.clamp(low - scrolled, 0, drawnRows - 1);
        int rowHigh = MathHelper.clamp(high - scrolled, 0, drawnRows - 1);

        int yBottom = Math.round(scale * (baseline - rowLow * lineH));
        int yTop = Math.round(scale * (baseline - rowHigh * lineH - lineH));
        int xLeft = 0;                                    // chatX -4, plus the +4 translate
        int xRight = Math.round(scale * (chatWidth + 12));
        return new Hovered(xLeft, xRight, yTop, yBottom, scale);
    }

    /**
     * The clipboard payload carried by a laid-out chat row, or null if it has
     * none. The server puts the click event on the line ROOT, so every
     * character inherits it and the first one is enough - including on wrapped
     * continuation rows, which keep their per-character styles.
     */
    private static String findCopyText(OrderedText text) {
        String[] found = new String[1];
        text.accept((index, style, codePoint) -> {
            if (style.getClickEvent() instanceof ClickEvent.CopyToClipboard copy) {
                found[0] = copy.value();
                return false;
            }
            return true;
        });
        return found[0];
    }

    /** Two overlapping sheets - the usual copy glyph, drawn from fills so it
     *  stays crisp at any GUI scale and needs no texture. */
    private static void drawCopyGlyph(DrawContext ctx, int x, int y, int size, int color) {
        int backSize = size - 2;
        outline(ctx, x, y, x + backSize, y + backSize, color);
        ctx.fill(x + 2, y + 2, x + size, y + size, ICON_OCCLUDE_COLOR);
        outline(ctx, x + 2, y + 2, x + size, y + size, color);
    }

    private static void outline(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        ctx.fill(x1, y1, x2, y1 + 1, color);
        ctx.fill(x1, y2 - 1, x2, y2, color);
        ctx.fill(x1, y1, x1 + 1, y2, color);
        ctx.fill(x2 - 1, y1, x2, y2, color);
    }
}
