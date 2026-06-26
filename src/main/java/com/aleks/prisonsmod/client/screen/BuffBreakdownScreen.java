package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.buffs.BuffSandboxStore;
import com.aleks.prisonsmod.client.buffs.BuffSnapshotState;
import com.aleks.prisonsmod.client.buffs.BuffStacker;
import com.aleks.prisonsmod.client.buffs.BuffTarget;
import com.aleks.prisonsmod.client.buffs.CustomModifier;
import com.aleks.prisonsmod.client.glass.GlassButton;
import com.aleks.prisonsmod.client.glass.GlassRender;
import com.aleks.prisonsmod.client.glass.GlassTextField;
import com.aleks.prisonsmod.client.glass.GlassTheme;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.payload.BuffSnapshotPayload;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interactive {@code /pickbuffs} breakdown + theorycrafting sandbox.
 *
 * <p>Per channel (Mining XP, Energy, Money, Combat, …) it lists every
 * contributing buff layer and lets the player:
 * <ul>
 *   <li>toggle a layer on/off (checkbox) to see the final value change,</li>
 *   <li>select a layer to open a <b>detail strip</b> with a slider that dials
 *       that layer's value up or down (type-aware absolute range), and</li>
 *   <li>add their own <b>Custom Modifiers</b> (Path-of-Building style) in a
 *       dedicated tab — increased %, more ×, or flat +, targeting one channel
 *       or a group (All Mining / All Combat / Everything).</li>
 * </ul>
 *
 * <p>All edits are client-only and recomputed locally by {@link BuffStacker};
 * custom modifiers and slider/toggle overrides persist via
 * {@link BuffSandboxStore}.
 */
public final class BuffBreakdownScreen extends Screen {

    // Theme — season2 frosted glass. Mode-aware chrome (panels, borders, header,
    // text, rows) is read live from GlassTheme/GlassRender at the draw sites; the
    // constants below are the handful of fixed glass tints/accents reused inline.
    private static final int HEADER_RULE   = 0x44FFFFFF;                 // hairline rule under headers
    private static final int ROW_ALT       = 0x0AFFFFFF;                 // zebra stripe
    private static final int ROW_SELECTED  = GlassTheme.withAlpha(GlassTheme.ACCENT, 0x40);
    private static final int CUSTOM_ACCENT = GlassTheme.ACCENT_SOFT;     // custom-modifier accent strip
    private static final int DELTA_UP      = GlassTheme.OK;
    private static final int DELTA_DOWN    = GlassTheme.WARN;
    private static final int DELETE_COLOR  = GlassTheme.WARN;

    private static final int PADDING = 8;
    private static final int TAB_W   = 180;
    private static final int TAB_H   = 22;
    private static final int ROW_H   = 14;
    private static final int CUSTOM_ROW_H = 18;
    private static final int CHECKBOX_W = 12;
    private static final int HEADER_H = 16;
    private static final int DETAIL_H = 34;
    private static final int BUILDER_H = 24;

    private enum View { CHANNEL, ORE_YIELDS, CUSTOM }

    private final Screen parent;
    private BuffSnapshotPayload snapshot;
    private byte activeChannel;
    private View view = View.CHANNEL;
    /** Index of the per-ore row currently expanded in the yields panel, or -1. */
    private int expandedOreIndex = -1;
    /** Selection key for the detail strip: {@code L|<ch>|<label>} or {@code C|<id>}. */
    private @Nullable String selectedKey;

    private double scrollOffset;
    private long lastRefreshAtMs;

    // ── Builder widgets (only present in CUSTOM view) ──────────────────────────
    private @Nullable GlassButton targetBtn;
    private @Nullable GlassButton kindBtn;
    private @Nullable GlassTextField valueField;
    private @Nullable GlassTextField nameField;
    private @Nullable GlassButton addBtn;
    private List<BuffTarget> targetOptions = new ArrayList<>();
    private int targetIndex;
    private int kindIndex; // 0 additive, 1 multiplicative, 2 flat — equals the kind byte
    private @Nullable String builderError;

    // ── Transient layout/hit geometry (set during render, read during input) ──
    private final List<int[]> tabRects = new ArrayList<>();      // {code,channelId,x,y,x2,y2}; code 0=chan 1=ore 2=custom
    private List<Row> activeRows = new ArrayList<>();
    private @Nullable Row selectedRow;
    private int listX, listW, listBodyTop, listBodyBottom;
    private boolean sliderActive;
    private int sliderTrackX, sliderTrackY, sliderTrackW;
    private double sliderMin, sliderMax;
    /** Slider bounds, frozen while dragging so the range can't chase the value. */
    private double sliderRangeMin, sliderRangeMax;
    private int[] resetHit  = null; // {x,y,x2,y2}
    private int[] pillHit   = null;
    private int[] deleteHit = null;
    private final List<int[]> customDeleteRects = new ArrayList<>(); // {x,y,x2,y2,id}
    private boolean draggingSlider;

    public BuffBreakdownScreen(Screen parent) {
        super(Text.literal("Pickaxe Buffs"));
        this.parent = parent;
        this.snapshot = BuffSnapshotState.latest();
        if (snapshot != null && !snapshot.channels.isEmpty()) {
            this.activeChannel = snapshot.channels.keySet().iterator().next();
        }
    }

    /** Called by NetworkHandler when a new snapshot arrives — refreshes if open. */
    public void onSnapshotUpdated() {
        this.snapshot = BuffSnapshotState.latest();
        if (snapshot != null && !snapshot.channels.containsKey(activeChannel) && !snapshot.channels.isEmpty()) {
            activeChannel = snapshot.channels.keySet().iterator().next();
        }
        selectedKey = null; // layer indices may have shifted; drop the selection
        if (this.client != null) {
            this.clearChildren();
            this.init();
        }
    }

    @Override
    protected void init() {
        addDrawableChild(new GlassButton(width - PADDING - 60, height - PADDING - 20, 60, 20,
                Text.literal("Done"), this::close).primary());

        addDrawableChild(new GlassButton(PADDING, height - PADDING - 20, 80, 20,
                Text.literal("Refresh"), this::requestRefresh));

        if (view == View.CHANNEL) {
            addDrawableChild(new GlassButton(width / 2 - 60, height - PADDING - 20, 120, 20,
                    Text.literal("Reset channel"), () -> {
                        BuffSandboxStore.resetChannel(activeChannel);
                        selectedKey = null;
                    }));
        }

        if (view == View.CUSTOM) initBuilderWidgets();
    }

    private void initBuilderWidgets() {
        targetOptions = BuffTarget.selectable(snapshot == null ? null : snapshot.channels.keySet());
        if (targetIndex >= targetOptions.size()) targetIndex = 0;

        int panelX = PADDING + TAB_W + PADDING;
        int panelRight = width - PADDING;
        int by = height - PADDING - 30 - BUILDER_H + 2; // inside panel, just above bottom buttons
        int x = panelX + PADDING;

        int targetW = 96, kindW = 84, valueW = 50, addW = 46;
        targetBtn = new GlassButton(x, by, targetW, 18, targetBtnText(), () -> {
                    targetIndex = (targetIndex + 1) % targetOptions.size();
                    if (targetBtn != null) targetBtn.setMessage(targetBtnText());
                });
        addDrawableChild(targetBtn);

        kindBtn = new GlassButton(x + targetW + 4, by, kindW, 18, kindBtnText(), () -> {
                    kindIndex = (kindIndex + 1) % 3;
                    if (kindBtn != null) kindBtn.setMessage(kindBtnText());
                });
        addDrawableChild(kindBtn);

        int valueX = x + targetW + 4 + kindW + 4;
        valueField = new GlassTextField(this.textRenderer, valueX, by, valueW, 18, Text.literal("value"));
        valueField.setMaxLength(12);
        valueField.setPlaceholder(Text.literal("25").formatted(Formatting.DARK_GRAY));
        addDrawableChild(valueField);

        int addX = panelRight - PADDING - addW;
        addBtn = new GlassButton(addX, by, addW, 18, Text.literal("Add"), this::onAddCustom).primary();
        addDrawableChild(addBtn);

        int nameX = valueX + valueW + 16; // leave room for the unit hint after the value field
        int nameW = Math.max(40, addX - 6 - nameX);
        nameField = new GlassTextField(this.textRenderer, nameX, by, nameW, 18, Text.literal("name"));
        nameField.setMaxLength(28);
        nameField.setPlaceholder(Text.literal("name (optional)").formatted(Formatting.DARK_GRAY));
        addDrawableChild(nameField);
    }

