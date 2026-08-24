package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.IconResolver;
import com.aleks.ancientsmod.client.LegacyText;
import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTextField;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.client.nametag.NametagClient;
import com.aleks.ancientsmod.client.nametag.NametagFormat;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.NametagOpenPayload;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The item-nametag rename GUI: type a name and watch the real item's tooltip
 * update as you go, instead of typing blind into chat and hoping.
 *
 * <p>Everything the player builds is legacy {@code &}-form — the exact string the
 * server already accepts — so the GUI is a composer, not a new format. The
 * preview runs the same translation the server will
 * ({@link NametagFormat#toSection}) and the counter measures what the server
 * measures ({@link NametagFormat#visibleLength}), so nothing here can promise
 * something the server then rejects.
 *
 * <h2>Palette clicks follow focus, not a mode</h2>
 * Clicking a colour swatch fills whichever gradient hex field has focus, and
 * otherwise inserts the colour into the name at the cursor. Focus is already
 * visible on screen, so there is no hidden "armed" state to get lost in.
 */
public final class NametagScreen extends Screen {

    private static final int PANEL_WIDTH = 452;
    private static final int PANEL_HEIGHT = 322;
    private static final int MARGIN = 14;

    private static final int SWATCH = 18;
    private static final int SWATCH_GAP = 2;
    /** Lore lines shown in the preview before it collapses into a "+N more" line. */
    private static final int PREVIEW_LORE_LINES = 5;

    private final NametagOpenPayload session;
    private final ItemStack previewStack;

    private GlassTextField nameField;
    private GlassTextField hexField;
    private GlassTextField gradFrom;
    private GlassTextField gradMid;
    private GlassTextField gradTo;
    private GlassButton confirmButton;

    /** Set when a gradient would encode past the packet cap; cleared on the next edit. */
    private String localError = "";

    public NametagScreen(NametagOpenPayload session) {
        super(Text.literal("Rename item"));
        this.session = session;
        this.previewStack = IconResolver.resolve(session.iconKey, Items.NAME_TAG, 1);
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        int cx = panelX + MARGIN;
        int cw = PANEL_WIDTH - 2 * MARGIN;

        // Name field. Capped at the packet's raw cap, not the visible cap: colour
        // codes are free against the server's limit, so the raw string is allowed
        // to be much longer than 32 characters.
        nameField = new GlassTextField(this.textRenderer, cx, panelY + 150, cw, 20,
                Text.literal("Item name"));
        nameField.setMaxLength(Protocol.NAMETAG_MAX_INPUT_CHARS);
        nameField.setPlaceholder(Text.literal("Type a name, use &a or &#FF55AA for colour…"));
        nameField.setText(session.currentName);
        nameField.setChangedListener(s -> localError = "");
        this.addDrawableChild(nameField);
        this.setInitialFocus(nameField);

        // ── Format inserts + hex insert ──────────────────────────────────────
        int fmtY = panelY + 222;
        int fmtW = 26;
        for (int i = 0; i < NametagFormat.FORMAT_CODES.length; i++) {
            final char code = NametagFormat.FORMAT_CODES[i];
            String glyph = String.valueOf(Character.toUpperCase(code));
            this.addDrawableChild(new GlassButton(cx + i * (fmtW + 3), fmtY, fmtW, 18,
                    Text.literal(glyph), () -> insertIntoName("&" + code)));
        }

        int hexX = cx + 5 * (fmtW + 3) + 10;
        hexField = new GlassTextField(this.textRenderer, hexX, fmtY, 58, 18, Text.literal("Hex"));
        hexField.setMaxLength(7);
        hexField.setPlaceholder(Text.literal("FF55AA"));
        this.addDrawableChild(hexField);
        this.addDrawableChild(new GlassButton(hexX + 62, fmtY, 52, 18,
                Text.literal("Insert"), this::insertHex));

        // ── Gradient ─────────────────────────────────────────────────────────
        int gradY = panelY + 258;
        gradFrom = hexStop(cx + 30, gradY, "FF5555");
        gradMid = hexStop(cx + 118, gradY, "");
        gradTo = hexStop(cx + 200, gradY, "55FFFF");
        this.addDrawableChild(gradFrom);
        this.addDrawableChild(gradMid);
        this.addDrawableChild(gradTo);
        this.addDrawableChild(new GlassButton(cx + 262, gradY, 56, 18,
                Text.literal("Apply"), this::applyGradient));
        this.addDrawableChild(new GlassButton(cx + 322, gradY, 56, 18,
                Text.literal("Strip"), this::stripColors));

        // ── Confirm / Cancel ─────────────────────────────────────────────────
        int btnY = panelY + PANEL_HEIGHT - MARGIN - 20;
        int btnW = (cw - 8) / 2;
        // Confirm-right rule: Cancel on the LEFT, Confirm (.primary()) on the RIGHT.
        this.addDrawableChild(new GlassButton(cx, btnY, btnW, 20, Text.literal("Cancel"), () -> {
            NametagClient.cancel();
            this.close();
        }));
        confirmButton = new GlassButton(cx + btnW + 8, btnY, btnW, 20,
                Text.literal("Confirm"), this::doSubmit).primary();
        this.addDrawableChild(confirmButton);
    }

    private GlassTextField hexStop(int x, int y, String initial) {
        GlassTextField f = new GlassTextField(this.textRenderer, x, y, 52, 18, Text.literal("stop"));
        f.setMaxLength(7);
        f.setPlaceholder(Text.literal("hex"));
        f.setText(initial);
        return f;
    }

    // ── Editing actions ──────────────────────────────────────────────────────

    /** Inserts at the cursor and keeps focus in the name field so typing continues. */
    private void insertIntoName(String token) {
        localError = "";
        nameField.write(token);
        this.setFocused(nameField);
    }

    private void insertHex() {
        int rgb = NametagFormat.parseHexInput(hexField.getText());
        if (rgb < 0) {
            localError = "Hex must be 6 digits, e.g. FF55AA";
            return;
        }
        insertIntoName(NametagFormat.hexToken(rgb));
    }

    /**
     * A palette click fills a focused gradient stop, otherwise it inserts the
     * colour into the name. The legacy code goes in rather than the equivalent
     * hex because it is shorter and reads better when you edit the raw string.
     */
    private void onSwatch(int index) {
        localError = "";
        String hex = String.format("%06X", NametagFormat.COLOR_RGB[index]);
        if (focusedStop() != null) {
            focusedStop().setText(hex);
            return;
        }
        insertIntoName("&" + NametagFormat.COLOR_CODES[index]);
    }

    private GlassTextField focusedStop() {
        if (gradFrom != null && gradFrom.isFocused()) return gradFrom;
        if (gradMid != null && gradMid.isFocused()) return gradMid;
        if (gradTo != null && gradTo.isFocused()) return gradTo;
        return null;
    }

    private void applyGradient() {
        List<Integer> stops = new ArrayList<>(3);
        int from = NametagFormat.parseHexInput(gradFrom.getText());
        int mid = NametagFormat.parseHexInput(gradMid.getText());
        int to = NametagFormat.parseHexInput(gradTo.getText());
        if (from < 0 || to < 0) {
            localError = "Gradient needs a valid start and end hex";
            return;
        }
        stops.add(from);
        if (mid >= 0) stops.add(mid);   // blank middle stop = a plain two-stop blend
        stops.add(to);

        String current = nameField.getText();
        if (NametagFormat.strip(current).isEmpty()) {
            localError = "Type a name first, then apply the gradient";
            return;
        }
        String result = NametagFormat.applyGradient(current, stops, activeFormats(current),
                Protocol.NAMETAG_MAX_INPUT_CHARS);
        if (result == null) {
            localError = "Gradient too long to encode — shorten the name or drop a format";
            return;
        }
        localError = "";
        nameField.setText(result);
    }

    /**
     * The format codes already in the string, so a gradient re-applies them per
     * character. Without this a gradient silently strips bold/italic, because a
     * colour code resets formatting in vanilla.
     */
    private String activeFormats(String input) {
        StringBuilder sb = new StringBuilder(8);
        String lower = input == null ? "" : input.toLowerCase(java.util.Locale.ROOT);
        for (char code : NametagFormat.FORMAT_CODES) {
            if (lower.contains("&" + code)) sb.append('&').append(code);
        }
        return sb.toString();
    }

    private void stripColors() {
        localError = "";
        nameField.setText(NametagFormat.strip(nameField.getText()));
    }

    private void doSubmit() {
        if (NametagClient.currentState() != NametagClient.State.OPEN) return;
        String raw = nameField.getText() == null ? "" : nameField.getText();
        if (!isSubmittable(raw)) return;
        NametagClient.submit(raw);
    }

    private boolean isSubmittable(String raw) {
        int visible = NametagFormat.visibleLength(raw);
        return visible > 0 && visible <= session.maxNameChars;
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        int rowX = panelX + MARGIN;
        int rowY = panelY + 198;
        double mx = click.x();
        double my = click.y();
        if (my >= rowY && my < rowY + SWATCH) {
            for (int i = 0; i < NametagFormat.COLOR_CODES.length; i++) {
                int sx = rowX + i * (SWATCH + SWATCH_GAP);
                if (mx >= sx && mx < sx + SWATCH) {
                    onSwatch(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void tick() {
        super.tick();
        boolean editable = NametagClient.currentState() == NametagClient.State.OPEN;
        nameField.setEditable(editable);
        confirmButton.active = editable && isSubmittable(nameField.getText());
    }

    // ── Render ───────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;

        GlassRender.menuBackdrop(ctx, this.width, this.height);
        GlassRender.panel(ctx, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);

        ctx.fill(panelX + GlassRender.RADIUS, panelY + GlassRender.RADIUS,
                panelX + PANEL_WIDTH - GlassRender.RADIUS, panelY + 24,
                GlassTheme.headerWash());
        ctx.drawText(this.textRenderer, Text.literal("Rename item"),
                panelX + MARGIN, panelY + 8, GlassTheme.text(), false);

        drawCounter(ctx, panelX, panelY);
        drawPreview(ctx, panelX, panelY);

        int cx = panelX + MARGIN;
        ctx.drawText(this.textRenderer, Text.literal("Name"), cx, panelY + 138, GlassTheme.textDim(), false);
        ctx.drawText(this.textRenderer, Text.literal("Colours"), cx, panelY + 186, GlassTheme.textDim(), false);
        ctx.drawText(this.textRenderer, Text.literal("Formats"), cx, panelY + 210, GlassTheme.textDim(), false);
        ctx.drawText(this.textRenderer, Text.literal("Gradient"), cx, panelY + 246, GlassTheme.textDim(), false);
    }

    /** "12 / 32" in the title bar, red once the server would reject it. */
    private void drawCounter(DrawContext ctx, int panelX, int panelY) {
        int visible = NametagFormat.visibleLength(nameField == null ? "" : nameField.getText());
        boolean over = visible > session.maxNameChars;
        String label = visible + " / " + session.maxNameChars;
        int w = this.textRenderer.getWidth(label);
        ctx.drawText(this.textRenderer, Text.literal(label),
                panelX + PANEL_WIDTH - MARGIN - w, panelY + 8,
                over ? GlassTheme.WARN : GlassTheme.textDim(), false);
    }

    /** The real item, with the name line live and the item's own lore under it. */
    private void drawPreview(DrawContext ctx, int panelX, int panelY) {
        int cx = panelX + MARGIN;
        int top = panelY + 32;
        int boxH = 100;
        int cw = PANEL_WIDTH - 2 * MARGIN;
        GlassRender.slot(ctx, cx, top, cx + cw, top + boxH);

        // Item icon, doubled so the art is actually readable.
        org.joml.Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) (cx + 10), (float) (top + 10));
        m.scale(2f, 2f);
        ctx.drawItem(previewStack, 0, 0);
        m.popMatrix();

        int textX = cx + 56;
        int y = top + 10;

        String raw = nameField == null ? session.currentName : nameField.getText();
        if (NametagFormat.strip(raw).isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal("(no name yet)"), textX, y,
                    GlassTheme.textMuted(), false);
        } else {
            ctx.drawText(this.textRenderer, LegacyText.parse(NametagFormat.toSection(raw)),
                    textX, y, GlassTheme.text(), false);
        }
        y += 14;

        int shown = Math.min(session.lore.size(), PREVIEW_LORE_LINES);
        for (int i = 0; i < shown; i++) {
            ctx.drawText(this.textRenderer, LegacyText.parse(session.lore.get(i)),
                    textX, y, GlassTheme.textDim(), false);
            y += 10;
        }
        if (session.lore.size() > shown) {
            ctx.drawText(this.textRenderer,
                    Text.literal("+" + (session.lore.size() - shown) + " more lines"),
                    textX, y, GlassTheme.textMuted(), false);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int panelX = (this.width - PANEL_WIDTH) / 2;
        int panelY = (this.height - PANEL_HEIGHT) / 2;
        int cx = panelX + MARGIN;

        drawSwatches(ctx, cx, panelY + 198, mouseX, mouseY);
        drawStopChips(ctx, cx, panelY + 258);
        drawCursorColor(ctx, panelX, panelY);
        drawStatus(ctx, panelX, panelY);
    }

    private void drawSwatches(DrawContext ctx, int x, int y, int mouseX, int mouseY) {
        for (int i = 0; i < NametagFormat.COLOR_RGB.length; i++) {
            int sx = x + i * (SWATCH + SWATCH_GAP);
            ctx.fill(sx, y, sx + SWATCH, y + SWATCH, 0xFF000000 | NametagFormat.COLOR_RGB[i]);
            boolean hover = mouseX >= sx && mouseX < sx + SWATCH && mouseY >= y && mouseY < y + SWATCH;
            GlassRender.roundedBorder(ctx, sx, y, sx + SWATCH, y + SWATCH, 2,
                    hover ? GlassTheme.ACCENT_SOFT : GlassTheme.slotRim());
        }
    }

    /** Colour chips in front of the three gradient hex fields, so the blend is visible. */
    private void drawStopChips(DrawContext ctx, int cx, int y) {
        ctx.drawText(this.textRenderer, Text.literal("from"), cx, y + 5, GlassTheme.textMuted(), false);
        chip(ctx, cx + 106, y, gradMid.getText());
        chip(ctx, cx + 24, y, gradFrom.getText());
        ctx.drawText(this.textRenderer, Text.literal("to"), cx + 176, y + 5, GlassTheme.textMuted(), false);
        chip(ctx, cx + 188, y, gradTo.getText());
    }

    private void chip(DrawContext ctx, int x, int y, String hex) {
        int rgb = NametagFormat.parseHexInput(hex);
        if (rgb < 0) {
            GlassRender.roundedBorder(ctx, x, y + 3, x + 10, y + 13, 2, GlassTheme.textMuted());
            return;
        }
        ctx.fill(x, y + 3, x + 10, y + 13, 0xFF000000 | rgb);
        GlassRender.roundedBorder(ctx, x, y + 3, x + 10, y + 13, 2, GlassTheme.slotRim());
    }

    /** Readout of the colour in force at the cursor — the "what am I editing" cue. */
    private void drawCursorColor(DrawContext ctx, int panelX, int panelY) {
        if (nameField == null) return;
        String token = NametagFormat.colorAtCaret(nameField.getText(), nameField.getCursor());
        int x = panelX + PANEL_WIDTH - MARGIN - 76;
        int y = panelY + 222;
        if (token.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal("no colour"), x, y + 5,
                    GlassTheme.textMuted(), false);
            return;
        }
        int rgb = NametagFormat.rgbOfToken(token);
        if (rgb >= 0) {
            ctx.fill(x, y + 4, x + 10, y + 14, 0xFF000000 | rgb);
            GlassRender.roundedBorder(ctx, x, y + 4, x + 10, y + 14, 2, GlassTheme.slotRim());
        }
        String label = rgb >= 0 && token.length() == 2
                ? token + " #" + String.format("%06X", rgb)
                : token;
        ctx.drawText(this.textRenderer, Text.literal(label), x + 14, y + 5,
                GlassTheme.textDim(), false);
    }

    private void drawStatus(DrawContext ctx, int panelX, int panelY) {
        int cx = panelX + MARGIN;
        int y = panelY + PANEL_HEIGHT - MARGIN - 34;

        String err = !localError.isEmpty() ? localError : NametagClient.lastError();
        if (!err.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal(err), cx, y, GlassTheme.WARN, false);
            return;
        }
        int visible = NametagFormat.visibleLength(nameField == null ? "" : nameField.getText());
        String hint;
        if (NametagClient.currentState() == NametagClient.State.SUBMITTING) {
            hint = "Applying…";
        } else if (visible == 0) {
            hint = "Type a name — colour codes don't count toward the limit";
        } else if (visible > session.maxNameChars) {
            hint = "Too long by " + (visible - session.maxNameChars) + " characters";
        } else {
            hint = "Click a colour to insert it, or focus a gradient box to fill it";
        }
        ctx.drawText(this.textRenderer, Text.literal(hint), cx, y, GlassTheme.textMuted(), false);
    }

    @Override
    public void close() {
        // ESC is a cancel: the server still holds the consumed nametag against this
        // session and only hands it back when told the player walked away.
        if (NametagClient.currentState() != NametagClient.State.IDLE) {
            NametagClient.cancel();
        }
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
