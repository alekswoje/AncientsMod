package com.aleks.prisonsmod.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Lunar Client-style HUD editor.
 *
 * <p>Drag any widget with the left mouse button. Hold and release moves the
 * widget; click without drag selects it for keyboard fine-adjustment:
 * <ul>
 *   <li>arrow keys: nudge by 1 px (shift = 10 px)</li>
 *   <li>{@code +} / {@code -}: scale up/down by 0.05</li>
 *   <li>{@code R}: reset selected widget to defaults</li>
 *   <li>{@code Esc} / Done: save and close</li>
 * </ul>
 *
 * <p>The editor draws every registered widget — including hidden ones with a
 * placeholder — so the player can position a widget before it's relevant
 * (e.g. position the Boosters panel even when no boosters are active).
 *
 * <p>Snap targets while dragging: screen edges, screen center (vertical and
 * horizontal axes), and the edges/centers of other widgets. Within
 * {@link #SNAP_THRESHOLD_PX} the drag origin sticks to the nearest target.
 */
public final class HudEditScreen extends Screen {

    private static final int SNAP_THRESHOLD_PX = 4;
    private static final int OUTLINE_COLOR_IDLE      = 0x66FFFFFF;
    private static final int OUTLINE_COLOR_HOVER     = 0xAA8FA3FF;
    private static final int OUTLINE_COLOR_SELECTED  = 0xFFFFC857;
    private static final int FILL_COLOR_INVISIBLE    = 0x33000000;
    private static final int SNAP_GUIDE_COLOR        = 0xFFFFC857;
    private static final int LABEL_COLOR             = 0xFFE0E0E0;

    /** Half-size of the corner resize-handle (handle is 2*HANDLE_R + 1 px square). */
    private static final int HANDLE_R = 3;

    private final Screen parent;

    private HudElement selected;
    private HudElement dragging;
    private HudElement resizing;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private int dragStartElementX;
    private int dragStartElementY;
    private int resizeStartWidth;
    private float resizeStartScale;
    private int snapGuideX = -1;
    private int snapGuideY = -1;

    public HudEditScreen(Screen parent) {
        super(Text.literal("Edit HUD"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int btnY = this.height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(this.width / 2 + 4, btnY, 100, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset All"), b -> {
            HudPositions.resetAll();
            selected = null;
        }).dimensions(this.width / 2 - 104, btnY, 100, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Subtle dim so the HUD widgets stand out without obscuring the world.
        ctx.fill(0, 0, this.width, this.height, 0x66000000);
        super.render(ctx, mouseX, mouseY, delta);

        MinecraftClient mc = this.client;
        if (mc == null) return;

        // Header text.
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Drag to move · drag corner to resize · arrows: nudge · R: reset"),
                this.width / 2, 20, 0xAAAAAA);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Right-click a widget to open its settings"),
                this.width / 2, 32, 0xFFFFC857);

        // Snap guides under the elements so a guide line draws across the whole screen.
        if (snapGuideX >= 0) {
            ctx.fill(snapGuideX, 0, snapGuideX + 1, this.height, SNAP_GUIDE_COLOR);
        }
        if (snapGuideY >= 0) {
            ctx.fill(0, snapGuideY, this.width, snapGuideY + 1, SNAP_GUIDE_COLOR);
        }

        // Render each widget through the same path as live HUD rendering so
        // positions look identical to gameplay. Wrap with an outline + label.
        for (HudElement el : HudRegistry.all()) {
            int x = HudPositions.getX(el, this.width);
            int y = HudPositions.getY(el, this.height);
            float scale = HudPositions.getScale(el);
            int w = Math.max(1, Math.round(el.width() * scale));
            int h = Math.max(1, Math.round(el.height() * scale));

            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            boolean isSelected = el == selected;

            if (el.isVisible()) {
                HudRenderer.drawElement(ctx, mc, el, this.width, this.height, delta);
            } else {
                // Placeholder fill + label so an empty widget still has a
                // grabbable footprint in the editor.
                ctx.fill(x, y, x + w, y + h, FILL_COLOR_INVISIBLE);
                String placeholder = el.editorPlaceholder() != null ? el.editorPlaceholder() : el.displayName();
                int textW = this.textRenderer.getWidth(placeholder);
                ctx.drawText(this.textRenderer, placeholder,
                        x + (w - textW) / 2, y + (h - this.textRenderer.fontHeight) / 2,
                        LABEL_COLOR, true);
            }

            int outlineColor = isSelected ? OUTLINE_COLOR_SELECTED
                    : hover ? OUTLINE_COLOR_HOVER : OUTLINE_COLOR_IDLE;
            drawOutline(ctx, x, y, w, h, outlineColor);

            if (isSelected) {
                String tag = String.format("%s  %dx%d  %.2fx", el.displayName(), x, y, scale);
                int tagW = this.textRenderer.getWidth(tag);
                int tagY = y - this.textRenderer.fontHeight - 3;
                if (tagY < 0) tagY = y + h + 3;
                ctx.fill(x - 2, tagY - 1, x + tagW + 2, tagY + this.textRenderer.fontHeight + 1, 0xCC000000);
                ctx.drawText(this.textRenderer, tag, x, tagY, OUTLINE_COLOR_SELECTED, false);

                // Bottom-right resize handle.
                int hx = x + w - 1;
                int hy = y + h - 1;
                boolean handleHover = mouseX >= hx - HANDLE_R && mouseX <= hx + HANDLE_R
                        && mouseY >= hy - HANDLE_R && mouseY <= hy + HANDLE_R;
                int handleColor = (resizing == el || handleHover) ? 0xFFFFFFFF : OUTLINE_COLOR_SELECTED;
                ctx.fill(hx - HANDLE_R, hy - HANDLE_R, hx + HANDLE_R + 1, hy + HANDLE_R + 1, handleColor);
            }
        }
    }

    private static void drawOutline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);            // top
        ctx.fill(x, y + h - 1, x + w, y + h, color);    // bottom
        ctx.fill(x, y, x + 1, y + h, color);            // left
        ctx.fill(x + w - 1, y, x + w, y + h, color);    // right
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;
        int mouseX = (int) click.x();
        int mouseY = (int) click.y();

        // Left-click on the resize handle of the currently-selected widget starts a resize.
        if (click.button() == 0 && selected != null && isOverResizeHandle(selected, mouseX, mouseY)) {
            resizing = selected;
            float scale = HudPositions.getScale(selected);
            resizeStartScale = scale;
            resizeStartWidth = Math.max(1, Math.round(selected.width() * scale));
            dragStartMouseX = mouseX;
            dragStartMouseY = mouseY;
            return true;
        }

        HudElement hit = elementAt(mouseX, mouseY);

        // Right-click on a widget body opens its settings (matches Lunar).
        if (click.button() == 1 && hit != null) {
            selected = hit;
            Screen settings = hit.openSettings(this);
            if (settings != null && this.client != null) {
                this.client.setScreen(settings);
            }
            return true;
        }

        if (click.button() != 0) return false;

        selected = hit;
        if (hit != null) {
            dragging = hit;
            dragStartMouseX = mouseX;
            dragStartMouseY = mouseY;
            dragStartElementX = HudPositions.getX(hit, this.width);
            dragStartElementY = HudPositions.getY(hit, this.height);
        }
        return true;
    }

    private boolean isOverResizeHandle(HudElement el, int mouseX, int mouseY) {
        float scale = HudPositions.getScale(el);
        int x = HudPositions.getX(el, this.width);
        int y = HudPositions.getY(el, this.height);
        int w = Math.max(1, Math.round(el.width() * scale));
        int h = Math.max(1, Math.round(el.height() * scale));
        int hx = x + w - 1;
        int hy = y + h - 1;
        return mouseX >= hx - HANDLE_R && mouseX <= hx + HANDLE_R
            && mouseY >= hy - HANDLE_R && mouseY <= hy + HANDLE_R;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        // Resize takes precedence over move when both could fire on the same frame.
        if (resizing != null) {
            int mouseX = (int) click.x();
            int totalDx = mouseX - dragStartMouseX;
            // Drag right grows, drag left shrinks. Scale change is proportional
            // to how far the mouse moved relative to the starting widget width.
            float newScale = resizeStartScale * (resizeStartWidth + totalDx) / (float) resizeStartWidth;
            // Snap to 0.05 increments + clamp to the same range HudPositions enforces.
            newScale = Math.round(newScale * 20.0f) / 20.0f;
            newScale = Math.max(0.5f, Math.min(2.0f, newScale));
            HudPositions.setScale(resizing, newScale);
            return true;
        }
        if (dragging == null) return super.mouseDragged(click, offsetX, offsetY);
        int mouseX = (int) click.x();
        int mouseY = (int) click.y();
        int newX = dragStartElementX + (mouseX - dragStartMouseX);
        int newY = dragStartElementY + (mouseY - dragStartMouseY);

        float scale = HudPositions.getScale(dragging);
        int w = Math.max(1, Math.round(dragging.width() * scale));
        int h = Math.max(1, Math.round(dragging.height() * scale));

        SnapResult snap = computeSnap(dragging, newX, newY, w, h);
        HudPositions.setX(dragging, snap.x);
        HudPositions.setY(dragging, snap.y);
        snapGuideX = snap.guideX;
        snapGuideY = snap.guideY;
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (resizing != null) {
            resizing = null;
            HudPositions.save();
        }
        if (dragging != null) {
            dragging = null;
            snapGuideX = -1;
            snapGuideY = -1;
            HudPositions.save();
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (selected != null) {
            int step = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;
            switch (input.key()) {
                case GLFW.GLFW_KEY_LEFT  -> { HudPositions.setX(selected, HudPositions.getX(selected, this.width) - step); HudPositions.save(); return true; }
                case GLFW.GLFW_KEY_RIGHT -> { HudPositions.setX(selected, HudPositions.getX(selected, this.width) + step); HudPositions.save(); return true; }
                case GLFW.GLFW_KEY_UP    -> { HudPositions.setY(selected, HudPositions.getY(selected, this.height) - step); HudPositions.save(); return true; }
                case GLFW.GLFW_KEY_DOWN  -> { HudPositions.setY(selected, HudPositions.getY(selected, this.height) + step); HudPositions.save(); return true; }
                case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
                    HudPositions.setScale(selected, HudPositions.getScale(selected) + 0.05f);
                    HudPositions.save();
                    return true;
                }
                case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> {
                    HudPositions.setScale(selected, HudPositions.getScale(selected) - 0.05f);
                    HudPositions.save();
                    return true;
                }
                case GLFW.GLFW_KEY_R -> {
                    HudPositions.reset(selected);
                    HudPositions.save();
                    return true;
                }
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false; // single-player smooth-paused while editing is fine, but multiplayer keeps the world rendering.
    }

    @Override
    public void close() {
        HudPositions.save();
        if (this.client != null) this.client.setScreen(parent);
    }

    private HudElement elementAt(int mouseX, int mouseY) {
        // Iterate in reverse so a widget rendered on top wins the click test
        // when widgets overlap.
        var list = HudRegistry.all();
        for (int i = list.size() - 1; i >= 0; i--) {
            HudElement el = list.get(i);
            float scale = HudPositions.getScale(el);
            int x = HudPositions.getX(el, this.width);
            int y = HudPositions.getY(el, this.height);
            int w = Math.max(1, Math.round(el.width() * scale));
            int h = Math.max(1, Math.round(el.height() * scale));
            if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
                return el;
            }
        }
        return null;
    }

    private SnapResult computeSnap(HudElement target, int x, int y, int w, int h) {
        int bestX = x, bestY = y;
        int gx = -1, gy = -1;
        int bestXDist = SNAP_THRESHOLD_PX + 1;
        int bestYDist = SNAP_THRESHOLD_PX + 1;

        // Screen edges + center for X.
        int[] xCandidates = {
                0, this.width / 2 - w / 2, this.width - w,
                this.width / 2, this.width / 2 - w
        };
        int[] xGuides = {
                0, this.width / 2, this.width - 1,
                this.width / 2, this.width / 2
        };
        for (int i = 0; i < xCandidates.length; i++) {
            int d = Math.abs(x - xCandidates[i]);
            if (d < bestXDist) {
                bestXDist = d;
                bestX = xCandidates[i];
                gx = xGuides[i];
            }
        }

        // Screen edges + center for Y.
        int[] yCandidates = {
                0, this.height / 2 - h / 2, this.height - h,
                this.height / 2, this.height / 2 - h
        };
        int[] yGuides = {
                0, this.height / 2, this.height - 1,
                this.height / 2, this.height / 2
        };
        for (int i = 0; i < yCandidates.length; i++) {
            int d = Math.abs(y - yCandidates[i]);
            if (d < bestYDist) {
                bestYDist = d;
                bestY = yCandidates[i];
                gy = yGuides[i];
            }
        }

        // Snap to other elements' edges (left/right/top/bottom + centers).
        for (HudElement other : HudRegistry.all()) {
            if (other == target) continue;
            float oScale = HudPositions.getScale(other);
            int ox = HudPositions.getX(other, this.width);
            int oy = HudPositions.getY(other, this.height);
            int ow = Math.max(1, Math.round(other.width() * oScale));
            int oh = Math.max(1, Math.round(other.height() * oScale));
            int[] otherX = { ox, ox + ow - w, ox + ow / 2 - w / 2 };
            int[] otherXGuide = { ox, ox + ow, ox + ow / 2 };
            for (int i = 0; i < otherX.length; i++) {
                int d = Math.abs(x - otherX[i]);
                if (d < bestXDist) {
                    bestXDist = d;
                    bestX = otherX[i];
                    gx = otherXGuide[i];
                }
            }
            int[] otherY = { oy, oy + oh - h, oy + oh / 2 - h / 2 };
            int[] otherYGuide = { oy, oy + oh, oy + oh / 2 };
            for (int i = 0; i < otherY.length; i++) {
                int d = Math.abs(y - otherY[i]);
                if (d < bestYDist) {
                    bestYDist = d;
                    bestY = otherY[i];
                    gy = otherYGuide[i];
                }
            }
        }

        return new SnapResult(bestX, bestY, gx, gy);
    }

    private record SnapResult(int x, int y, int guideX, int guideY) {}
}
