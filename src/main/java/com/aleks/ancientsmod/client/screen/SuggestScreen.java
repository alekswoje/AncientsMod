package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTextField;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.client.suggest.SuggestClient;
import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;

/**
 * In-game suggestion screen. Single-column compact dialog on one flat glass panel:
 * <ol>
 *   <li>Header: ember title over a bronze rule</li>
 *   <li>Mod / Server category picker (two segments, the chosen one ember-tinted)</li>
 *   <li>Description text field, with any server error in Cinder below it</li>
 *   <li>Footer: status hint on the left, Cancel then Submit on the right</li>
 * </ol>
 *
 * <p>All actions route through {@link SuggestClient} so the state machine stays
 * coherent. ESC fires {@code Close} to free the server-side session token.
 */
public final class SuggestScreen extends Screen {

    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 156;
    private static final int PAD = 10;
    private static final int HEADER_H = 24;
    private static final int FOOTER_H = 28;
    private static final int BTN_H = 16;

    // Content rows, relative to the panel top.
    private static final int CAT_LABEL_Y = 34;
    private static final int CAT_Y = 46;
    private static final int BODY_LABEL_Y = 70;
    private static final int BODY_Y = 82;
    private static final int ERROR_Y = 106;

    private byte category = Protocol.SUGGEST_CAT_SERVER; // default to server-side suggestions
    private GlassTextField bodyField;
    private GlassButton submitButton;
    private GlassButton cancelButton;

    public SuggestScreen() {
        super(Text.literal("Suggest"));
    }

    private int panelX() { return (this.width - PANEL_WIDTH) / 2; }
    private int panelY() { return (this.height - PANEL_HEIGHT) / 2; }

    @Override
    protected void init() {
        int panelX = panelX();
        int panelY = panelY();
        int contentX = panelX + PAD;
        int contentW = PANEL_WIDTH - 2 * PAD;

        int catY = panelY + CAT_Y;
        int catW = (contentW - 6) / 2;
        this.addDrawableChild(new CategorySegment(contentX, catY, catW, BTN_H, "Mod",
                () -> category == Protocol.SUGGEST_CAT_MOD, () -> category = Protocol.SUGGEST_CAT_MOD));
        this.addDrawableChild(new CategorySegment(contentX + catW + 6, catY, catW, BTN_H, "Server",
                () -> category == Protocol.SUGGEST_CAT_SERVER, () -> category = Protocol.SUGGEST_CAT_SERVER));

        bodyField = new GlassTextField(this.textRenderer, contentX, panelY + BODY_Y, contentW, 18,
                Text.literal("Your suggestion"));
        bodyField.setMaxLength(Protocol.SUGGEST_MAX_BODY_CHARS);
        bodyField.setPlaceholder(Text.literal("Describe your suggestion…"));
        this.addDrawableChild(bodyField);
        this.setInitialFocus(bodyField);

        // Confirm-right rule: Cancel, then Submit (.primary()) on the far RIGHT of the footer.
        int btnY = panelY + PANEL_HEIGHT - FOOTER_H + (FOOTER_H - BTN_H) / 2;
        int right = panelX + PANEL_WIDTH - PAD;
        int submitW = 64, cancelW = 56;
        submitButton = new GlassButton(right - submitW, btnY, submitW, BTN_H,
                Text.literal("Submit"), this::doSubmit).primary();
        cancelButton = new GlassButton(right - submitW - 6 - cancelW, btnY, cancelW, BTN_H,
                Text.literal("Cancel"), () -> {
                    SuggestClient.close();
                    this.close();
                });
        this.addDrawableChild(submitButton);
        this.addDrawableChild(cancelButton);
    }

    private void doSubmit() {
        if (SuggestClient.currentState() != SuggestClient.State.OPEN) return;
        String body = bodyField.getText() == null ? "" : bodyField.getText().trim();
        if (body.isEmpty()) return;
        SuggestClient.submit(category, body);
    }