    private Text targetBtnText() {
        String n = targetOptions.isEmpty() ? "?" : targetOptions.get(targetIndex).displayName();
        return Text.literal("→ " + n);
    }

    private Text kindBtnText() {
        return Text.literal(switch (kindIndex) {
            case 0 -> "increased %";
            case 1 -> "more %";
            default -> "flat +";
        });
    }

    private void onAddCustom() {
        if (valueField == null) return;
        String vt = valueField.getText().trim();
        double parsed;
        try {
            parsed = Double.parseDouble(vt);
        } catch (NumberFormatException e) {
            builderError = "Enter a number";
            return;
        }
        byte kind = (byte) kindIndex; // index lines up with the KIND_ byte
        // increased %: fraction for the additive pool (25 -> 0.25).
        // more %: a multiplier factor (25 -> ×1.25), stacks multiplicatively.
        // flat +: literal amount.
        double value = switch (kind) {
            case BuffSnapshotPayload.KIND_ADDITIVE -> parsed / 100.0;
            case BuffSnapshotPayload.KIND_MULTIPLICATIVE -> 1.0 + parsed / 100.0;
            default -> parsed;
        };
        BuffTarget target = targetOptions.isEmpty() ? BuffTarget.everything() : targetOptions.get(targetIndex);
        String name = nameField == null ? "" : nameField.getText().trim();
        BuffSandboxStore.addCustom(name, target, kind, value);
        valueField.setText("");
        if (nameField != null) nameField.setText("");
        builderError = null;
    }

