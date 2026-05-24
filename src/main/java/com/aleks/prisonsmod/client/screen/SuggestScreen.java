package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.suggest.SuggestClient;
import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
    private TextFieldWidget bodyField;
    private ButtonWidget modButton;
    private ButtonWidget serverButton;
    private ButtonWidget submitButton;
    private ButtonWidget cancelButton;

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
        modButton = ButtonWidget.builder(categoryLabel("Mod", Protocol.SUGGEST_CAT_MOD), b -> {
                    category = Protocol.SUGGEST_CAT_MOD;
                    refreshCategoryButtons();
                })
                .dimensions(contentX, catY, catW, 20)
                .build();
        serverButton = ButtonWidget.builder(categoryLabel("Server", Protocol.SUGGEST_CAT_SERVER), b -> {
                    category = Protocol.SUGGEST_CAT_SERVER;
                    refreshCategoryButtons();
                })
                .dimensions(contentX + catW + 8, catY, catW, 20)
                .build();
        this.addDrawableChild(modButton);
        this.addDrawableChild(serverButton);

        int bodyY = catY + 50;
        bodyField = new TextFieldWidget(this.textRenderer, contentX, bodyY, contentW, 20,
                Text.literal("Your suggestion"));
        bodyField.setMaxLength(Protocol.SUGGEST_MAX_BODY_CHARS);
        bodyField.setPlaceholder(Text.literal("Describe your suggestion…"));
        this.addDrawableChild(bodyField);
        this.setInitialFocus(bodyField);

        int btnY = panelY + PANEL_HEIGHT - MARGIN - 20;
        int btnW = (contentW - 8) / 2;
        submitButton = ButtonWidget.builder(Text.literal("Submit"), b -> doSubmit())
                .dimensions(contentX, btnY, btnW, 20)
                .build();
        cancelButton = ButtonWidget.builder(Text.literal("Cancel"), b -> {
                    SuggestClient.close();
                    this.close();
                })
                .dimensions(contentX + btnW + 8, btnY, btnW, 20)
                .build();
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

        // Panel background — drawn here (in renderBackground) so the buttons +
        // text field render ON TOP of it instead of being dimmed by it.
        ctx.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xF0101010);
        ctx.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 1, 0xFF555555);
        ctx.fill(panelX, panelY + PANEL_HEIGHT - 1, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xFF555555);

        // Title bar.
        ctx.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + 24, 0xFF1A1A1A);
        ctx.drawText(this.textRenderer, Text.literal("§eSuggest a change"),
                panelX + MARGIN, panelY + 8, 0xFFFFFFFF, true);

        // Field labels.
        ctx.drawText(this.textRenderer, Text.literal("§7Category"),
                panelX + MARGIN, panelY + 30, 0xFFAAAAAA, false);
        ctx.drawText(this.textRenderer, Text.literal("§7Your suggestion"),
                panelX + MARGIN, panelY + 80, 0xFFAAAAAA, false);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Order: renderBackground (panel) → drawable children (buttons + text field) → overlay.
        super.render(ctx, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        // Highlight border around the selected category — drawn ON TOP of the button.
        ButtonWidget selected = (category == Protocol.SUGGEST_CAT_MOD) ? modButton : serverButton;
        int hx = selected.getX();
        int hy = selected.getY();
        int hw = selected.getWidth();
        int hh = selected.getHeight();
        int hi = 0xFFFFCC33;
        ctx.fill(hx - 1, hy - 1, hx + hw + 1, hy,           hi); // top
        ctx.fill(hx - 1, hy + hh, hx + hw + 1, hy + hh + 1, hi); // bottom
        ctx.fill(hx - 1, hy - 1, hx, hy + hh + 1,           hi); // left
        ctx.fill(hx + hw, hy - 1, hx + hw + 1, hy + hh + 1, hi); // right

        // Error line under the body field.
        String err = SuggestClient.lastError();
        if (!err.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal("§c" + err),
                    panelX + MARGIN, panelY + 124, 0xFFFF6E6E, false);
        }

        // Status hint.
        String hint = switch (SuggestClient.currentState()) {
            case SUBMITTING -> "§7Submitting…";
            case OPEN -> "§8ESC or Cancel to dismiss";
            default -> "";
        };
        if (!hint.isEmpty()) {
            int w = this.textRenderer.getWidth(hint);
            ctx.drawText(this.textRenderer, Text.literal(hint),
                    panelX + PANEL_WIDTH - MARGIN - w, panelY + PANEL_HEIGHT - 12, 0xFFAAAAAA, false);
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
