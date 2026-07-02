package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTextField;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.client.suggest.SuggestClient;
import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * In-game suggestion screen. Single-column compact dialog:
 * <ol>
 *   <li>Mod / Server category toggle (radio-like buttons)</li>
 *   <li>Description text field</li>
 *   <li>Submit / Cancel</li>
 * </ol>
 *
 * <p>All actions route through {@link SuggestClient} so the state machine stays
 * coherent. ESC fires {@code Close} to free the server-side session token.
 */
public final class SuggestScreen extends Screen {

    private static final int PANEL_WIDTH = 380;
    private static final int PANEL_HEIGHT = 200;
    private static final int MARGIN = 14;

    private byte category = Protocol.SUGGEST_CAT_SERVER; // default to server-side suggestions
    private GlassTextField bodyField;
    private GlassButton modButton;
    private GlassButton serverButton;
    private GlassButton submitButton;
    private GlassButton cancelButton;

    public SuggestScreen() {
        super(Text.literal("Suggest"));
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        int contentX = panelX + MARGIN;
        int contentW = PANEL_WIDTH - 2 * MARGIN;

        int catY = panelY + 40;
        int catW = (contentW - 8) / 2;
        modButton = new GlassButton(contentX, catY, catW, 20,
                categoryLabel("Mod", Protocol.SUGGEST_CAT_MOD), () -> {
                    category = Protocol.SUGGEST_CAT_MOD;
                    refreshCategoryButtons();
                });
        serverButton = new GlassButton(contentX + catW + 8, catY, catW, 20,
                categoryLabel("Server", Protocol.SUGGEST_CAT_SERVER), () -> {
                    category = Protocol.SUGGEST_CAT_SERVER;
                    refreshCategoryButtons();
                });
        this.addDrawableChild(modButton);
        this.addDrawableChild(serverButton);

        int bodyY = catY + 50;
        bodyField = new GlassTextField(this.textRenderer, contentX, bodyY, contentW, 20,
                Text.literal("Your suggestion"));
        bodyField.setMaxLength(Protocol.SUGGEST_MAX_BODY_CHARS);
        bodyField.setPlaceholder(Text.literal("Describe your suggestion…"));
        this.addDrawableChild(bodyField);
        this.setInitialFocus(bodyField);

        int btnY = panelY + PANEL_HEIGHT - MARGIN - 20;
        int btnW = (contentW - 8) / 2;
        // Confirm-right rule: Cancel on the LEFT, Submit (.primary()) on the RIGHT.
        cancelButton = new GlassButton(contentX, btnY, btnW, 20, Text.literal("Cancel"), () -> {
                    SuggestClient.close();
                    this.close();
                });
        submitButton = new GlassButton(contentX + btnW + 8, btnY, btnW, 20,
                Text.literal("Submit"), this::doSubmit).primary();
        this.addDrawableChild(submitButton);
        this.addDrawableChild(cancelButton);

        refreshCategoryButtons();
    }

    private void refreshCategoryButtons() {
        // Keep both buttons active so they render at full brightness; selection
        // is shown via a radio-style label prefix and a highlight border in render().
        modButton.active = true;
        serverButton.active = true;
        modButton.setMessage(categoryLabel("Mod", Protocol.SUGGEST_CAT_MOD));
        serverButton.setMessage(categoryLabel("Server", Protocol.SUGGEST_CAT_SERVER));
    }

    private Text categoryLabel(String name, byte cat) {
        return Text.literal((category == cat ? "◉ " : "○ ") + name);
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
        // Only the Submit button + body field gate on state; the category buttons
        // stay clickable so the selection stays visually obvious.
        boolean canEdit = SuggestClient.currentState() == SuggestClient.State.OPEN;
        submitButton.active = canEdit;
        bodyField.setEditable(canEdit);
        refreshCategoryButtons();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        // Real blurred backdrop + scrim behind the dialog (once, before the panel).
        GlassRender.menuBackdrop(ctx, this.width, this.height);

        // Frosted glass panel — drawn here (in renderBackground) so the buttons +
        // text field render ON TOP of it instead of being dimmed by it.
        GlassRender.panel(ctx, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);

        // Violet title-bar wash, inset by the panel radius.
        ctx.fill(panelX + GlassRender.RADIUS, panelY + GlassRender.RADIUS,
                panelX + PANEL_WIDTH - GlassRender.RADIUS, panelY + 24,
                GlassTheme.withAlpha(GlassTheme.ACCENT, 0x2E));
        ctx.drawText(this.textRenderer, Text.literal("Suggest a change"),
                panelX + MARGIN, panelY + 8, GlassTheme.text(), false);

        // Field labels.
        ctx.drawText(this.textRenderer, Text.literal("Category"),
                panelX + MARGIN, panelY + 30, GlassTheme.textDim(), false);
        ctx.drawText(this.textRenderer, Text.literal("Your suggestion"),
                panelX + MARGIN, panelY + 80, GlassTheme.textDim(), false);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Order: renderBackground (panel) → drawable children (buttons + text field) → overlay.
        super.render(ctx, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        // Highlight border around the selected category — drawn ON TOP of the button.
        GlassButton selected = (category == Protocol.SUGGEST_CAT_MOD) ? modButton : serverButton;
        int hx = selected.getX();
        int hy = selected.getY();
        int hw = selected.getWidth();
        int hh = selected.getHeight();
        GlassRender.roundedBorder(ctx, hx - 1, hy - 1, hx + hw + 1, hy + hh + 1, 6, GlassTheme.ACCENT_SOFT);

        // Error line under the body field.
        String err = SuggestClient.lastError();
        if (!err.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal(err),
                    panelX + MARGIN, panelY + 124, GlassTheme.WARN, false);
        }

        // Status hint.
        String hint = switch (SuggestClient.currentState()) {
            case SUBMITTING -> "Submitting…";
            case OPEN -> "ESC or Cancel to dismiss";
            default -> "";
        };
        if (!hint.isEmpty()) {
            int w = this.textRenderer.getWidth(hint);
            ctx.drawText(this.textRenderer, Text.literal(hint),
                    panelX + PANEL_WIDTH - MARGIN - w, panelY + PANEL_HEIGHT - 12, GlassTheme.textMuted(), false);
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
}
