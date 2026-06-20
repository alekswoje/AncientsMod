package com.aleks.prisonsmod.client.wiki;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The in-mod wiki's interactive-tooltip layer.
 *
 * <p>While you hold <b>Alt</b> over a wired-up item (any armor trim), its
 * Trim), its tooltip <i>pins</i> in place so you can move the cursor off the
 * item and onto the tooltip itself — vanilla tooltips normally follow the mouse
 * and vanish the instant you leave the slot. Recognised terms (the "more" /
 * "less" multipliers, "Max HP", the Full Set Ability, the per-piece note) are
 * underlined; clicking one opens a compact wiki popup explaining it. Esc or a
 * click outside closes the popup.
 *
 * <h2>How the pin works</h2>
 * <ol>
 *   <li>{@link ItemTooltipCallback} fires while vanilla builds the hovered
 *       item's tooltip. If it's a wired item and Alt is down, we snapshot the
 *       lines + resolved {@link WikiLink}s and flag {@code hoveredThisFrame}.</li>
 *   <li>In {@link ScreenEvents#afterRender} we <i>latch</i> the pin: freeze the
 *       snapshot and compute a fixed anchor near the cursor.</li>
 *   <li>{@code HandledScreenTooltipSuppressMixin} cancels vanilla's hovered
 *       tooltip while pinned, and we redraw the frozen tooltip ourselves with
 *       authentic vanilla chrome ({@link DrawContext#drawTooltipImmediately})
 *       plus our underline/hover overlays — so the cursor can roam over other
 *       slots without spawning competing tooltips.</li>
 *   <li>Releasing Alt unpins. Click geometry is ours, so the underline and
 *       hit-box always match the glyphs (style-aware width, bold-safe).</li>
 * </ol>
 *
 * <p>Strictly cosmetic and gated on {@link ServerAllowlist} + the
 * {@code itemWiki} toggle, like every other PrisonsMod display feature.
 */
public final class InteractiveItemTooltip {

    /** Vanilla text-line height inside a tooltip (font height + 1). */
    private static final int LINE_H = 10;
    /** Vanilla inserts a 2px gap after the first (title) line. */
    private static final int TITLE_GAP = 2;

    /** Returns the cursor-relative position verbatim — we place the pinned tooltip ourselves. */
    private static final TooltipPositioner FIXED_POSITIONER = new TooltipPositioner() {
        @Override
        public Vector2ic getPosition(int screenWidth, int screenHeight, int x, int y, int width, int height) {
            return new Vector2i(x, y);
        }
    };

    // ── Per-frame capture from the tooltip callback ──────────────────────────
    private static boolean hoveredThisFrame = false;
    private static List<Text> pendingLines = null;
    private static List<WikiLink> pendingLinks = null;

    // ── Pinned state (Alt held over a wired item) ──────────────────────────
    private static boolean pinned = false;
    private static List<Text> pinnedLines = null;
    private static List<WikiLink> pinnedLinks = null;
    private static int anchorX, anchorY;
    private static final List<SpanBox> spanBoxes = new ArrayList<>();
    // Vertical scroll for a pinned tooltip taller than the screen — Alt held, scroll wheel pans it.
    private static int pinnedScroll = 0;
    private static int pinnedRenderY = 0;
    private static boolean pinnedOversized = false;
    private static final int SCROLL_STEP_PX = 12;

    // ── Wiki popup state ─────────────────────────────────────────────────────
    private static String openEntryId = null;
    private static int popupX, popupY, popupW, popupH;

    private record SpanBox(int x1, int y1, int x2, int y2, String entryId) {
        boolean contains(double mx, double my) {
            return mx >= x1 && mx < x2 && my >= y1 && my < y2;
        }
    }

    private record Run(String text, Style style) {}

    public static void register() {
        // Snapshot a wired item's tooltip while Alt is held, before we pin.
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (!ServerAllowlist.isAllowed()) return;
            if (!FeatureToggles.isItemWikiEnabled()) return;
            if (!isAltDown()) return;
            if (stack == null || stack.isEmpty()) return;
            List<WikiLink> links = WikiLinkResolver.resolve(lines);
            if (links.isEmpty()) return;
            pendingLines = new ArrayList<>(lines);
            pendingLinks = links;
            hoveredThisFrame = true;
        });

        // Per-screen render + input hooks. Reset on (re)init so a stale pin/popup
        // never leaks across screens.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            reset();
            ScreenEvents.afterRender(screen).register(InteractiveItemTooltip::onAfterRender);
            ScreenMouseEvents.allowMouseClick(screen).register(InteractiveItemTooltip::onAllowMouseClick);
            ScreenKeyboardEvents.allowKeyPress(screen).register(InteractiveItemTooltip::onAllowKeyPress);
            ScreenMouseEvents.allowMouseScroll(screen).register(InteractiveItemTooltip::onAllowMouseScroll);
        });
    }

    /** True while we're rendering our own pinned tooltip / popup — read by the suppress mixin. */
    public static boolean isSuppressingVanillaTooltip() {
        return pinned || openEntryId != null;
    }

    public static void reset() {
        pinned = false;
        pinnedLines = null;
        pinnedLinks = null;
        spanBoxes.clear();
        openEntryId = null;
        pendingLines = null;
        pendingLinks = null;
        hoveredThisFrame = false;
        pinnedScroll = 0;
        pinnedOversized = false;
    }

    // ── Render ───────────────────────────────────────────────────────────────

    private static void onAfterRender(Screen screen, DrawContext context, int mouseX, int mouseY, float tickDelta) {
        if (!ServerAllowlist.isAllowed() || !FeatureToggles.isItemWikiEnabled()) {
            pinned = false;
            openEntryId = null;
            hoveredThisFrame = false;
            return;
        }
        boolean alt = isAltDown();

        // Latch the pin the first frame Alt is held over a freshly-snapshotted item.
        if (!pinned && openEntryId == null && alt && hoveredThisFrame
                && pendingLines != null && pendingLinks != null && !pendingLinks.isEmpty()) {
            pinned = true;
            pinnedLines = pendingLines;
            pinnedLinks = pendingLinks;
            computeAnchor(mouseX, mouseY, screen);
            pinnedScroll = 0;
        }

        // Releasing Alt unpins (an open popup stays until dismissed).
        if (pinned && !alt) {
            pinned = false;
            pinnedLines = null;
            pinnedLinks = null;
            spanBoxes.clear();
            pinnedScroll = 0;
            pinnedOversized = false;
        }

        if (openEntryId != null) {
            renderPopup(context, screen);
        } else if (pinned && pinnedLines != null) {
            renderPinnedTooltip(context, mouseX, mouseY, screen.height);
        }

        hoveredThisFrame = false;
    }

    private static void renderPinnedTooltip(DrawContext ctx, int mouseX, int mouseY, int screenH) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        // If the pinned tooltip is taller than the screen, let the wheel pan it between
        // top-aligned and bottom-aligned; otherwise keep it at its frozen anchor.
        int h = tooltipHeight(pinnedLines.size());
        pinnedOversized = h > screenH - 12;
        int eff = anchorY;
        if (pinnedOversized) {
            int top = 6;
            int bottom = screenH - 6 - h; // negative — top spills above the screen
            eff = anchorY + pinnedScroll;
            if (eff > top) eff = top;
            if (eff < bottom) eff = bottom;
            pinnedScroll = eff - anchorY; // keep the stored offset clamped
        } else {
            pinnedScroll = 0;
        }
        pinnedRenderY = eff;

        // Authentic vanilla background + text at our frozen (scrolled) anchor.
        List<TooltipComponent> comps = new ArrayList<>(pinnedLines.size());
        for (Text line : pinnedLines) comps.add(TooltipComponent.of(line.asOrderedText()));
        ctx.drawTooltipImmediately(tr, comps, anchorX, eff, FIXED_POSITIONER, null);

        // Compute hit-boxes and draw underline + hover highlight on top.
        spanBoxes.clear();
        for (WikiLink link : pinnedLinks) {
            int li = link.lineIndex();
            if (li < 0 || li >= pinnedLines.size()) continue;
            Text line = pinnedLines.get(li);
            int lineY = (li == 0) ? eff : eff + LINE_H * li + TITLE_GAP;
            int x1 = anchorX + styledWidth(tr, line, link.startCol());
            int x2 = anchorX + styledWidth(tr, line, link.endCol());
            if (x2 <= x1) continue;
            int y2 = lineY + LINE_H;
            spanBoxes.add(new SpanBox(x1, lineY, x2, y2, link.entryId()));

            boolean hover = mouseX >= x1 && mouseX < x2 && mouseY >= lineY && mouseY < y2;
            if (hover) ctx.fill(x1 - 1, lineY - 1, x2 + 1, y2, 0x33FFFFFF);
            int underline = hover ? 0xFFFFFFFF : 0xFF7FB0FF;
            ctx.fill(x1, lineY + LINE_H - 1, x2, lineY + LINE_H, underline);
        }
    }

    private static void renderPopup(DrawContext ctx, Screen screen) {
        WikiEntry entry = WikiRegistry.get(openEntryId);
        if (entry == null) {
            openEntryId = null;
            return;
        }
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        final int pad = 8;
        final int maxTextW = 220;

        List<OrderedText> body = new ArrayList<>();
        List<String> paragraphs = entry.paragraphs();
        for (int p = 0; p < paragraphs.size(); p++) {
            if (p > 0) body.add(OrderedText.EMPTY);
            body.addAll(tr.wrapLines(Text.literal(paragraphs.get(p)), maxTextW));
        }

        MutableText title = Text.literal(entry.title()).formatted(Formatting.BOLD);
        int contentW = Math.max(tr.getWidth(title), maxWidth(tr, body));
        contentW = Math.min(contentW, maxTextW);

        int titleBlock = LINE_H + 5;   // title line + gap below the divider
        int bodyBlock = body.size() * LINE_H;
        int footerBlock = LINE_H + 4;  // gap + close hint
        int panelW = contentW + pad * 2;
        int panelH = pad * 2 + titleBlock + bodyBlock + footerBlock;

        int x = (screen.width - panelW) / 2;
        int y = (screen.height - panelH) / 2;
        if (x < 6) x = 6;
        if (y < 6) y = 6;
        popupX = x;
        popupY = y;
        popupW = panelW;
        popupH = panelH;

        ctx.fill(x, y, x + panelW, y + panelH, 0xF00E0814);
        drawBorder(ctx, x, y, panelW, panelH, 0xFF7A57C8);

        int tx = x + pad;
        int ty = y + pad;
        ctx.drawText(tr, title, tx, ty, 0xFFE6D8FF, true);
        ctx.fill(tx, ty + LINE_H + 1, x + panelW - pad, ty + LINE_H + 2, 0x40FFFFFF);

        int by = ty + titleBlock;
        for (OrderedText ot : body) {
            ctx.drawText(tr, ot, tx, by, 0xFFCFC9D6, false);
            by += LINE_H;
        }
        ctx.drawText(tr, Text.literal("[Esc] or click outside to close"),
                tx, y + panelH - pad - LINE_H + 2, 0xFF6A6480, false);
    }

    // ── Input ────────────────────────────────────────────────────────────────

    private static boolean onAllowMouseClick(Screen screen, Click click) {
        if (!ServerAllowlist.isAllowed() || !FeatureToggles.isItemWikiEnabled()) return true;
        double mx = click.x();
        double my = click.y();

        if (openEntryId != null) {
            boolean inside = mx >= popupX && mx < popupX + popupW && my >= popupY && my < popupY + popupH;
            if (!inside) openEntryId = null; // click outside dismisses
            return false;                    // consume the click either way
        }

        if (pinned && click.button() == 0) {
            for (SpanBox b : spanBoxes) {
                if (b.contains(mx, my)) {
                    openEntryId = b.entryId();
                    return false;
                }
            }
            if (overPinnedTooltip(mx, my)) return false; // swallow clicks on the tooltip body
        }
        return true;
    }

    private static boolean onAllowKeyPress(Screen screen, KeyInput input) {
        if (openEntryId != null && input.key() == GLFW.GLFW_KEY_ESCAPE) {
            openEntryId = null;
            return false; // consume so the screen itself doesn't close
        }
        return true;
    }

    private static boolean onAllowMouseScroll(Screen screen, double mouseX, double mouseY,
                                              double horizontalAmount, double verticalAmount) {
        if (!ServerAllowlist.isAllowed() || !FeatureToggles.isItemWikiEnabled()) return true;
        // Only steal the wheel for a tall pinned tooltip (no popup open). Otherwise let it pass
        // so normal scrollable-tooltips and container scrolling keep working.
        if (!pinned || openEntryId != null || !pinnedOversized) return true;
        pinnedScroll += (int) Math.round(verticalAmount * SCROLL_STEP_PX);
        return false; // consume — pan the pinned tooltip instead of the screen
    }

    // ── Geometry helpers ─────────────────────────────────────────────────────

    private static void computeAnchor(int mouseX, int mouseY, Screen screen) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int w = 0;
        for (Text line : pinnedLines) w = Math.max(w, tr.getWidth(line));
        int h = tooltipHeight(pinnedLines.size());

        int x = mouseX + 12;
        if (x + w > screen.width - 6) x = mouseX - 16 - w;
        if (x < 6) x = 6;
        int y = mouseY - 12;
        if (y + h > screen.height - 6) y = screen.height - 6 - h;
        if (y < 6) y = 6;
        anchorX = x;
        anchorY = y;
    }

    private static boolean overPinnedTooltip(double mx, double my) {
        if (!pinned || pinnedLines == null) return false;
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int w = 0;
        for (Text line : pinnedLines) w = Math.max(w, tr.getWidth(line));
        int h = tooltipHeight(pinnedLines.size());
        return mx >= anchorX - 4 && mx < anchorX + w + 4 && my >= pinnedRenderY - 4 && my < pinnedRenderY + h + 4;
    }

    private static int tooltipHeight(int lineCount) {
        return lineCount <= 0 ? 0 : lineCount * LINE_H + TITLE_GAP;
    }

    /** Pixel width of the first {@code col} plain characters of {@code line}, honouring per-run styles (bold-safe). */
    private static int styledWidth(TextRenderer tr, Text line, int col) {
        if (col <= 0) return 0;
        MutableText prefix = Text.empty();
        int seen = 0;
        for (Run run : flatten(line)) {
            if (seen >= col) break;
            int take = Math.min(run.text().length(), col - seen);
            prefix.append(Text.literal(run.text().substring(0, take)).setStyle(run.style()));
            seen += take;
        }
        return tr.getWidth(prefix);
    }

    private static List<Run> flatten(Text line) {
        List<Run> runs = new ArrayList<>();
        line.visit((style, str) -> {
            if (!str.isEmpty()) runs.add(new Run(str, style));
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    private static int maxWidth(TextRenderer tr, List<OrderedText> lines) {
        int w = 0;
        for (OrderedText line : lines) w = Math.max(w, tr.getWidth(line));
        return w;
    }

    private static void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static boolean isAltDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return false;
        Window window = client.getWindow();
        if (window == null) return false;
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    private InteractiveItemTooltip() {}
}
