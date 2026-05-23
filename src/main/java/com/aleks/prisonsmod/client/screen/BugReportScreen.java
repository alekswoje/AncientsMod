package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.bugreport.BugReportClient;
import com.aleks.prisonsmod.net.Protocol;
import com.aleks.prisonsmod.net.payload.BugReportOpenPayload;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game bug-report UI. Renders in two modes that share the same screen so
 * the player never sees a screen swap mid-flow:
 *
 * <ol>
 *   <li><b>Compose</b> ({@link BugReportClient.State#OPEN}) — left half shows
 *       the sanitized snapshot the server would file, right half shows 9
 *       category checkboxes and a multi-line description box with Submit /
 *       Cancel buttons.</li>
 *   <li><b>Chat</b> ({@code SUBMITTING|AWAITING_AI|CHATTING|RESOLVED|ESCALATED}) —
 *       the snapshot stays on the left so the player can keep referring to it,
 *       and the right half becomes a chat thread with the AI: their description
 *       at the top, AI replies, follow-up text input, and Mark Resolved / Talk
 *       to Staff / Close buttons.</li>
 * </ol>
 *
 * <p>The screen is "input-only" toward the network — every Submit / Follow-up /
 * Escalate / Close routes through {@link BugReportClient}, never directly to
 * {@link com.aleks.prisonsmod.net.NetworkHandler}, so the state machine stays
 * coherent. Closing via ESC fires a {@code Close(resolved=false)} so the server
 * frees the preview token immediately.
 */
public final class BugReportScreen extends Screen {

    // ── Layout constants ─────────────────────────────────────────────────────

    private static final int MARGIN = 16;
    private static final int HEADER_H = 28;
    private static final int FOOTER_H = 48;
    private static final int GUTTER = 12;
    private static final int LINE_H = 10;
    private static final int SECTION_GAP = 8;
    private static final int SECTION_HEADER_H = 12;

    private static final int CHAT_INPUT_H = 22;
    private static final int CHAT_LINE_PADDING = 4;
    private static final int CHAT_LINE_WRAP_PAD = 6;

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
    private final List<CheckboxWidget> catWidgets = new ArrayList<>();
    private TextFieldWidget descBox;
    private TextFieldWidget followupBox;
    private ButtonWidget submitButton;
    private ButtonWidget cancelButton;
    private ButtonWidget sendFollowupButton;
    private ButtonWidget escalateButton;
    private ButtonWidget resolveButton;
    private ButtonWidget closeButton;

    /** Pixel offset for the snapshot column. Driven by mouse wheel. */
    private int leftScroll = 0;
    /** Pixel offset for the chat thread column. Driven by mouse wheel. */
    private int chatScroll = 0;

    public BugReportScreen() {
        super(Text.literal("Bug Report"));
    }

    @Override
    protected void init() {
        BugReportClient.State s = BugReportClient.currentState();

        // Pre-fill description in compose mode from the player's /bugreport args.
        int rightX = this.width / 2 + GUTTER;
        int rightW = this.width - rightX - MARGIN;

        // Categories grid (right half, top half of compose mode).
        catWidgets.clear();
        int catRowHeight = 20;
        int catColumns = 1; // single column keeps the labels readable
        int catY = HEADER_H + MARGIN;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            CheckboxWidget cb = CheckboxWidget.builder(Text.literal(CATEGORIES[i].label), this.textRenderer)
                    .pos(rightX, catY + idx * catRowHeight)
                    .checked(checked[i])
                    .callback((widget, value) -> checked[idx] = value)
                    .build();
            catWidgets.add(cb);
            this.addDrawableChild(cb);
        }

        // Description input (single-line, scrolls horizontally; MC 1.21.x lacks a stable
        // multi-line widget signature, so we use TextFieldWidget for both compose and chat
        // text entry — players can still type ~1KB of description, it just scrolls).
        int descY = catY + CATEGORIES.length * catRowHeight + 8;
        descBox = new TextFieldWidget(this.textRenderer, rightX, descY, rightW, 20,
                Text.literal("Describe what went wrong"));
        descBox.setMaxLength(Protocol.BUGREPORT_MAX_DESCRIPTION_CHARS);
        descBox.setPlaceholder(Text.literal("Describe what went wrong (when, where, what you tried)…"));
        if (!BugReportClient.currentPrefill().isEmpty()) {
            descBox.setText(BugReportClient.currentPrefill());
        }
        this.addDrawableChild(descBox);

        // Compose-mode buttons.
        int btnY = this.height - FOOTER_H + 8;
        submitButton = ButtonWidget.builder(Text.literal("Submit Report"), b -> doSubmit())
                .dimensions(rightX, btnY, rightW / 2 - 4, 20)
                .build();
        cancelButton = ButtonWidget.builder(Text.literal("Cancel"), b -> {
                    BugReportClient.close(false);
                    this.close();
                })
                .dimensions(rightX + rightW / 2 + 4, btnY, rightW / 2 - 4, 20)
                .build();
        this.addDrawableChild(submitButton);
        this.addDrawableChild(cancelButton);

        // Chat-mode widgets (followup input + buttons). Created up front but
        // toggled visible based on state.
        followupBox = new TextFieldWidget(this.textRenderer,
                rightX, this.height - FOOTER_H - CHAT_INPUT_H - 4,
                rightW, CHAT_INPUT_H,
                Text.literal("Reply to the bot…"));
        followupBox.setMaxLength(Protocol.BUGREPORT_MAX_FOLLOWUP_CHARS);
        this.addDrawableChild(followupBox);

        sendFollowupButton = ButtonWidget.builder(Text.literal("Send"), b -> doFollowup())
                .dimensions(rightX, btnY, rightW / 3 - 4, 20)
                .build();
        resolveButton = ButtonWidget.builder(Text.literal("Mark Resolved"), b -> {
                    BugReportClient.close(true);
                    this.close();
                })
                .dimensions(rightX + rightW / 3, btnY, rightW / 3 - 4, 20)
                .build();
        escalateButton = ButtonWidget.builder(Text.literal("Talk to Staff"), b -> BugReportClient.escalate())
                .dimensions(rightX + 2 * rightW / 3, btnY, rightW / 3, 20)
                .build();
        closeButton = ButtonWidget.builder(Text.literal("Close"), b -> {
                    BugReportClient.close(false);
                    this.close();
                })
                .dimensions(rightX + rightW - 60, btnY - 24, 60, 20)
                .build();
        this.addDrawableChild(sendFollowupButton);
        this.addDrawableChild(resolveButton);
        this.addDrawableChild(escalateButton);
        this.addDrawableChild(closeButton);

        applyVisibility(s);
    }

    private boolean isCompose(BugReportClient.State s) {
        return s == BugReportClient.State.OPEN || s == BugReportClient.State.IDLE;
    }

    private boolean isResolvedLike(BugReportClient.State s) {
        return s == BugReportClient.State.RESOLVED || s == BugReportClient.State.ESCALATED;
    }

    private void applyVisibility(BugReportClient.State s) {
        boolean compose = isCompose(s);
        for (CheckboxWidget cb : catWidgets) cb.visible = compose;
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
        // Visibility may need to switch from compose → chat as the state machine progresses.
        applyVisibility(BugReportClient.currentState());
        super.tick();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        drawHeader(context);
        drawSnapshotColumn(context, mouseX, mouseY);
        if (!isCompose(BugReportClient.currentState())) {
            drawChatColumn(context);
        }
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
        return false; // don't pause SP — the player may want to see the world while filing.
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    private void drawHeader(DrawContext ctx) {
        // Background bar.
        ctx.fill(0, 0, this.width, HEADER_H, 0xC0000000);

        // Title.
        String title = "Bug Report";
        BugReportClient.State s = BugReportClient.currentState();
        if (s == BugReportClient.State.AWAITING_AI) title = "Bug Report — investigating…";
        else if (s == BugReportClient.State.CHATTING) title = "Bug Report — chat with Hermes";
        else if (s == BugReportClient.State.RESOLVED) title = "Bug Report — resolved";
        else if (s == BugReportClient.State.ESCALATED) title = "Bug Report — escalated to staff";
        else if (s == BugReportClient.State.SUBMITTING) title = "Bug Report — submitting…";

        ctx.drawText(this.textRenderer, Text.literal(title), MARGIN, (HEADER_H - 9) / 2, 0xFFFFFFFF, true);

        String reportId = BugReportClient.currentReportId();
        if (!reportId.isEmpty()) {
            String label = "ID: " + reportId;
            int w = this.textRenderer.getWidth(label);
            ctx.drawText(this.textRenderer, Text.literal(label),
                    this.width - MARGIN - w, (HEADER_H - 9) / 2, 0xFFFFCC66, true);
        }

        // Separator.
        ctx.fill(0, HEADER_H, this.width, HEADER_H + 1, 0xFF555555);
    }

    private void drawSnapshotColumn(DrawContext ctx, int mouseX, int mouseY) {
        int x = MARGIN;
        int y0 = HEADER_H + MARGIN;
        int w = this.width / 2 - MARGIN - GUTTER / 2;
        int h = this.height - HEADER_H - FOOTER_H - MARGIN;

        // Background.
        ctx.fill(x, y0, x + w, y0 + h, 0x80101010);

        // Title.
        ctx.drawText(this.textRenderer, Text.literal("§eCollected stats"), x + 6, y0 + 6, 0xFFFFFFFF, true);

        // Clipped content area.
        int contentY = y0 + 22;
        int contentH = h - 28;

        ctx.enableScissor(x, contentY, x + w, contentY + contentH);
        int drawY = contentY - leftScroll;

        List<BugReportOpenPayload.Section> sections = BugReportClient.currentSections();
        if (sections.isEmpty()) {
            ctx.drawText(this.textRenderer,
                    Text.literal("§7Waiting for snapshot…"),
                    x + 8, drawY + 4, 0xFFAAAAAA, true);
        } else {
            for (BugReportOpenPayload.Section section : sections) {
                drawY = drawSection(ctx, section, x + 4, drawY, w - 8);
                drawY += SECTION_GAP;
            }
        }
        ctx.disableScissor();

        // Show scroll hint at the bottom.
        ctx.drawText(this.textRenderer,
                Text.literal("§8scroll with mouse wheel"),
                x + 6, y0 + h - 12, 0x80AAAAAA, false);
    }

    private int drawSection(DrawContext ctx, BugReportOpenPayload.Section section, int x, int y, int w) {
        int sectionColor = sectionColor(section.sectionId);
        ctx.fill(x, y, x + 3, y + SECTION_HEADER_H, sectionColor);
        ctx.drawText(this.textRenderer, Text.literal("§f" + section.title),
                x + 8, y + 2, 0xFFFFFFFF, true);
        int cur = y + SECTION_HEADER_H + 2;
        for (String line : section.lines) {
            // Wrap to multiple visual lines if needed.
            List<String> wrapped = wrap(line, w - 12);
            for (String chunk : wrapped) {
                ctx.drawText(this.textRenderer, Text.literal("§7" + chunk),
                        x + 12, cur, 0xFFCCCCCC, false);
                cur += LINE_H;
            }
        }
        return cur;
    }

    private int sectionColor(byte sectionId) {
        return switch (sectionId) {
            case Protocol.BR_SECTION_PLAYER          -> 0xFF8AC2FF;
            case Protocol.BR_SECTION_SERVER_STATE    -> 0xFFFFC857;
            case Protocol.BR_SECTION_RECENT_COMMANDS -> 0xFFD37CFF;
            case Protocol.BR_SECTION_RING_BUFFER     -> 0xFFFF6E6E;
            case Protocol.BR_SECTION_NEARBY          -> 0xFF6FE8C9;
            case Protocol.BR_SECTION_INVENTORY_LOG   -> 0xFFE6B05A;
            case Protocol.BR_SECTION_INVENTORY       -> 0xFF9DD2FF;
            default                                  -> 0xFFCFB8FF;
        };
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

    private void drawChatColumn(DrawContext ctx) {
        int x = this.width / 2 + GUTTER / 2;
        int y0 = HEADER_H + MARGIN;
        int w = this.width - x - MARGIN;
        int bottomGuard = isResolvedLike(BugReportClient.currentState())
                ? FOOTER_H + MARGIN
                : FOOTER_H + CHAT_INPUT_H + MARGIN + 4;
        int h = this.height - y0 - bottomGuard;

        // Background.
        ctx.fill(x, y0, x + w, y0 + h, 0x80101010);

        // Title.
        ctx.drawText(this.textRenderer, Text.literal("§eConversation"),
                x + 6, y0 + 6, 0xFFFFFFFF, true);

        int contentY = y0 + 22;
        int contentH = h - 28;
        ctx.enableScissor(x, contentY, x + w, contentY + contentH);

        // Render messages bottom-up so the newest are always visible by default.
        // To keep scroll natural, we lay out top-down but offset by chatScroll.
        int drawY = contentY - chatScroll;
        List<BugReportClient.ChatLine> lines = BugReportClient.currentChatLines();
        for (BugReportClient.ChatLine line : lines) {
            drawY = drawChatLine(ctx, line, x + 4, drawY, w - 8);
            drawY += CHAT_LINE_PADDING;
        }

        ctx.disableScissor();
    }

    private int drawChatLine(DrawContext ctx, BugReportClient.ChatLine line, int x, int y, int w) {
        int color;
        String prefix;
        int bg;
        switch (line.kind) {
            case AI -> {
                color = 0xFFFFFFFF;
                prefix = "Hermes: ";
                bg = 0x40224488;
            }
            case PLAYER -> {
                color = 0xFFFFFFFF;
                prefix = "You: ";
                bg = 0x40448822;
            }
            default -> {
                color = 0xFFCCCCCC;
                prefix = "* ";
                bg = 0x30AAAAAA;
            }
        }
        List<String> wrapped = wrap(prefix + line.text, w - 2 * CHAT_LINE_WRAP_PAD);
        int lineHeight = wrapped.size() * LINE_H + 6;
        ctx.fill(x, y, x + w, y + lineHeight, bg);
        int cur = y + 3;
        for (String chunk : wrapped) {
            ctx.drawText(this.textRenderer, Text.literal(chunk),
                    x + CHAT_LINE_WRAP_PAD, cur, color, false);
            cur += LINE_H;
        }
        return y + lineHeight;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private record CategoryDef(String label, int bit) {}
}
