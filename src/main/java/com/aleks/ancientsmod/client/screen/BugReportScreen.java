package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.bugreport.BugReportClient;
import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTextField;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.BugReportOpenPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.AbstractInput;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * In-game bug-report UI. Renders in two modes that share the same screen so
 * the player never sees a screen swap mid-flow:
 *
 * <ol>
 *   <li><b>Compose</b> ({@link BugReportClient.State#OPEN}): the left column shows
 *       the sanitized snapshot the server would file, the right column shows 9
 *       category checkboxes and a description box, with Cancel / Submit in the
 *       footer.</li>
 *   <li><b>Chat</b> ({@code SUBMITTING|AWAITING_AI|CHATTING|RESOLVED|ESCALATED}):
 *       the snapshot stays on the left so the player can keep referring to it,
 *       and the right column becomes a chat thread with the AI: their description
 *       at the top, AI replies, a follow-up input, and Talk to Staff / Mark
 *       Resolved / Send (or Close once the thread is over) in the footer.</li>
 * </ol>
 *
 * <p>Drawn as one flat glass panel in the Hearth palette: header text over a bronze
 * rule, the two columns split by a vertical rule, a footer rule with the primary
 * action on the right. Snapshot sections are ember labels with a hairline, not boxes.
 *
 * <p>The screen is "input-only" toward the network: every Submit / Follow-up /
 * Escalate / Close routes through {@link BugReportClient}, never directly to
 * {@link com.aleks.ancientsmod.net.NetworkHandler}, so the state machine stays
 * coherent. Closing via ESC fires a {@code Close(resolved=false)} so the server
 * frees the preview token immediately.
 */
public final class BugReportScreen extends Screen {

    // ── Layout constants ─────────────────────────────────────────────────────

    /** Gap between the screen edge and the panel. */
    private static final int OUTER = 8;
    /** Inner padding of the panel and of each column. */
    private static final int PAD = 10;
    /** Header height: title line, then the bronze rule. */
    private static final int HEADER_H = 26;
    /** Footer height: rule, then the button row. */
    private static final int FOOTER_H = 28;
    private static final int BTN_H = 16;
    private static final int BTN_GAP = 6;
    private static final int LINE_H = 10;
    private static final int SECTION_GAP = 6;
    private static final int SECTION_HEADER_H = 12;
    /** Column heading line + gap before the column's content. */
    private static final int COLUMN_HEAD_H = 16;

    private static final int CAT_ROW_H = 15;
    private static final int FIELD_H = 18;
    private static final int CHAT_LINE_GAP = 6;

    // ── Category catalog (must match Protocol.BR_CAT_* bits, order = display order) ─

    private static final CategoryDef[] CATEGORIES = new CategoryDef[] {
            new CategoryDef("Lost items / inventory",         Protocol.BR_CAT_LOST_ITEMS),
            new CategoryDef("Death / damage / PvP",           Protocol.BR_CAT_DEATH_PVP),
            new CategoryDef("Teleport / stuck / position",    Protocol.BR_CAT_TELEPORT),
            new CategoryDef("Economy / shop / sell / AH",     Protocol.BR_CAT_ECONOMY),
            new CategoryDef("Mining / enchant / pickaxe",     Protocol.BR_CAT_MINING),
            new CategoryDef("Event (KOTH / meteor / boss…)",  Protocol.BR_CAT_EVENT),
            new CategoryDef("Visual / render / UI",           Protocol.BR_CAT_VISUAL),
            new CategoryDef("Performance / lag / crash",      Protocol.BR_CAT_PERF),
            new CategoryDef("Permission / cosmetic / other",  Protocol.BR_CAT_OTHER),
    };

    // ── State ────────────────────────────────────────────────────────────────

    private final boolean[] checked = new boolean[CATEGORIES.length];
    private final List<CategoryCheck> catWidgets = new ArrayList<>();
    private GlassTextField descBox;
    private GlassTextField followupBox;
    private GlassButton submitButton;
    private GlassButton cancelButton;
    private GlassButton sendFollowupButton;
    private GlassButton escalateButton;
    private GlassButton resolveButton;
    private GlassButton closeButton;

    /** Pixel offset for the snapshot column. Driven by mouse wheel. */
    private int leftScroll = 0;
    /** Pixel offset for the chat thread column. Driven by mouse wheel. */
    private int chatScroll = 0;

    // Geometry, recomputed in init().
    private int px1, py1, px2, py2;
    private int bodyTop, bodyBottom, footY;
    private int leftX, leftW, rightX, rightW;
    private int descLabelY;

    public BugReportScreen() {
        super(Text.literal("Bug Report"));
    }

    @Override
    protected void init() {
        BugReportClient.State s = BugReportClient.currentState();

        px1 = OUTER;
        py1 = OUTER;
        px2 = this.width - OUTER;
        py2 = this.height - OUTER;
        bodyTop = py1 + HEADER_H + 8;
        footY = py2 - FOOTER_H;
        bodyBottom = footY - 6;

        int mid = this.width / 2;
        leftX = px1 + PAD;
        leftW = mid - PAD - leftX;
        rightX = mid + PAD;
        rightW = px2 - PAD - rightX;

        int btnY = footY + (FOOTER_H - BTN_H) / 2;

        // Category checkboxes (right column, compose mode). Single column keeps the labels readable.
        catWidgets.clear();
        int catY = bodyTop + COLUMN_HEAD_H;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            CategoryCheck cb = new CategoryCheck(rightX - 4, catY + idx * CAT_ROW_H, rightW + 4, CAT_ROW_H - 1,
                    CATEGORIES[i].label, checked[i], value -> checked[idx] = value);
            catWidgets.add(cb);
            this.addDrawableChild(cb);
        }

        // Description input (single-line, scrolls horizontally; MC 1.21.x lacks a stable
        // multi-line widget signature, so we use TextFieldWidget for both compose and chat
        // text entry. Players can still type ~1KB of description, it just scrolls).
        descLabelY = catY + CATEGORIES.length * CAT_ROW_H + 8;
        descBox = new GlassTextField(this.textRenderer, rightX, descLabelY + 12, rightW, FIELD_H,
                Text.literal("Describe what went wrong"));
        descBox.setMaxLength(Protocol.BUGREPORT_MAX_DESCRIPTION_CHARS);
        descBox.setPlaceholder(Text.literal("Describe what went wrong (when, where, what you tried)…"));
        if (!BugReportClient.currentPrefill().isEmpty()) {
            descBox.setText(BugReportClient.currentPrefill());
        }
        this.addDrawableChild(descBox);

        // Footer buttons are laid out right to left so the primary action sits on the far RIGHT.
        // Compose: Cancel, then Submit Report (.primary).
        int x = px2 - PAD;
        submitButton = footerButton(x, btnY, "Submit Report", this::doSubmit).primary();
        x = submitButton.getX() - BTN_GAP;
        cancelButton = footerButton(x, btnY, "Cancel", () -> {
            BugReportClient.close(false);
            this.close();
        });
        this.addDrawableChild(submitButton);
        this.addDrawableChild(cancelButton);

        // Chat-mode widgets (follow-up input + buttons). Created up front but
        // toggled visible based on state.
        followupBox = new GlassTextField(this.textRenderer, rightX, bodyBottom - FIELD_H, rightW, FIELD_H,
                Text.literal("Reply to the bot…"));
        followupBox.setMaxLength(Protocol.BUGREPORT_MAX_FOLLOWUP_CHARS);
        followupBox.setPlaceholder(Text.literal("Reply to Hermes…"));
        this.addDrawableChild(followupBox);

        // Chat: Talk to Staff, Mark Resolved, then Send (.primary) on the right.
        x = px2 - PAD;
        sendFollowupButton = footerButton(x, btnY, "Send", this::doFollowup).primary();
        x = sendFollowupButton.getX() - BTN_GAP;
        resolveButton = footerButton(x, btnY, "Mark Resolved", () -> {
            BugReportClient.close(true);
            this.close();
        });
        x = resolveButton.getX() - BTN_GAP;
        escalateButton = footerButton(x, btnY, "Talk to Staff", BugReportClient::escalate);

        // Resolved / escalated: a single Close on the right.
        closeButton = footerButton(px2 - PAD, btnY, "Close", () -> {
            BugReportClient.close(false);
            this.close();
        }).primary();
        this.addDrawableChild(sendFollowupButton);
        this.addDrawableChild(resolveButton);
        this.addDrawableChild(escalateButton);
        this.addDrawableChild(closeButton);

        applyVisibility(s);
    }

    /** Footer button sized to its label, with its RIGHT edge at {@code rightEdge}. */
    private GlassButton footerButton(int rightEdge, int y, String label, Runnable action) {
        int w = Math.max(52, this.textRenderer.getWidth(label) + 16);
        return new GlassButton(rightEdge - w, y, w, BTN_H, Text.literal(label), action);
    }

    private boolean isCompose(BugReportClient.State s) {
        return s == BugReportClient.State.OPEN || s == BugReportClient.State.IDLE;
    }

    private boolean isResolvedLike(BugReportClient.State s) {
        return s == BugReportClient.State.RESOLVED || s == BugReportClient.State.ESCALATED;
    }

    private void applyVisibility(BugReportClient.State s) {
        boolean compose = isCompose(s);
        for (CategoryCheck cb : catWidgets) cb.visible = compose;
        descBox.visible = compose;
        submitButton.visible = compose;
        cancelButton.visible = compose;

        boolean chat = !compose;
        followupBox.visible = chat && !isResolvedLike(s);
        sendFollowupButton.visible = chat && !isResolvedLike(s);
        resolveButton.visible = chat && !isResolvedLike(s);
        escalateButton.visible = chat && !isResolvedLike(s);
        closeButton.visible = chat && isResolvedLike(s);

        // Disable submit while in transitional states.
        boolean canSubmit = (s == BugReportClient.State.OPEN);
        submitButton.active = canSubmit;
    }

    private void doSubmit() {
        if (BugReportClient.currentState() != BugReportClient.State.OPEN) return;
        int mask = 0;
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (checked[i]) mask |= CATEGORIES[i].bit;
        }
        String desc = descBox.getText() == null ? "" : descBox.getText().trim();
        if (desc.isEmpty()) {
            descBox.setText("(no description)");
            desc = "(no description)";
        }
        BugReportClient.submit(mask, desc);
    }

    private void doFollowup() {
        String text = followupBox.getText() == null ? "" : followupBox.getText().trim();
        if (text.isEmpty()) return;
        BugReportClient.followup(text);
        followupBox.setText("");
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        // Scroll left column when mouse is over it.
        if (mouseX < this.width / 2.0) {
            leftScroll = Math.max(0, leftScroll - (int) (vertical * 12));
            return true;
        }
        // Scroll chat column when mouse is over it and we're in chat mode.
        if (!isCompose(BugReportClient.currentState())) {
            chatScroll = Math.max(0, chatScroll - (int) (vertical * 12));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void tick() {
        // Visibility may need to switch from compose to chat as the state machine progresses.
        applyVisibility(BugReportClient.currentState());
        super.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Backdrop + panel first so the widgets (drawn by super.render) sit on top of the glass.
        GlassRender.menuBackdrop(context, this.width, this.height);
        GlassRender.panel(context, px1, py1, px2 - px1, py2 - py1);

        BugReportClient.State s = BugReportClient.currentState();
        drawHeader(context, s);
        GlassRender.vrule(context, this.width / 2, bodyTop, bodyBottom);
        drawSnapshotColumn(context);
        if (isCompose(s)) {
            drawComposeLabels(context);
        } else {
            drawChatColumn(context, s);
        }
        drawFooter(context, s);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        // ESC out: tell the server to free the preview token (resolved=false).
        BugReportClient.State s = BugReportClient.currentState();
        if (s == BugReportClient.State.OPEN) {
            BugReportClient.close(false);
        }
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false; // don't pause SP, the player may want to see the world while filing.
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private void drawHeader(DrawContext ctx, BugReportClient.State s) {
        String status = switch (s) {
            case AWAITING_AI -> "Investigating…";
            case CHATTING    -> "Chat with Hermes";
            case RESOLVED    -> "Resolved";
            case ESCALATED   -> "Escalated to staff";
            case SUBMITTING  -> "Submitting…";
            default          -> "";
        };

        int tx = px1 + PAD;
        int ty = py1 + 10;
        String title = "Bug Report";
        ctx.drawText(this.textRenderer, Text.literal(title), tx, ty, GlassTheme.ACCENT, true);

        int rightEdge = px2 - PAD;
        String reportId = BugReportClient.currentReportId();
        if (!reportId.isEmpty()) {
            String idText = reportId;
            int idW = this.textRenderer.getWidth(idText);
            ctx.drawText(this.textRenderer, Text.literal(idText), rightEdge - idW, ty, GlassTheme.VALUE, false);
            String idLabel = "ID ";
            int labelW = this.textRenderer.getWidth(idLabel);
            ctx.drawText(this.textRenderer, Text.literal(idLabel), rightEdge - idW - labelW, ty,
                    GlassTheme.textMuted(), false);
            rightEdge -= idW + labelW + 12;
        }

        if (!status.isEmpty()) {
            int sx = tx + this.textRenderer.getWidth(title) + 8;
            String fit = this.textRenderer.trimToWidth(status, Math.max(0, rightEdge - sx));
            ctx.drawText(this.textRenderer, Text.literal(fit), sx, ty, GlassTheme.textMuted(), false);
        }

        GlassRender.rule(ctx, px1 + 1, px2 - 1, py1 + HEADER_H);
    }

    private void drawFooter(DrawContext ctx, BugReportClient.State s) {
        GlassRender.rule(ctx, px1 + 1, px2 - 1, footY);

        String hint;
        int firstButtonX;
        if (isCompose(s)) {
            hint = "Scroll the stats with the mouse wheel. ESC cancels.";
            firstButtonX = cancelButton.getX();
        } else if (isResolvedLike(s)) {
            hint = "This report is closed. Scroll to reread the thread.";
            firstButtonX = closeButton.getX();
        } else {
            hint = "Scroll to read the thread.";
            firstButtonX = escalateButton.getX();
        }
        int maxW = firstButtonX - 12 - (px1 + PAD);
        if (maxW > 40) {
            ctx.drawText(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(hint, maxW)),
                    px1 + PAD, footY + (FOOTER_H - this.textRenderer.fontHeight) / 2,
                    GlassTheme.textMuted(), false);
        }
    }

    /** Column heading: primary text with optional muted meta after it. */
    private void drawColumnHeading(DrawContext ctx, int x, int y, int w, String heading, String meta) {
        String h = this.textRenderer.trimToWidth(heading, w);
        ctx.drawText(this.textRenderer, Text.literal(h), x, y, GlassTheme.text(), false);
        if (meta != null && !meta.isEmpty()) {
            int mx = x + this.textRenderer.getWidth(h) + 6;
            String m = this.textRenderer.trimToWidth(meta, Math.max(0, x + w - mx));
            ctx.drawText(this.textRenderer, Text.literal(m), mx, y, GlassTheme.textMuted(), false);
        }
    }

    private void drawSnapshotColumn(DrawContext ctx) {
        drawColumnHeading(ctx, leftX, bodyTop, leftW, "Collected stats", "sent with your report");

        int contentY = bodyTop + COLUMN_HEAD_H;
        ctx.enableScissor(leftX - 2, contentY, leftX + leftW + 2, bodyBottom);
        int drawY = contentY - leftScroll;

        List<BugReportOpenPayload.Section> sections = BugReportClient.currentSections();
        if (sections.isEmpty()) {
            ctx.drawText(this.textRenderer, Text.literal("Waiting for snapshot…"),
                    leftX, drawY, GlassTheme.textMuted(), false);
        } else {
            for (BugReportOpenPayload.Section section : sections) {
                drawY = drawSection(ctx, section, leftX, drawY, leftW);
                drawY += SECTION_GAP;
            }
        }
        ctx.disableScissor();
    }

    private int drawSection(DrawContext ctx, BugReportOpenPayload.Section section, int x, int y, int w) {
        // Ember label with a bronze hairline running to the column edge.
        String title = this.textRenderer.trimToWidth(section.title, w);
        ctx.drawText(this.textRenderer, Text.literal(title), x, y + 1, GlassTheme.sectionLabel(), false);
        int lx = x + this.textRenderer.getWidth(title) + 6;
        if (lx < x + w) GlassRender.rule(ctx, lx, x + w, y + 1 + this.textRenderer.fontHeight / 2);

        int cur = y + SECTION_HEADER_H + 2;
        for (String line : section.lines) {
            // Wrap to multiple visual lines if needed.
            List<String> wrapped = wrap(line, w - 6);
            for (String chunk : wrapped) {
                ctx.drawText(this.textRenderer, Text.literal(chunk), x + 6, cur, GlassTheme.textDim(), false);
                cur += LINE_H;
            }
        }
        return cur;
    }

    private void drawComposeLabels(DrawContext ctx) {
        drawColumnHeading(ctx, rightX, bodyTop, rightW, "What went wrong?", "pick any that apply");
        ctx.drawText(this.textRenderer, Text.literal("Description"), rightX, descLabelY,
                GlassTheme.textMuted(), false);
    }

    private List<String> wrap(String s, int maxWidthPx) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            out.add("");
            return out;
        }
        // Honour explicit \r and \n as forced breaks BEFORE word-wrapping, so
        // AI replies that contain newlines don't render as missing-glyph boxes
        // (the vanilla text renderer doesn't interpret \n; it just tries to
        // draw the U+000A glyph and falls back to the missing-glyph square).
        for (String paragraph : s.split("\\r?\\n", -1)) {
            wrapParagraph(paragraph, maxWidthPx, out);
        }
        return out;
    }

    private void wrapParagraph(String s, int maxWidthPx, List<String> out) {
        if (s == null || s.isEmpty()) { out.add(""); return; }
        int width = 0;
        StringBuilder current = new StringBuilder();
        for (String word : s.split(" ")) {
            int wordWidth = this.textRenderer.getWidth(word);
            int spaceWidth = current.length() == 0 ? 0 : this.textRenderer.getWidth(" ");
            if (width + spaceWidth + wordWidth > maxWidthPx) {
                if (current.length() > 0) out.add(current.toString());
                current.setLength(0);
                width = 0;
                if (wordWidth > maxWidthPx) {
                    out.add(word.substring(0, Math.min(word.length(), 64)));
                    continue;
                }
            }
            if (current.length() > 0) {
                current.append(' ');
                width += spaceWidth;
            }
            current.append(word);
            width += wordWidth;
        }
        if (current.length() > 0) out.add(current.toString());
    }

    private void drawChatColumn(DrawContext ctx, BugReportClient.State s) {
        drawColumnHeading(ctx, rightX, bodyTop, rightW, "Conversation", null);

        int contentY = bodyTop + COLUMN_HEAD_H;
        int contentBottom = isResolvedLike(s) ? bodyBottom : followupBox.getY() - 6;
        if (contentBottom <= contentY) return;
        ctx.enableScissor(rightX - 2, contentY, rightX + rightW + 2, contentBottom);

        // Laid out top-down, offset by chatScroll.
        int drawY = contentY - chatScroll;
        List<BugReportClient.ChatLine> lines = BugReportClient.currentChatLines();
        for (BugReportClient.ChatLine line : lines) {
            drawY = drawChatLine(ctx, line, rightX, drawY, rightW);
            drawY += CHAT_LINE_GAP;
        }

        ctx.disableScissor();
    }

    /**
     * One thread entry: the speaker's name (Hermes in ember, the player in primary text) leads
     * the first line, the message follows in body text. System notes are muted with no name.
     */
    private int drawChatLine(DrawContext ctx, BugReportClient.ChatLine line, int x, int y, int w) {
        String name;
        int nameColor;
        int bodyColor;
        switch (line.kind) {
            case AI -> {
                name = "Hermes:";
                nameColor = GlassTheme.ACCENT;
                bodyColor = GlassTheme.textDim();
            }
            case PLAYER -> {
                name = "You:";
                nameColor = GlassTheme.text();
                bodyColor = GlassTheme.textDim();
            }
            default -> {
                name = null;
                nameColor = 0;
                bodyColor = GlassTheme.textMuted();
            }
        }
        String full = name == null ? line.text : name + " " + line.text;
        List<String> wrapped = wrap(full, w);
        int cur = y;
        for (int i = 0; i < wrapped.size(); i++) {
            String chunk = wrapped.get(i);
            if (i == 0 && name != null && chunk.startsWith(name)) {
                ctx.drawText(this.textRenderer, Text.literal(name), x, cur, nameColor, false);
                String rest = chunk.substring(name.length());
                if (!rest.isEmpty()) {
                    ctx.drawText(this.textRenderer, Text.literal(rest),
                            x + this.textRenderer.getWidth(name), cur, bodyColor, false);
                }
            } else {
                ctx.drawText(this.textRenderer, Text.literal(chunk), x, cur, bodyColor, false);
            }
            cur += LINE_H;
        }
        return cur;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record CategoryDef(String label, int bit) {}

    /**
     * Flat glass checkbox row for the category list (the vanilla {@code CheckboxWidget}
     * draws its own gray texture, which clashes with the glass panel). Transparent at
     * rest, soft tint on hover; the box fills ember with an ink tick when checked.
     */
    private static final class CategoryCheck extends PressableWidget {

        private static final int BOX = 9;

        private boolean value;
        private final Consumer<Boolean> onChange;
        private final String labelText;

        CategoryCheck(int x, int y, int width, int height, String label, boolean initial, Consumer<Boolean> onChange) {
            super(x, y, width, height, Text.literal(label));
            this.labelText = label;
            this.value = initial;
            this.onChange = onChange;
        }

        @Override
        public void onPress(AbstractInput input) {
            value = !value;
            if (onChange != null) onChange.accept(value);
        }

        @Override
        protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
            int x1 = getX(), y1 = getY(), x2 = x1 + getWidth(), y2 = y1 + getHeight();
            GlassRender.row(ctx, x1, y1, x2, y2, isHovered());

            int bx = x1 + 4;
            int by = y1 + (getHeight() - BOX) / 2;
            if (value) {
                GlassRender.roundedRect(ctx, bx, by, bx + BOX, by + BOX, 1, GlassTheme.ACCENT);
                int ink = GlassTheme.INK;
                ctx.fill(bx + 2, by + 4, bx + 3, by + 6, ink);
                ctx.fill(bx + 3, by + 5, bx + 4, by + 7, ink);
                ctx.fill(bx + 4, by + 4, bx + 5, by + 6, ink);
                ctx.fill(bx + 5, by + 3, bx + 6, by + 5, ink);
                ctx.fill(bx + 6, by + 2, bx + 7, by + 4, ink);
            } else {
                GlassRender.field(ctx, bx, by, bx + BOX, by + BOX, false);
            }

            TextRenderer fr = MinecraftClient.getInstance().textRenderer;
            int lx = bx + BOX + 6;
            String label = fr.trimToWidth(labelText, Math.max(0, x2 - 4 - lx));
            int color = !this.active ? GlassTheme.textMuted()
                    : (value || isHovered()) ? GlassTheme.text() : GlassTheme.textDim();
            ctx.drawText(fr, Text.literal(label), lx, y1 + (getHeight() - fr.fontHeight) / 2 + 1, color, false);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }
}