    private void requestRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshAtMs < 1100L) return;
        lastRefreshAtMs = now;
        NetworkHandler.sendBuffRefreshRequest();
    }

    private void switchView(View v) {
        if (view == v) return;
        view = v;
        scrollOffset = 0;
        selectedKey = null;
        this.clearChildren();
        this.init();
    }

    // ── Row model ──────────────────────────────────────────────────────────────

    /** A renderable/clickable line — a snapshot layer (with overrides applied) or a custom modifier. */
    private static final class Row {
        boolean custom;
        int snapshotIndex = -1;
        CustomModifier mod;
        byte channelId;
        String rawLabel = "";   // snapshot label — the override key (real rows)
        String label = "";      // display label
        String detail = "";
        byte category;
        byte kind;
        byte state;
        double value;
        double liveValue;
        boolean enabled;
        boolean defaultEnabled;
        boolean toggleable;
        boolean sliderable;
        /** A custom flat modifier on a pure-multiplier channel: it doesn't change
         *  the multiplier, it adds per-ore — so it's excluded from the header math
         *  and shown with a "per-ore" note. */
        boolean perOreOnly;
        String selKey = "";
    }

    private List<Row> buildChannelRows(BuffSnapshotPayload.Channel ch) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < ch.layers.size(); i++) {
            BuffSnapshotPayload.Layer l = ch.layers.get(i);
            boolean structural = l.kind == BuffSnapshotPayload.KIND_BASE
                              || l.kind == BuffSnapshotPayload.KIND_PROC_DAMAGE;
            boolean defActive = l.state == BuffSnapshotPayload.STATE_ACTIVE;
            Row r = new Row();
            r.custom = false;
            r.snapshotIndex = i;
            r.channelId = ch.id;
            r.rawLabel = l.label;
            r.label = l.label;
            r.detail = l.detail;
            r.category = l.category;
            r.kind = l.kind;
            r.state = l.state;
            r.liveValue = l.value;
            r.value = structural ? l.value : BuffSandboxStore.value(ch.id, l.label, l.value);
            r.defaultEnabled = defActive;
            r.enabled = structural || BuffSandboxStore.enabled(ch.id, l.label, defActive);
            r.toggleable = !structural;
            // Crit rows toggle on/off but aren't sliderable — their values are small
            // fractions (a 1.5% chance), so the flat-bonus slider step is meaningless.
            r.sliderable = (l.kind == BuffSnapshotPayload.KIND_ADDITIVE
                        || l.kind == BuffSnapshotPayload.KIND_MULTIPLICATIVE
                        || l.kind == BuffSnapshotPayload.KIND_FLAT_BONUS
                        || l.kind == BuffSnapshotPayload.KIND_LUCKY_PROC)
                        && !isCritChannel(ch.id);
            r.selKey = "L|" + ch.id + "|" + l.label;
            rows.add(r);
        }
        boolean hasBase = channelHasBase(ch.id);
        for (CustomModifier m : BuffSandboxStore.customsTargeting(ch.id)) {
            Row r = customRow(m);
            // On a pure-multiplier channel (mining), a flat modifier applies
            // per-ore, not to the multiplier — flag it so the header math skips
            // it and the row reads "custom · per-ore".
            if (!hasBase && m.kind == BuffSnapshotPayload.KIND_FLAT_BONUS) {
                r.perOreOnly = true;
                r.detail = "custom · per-ore";
            }
            rows.add(r);
        }
        return rows;
    }

    private boolean channelHasBase(byte id) {
        BuffSnapshotPayload.Channel c = snapshot == null ? null : snapshot.channels.get(id);
        if (c == null) return false;
        for (BuffSnapshotPayload.Layer l : c.layers) {
            if (l.kind == BuffSnapshotPayload.KIND_BASE) return true;
        }
        return false;
    }

    private List<Row> buildCustomRows() {
        List<Row> rows = new ArrayList<>();
        for (CustomModifier m : BuffSandboxStore.customs()) rows.add(customRow(m));
        return rows;
    }

    private Row customRow(CustomModifier m) {
        Row r = new Row();
        r.custom = true;
        r.mod = m;
        r.label = "✦ " + m.displayName();
        r.detail = m.detailLine();
        r.kind = m.kind;
        r.state = BuffSnapshotPayload.STATE_ACTIVE;
        r.value = m.value;
        r.liveValue = m.value;
        r.enabled = m.enabled;
        r.defaultEnabled = true;
        r.toggleable = true;
        r.sliderable = true;
        r.selKey = "C|" + m.id;
        return r;
    }

    /**
     * Recompute a channel's sandbox multiplier/value from the current rows.
     * Per-ore-only flat modifiers are excluded — they don't move the channel
     * multiplier (they're applied to each ore's base in the yields tab).
     */
    private BuffStacker.Result computeRows(List<Row> rows) {
        List<BuffSnapshotPayload.Layer> eff = new ArrayList<>(rows.size());
        BitSet active = new BitSet(rows.size());
        for (Row r : rows) {
            if (r.perOreOnly) continue;
            eff.add(new BuffSnapshotPayload.Layer(r.label, r.detail, r.category, r.kind, r.state, r.value));
            if (r.enabled) active.set(eff.size() - 1);
        }
        return BuffStacker.compute(eff, active::get);
    }

    // ── Per-ore sandbox helpers ────────────────────────────────────────────────

    /** Sandbox/server ratio for a channel — how much the sandbox scaled it. */
    private double channelRatio(byte id) {
        BuffSnapshotPayload.Channel c = snapshot == null ? null : snapshot.channels.get(id);
        if (c == null || c.serverFinalValue == 0.0) return 1.0;
        return computeRows(buildChannelRows(c)).finalValue / c.serverFinalValue;
    }

    /** Sum of enabled custom flat modifiers that apply per-ore to this channel. */
    private double channelPerOreFlat(byte id) {
        if (channelHasBase(id)) return 0.0; // absolute channels fold flat into the header instead
        double sum = 0.0;
        for (CustomModifier m : BuffSandboxStore.customsTargeting(id)) {
            if (m.enabled && m.kind == BuffSnapshotPayload.KIND_FLAT_BONUS) sum += m.value;
        }
        return sum;
    }

    /** (base + flat) × sandboxMultiplier, expressed via the server per-ore value. */
    private static double sandboxPerOre(double base, double serverPerOre, double flat, double ratio) {
        double scaled = serverPerOre * ratio;
        if (flat != 0.0) {
            if (base > 0.0) scaled *= (base + flat) / base;
            else scaled += flat * ratio;
        }
        return scaled;
    }

    private @Nullable Row resolveSelectedRow() {
        if (selectedKey == null) return null;
        for (Row r : activeRows) if (r.selKey.equals(selectedKey)) return r;
        return null;
    }

    // ── Render ─────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        GlassRender.menuBackdrop(ctx, this.width, this.height);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Pickaxe Buffs — interactive breakdown"),
                width / 2, PADDING, GlassTheme.ACCENT_SOFT);

        if (snapshot == null || snapshot.channels.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("No snapshot yet. Press Refresh."),
                    width / 2, height / 2, GlassTheme.textDim());
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        int topY = PADDING + 24;
        int contentBottom = height - PADDING - 30;
        int leftRailX = PADDING;
        int panelX = leftRailX + TAB_W + PADDING;
        int panelY = topY;
        int panelW = width - panelX - PADDING;
        int panelH = contentBottom - topY;

        renderLeftRail(ctx, leftRailX, topY, TAB_W, contentBottom - topY, mouseX, mouseY);

        // Reset transient input geometry each frame.
        sliderActive = false;
        resetHit = pillHit = deleteHit = null;
        customDeleteRects.clear();
        selectedRow = null;

        switch (view) {
            case ORE_YIELDS -> renderOrePanel(ctx, panelX, panelY, panelW, panelH, mouseX, mouseY);
            case CUSTOM     -> renderCustomPanel(ctx, panelX, panelY, panelW, panelH, mouseX, mouseY);
            case CHANNEL    -> renderChannelPanel(ctx, panelX, panelY, panelW, panelH, mouseX, mouseY);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderLeftRail(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        GlassRender.roundedRectGrad(ctx, x, y, x + w, y + h, GlassRender.RADIUS, GlassTheme.panelTop(), GlassTheme.panelBot());
        GlassRender.roundedBorder(ctx, x, y, x + w, y + h, GlassRender.RADIUS, GlassTheme.rim());
        tabRects.clear();

        int rowY = y + 4;
        int leftPad = 8, rightPad = 6, gap = 6;
        for (Map.Entry<Byte, BuffSnapshotPayload.Channel> e : snapshot.channels.entrySet()) {
            if (rowY + TAB_H > y + h) break;
            byte chId = e.getKey();
            BuffSnapshotPayload.Channel ch = e.getValue();
            boolean active = view == View.CHANNEL && chId == activeChannel;
            boolean hovered = mouseX >= x + 2 && mouseX <= x + w - 2 && mouseY >= rowY && mouseY <= rowY + TAB_H - 2;
            int bg = active ? GlassTheme.withAlpha(GlassTheme.ACCENT, 0x33) : (hovered ? GlassTheme.rowHover() : 0);
            if (bg != 0) ctx.fill(x + 2, rowY, x + w - 2, rowY + TAB_H - 2, bg);
            int textY = rowY + (TAB_H - 2 - textRenderer.fontHeight) / 2;

            String mini = formatValueForChannel(chId, ch.serverFinalValue);
            int miniW = textRenderer.getWidth(mini);
            int miniX = x + w - rightPad - miniW;
            ctx.drawText(textRenderer, Text.literal(mini), miniX, textY, GlassTheme.textDim(), true);

            int nameRoom = Math.max(0, miniX - gap - (x + leftPad));
            String fitted = textRenderer.trimToWidth(BuffSnapshotPayload.channelDisplayName(chId), nameRoom);
            ctx.drawText(textRenderer, Text.literal(fitted), x + leftPad, textY, active ? GlassTheme.ACCENT_SOFT : GlassTheme.text(), true);

            tabRects.add(new int[]{0, chId, x + 2, rowY, x + w - 2, rowY + TAB_H - 2});
            rowY += TAB_H;
        }

        // Synthetic tabs (divider above).
        if (rowY + TAB_H <= y + h) {
            ctx.fill(x + 6, rowY, x + w - 6, rowY + 1, HEADER_RULE);
            rowY += 3;
        }
        if (snapshot.oreYields != null && !snapshot.oreYields.isEmpty() && rowY + TAB_H <= y + h) {
            rowY = drawSyntheticTab(ctx, x, w, rowY, "Per-Ore Yields", view == View.ORE_YIELDS, mouseX, mouseY, 1);
        }
        if (rowY + TAB_H <= y + h) {
            int n = BuffSandboxStore.customs().size();
            String label = "Custom Modifiers" + (n > 0 ? " (" + n + ")" : "");
            drawSyntheticTab(ctx, x, w, rowY, label, view == View.CUSTOM, mouseX, mouseY, 2);
        }
    }

    private int drawSyntheticTab(DrawContext ctx, int x, int w, int rowY, String label, boolean active,
                                  int mouseX, int mouseY, int code) {
        boolean hovered = mouseX >= x + 2 && mouseX <= x + w - 2 && mouseY >= rowY && mouseY <= rowY + TAB_H - 2;
        int bg = active ? GlassTheme.withAlpha(GlassTheme.ACCENT, 0x33) : (hovered ? GlassTheme.rowHover() : 0);
        if (bg != 0) ctx.fill(x + 2, rowY, x + w - 2, rowY + TAB_H - 2, bg);
        int textY = rowY + (TAB_H - 2 - textRenderer.fontHeight) / 2;
        int room = Math.max(0, (x + w - 6) - (x + 8));
        String fitted = textRenderer.trimToWidth(label, room);
        ctx.drawText(textRenderer, Text.literal(fitted), x + 8, textY, active ? GlassTheme.ACCENT_SOFT : GlassTheme.text(), true);
        tabRects.add(new int[]{code, 0, x + 2, rowY, x + w - 2, rowY + TAB_H - 2});
        return rowY + TAB_H;
    }

    private void renderChannelPanel(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        GlassRender.roundedRectGrad(ctx, x, y, x + w, y + h, GlassRender.RADIUS, GlassTheme.panelTop(), GlassTheme.panelBot());
        GlassRender.roundedBorder(ctx, x, y, x + w, y + h, GlassRender.RADIUS, GlassTheme.rim());

        BuffSnapshotPayload.Channel ch = snapshot.channels.get(activeChannel);
        if (ch == null) return;

        activeRows = buildChannelRows(ch);
        selectedRow = resolveSelectedRow();
        BuffStacker.Result sandbox = computeRows(activeRows);

        // Header.
        ctx.fill(x + GlassRender.RADIUS, y + 1, x + w - GlassRender.RADIUS, y + 1 + HEADER_H, GlassTheme.headerWash());
        ctx.fill(x + 1, y + 1 + HEADER_H, x + w - 1, y + 1 + HEADER_H + 1, HEADER_RULE);
        ctx.drawText(textRenderer, Text.literal(BuffSnapshotPayload.channelDisplayName(ch.id)),
                x + PADDING, y + 5, GlassTheme.ACCENT_SOFT, true);
        String current = formatValueForChannel(ch.id, ch.serverFinalValue);
        String sand = formatValueForChannel(ch.id, sandbox.finalValue);
        int deltaColor = Math.abs(sandbox.finalValue - ch.serverFinalValue) < 1e-6
                ? GlassTheme.text() : (sandbox.finalValue > ch.serverFinalValue ? DELTA_UP : DELTA_DOWN);
        String arrow = "  →  ";
        String composite = current + arrow + sand;
        int compX = x + w - PADDING - textRenderer.getWidth(composite);
        ctx.drawText(textRenderer, Text.literal(current), compX, y + 5, GlassTheme.textDim(), true);
        ctx.drawText(textRenderer, Text.literal(arrow), compX + textRenderer.getWidth(current), y + 5, GlassTheme.textDim(), true);
        ctx.drawText(textRenderer, Text.literal(sand),
                compX + textRenderer.getWidth(current + arrow), y + 5, deltaColor, true);

        // Body geometry — leave room for footer + (optional) detail strip.
        int footerTop = y + h - 14;
        int detailH = (selectedRow != null && selectedRow.sliderable) ? DETAIL_H : 0;
        int detailTop = footerTop - detailH;
        listX = x;
        listW = w;
        listBodyTop = y + 1 + HEADER_H + 4;
        listBodyBottom = detailTop - (detailH > 0 ? 2 : 0);

        renderRowList(ctx, activeRows, ROW_H, sandbox, mouseX, mouseY);

        if (detailH > 0) renderDetailStrip(ctx, x, detailTop, w, detailH, selectedRow, mouseX, mouseY);

        // Footer composition hint.
        ctx.fill(x + 1, footerTop, x + w - 1, footerTop + 1, HEADER_RULE);
        String footer = String.format(Locale.US, "additive pool × %s × multipliers × %s",
                formatX(sandbox.additivePool), formatX(sandbox.multiplicativeProduct));
        ctx.drawText(textRenderer, Text.literal(footer), x + PADDING, footerTop + 3, GlassTheme.textDim(), false);
    }

    /** Shared compact row list for both the channel view and the custom-modifier list. */
    private void renderRowList(DrawContext ctx, List<Row> rows, int rowH, BuffStacker.Result sandbox,
                               int mouseX, int mouseY) {
        int totalH = rows.size() * rowH;
        int viewH = listBodyBottom - listBodyTop;
        double maxScroll = Math.max(0, totalH - viewH);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;

        ctx.enableScissor(listX + 2, listBodyTop, listX + listW - 2, listBodyBottom);
        int rowY = listBodyTop - (int) scrollOffset;
        for (Row r : rows) {
            if (rowY + rowH >= listBodyTop && rowY < listBodyBottom) {
                renderRow(ctx, listX + 4, rowY, listW - 8, rowH, r, mouseX, mouseY, sandbox);
            }
            rowY += rowH;
        }
        ctx.disableScissor();
    }

    private void renderRow(DrawContext ctx, int x, int y, int w, int rowH, Row r,
                           int mouseX, int mouseY, BuffStacker.Result sandbox) {
        boolean selected = selectedKey != null && selectedKey.equals(r.selKey);
        boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + rowH;
        if (selected) ctx.fill(x, y, x + w, y + rowH, ROW_SELECTED);
        else if (hovered) ctx.fill(x, y, x + w, y + rowH, GlassTheme.rowHover());
        else if (((y / rowH) & 1) == 1) ctx.fill(x, y, x + w, y + rowH, ROW_ALT);

        int accent = r.custom ? CUSTOM_ACCENT : BuffSnapshotPayload.categoryColor(r.category);
        ctx.fill(x + 2, y + 2, x + 4, y + rowH - 2, accent);

        int checkboxX = x + 8;
        int checkboxY = y + (rowH - CHECKBOX_W) / 2;
        if (r.toggleable) {
            GlassRender.slot(ctx, checkboxX, checkboxY, checkboxX + CHECKBOX_W, checkboxY + CHECKBOX_W);
            if (r.enabled) {
                GlassRender.roundedRect(ctx, checkboxX + 2, checkboxY + 2,
                        checkboxX + CHECKBOX_W - 2, checkboxY + CHECKBOX_W - 2, 2, accent);
            }
        }

        int labelX = checkboxX + CHECKBOX_W + 6;
        int textY = y + (rowH - textRenderer.fontHeight) / 2;
        int labelColor = (!r.enabled && r.toggleable) ? GlassTheme.textDim()
                : (r.state == BuffSnapshotPayload.STATE_POTENTIAL ? GlassTheme.textDim() : GlassTheme.text());
        if (r.kind == BuffSnapshotPayload.KIND_PROC_DAMAGE) labelColor = GlassTheme.text();

        // Reserve space on the right for the value (+ delete glyph in the custom list).
        boolean deletable = r.custom && view == View.CUSTOM;
        int rightEdge = x + w - 6;
        if (deletable) {
            String del = "✕";
            int delW = textRenderer.getWidth(del);
            int delX = rightEdge - delW;
            boolean delHover = mouseX >= delX - 2 && mouseX <= delX + delW + 2 && mouseY >= y && mouseY <= y + rowH;
            ctx.drawText(textRenderer, Text.literal(del), delX, textY, delHover ? 0xFFFF5555 : DELETE_COLOR, true);
            customDeleteRects.add(new int[]{delX - 2, y, delX + delW + 2, y + rowH, r.mod.id});
            rightEdge = delX - 8;
        }

        String valueStr = rowValueString(r, sandbox);
        int valW = textRenderer.getWidth(valueStr);
        int valColor = (!r.enabled && r.toggleable) ? GlassTheme.textDim() : GlassTheme.text();
        ctx.drawText(textRenderer, Text.literal(valueStr), rightEdge - valW, textY, valColor, true);

        int labelRoom = Math.max(0, (rightEdge - valW - 6) - labelX);
        String fittedLabel = textRenderer.trimToWidth(r.label, labelRoom);
        ctx.drawText(textRenderer, Text.literal(fittedLabel), labelX, textY, labelColor, true);
        int usedW = textRenderer.getWidth(fittedLabel);
        if (!r.detail.isEmpty() && usedW < labelRoom) {
            String detail = textRenderer.trimToWidth("  " + r.detail, labelRoom - usedW);
            ctx.drawText(textRenderer, Text.literal(detail), labelX + usedW, textY, GlassTheme.textDim(), false);
        }
    }

    private String rowValueString(Row r, BuffStacker.Result sandbox) {
        if (r.kind == BuffSnapshotPayload.KIND_PROC_DAMAGE) {
            double displayed = r.value * sandbox.additivePool * sandbox.multiplicativeProduct * sandbox.luckyProcMult;
            return String.format(Locale.US, "%.2f", displayed);
        }
        // Crit channels store fractional chance/multi — show base + per-source rows as %.
        if (!r.custom && isCritChannel(r.channelId)
                && (r.kind == BuffSnapshotPayload.KIND_BASE || r.kind == BuffSnapshotPayload.KIND_FLAT_BONUS)) {
            return formatCritPct(r.channelId, r.value);
        }
        // Custom "more" modifiers read as "X% more"; server multiplicative layers
        // (boosters, fate cards) keep their native ×.
        if (r.custom && r.kind == BuffSnapshotPayload.KIND_MULTIPLICATIVE) return formatMorePercent(r.value);
        return formatKindValue(r.kind, r.value);
    }

    private void renderDetailStrip(DrawContext ctx, int x, int top, int w, int h, Row r, int mouseX, int mouseY) {
        ctx.fill(x + GlassRender.RADIUS, top, x + w - GlassRender.RADIUS, top + 1, HEADER_RULE);
        GlassRender.roundedRect(ctx, x + GlassRender.RADIUS, top + 2, x + w - GlassRender.RADIUS, top + h, 6, GlassTheme.slot());

        int innerX = x + PADDING;
        int innerRight = x + w - PADDING;

        // Line 1: "Adjusting: <label>" + on/off pill + reset/delete.
        String head = "Adjusting: " + stripGlyph(r.label);
        ctx.drawText(textRenderer, Text.literal(textRenderer.trimToWidth(head, w / 2)), innerX, top + 4, GlassTheme.ACCENT_SOFT, true);

        int btnY = top + 3;
        // Reset (real) or Delete (custom) — far right.
        String actionText = r.custom ? "Delete" : "Reset";
        int actionColor = r.custom ? DELETE_COLOR : GlassTheme.textDim();
        int actionW = textRenderer.getWidth(actionText);
        int actionX = innerRight - actionW;
        boolean actionHover = mouseX >= actionX - 2 && mouseX <= actionX + actionW + 2 && mouseY >= btnY && mouseY <= btnY + 10;
        ctx.drawText(textRenderer, Text.literal(actionText), actionX, btnY,
                actionHover ? 0xFFFFFFFF : actionColor, true);
        if (r.custom) deleteHit = new int[]{actionX - 2, btnY, actionX + actionW + 2, btnY + 10};
        else resetHit = new int[]{actionX - 2, btnY, actionX + actionW + 2, btnY + 10};

        // On/Off pill — to the left of the action.
        String pill = r.enabled ? "On" : "Off";
        int pillW = textRenderer.getWidth(pill) + 10;
        int pillX = actionX - 8 - pillW;
        boolean pillHover = mouseX >= pillX && mouseX <= pillX + pillW && mouseY >= btnY - 1 && mouseY <= btnY + 10;
        GlassRender.button(ctx, pillX, btnY - 1, pillX + pillW, btnY + 10, pillHover, true, r.enabled);
        ctx.drawText(textRenderer, Text.literal(pill), pillX + 5, btnY,
                r.enabled ? 0xFFFFFFFF : GlassTheme.textDim(), true);
        pillHit = new int[]{pillX, btnY - 1, pillX + pillW, btnY + 10};

        // Line 2: slider + value readout. Freeze the range while dragging — if we
        // recomputed it from the live value every frame, dragging the knob to the
        // edge would keep doubling the max and the value would run away.
        if (!draggingSlider) {
            double[] range = rangeFor(r.kind, r.liveValue, r.value);
            sliderRangeMin = range[0];
            sliderRangeMax = range[1];
        }
        double min = sliderRangeMin, max = sliderRangeMax;
        String readout = (r.custom && r.kind == BuffSnapshotPayload.KIND_MULTIPLICATIVE)
                ? formatMorePercent(r.value) : formatKindValue(r.kind, r.value);
        int readoutW = textRenderer.getWidth(readout);
        int readoutX = innerRight - readoutW;
        int trackX = innerX;
        int trackY = top + 20;
        int trackW = Math.max(20, (readoutX - 8) - trackX);
        double frac = (max > min) ? (r.value - min) / (max - min) : 0.0;
        frac = Math.max(0.0, Math.min(1.0, frac));
        GlassRender.sliderTrack(ctx, trackX, trackY, trackX + trackW, trackY + 5, (float) frac);
        int fillW = (int) Math.round(frac * trackW);
        int knobX = trackX + fillW;
        GlassRender.roundedRect(ctx, knobX - 2, trackY - 3, knobX + 2, trackY + 8, 2, 0xFFFFFFFF);
        ctx.drawText(textRenderer, Text.literal(readout), readoutX, top + 18, GlassTheme.text(), true);

        sliderActive = true;
        sliderTrackX = trackX;
        sliderTrackY = trackY;
        sliderTrackW = trackW;
        sliderMin = min;
        sliderMax = max;
    }

    private void renderCustomPanel(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        GlassRender.roundedRectGrad(ctx, x, y, x + w, y + h, GlassRender.RADIUS, GlassTheme.panelTop(), GlassTheme.panelBot());
        GlassRender.roundedBorder(ctx, x, y, x + w, y + h, GlassRender.RADIUS, GlassTheme.rim());

        activeRows = buildCustomRows();
        selectedRow = resolveSelectedRow();

        // Header.
        ctx.fill(x + GlassRender.RADIUS, y + 1, x + w - GlassRender.RADIUS, y + 1 + HEADER_H, GlassTheme.headerWash());
        ctx.fill(x + 1, y + 1 + HEADER_H, x + w - 1, y + 1 + HEADER_H + 1, HEADER_RULE);
        ctx.drawText(textRenderer, Text.literal("Custom Modifiers"), x + PADDING, y + 5, GlassTheme.ACCENT_SOFT, true);
        ctx.drawText(textRenderer, Text.literal("your what-if modifiers — fold into every matching channel"),
                x + PADDING + textRenderer.getWidth("Custom Modifiers") + 8, y + 5, GlassTheme.textDim(), false);

        int builderTop = (addBtn != null) ? addBtn.getY() - 4 : y + h - BUILDER_H - 2;
        int detailH = (selectedRow != null && selectedRow.sliderable) ? DETAIL_H : 0;
        int detailTop = builderTop - detailH - 2;
        listX = x;
        listW = w;
        listBodyTop = y + 1 + HEADER_H + 4;
        listBodyBottom = detailTop;

        if (activeRows.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("No custom modifiers yet — add one below.").formatted(Formatting.GRAY),
                    x + PADDING, listBodyTop + 6, GlassTheme.textDim(), false);
        } else {
            renderRowList(ctx, activeRows, CUSTOM_ROW_H, BuffStacker.compute(List.of(), i -> false), mouseX, mouseY);
        }

        if (detailH > 0) renderDetailStrip(ctx, x, detailTop, w, detailH, selectedRow, mouseX, mouseY);

        // Builder hints (the widgets themselves render in super.render()).
        if (valueField != null) {
            String unit = switch (kindIndex) { case 0 -> "%"; case 1 -> "%"; default -> "+"; };
            ctx.drawText(textRenderer, Text.literal(unit),
                    valueField.getX() + valueField.getWidth() + 4, valueField.getY() + 5, GlassTheme.textDim(), true);
            ctx.drawText(textRenderer, Text.literal("Add modifier:").formatted(Formatting.GRAY),
                    x + PADDING, valueField.getY() - 11, GlassTheme.textDim(), false);
            if (builderError != null) {
                ctx.drawText(textRenderer, Text.literal(builderError).formatted(Formatting.RED),
                        x + PADDING + textRenderer.getWidth("Add modifier: ") + 6, valueField.getY() - 11, 0xFFFF6E6E, false);
            }
        }
    }

    // ── Per-ore yields panel (informational; unchanged behaviour) ──────────────

    private void renderOrePanel(DrawContext ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
        GlassRender.roundedRectGrad(ctx, x, y, x + w, y + h, GlassRender.RADIUS, GlassTheme.panelTop(), GlassTheme.panelBot());
        GlassRender.roundedBorder(ctx, x, y, x + w, y + h, GlassRender.RADIUS, GlassTheme.rim());

        ctx.fill(x + GlassRender.RADIUS, y + 1, x + w - GlassRender.RADIUS, y + 1 + HEADER_H, GlassTheme.headerWash());
        ctx.fill(x + 1, y + 1 + HEADER_H, x + w - 1, y + 1 + HEADER_H + 1, HEADER_RULE);
        ctx.drawText(textRenderer, Text.literal("Per-Ore Yields"), x + PADDING, y + 5, GlassTheme.ACCENT_SOFT, true);
        ctx.drawText(textRenderer, Text.literal("click any row for the math"),
                x + PADDING + textRenderer.getWidth("Per-Ore Yields") + 8, y + 5, GlassTheme.textDim(), false);

        int bodyY = y + 1 + HEADER_H + 4;
        int bodyH = h - (1 + HEADER_H + 4) - 4;
        int rightPad = 6;
        int oreColW = 72;
        int dataW = w - PADDING - oreColW - rightPad;
        int colWidth = dataW / 4;
        int oreX = x + PADDING;
        int xpRight     = x + PADDING + oreColW + colWidth;
        int energyRight = x + PADDING + oreColW + colWidth * 2;
        int moneyRight  = x + PADDING + oreColW + colWidth * 3;
        int shardRight  = x + PADDING + oreColW + colWidth * 4;

        ctx.drawText(textRenderer, Text.literal("Ore"), oreX, bodyY, GlassTheme.textDim(), false);
        drawRightAligned(ctx, "XP",     xpRight - 4,     bodyY, GlassTheme.textDim());
        drawRightAligned(ctx, "Energy", energyRight - 4, bodyY, GlassTheme.textDim());
        drawRightAligned(ctx, "Money",  moneyRight - 4,  bodyY, GlassTheme.textDim());
        drawRightAligned(ctx, "Shard%", shardRight - 4,  bodyY, GlassTheme.textDim());
        ctx.fill(x + 2, bodyY + textRenderer.fontHeight + 2, x + w - 2, bodyY + textRenderer.fontHeight + 3, HEADER_RULE);

        // Per-channel sandbox scaling: ratio = sandbox/server multiplier change,
        // flat = custom flat modifiers applied to each ore's base.
        double rXp = channelRatio(BuffSnapshotPayload.CH_MINING_XP),     fXp = channelPerOreFlat(BuffSnapshotPayload.CH_MINING_XP);
        double rEn = channelRatio(BuffSnapshotPayload.CH_MINING_ENERGY), fEn = channelPerOreFlat(BuffSnapshotPayload.CH_MINING_ENERGY);
        double rMo = channelRatio(BuffSnapshotPayload.CH_MINING_MONEY),  fMo = channelPerOreFlat(BuffSnapshotPayload.CH_MINING_MONEY);
        double rSh = channelRatio(BuffSnapshotPayload.CH_MINING_SHARDS), fSh = channelPerOreFlat(BuffSnapshotPayload.CH_MINING_SHARDS);

        int rowsStartY = bodyY + textRenderer.fontHeight + 5;
        ctx.enableScissor(x + 2, rowsStartY, x + w - 2, bodyY + bodyH);
        int rowY = rowsStartY - (int) scrollOffset;
        int i = 0;
        for (BuffSnapshotPayload.OreYield o : snapshot.oreYields) {
            boolean expanded = (i == expandedOreIndex);
            boolean hovered = mouseY >= rowY && mouseY <= rowY + ROW_H && mouseX >= x + 2 && mouseX <= x + w - 2;
            if (rowY + ROW_H >= rowsStartY && rowY < bodyY + bodyH) {
                if (hovered) ctx.fill(x + 2, rowY, x + w - 2, rowY + ROW_H, GlassTheme.rowHover());
                else if (expanded) ctx.fill(x + 2, rowY, x + w - 2, rowY + ROW_H, GlassTheme.withAlpha(GlassTheme.ACCENT, 0x33));
                else if ((i & 1) == 1) ctx.fill(x + 2, rowY, x + w - 2, rowY + ROW_H, ROW_ALT);
                int textY = rowY + (ROW_H - textRenderer.fontHeight) / 2;
                String tri = expanded ? "▼ " : "▶ ";
                ctx.drawText(textRenderer, Text.literal(tri), oreX, textY, GlassTheme.textDim(), false);
                int nameX = oreX + textRenderer.getWidth(tri);
                ctx.drawText(textRenderer, Text.literal(o.displayName), nameX, textY, GlassTheme.text(), true);
                drawTransformCell(ctx, formatYieldXp(o.baseXp), formatYieldXp(sandboxPerOre(o.baseXp, o.xpPerOre, fXp, rXp)), xpRight - 4, textY);
                drawTransformCell(ctx, formatYieldEnergy(o.baseEnergy), formatYieldEnergy(sandboxPerOre(o.baseEnergy, o.energyPerOre, fEn, rEn)), energyRight - 4, textY);
                drawTransformCell(ctx, formatYieldMoney(o.baseMoney), formatYieldMoney(sandboxPerOre(o.baseMoney, o.moneyPerOre, fMo, rMo)), moneyRight - 4, textY);
                drawTransformCell(ctx, formatYieldShard(o.baseShard), formatYieldShard(sandboxPerOre(o.baseShard, o.shardChancePerOre, fSh, rSh)), shardRight - 4, textY);
            }
            rowY += ROW_H;
            if (expanded) rowY += renderOreBreakdownRows(ctx, x, rowY, w, o);
            i++;
        }
        ctx.disableScissor();
    }

    private int renderOreBreakdownRows(DrawContext ctx, int x, int startY, int w, BuffSnapshotPayload.OreYield ore) {
        int rowH = 11, lineY = startY, indent = PADDING + 14, lineW = w - indent - PADDING;
        renderBreakdownLine(ctx, "XP",     ore.baseXp,     ore.xpPerOre,          BuffSnapshotPayload.CH_MINING_XP,     x + indent, lineY, lineW); lineY += rowH;
        renderBreakdownLine(ctx, "Energy", ore.baseEnergy, ore.energyPerOre,      BuffSnapshotPayload.CH_MINING_ENERGY, x + indent, lineY, lineW); lineY += rowH;
        renderBreakdownLine(ctx, "Money",  ore.baseMoney,  ore.moneyPerOre,       BuffSnapshotPayload.CH_MINING_MONEY,  x + indent, lineY, lineW); lineY += rowH;
        renderBreakdownLine(ctx, "Shard",  ore.baseShard,  ore.shardChancePerOre, BuffSnapshotPayload.CH_MINING_SHARDS, x + indent, lineY, lineW); lineY += rowH;
        ctx.fill(x + indent, lineY, x + w - PADDING, lineY + 1, HEADER_RULE);
        lineY += 3;
        return lineY - startY;
    }

    /** Sandbox-aware per-ore math: {@code (base [+ flat]) × enabled-multipliers = result}. */
    private void renderBreakdownLine(DrawContext ctx, String label, double base, double serverPerOre,
                                     byte channelId, int x, int y, int maxWidth) {
        double flat = channelPerOreFlat(channelId);
        double ratio = channelRatio(channelId);
        double result = sandboxPerOre(base, serverPerOre, flat, ratio);

        StringBuilder sb = new StringBuilder();
        if (flat != 0.0) sb.append('(').append(formatNum(base)).append(" + ").append(formatNum(flat)).append(')');
        else sb.append(formatNum(base));

        BuffSnapshotPayload.Channel ch = snapshot.channels.get(channelId);
        if (ch != null) {
            for (Row r : buildChannelRows(ch)) {
                if (!r.enabled || r.perOreOnly) continue;
                if (r.kind == BuffSnapshotPayload.KIND_BASE
                        || r.kind == BuffSnapshotPayload.KIND_PROC_DAMAGE
                        || r.kind == BuffSnapshotPayload.KIND_LUCKY_PROC) continue;
                String shortLabel = shortLayerLabel(stripGlyph(r.label));
                if (r.kind == BuffSnapshotPayload.KIND_ADDITIVE) {
                    if (Math.abs(r.value) < 0.0001) continue;
                    sb.append("  × ").append(formatSignedPct(r.value)).append(' ').append(shortLabel);
                } else if (r.kind == BuffSnapshotPayload.KIND_MULTIPLICATIVE) {
                    if (r.custom) {
                        sb.append("  × (").append(formatMorePercent(r.value)).append(") ").append(shortLabel);
                    } else {
                        if (Math.abs(r.value - 1.0) < 0.0001) continue;
                        sb.append("  × ").append(formatXShort(r.value)).append(' ').append(shortLabel);
                    }
                } else if (r.kind == BuffSnapshotPayload.KIND_FLAT_BONUS) {
                    if (Math.abs(r.value) < 0.0001) continue;
                    sb.append("  + ").append(formatNum(r.value)).append(' ').append(shortLabel);
                }
            }
        }
        sb.append("  =  ").append(formatNum(result));
        String labelText = label + ": ";
        int labelW = textRenderer.getWidth(labelText);
        ctx.drawText(textRenderer, Text.literal(labelText), x, y, GlassTheme.ACCENT_SOFT, false);
        String fitted = textRenderer.trimToWidth(sb.toString(), Math.max(0, maxWidth - labelW));
        ctx.drawText(textRenderer, Text.literal(fitted), x + labelW, y, GlassTheme.textDim(), false);
    }

    private int oreRowAtRelY(int relY) {
        int expandedExtraRowH = 11, cursor = 0;
        for (int i = 0; i < snapshot.oreYields.size(); i++) {
            int rowTop = cursor;
            cursor += ROW_H;
            if (relY >= rowTop && relY < cursor) return i;
            if (i == expandedOreIndex) cursor += expandedExtraRowH * 4 + 3;
        }
        return -1;
    }

    // ── Input ──────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;
        if (snapshot == null) return false;
        double mx = click.x(), my = click.y();
        if (click.button() != 0) return false;

        // Slider drag start (detail strip).
        if (sliderActive && selectedRow != null
                && mx >= sliderTrackX && mx <= sliderTrackX + sliderTrackW
                && my >= sliderTrackY - 3 && my <= sliderTrackY + 9) {
            draggingSlider = true;
            applySliderValue(mx);
            return true;
        }
        // Detail-strip buttons.
        if (hit(pillHit, mx, my)) { toggleSelected(); return true; }
        if (hit(resetHit, mx, my)) { resetSelectedLayer(); return true; }
        if (hit(deleteHit, mx, my)) { deleteSelectedCustom(); return true; }

        // Left-rail tabs.
        for (int[] t : tabRects) {
            if (mx >= t[2] && mx <= t[4] && my >= t[3] && my <= t[5]) {
                switch (t[0]) {
                    case 0 -> { activeChannel = (byte) t[1]; switchToChannelTab(); }
                    case 1 -> switchView(View.ORE_YIELDS);
                    case 2 -> switchView(View.CUSTOM);
                }
                return true;
            }
        }

        // Per-ore yields rows.
        if (view == View.ORE_YIELDS) {
            int bodyTop = listOreBodyTop();
            int bodyBottom = (height - PADDING - 30);
            if (mx < PADDING + TAB_W + PADDING || mx > width - PADDING) return false;
            if (my < bodyTop || my > bodyBottom) return false;
            int relY = (int) (my - bodyTop + scrollOffset);
            int idx = oreRowAtRelY(relY);
            if (idx < 0) return false;
            expandedOreIndex = (expandedOreIndex == idx) ? -1 : idx;
            return true;
        }

        // Custom-list delete glyphs.
        for (int[] d : customDeleteRects) {
            if (mx >= d[0] && mx <= d[2] && my >= d[1] && my <= d[3]) {
                if (selectedKey != null && selectedKey.equals("C|" + d[4])) selectedKey = null;
                BuffSandboxStore.removeCustom(d[4]);
                return true;
            }
        }

        // Compact row list (channel or custom view).
        if (mx < listX + 2 || mx > listX + listW - 2) return false;
        if (my < listBodyTop || my > listBodyBottom) return false;
        int rowH = (view == View.CUSTOM) ? CUSTOM_ROW_H : ROW_H;
        int relY = (int) (my - listBodyTop + scrollOffset);
        int idx = relY / rowH;
        if (idx < 0 || idx >= activeRows.size()) return false;
        Row r = activeRows.get(idx);

        // Checkbox region → toggle; elsewhere → select.
        int checkboxX = listX + 4 + 8;
        if (r.toggleable && mx >= checkboxX && mx <= checkboxX + CHECKBOX_W) {
            toggleRow(r);
            return true;
        }
        if (r.sliderable) {
            selectedKey = r.selKey.equals(selectedKey) ? null : r.selKey;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingSlider) {
            applySliderValue(click.x());
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingSlider) {
            draggingSlider = false;
            BuffSandboxStore.save();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontalAmount, double verticalAmount) {
        scrollOffset -= verticalAmount * ROW_H * 2;
        return true;
    }

    private void applySliderValue(double mx) {
        if (!sliderActive || selectedRow == null) return;
        double frac = (sliderTrackW <= 0) ? 0 : (mx - sliderTrackX) / (double) sliderTrackW;
        frac = Math.max(0.0, Math.min(1.0, frac));
        double raw = sliderMin + frac * (sliderMax - sliderMin);
        double v = snap(raw, selectedRow.kind);
        if (selectedRow.custom) {
            BuffSandboxStore.setCustomValueNoSave(selectedRow.mod.id, v);
        } else {
            BuffSandboxStore.setValueNoSave(selectedRow.channelId, selectedRow.rawLabel, v);
        }
    }

    private void toggleRow(Row r) {
        if (r.custom) BuffSandboxStore.toggleCustom(r.mod.id);
        else BuffSandboxStore.toggleEnabled(r.channelId, r.rawLabel, r.defaultEnabled);
    }

    private void toggleSelected() {
        if (selectedRow != null) toggleRow(selectedRow);
    }

    private void resetSelectedLayer() {
        if (selectedRow == null || selectedRow.custom) return;
        BuffSandboxStore.clearValue(selectedRow.channelId, selectedRow.rawLabel);
        BuffSandboxStore.setEnabled(selectedRow.channelId, selectedRow.rawLabel,
                selectedRow.defaultEnabled, selectedRow.defaultEnabled);
    }

    private void deleteSelectedCustom() {
        if (selectedRow == null || !selectedRow.custom) return;
        BuffSandboxStore.removeCustom(selectedRow.mod.id);
        selectedKey = null;
    }

    private void switchToChannelTab() {
        if (view != View.CHANNEL) {
            switchView(View.CHANNEL);
        } else {
            scrollOffset = 0;
            selectedKey = null;
        }
    }

    private int listOreBodyTop() {
        int y = PADDING + 24;
        return y + 1 + HEADER_H + 4 + textRenderer.fontHeight + 5;
    }

    private static boolean hit(int[] rect, double mx, double my) {
        return rect != null && mx >= rect[0] && mx <= rect[2] && my >= rect[1] && my <= rect[3];
    }

    // ── Slider range / snapping ────────────────────────────────────────────────

    /** Type-aware [min,max] for a slider, expanded to include the current value. */
    private static double[] rangeFor(byte kind, double live, double current) {
        double ref = Math.max(Math.abs(live), Math.abs(current));
        double min, max;
        switch (kind) {
            case BuffSnapshotPayload.KIND_ADDITIVE -> { min = -1.0; max = Math.max(2.0, ref * 2.0); }
            case BuffSnapshotPayload.KIND_MULTIPLICATIVE, BuffSnapshotPayload.KIND_LUCKY_PROC -> { min = 0.0; max = Math.max(5.0, ref * 2.0); }
            default -> { min = 0.0; max = Math.max(10.0, ref * 2.0); } // FLAT_BONUS
        }
        min = Math.min(min, current);
        max = Math.max(max, current);
        if (max <= min) max = min + 1.0;
        return new double[]{min, max};
    }

    private static double snap(double raw, byte kind) {
        double step = switch (kind) {
            case BuffSnapshotPayload.KIND_ADDITIVE -> 0.01;
            case BuffSnapshotPayload.KIND_MULTIPLICATIVE, BuffSnapshotPayload.KIND_LUCKY_PROC -> 0.05;
            default -> 0.5;
        };
        return Math.round(raw / step) * step;
    }

    private static String stripGlyph(String s) {
        return s.startsWith("✦ ") ? s.substring(2) : s;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (this.client != null) this.client.setScreen(parent); }

    public static void openNow(@Nullable Screen parent) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (mc.currentScreen instanceof BuffBreakdownScreen open) { open.onSnapshotUpdated(); return; }
            mc.setScreen(new BuffBreakdownScreen(parent));
        });
    }

    // ── Formatting helpers ─────────────────────────────────────────────────────

    private static String formatKindValue(byte kind, double v) {
        return switch (kind) {
            case BuffSnapshotPayload.KIND_ADDITIVE -> {
                double pct = v * 100.0;
                if (Math.abs(pct) < 0.005) yield "+0%";
                yield String.format(Locale.US, "%+.2f%%", pct);
            }
            case BuffSnapshotPayload.KIND_FLAT_BONUS -> String.format(Locale.US, "+%.1f", v);
            case BuffSnapshotPayload.KIND_BASE -> v == 0.0 ? "—" : String.format(Locale.US, "%.2f", v);
            default -> formatX(v); // MULTIPLICATIVE / LUCKY_PROC
        };
    }

    /** A custom "more" modifier's × factor rendered as "X% more / less". */
    private static String formatMorePercent(double factor) {
        double pct = (factor - 1.0) * 100.0;
        double a = Math.abs(pct);
        if (a < 0.05) return "0% more";
        String word = pct < 0 ? "less" : "more";
        if (a == Math.floor(a)) return String.format(Locale.US, "%.0f%% %s", a, word);
        return String.format(Locale.US, "%.1f%% %s", a, word);
    }

    private static String shortLayerLabel(String label) {
        int paren = label.indexOf(" (");
        if (paren > 0) label = label.substring(0, paren);
        return label;
    }

    private static String formatSignedPct(double v) {
        double pct = v * 100.0;
        if (Math.abs(pct) < 0.05) return "+0%";
        if (pct == Math.floor(pct)) return String.format(Locale.US, "%+.0f%%", pct);
        return String.format(Locale.US, "%+.1f%%", pct);
    }

    private static String formatXShort(double v) {
        if (v == Math.floor(v)) return String.format(Locale.US, "×%d", (int) v);
        if (v >= 10) return String.format(Locale.US, "×%.1f", v);
        return String.format(Locale.US, "×%.2f", v);
    }

    private static String formatNum(double v) {
        if (v <= 0) return "0";
        if (v >= 100_000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        if (v >= 10) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private void drawTransformCell(DrawContext ctx, String base, String result, int rightEdge, int y) {
        String arrow = " → ";
        int resultW = textRenderer.getWidth(result);
        int arrowW = textRenderer.getWidth(arrow);
        int baseW = textRenderer.getWidth(base);
        ctx.drawText(textRenderer, Text.literal(result), rightEdge - resultW, y, GlassTheme.text(), true);
        int arrowX = rightEdge - resultW - arrowW;
        int baseX = arrowX - baseW;
        if (baseX < 0) return;
        ctx.drawText(textRenderer, Text.literal(arrow), arrowX, y, GlassTheme.textDim(), false);
        ctx.drawText(textRenderer, Text.literal(base), baseX, y, GlassTheme.textDim(), false);
    }

    private void drawRightAligned(DrawContext ctx, String text, int rightEdge, int y, int color) {
        ctx.drawText(textRenderer, Text.literal(text), rightEdge - textRenderer.getWidth(text), y, color, true);
    }

    private static String formatYieldXp(double v) {
        if (v >= 100_000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        if (v >= 10) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static String formatYieldEnergy(double v) {
        if (v >= 100_000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        if (v >= 10) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    private static String formatYieldMoney(double v) {
        if (v <= 0) return "—";
        if (v >= 1_000_000) return String.format(Locale.US, "$%.1fM", v / 1_000_000.0);
        if (v >= 10_000) return String.format(Locale.US, "$%.1fK", v / 1000.0);
        if (v >= 10) return String.format(Locale.US, "$%.0f", v);
        return String.format(Locale.US, "$%.2f", v);
    }

    private static String formatYieldShard(double v) {
        if (v <= 0) return "—";
        if (v >= 0.01) return String.format(Locale.US, "%.2f%%", v * 100.0);
        return String.format(Locale.US, "%.3f%%", v * 100.0);
    }

    private static String formatX(double v) {
        if (v == Math.floor(v) && Math.abs(v) < 1000) return String.format(Locale.US, "x%d", (int) v);
        if (Math.abs(v) >= 100) return String.format(Locale.US, "x%.0f", v);
        if (Math.abs(v) >= 10)  return String.format(Locale.US, "x%.1f", v);
        return String.format(Locale.US, "x%.2f", v);
    }

    private static String formatValueForChannel(byte chId, double v) {
        if (chId == BuffSnapshotPayload.CH_MINING_SHARDS) return String.format(Locale.US, "%.2f%%", v * 100.0);
        if (chId == BuffSnapshotPayload.CH_COMBAT_OUT || chId == BuffSnapshotPayload.CH_BOSS_OUT)
            return String.format(Locale.US, "%.2f dmg", v);
        if (chId == BuffSnapshotPayload.CH_COMBAT_IN || chId == BuffSnapshotPayload.CH_BOSS_IN) {
            double reductionPct = (1.0 - v) * 100.0;
            double takenPct = v * 100.0;
            return String.format(Locale.US, "-%.1f%% (%.1f%% taken)", reductionPct, takenPct);
        }
        if (chId == BuffSnapshotPayload.CH_CRIT_CHANCE) return String.format(Locale.US, "%.1f%%", v * 100.0);
        // Crit damage value is the bonus multi (0.50 = +50% → crits hit ×1.50).
        if (chId == BuffSnapshotPayload.CH_CRIT_DAMAGE) return String.format(Locale.US, "+%.0f%% (x%.2f)", v * 100.0, 1.0 + v);
        return formatX(v);
    }

    private static boolean isCritChannel(byte id) {
        return id == BuffSnapshotPayload.CH_CRIT_CHANCE || id == BuffSnapshotPayload.CH_CRIT_DAMAGE;
    }

    /** Crit rows store fractional values; render them as percentages (chance plain, damage signed). */
    private static String formatCritPct(byte chId, double v) {
        if (chId == BuffSnapshotPayload.CH_CRIT_DAMAGE) return String.format(Locale.US, "+%.1f%%", v * 100.0);
        return String.format(Locale.US, "%.1f%%", v * 100.0);
    }
}
