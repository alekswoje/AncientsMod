package com.aleks.ancientsmod.client.chat;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.client.glass.GlassTheme;
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
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Click-to-copy for chat, plus the hover affordance drawn on top of the open
 * chat screen.
 *
 * <p>Two sources of copy, one gesture. The server marks each player chat line
 * with a {@code copy_to_clipboard} click event carrying {@code <name>: <message>},
 * but only for players running this mod, and vanilla performs that copy itself.
 * Every other message (drop broadcasts, event announcements, system lines) has
 * no such marker, so {@link #onMouseClick} copies those: a left click on a
 * message, anywhere that has no click action of its own, puts the whole message
 * (every wrapped row) on the clipboard as plain text. Anything clickable keeps
 * its own action, so a player's name still starts a {@code /msg} and the
 * {@code [brag]} / {@code [ah]} tokens still run their commands.
 *
 * <p>The copy is always on, on the Ancients server. The Settings toggle only
 * controls the visual: the message under the cursor gets a soft warm
 * highlight, a thin Ember bar down its left edge, and a Candle copy icon (Moss
 * for a moment after a copy) in the chat box's right padding strip.
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

    // Fixed dark-mode tones: this always draws over vanilla's black chat
    // background, whatever the glass theme is set to.
    private static final int HIGHLIGHT_COLOR = 0x1AEADFCB;
    private static final int ACCENT_COLOR = GlassTheme.withAlpha(GlassTheme.ACCENT, 0xCC);
    private static final int ICON_COLOR = GlassTheme.ACCENT_SOFT;
    private static final int ICON_COPIED_COLOR = GlassTheme.OK;
    /** Painted behind the front sheet so it reads as overlapping the back one. */
    private static final int ICON_OCCLUDE_COLOR = 0xE0000000;

    /** How long the icon stays green after a copy. */
    private static final long COPIED_FLASH_MS = 700L;

    private static long copiedFlashUntil;

    private ChatCopyOverlay() {}

    /** Pack-font glyphs (badges, icons) render as boxes once pasted elsewhere. */
    private static final Pattern PRIVATE_USE =
            Pattern.compile("[\\uE000-\\uF8FF\\x{F0000}-\\x{10FFFD}]");
    private static final Pattern LEGACY_CODE =
            Pattern.compile("\u00a7[0-9a-fk-orx]", Pattern.CASE_INSENSITIVE);

    /**
     * Where the hovered message sits on screen, in real (unscaled) pixels, and
     * which rows of {@code visibleMessages} it spans ({@code low} is its
     * endOfEntry row).
     */
    private record Hovered(int xLeft, int xRight, int yTop, int yBottom, float scale, int low, int high) {}

    /** Draw the highlight + icon for the message under the cursor, if any. */
    public static void render(DrawContext ctx, int mouseX, int mouseY) {
        if (!ServerAllowlist.isAllowed() || !FeatureToggles.isChatCopyEnabled()) return;
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
     * Called before the chat screen handles a click. Never swallows it.
     *
     * <p>Uses vanilla's own hit-test first. If the click landed on a style with
     * a click event, vanilla runs it (a server copy, a name's {@code /msg}
     * suggestion, a {@code [brag]} command) and this only arms the copied flash
     * for a real copy. Otherwise the click is on plain message text or the
     * empty end of a row, and this copies the message itself.
     */
    public static void onMouseClick(Click click) {
        if (!ServerAllowlist.isAllowed()) return;
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
        if (style != null && style.getClickEvent() != null) {
            if (style.getClickEvent() instanceof ClickEvent.CopyToClipboard) {
                copiedFlashUntil = System.currentTimeMillis() + COPIED_FLASH_MS;
            }
            return;
        }

        Hovered hovered = hoveredMessage(mc.getWindow().getScaledHeight(), (int) click.x(), (int) click.y());
        if (hovered == null) return;
        String text = messageCopyText(mc.inGameHud.getChatHud(), hovered.low(), hovered.high());
        if (text == null || text.isEmpty()) return;
        mc.keyboard.setClipboard(text);
        copiedFlashUntil = System.currentTimeMillis() + COPIED_FLASH_MS;
    }

    /**
     * What a click on the message spanning visible rows {@code low..high}
     * copies. A player chat line keeps the server's {@code <name>: <message>}
     * payload, so a click on its empty row end copies the same thing as a
     * click on its text. Anything else copies the message as plain text.
     */
    private static String messageCopyText(ChatHud hud, int low, int high) {
        List<ChatHudLine.Visible> lines = ((ChatHudAccessor) hud).ancientsmod$visibleMessages();
        for (int i = low; i <= high; i++) {
            String marked = findCopyText(lines.get(i).content());
            if (marked != null) return marked;
        }

        String raw = sourceMessageText(hud, lines, low, high);
        if (raw == null) {
            // Rows read top to bottom are high..low. Wrapping eats the space it
            // broke at, so join with one and let clean() collapse any doubles.
            StringBuilder sb = new StringBuilder();
            for (int i = high; i >= low; i--) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(plain(lines.get(i).content()));
            }
            raw = sb.toString();
        }
        return clean(raw);
    }

    /**
     * The unwrapped text of the message whose endOfEntry row is {@code low},
     * read from {@code ChatHud.messages} so newlines and the spaces wrapping
     * removed survive. Returns null if the two lists do not line up (an empty
     * message laid out to zero rows would shift the count), so the caller
     * falls back to the rows themselves rather than copy the wrong message.
     */
    private static String sourceMessageText(ChatHud hud, List<ChatHudLine.Visible> lines, int low, int high) {
        List<ChatHudLine> messages = ((ChatHudAccessor) hud).ancientsmod$messages();
        int ordinal = -1;
        for (int i = 0; i <= low; i++) {
            if (lines.get(i).endOfEntry()) ordinal++;
        }
        if (ordinal < 0 || ordinal >= messages.size()) return null;

        Text content = messages.get(ordinal).content();
        StringBuilder rows = new StringBuilder();
        for (int i = high; i >= low; i--) rows.append(plain(lines.get(i).content()));
        // The rows were laid out from the emoji-expanded text and the stored
        // message was not, so compare like with like.
        String laidOut = EmojiChatText.expand(content).getString();
        if (!stripWhitespace(laidOut).equals(stripWhitespace(rows.toString()))) return null;
        return content.getString();
    }

    private static String plain(OrderedText text) {
        StringBuilder sb = new StringBuilder();
        text.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.toString();
    }

    private static String stripWhitespace(String s) {
        return s.replaceAll("\\s+", "");
    }

    /** Drop pack glyphs and stray colour codes, tidy spacing, keep line breaks. */
    private static String clean(String raw) {
        String s = PRIVATE_USE.matcher(raw).replaceAll("");
        s = LEGACY_CODE.matcher(s).replaceAll("");
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\\R")) {
            String t = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
            if (t.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(t);
        }
        return out.toString();
    }

    /** Clear transient state so a flash cannot survive into the next session. */
    public static void reset() {
        copiedFlashUntil = 0L;
    }

    /**
     * Resolve the cursor to a whole chat message and return its screen rect,
     * or null when the cursor is not over one.
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
        return new Hovered(xLeft, xRight, yTop, yBottom, scale, low, high);
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