    @Override
    public void tick() {
        super.tick();
        // Only the Submit button + body field gate on state; the category segments
        // stay clickable so the selection stays visually obvious.
        boolean canEdit = SuggestClient.currentState() == SuggestClient.State.OPEN;
        submitButton.active = canEdit;
        bodyField.setEditable(canEdit);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);

        int panelX = panelX();
        int panelY = panelY();

        // Real blurred backdrop + scrim behind the dialog (once, before the panel).
        GlassRender.menuBackdrop(ctx, this.width, this.height);

        // Glass panel, drawn here (in renderBackground) so the buttons + text field
        // render ON TOP of it instead of being dimmed by it.
        GlassRender.panel(ctx, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);

        // Header: ember title over a bronze rule.
        ctx.drawText(this.textRenderer, Text.literal("Suggest a change"),
                panelX + PAD, panelY + 9, GlassTheme.ACCENT, true);
        GlassRender.rule(ctx, panelX + 1, panelX + PANEL_WIDTH - 1, panelY + HEADER_H);

        // Field labels.
        ctx.drawText(this.textRenderer, Text.literal("Category"),
                panelX + PAD, panelY + CAT_LABEL_Y, GlassTheme.textMuted(), false);
        ctx.drawText(this.textRenderer, Text.literal("Your suggestion"),
                panelX + PAD, panelY + BODY_LABEL_Y, GlassTheme.textMuted(), false);

        // Footer rule.
        GlassRender.rule(ctx, panelX + 1, panelX + PANEL_WIDTH - 1, panelY + PANEL_HEIGHT - FOOTER_H);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Order: renderBackground (panel) → drawable children (segments, field, buttons) → overlay.
        super.render(ctx, mouseX, mouseY, delta);

        int panelX = panelX();
        int panelY = panelY();

        // Error line under the body field.
        String err = SuggestClient.lastError();
        if (!err.isEmpty()) {
            ctx.drawText(this.textRenderer,
                    Text.literal(this.textRenderer.trimToWidth(err, PANEL_WIDTH - 2 * PAD)),
                    panelX + PAD, panelY + ERROR_Y, GlassTheme.WARN, false);
        }

        // Status hint, left side of the footer.
        String hint = switch (SuggestClient.currentState()) {
            case SUBMITTING -> "Submitting…";
            case OPEN -> "ESC or Cancel to dismiss";
            default -> "";
        };
        if (!hint.isEmpty()) {
            int maxW = cancelButton.getX() - 8 - (panelX + PAD);
            int footY = panelY + PANEL_HEIGHT - FOOTER_H;
            ctx.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(hint, maxW)),
                    panelX + PAD, footY + (FOOTER_H - this.textRenderer.fontHeight) / 2,
                    GlassTheme.textMuted(), false);
        }
    }

    @Override
    public void close() {
        if (SuggestClient.currentState() == SuggestClient.State.OPEN) {
            SuggestClient.close();
        }
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /**
     * One half of the Mod / Server picker. The chosen segment draws as a selected item
     * (ember tint + rim, primary text); the other is a quiet slot that tints on hover.
     */
    private static final class CategorySegment extends PressableWidget {

        private final BooleanSupplier isSelected;
        private final Runnable onSelect;

        CategorySegment(int x, int y, int width, int height, String label, BooleanSupplier isSelected, Runnable onSelect) {
            super(x, y, width, height, Text.literal(label));
            this.isSelected = isSelected;
            this.onSelect = onSelect;
        }

        @Override
        public void onPress(AbstractInput input) {
            onSelect.run();
        }

        @Override
        protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
            int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
            boolean sel = isSelected.getAsBoolean();
            if (sel) {
                GlassRender.selected(ctx, x1, y1, x2, y2);
            } else {
                GlassRender.button(ctx, x1, y1, x2, y2, isHovered(), true, false);
            }
            TextRenderer fr = MinecraftClient.getInstance().textRenderer;
            Text m = getMessage();
            int color = sel ? GlassTheme.text() : isHovered() ? GlassTheme.text() : GlassTheme.textDim();
            ctx.drawText(fr, m, x1 + (getWidth() - fr.getWidth(m)) / 2, y1 + (getHeight() - fr.fontHeight) / 2,
                    color, false);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }
}
