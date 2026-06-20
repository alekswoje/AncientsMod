package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.muffler.MufflerCapture;
import com.aleks.prisonsmod.client.muffler.MufflerCatalog;
import com.aleks.prisonsmod.client.muffler.MufflerSettings;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Sound &amp; Particle Muffler screen. A tabbed, searchable, virtualized list:
 *
 * <ul>
 *   <li><b>Sounds</b> / <b>Particles</b> — curated game-system groups. Sounds get
 *       a 0–100% volume slider (0 = muted); particles get an ON/OFF pill.</li>
 *   <li><b>Server FX</b> — one-click bundles that mute the vanilla ids a given
 *       server event/ability emits.</li>
 *   <li><b>Recent</b> — everything that's played since the screen opened; click to
 *       mute whatever just annoyed you.</li>
 *   <li><b>Advanced</b> — every registered sound / particle id, for power users.</li>
 * </ul>
 *
 * <p>Rows are custom-drawn (not per-row widgets) and only the visible slice is
 * rendered/hit-tested, so the Advanced tab scales to the full ~1500-id registry
 * without lag. State lives in {@link MufflerSettings} and is read live by the
 * sound/particle mixins, so changes take effect the instant you drag a slider.
 */
public final class MufflerScreen extends Screen {

    private enum Tab {
        SOUNDS("Sounds"), PARTICLES("Particles"), SERVER_FX("Server FX"),
        RECENT("Recent"), ADVANCED("Advanced");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private enum Kind { HEADER, SOUND, PARTICLE, BUNDLE, RECENT_SOUND, RECENT_PARTICLE }

    private static final class Row {
        Kind kind;
        String label;
        String search;          // lowercased label + id, for filtering
        Identifier id;          // SOUND / PARTICLE / RECENT_*
        MufflerCatalog.Bundle bundle; // BUNDLE
        String note;            // optional hover note (bundle / advanced)
        int count;              // RECENT_* hit count
        Row(Kind kind) { this.kind = kind; }
    }

    private static final int ROW_H = 22;
    private static final int TRACK_W = 104;
    private static final int PILL_W = 54;
    private static final int BTN_W = 58;
    private static final int TAB_Y = 30;
    private static final int TAB_H = 20;
    private static final int HEADER_Y = 56;

    private static final int ACCENT = 0xFFFFC857;
    private static final int RED = 0xFFFF5555;
    private static final int GREEN = 0xFF55FF55;

    private final Screen parent;

    private Tab activeTab = Tab.SOUNDS;
    private final List<Row> rows = new ArrayList<>();
    private TextFieldWidget search;
    private int scrollY;
    private long lastRecentRebuild;

    // Layout metrics (recomputed each frame / on init).
    private int contentW, cx, left, right, viewTop, viewBottom;

    // Active slider drag.
    private Identifier draggingSound;

    public MufflerScreen(Screen parent) {
        super(Text.literal("Sound & Particle Muffler"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        computeMetrics();
        MufflerCapture.setActive(true);

        // Master ON/OFF.
        CyclingButtonWidget<Boolean> master = CyclingButtonWidget
                .onOffBuilder(
                        Text.literal("ON").formatted(Formatting.GREEN, Formatting.BOLD),
                        Text.literal("OFF").formatted(Formatting.RED, Formatting.BOLD),
                        MufflerSettings.isEnabled())
                .build(left, HEADER_Y, 120, 20, Text.literal("Muffler"),
                        (b, v) -> MufflerSettings.setEnabled(v));
        addDrawableChild(master);

        // Clear-all.
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), b -> {
            MufflerSettings.clearAll();
            rebuildRows();
        }).dimensions(right - 80, HEADER_Y, 80, 20).build());

        // Search field (between master and clear).
        int searchX = left + 128;
        int searchW = (right - 84) - searchX;
        search = new TextFieldWidget(this.textRenderer, searchX, HEADER_Y, Math.max(60, searchW), 20,
                Text.literal("Search"));
        search.setPlaceholder(Text.literal("Search…"));
        search.setChangedListener(s -> { scrollY = 0; rebuildRows(); });
        addDrawableChild(search);

        // Done.
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> this.close())
                .dimensions(cx - 50, this.height - 28, 100, 20).build());

        rebuildRows();
    }

    private void computeMetrics() {
        contentW = Math.min(440, this.width - 60);
        cx = this.width / 2;
        left = cx - contentW / 2;
        right = cx + contentW / 2;
        viewTop = 92;
        viewBottom = this.height - 36;
    }

    // ── Row building ─────────────────────────────────────────────────────────

    private String query() {
        if (search == null) return "";
        String s = search.getText();
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private void rebuildRows() {
        rows.clear();
        String q = query();
        switch (activeTab) {
            case SOUNDS -> {
                for (MufflerCatalog.SoundGroup g : MufflerCatalog.soundGroups()) {
                    addGroup(g.name(), () -> {
                        for (MufflerCatalog.SoundEntry e : g.entries()) {
                            addSoundRow(e.id(), e.friendly(), q);
                        }
                    }, q, hasSoundMatch(g, q));
                }
            }
            case PARTICLES -> {
                for (MufflerCatalog.ParticleGroup g : MufflerCatalog.particleGroups()) {
                    addGroup(g.name(), () -> {
                        for (MufflerCatalog.ParticleEntry e : g.entries()) {
                            addParticleRow(e.id(), e.friendly(), q);
                        }
                    }, q, hasParticleMatch(g, q));
                }
            }
            case SERVER_FX -> {
                addHeader("One click mutes the vanilla sounds & particles an event uses");
                for (MufflerCatalog.Bundle b : MufflerCatalog.serverFx()) {
                    String hay = (b.name() + " " + b.note()).toLowerCase(Locale.ROOT);
                    if (!q.isEmpty() && !hay.contains(q)) continue;
                    Row r = new Row(Kind.BUNDLE);
                    r.bundle = b;
                    r.label = b.name();
                    r.note = b.note();
                    rows.add(r);
                }
            }
            case RECENT -> buildRecentRows(q);
            case ADVANCED -> {
                addGroup("All sounds (" + MufflerCatalog.allSoundIds().size() + ")", () -> {
                    for (Identifier id : MufflerCatalog.allSoundIds()) {
                        addSoundRow(id, id.toString(), q);
                    }
                }, q, q.isEmpty() || MufflerCatalog.allSoundIds().stream().anyMatch(i -> matches(i.toString(), q)));
                addGroup("All particles (" + MufflerCatalog.allParticleIds().size() + ")", () -> {
                    for (Identifier id : MufflerCatalog.allParticleIds()) {
                        addParticleRow(id, id.toString(), q);
                    }
                }, q, q.isEmpty() || MufflerCatalog.allParticleIds().stream().anyMatch(i -> matches(i.toString(), q)));
            }
        }
        clampScroll();
    }

    private void buildRecentRows(String q) {
        lastRecentRebuild = System.currentTimeMillis();
        List<MufflerCapture.Entry> sounds = MufflerCapture.recentSounds();
        List<MufflerCapture.Entry> particles = MufflerCapture.recentParticles();
        if (sounds.isEmpty() && particles.isEmpty()) {
            addHeader("Nothing captured yet — play the game with this open");
            return;
        }
        if (!sounds.isEmpty()) {
            addHeader("Recent sounds");
            for (MufflerCapture.Entry e : sounds) {
                if (!q.isEmpty() && !matches(e.id.toString(), q)) continue;
                Row r = new Row(Kind.RECENT_SOUND);
                r.id = e.id;
                r.label = e.id.toString();
                r.count = e.count;
                rows.add(r);
            }
        }
        if (!particles.isEmpty()) {
            addHeader("Recent particles");
            for (MufflerCapture.Entry e : particles) {
                if (!q.isEmpty() && !matches(e.id.toString(), q)) continue;
                Row r = new Row(Kind.RECENT_PARTICLE);
                r.id = e.id;
                r.label = e.id.toString();
                r.count = e.count;
                rows.add(r);
            }
        }
    }

    private interface RowAdder { void add(); }

    /** Add a header + its rows, but only if the group has at least one matching row. */
    private void addGroup(String name, RowAdder body, String q, boolean anyMatch) {
        if (!anyMatch) return;
        addHeader(name);
        body.add();
    }

    private void addHeader(String text) {
        Row r = new Row(Kind.HEADER);
        r.label = text;
        rows.add(r);
    }

    private void addSoundRow(Identifier id, String friendly, String q) {
        if (!q.isEmpty() && !matches(friendly + " " + id, q)) return;
        Row r = new Row(Kind.SOUND);
        r.id = id;
        r.label = friendly;
        r.note = id.toString();
        rows.add(r);
    }

    private void addParticleRow(Identifier id, String friendly, String q) {
        if (!q.isEmpty() && !matches(friendly + " " + id, q)) return;
        Row r = new Row(Kind.PARTICLE);
        r.id = id;
        r.label = friendly;
        r.note = id.toString();
        rows.add(r);
    }

    private static boolean matches(String hay, String q) {
        return hay.toLowerCase(Locale.ROOT).contains(q);
    }

    private boolean hasSoundMatch(MufflerCatalog.SoundGroup g, String q) {
        if (q.isEmpty()) return !g.entries().isEmpty();
        for (MufflerCatalog.SoundEntry e : g.entries()) {
            if (matches(e.friendly() + " " + e.id(), q)) return true;
        }
        return false;
    }

    private boolean hasParticleMatch(MufflerCatalog.ParticleGroup g, String q) {
        if (q.isEmpty()) return !g.entries().isEmpty();
        for (MufflerCatalog.ParticleEntry e : g.entries()) {
            if (matches(e.friendly() + " " + e.id(), q)) return true;
        }
        return false;
    }

    // ── Scroll ───────────────────────────────────────────────────────────────

    private int contentHeight() { return rows.size() * ROW_H; }

    private int maxScroll() { return Math.max(0, contentHeight() - (viewBottom - viewTop)); }

    private void clampScroll() {
        scrollY = Math.max(0, Math.min(scrollY, maxScroll()));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horiz, double vert) {
        scrollY -= (int) Math.round(vert * ROW_H);
        clampScroll();
        return true;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        computeMetrics();
        // Live-refresh the Recent tab a few times a second.
        if (activeTab == Tab.RECENT && System.currentTimeMillis() - lastRecentRebuild > 300) {
            int prev = scrollY;
            rebuildRows();
            scrollY = prev;
            clampScroll();
        }

        ctx.fill(0, 0, this.width, this.height, 0x88000000);
        ctx.fill(left - 4, viewTop - 2, right + 4, viewBottom + 2, 0x33000000);

        super.render(ctx, mouseX, mouseY, delta);

        // Title + subtitle.
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 10, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Muted: " + MufflerSettings.mutedSoundCount() + " sounds · "
                        + MufflerSettings.mutedParticleCount() + " particles"),
                cx, 21, 0x888888);

        drawTabs(ctx, mouseX, mouseY);
        drawRows(ctx, mouseX, mouseY);
    }

    private void drawTabs(DrawContext ctx, int mouseX, int mouseY) {
        Tab[] tabs = Tab.values();
        int tabW = contentW / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            int tx = left + i * tabW;
            int tw = (i == tabs.length - 1) ? (right - tx) : tabW;
            boolean active = tabs[i] == activeTab;
            boolean hover = mouseX >= tx && mouseX < tx + tw && mouseY >= TAB_Y && mouseY < TAB_Y + TAB_H;
            ctx.fill(tx + 1, TAB_Y, tx + tw - 1, TAB_Y + TAB_H, active ? 0x55FFC857 : (hover ? 0x33FFFFFF : 0x22000000));
            if (active) {
                ctx.fill(tx + 1, TAB_Y + TAB_H - 2, tx + tw - 1, TAB_Y + TAB_H, ACCENT);
            }
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(tabs[i].label),
                    tx + tw / 2, TAB_Y + 6, active ? ACCENT : 0xFFCCCCCC);
        }
    }

    private void drawRows(DrawContext ctx, int mouseX, int mouseY) {
        ctx.enableScissor(left - 4, viewTop, right + 4, viewBottom);
        int first = Math.max(0, scrollY / ROW_H);
        int last = Math.min(rows.size(), (scrollY + (viewBottom - viewTop)) / ROW_H + 1);
        Row hoverNoteRow = null;
        for (int i = first; i < last; i++) {
            Row r = rows.get(i);
            int rowTop = viewTop - scrollY + i * ROW_H;
            int midY = rowTop + (ROW_H - this.textRenderer.fontHeight) / 2;
            boolean hovered = mouseY >= rowTop && mouseY < rowTop + ROW_H
                    && mouseX >= left && mouseX <= right && r.kind != Kind.HEADER;
            if (hovered) {
                ctx.fill(left, rowTop, right, rowTop + ROW_H, 0x22FFFFFF);
                if (r.note != null) hoverNoteRow = r;
            }
            switch (r.kind) {
                case HEADER -> {
                    int lineY = rowTop + ROW_H / 2;
                    ctx.fill(left, lineY, right, lineY + 1, 0x33FFFFFF);
                    int w = this.textRenderer.getWidth(r.label);
                    ctx.fill(left + 6, midY - 1, left + 12 + w, midY + this.textRenderer.fontHeight, 0xAA000000);
                    ctx.drawText(this.textRenderer, r.label, left + 9, midY, ACCENT, false);
                }
                case SOUND -> drawSoundRow(ctx, r, rowTop, midY);
                case PARTICLE -> drawPillRow(ctx, r, rowTop, midY,
                        MufflerSettings.isParticleMutedGui(r.id));
                case BUNDLE -> drawPillRow(ctx, r, rowTop, midY,
                        MufflerCatalog.isBundleMuffled(r.bundle));
                case RECENT_SOUND -> drawRecentRow(ctx, r, rowTop, midY,
                        MufflerSettings.isSoundSilenced(r.id));
                case RECENT_PARTICLE -> drawRecentRow(ctx, r, rowTop, midY,
                        MufflerSettings.isParticleMutedGui(r.id));
            }
        }
        ctx.disableScissor();

        // Scrollbar.
        int viewportH = viewBottom - viewTop;
        if (contentHeight() > viewportH) {
            int barX = right + 6;
            int barH = Math.max(20, viewportH * viewportH / contentHeight());
            int barY = viewTop + (maxScroll() == 0 ? 0 : scrollY * (viewportH - barH) / maxScroll());
            ctx.fill(barX, viewTop, barX + 3, viewBottom, 0x44FFFFFF);
            ctx.fill(barX, barY, barX + 3, barY + barH, ACCENT);
        }

        // Hover note tooltip (bundle description / exact id).
        if (hoverNoteRow != null) {
            ctx.drawTooltip(this.textRenderer, List.of(Text.literal(hoverNoteRow.note)), mouseX, mouseY);
        }
    }

    private void drawSoundRow(DrawContext ctx, Row r, int rowTop, int midY) {
        float factor = MufflerSettings.getSoundVolume(r.id);
        int trackRight = right - 8;
        int trackX = trackRight - TRACK_W;
        int pctRight = trackX - 6;
        int pctX = pctRight - 40;
        int trackMid = rowTop + ROW_H / 2;

        // Label (left), trimmed so it never runs under the % text.
        String label = this.textRenderer.trimToWidth(r.label, pctX - (left + 6) - 4);
        ctx.drawText(this.textRenderer, label, left + 6, midY, 0xFFFFFFFF, false);

        // Percent / Muted.
        boolean muted = factor <= 0f;
        String pct = muted ? "Muted" : (Math.round(factor * 100) + "%");
        int pctColor = muted ? RED : (factor < 1f ? ACCENT : 0xFFAAAAAA);
        int pw = this.textRenderer.getWidth(pct);
        ctx.drawText(this.textRenderer, pct, pctRight - pw, midY, pctColor, false);

        // Track + fill + knob.
        ctx.fill(trackX, trackMid - 2, trackRight, trackMid + 2, 0x44FFFFFF);
        int fillW = Math.round(factor * TRACK_W);
        if (fillW > 0) {
            ctx.fill(trackX, trackMid - 2, trackX + fillW, trackMid + 2, muted ? RED : ACCENT);
        }
        int knobX = trackX + fillW;
        ctx.fill(knobX - 1, trackMid - 5, knobX + 2, trackMid + 5, 0xFFFFFFFF);
    }

    private void drawPillRow(DrawContext ctx, Row r, int rowTop, int midY, boolean muffled) {
        int pillRight = right - 8;
        int pillX = pillRight - PILL_W;
        String label = this.textRenderer.trimToWidth(r.label, pillX - (left + 6) - 6);
        ctx.drawText(this.textRenderer, label, left + 6, midY, 0xFFFFFFFF, false);

        int pillTop = rowTop + 3;
        int pillBot = rowTop + ROW_H - 3;
        ctx.fill(pillX, pillTop, pillRight, pillBot, muffled ? 0x55FF5555 : 0x5555FF55);
        String txt = muffled ? "OFF" : "ON";
        int color = muffled ? RED : GREEN;
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(txt),
                (pillX + pillRight) / 2, midY, color);
    }

    private void drawRecentRow(DrawContext ctx, Row r, int rowTop, int midY, boolean muted) {
        int btnRight = right - 8;
        int btnX = btnRight - BTN_W;
        String suffix = r.count > 1 ? "  ×" + r.count : "";
        int suffixW = this.textRenderer.getWidth(suffix);
        ctx.drawText(this.textRenderer, suffix, btnX - 8 - suffixW, midY, 0xFF888888, false);
        String label = this.textRenderer.trimToWidth(r.label, (btnX - 8 - suffixW) - (left + 6) - 4);
        ctx.drawText(this.textRenderer, label, left + 6, midY, 0xFFFFFFFF, false);

        int btnTop = rowTop + 3;
        int btnBot = rowTop + ROW_H - 3;
        ctx.fill(btnX, btnTop, btnRight, btnBot, muted ? 0x5555FF55 : 0x55FF5555);
        String txt = muted ? "Unmute" : "Mute";
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(txt),
                (btnX + btnRight) / 2, midY, muted ? GREEN : RED);
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (super.mouseClicked(click, doubleClick)) return true;
        if (click.button() != 0) return false;
        double mx = click.x();
        double my = click.y();

        // Tab strip.
        if (my >= TAB_Y && my < TAB_Y + TAB_H && mx >= left && mx <= right) {
            Tab[] tabs = Tab.values();
            int tabW = contentW / tabs.length;
            // Clamp to the last tab: drawTabs stretches the final tab to `right`
            // to fill the integer-division remainder, so the rightmost pixels
            // belong to it (mx is already known to be within [left,right]).
            int idx = Math.min(tabs.length - 1, (int) ((mx - left) / tabW));
            if (idx >= 0 && tabs[idx] != activeTab) {
                activeTab = tabs[idx];
                scrollY = 0;
                rebuildRows();
            }
            return true;
        }

        // Rows.
        if (my < viewTop || my >= viewBottom || mx < left || mx > right) return false;
        int idx = (int) ((my - viewTop + scrollY) / ROW_H);
        if (idx < 0 || idx >= rows.size()) return false;
        Row r = rows.get(idx);
        int rowTop = viewTop - scrollY + idx * ROW_H;
        switch (r.kind) {
            case SOUND -> handleSoundClick(r, mx, rowTop);
            case PARTICLE -> {
                if (inPill(mx)) { MufflerSettings.toggleParticle(r.id); }
            }
            case BUNDLE -> {
                if (inPill(mx)) { MufflerCatalog.setBundleMuffled(r.bundle, !MufflerCatalog.isBundleMuffled(r.bundle)); }
            }
            case RECENT_SOUND -> {
                if (inButton(mx)) {
                    MufflerSettings.setSoundVolume(r.id, MufflerSettings.isSoundSilenced(r.id) ? 1f : 0f);
                }
            }
            case RECENT_PARTICLE -> {
                if (inButton(mx)) { MufflerSettings.toggleParticle(r.id); }
            }
            default -> { return false; }
        }
        return true;
    }

    private void handleSoundClick(Row r, double mx, int rowTop) {
        int trackRight = right - 8;
        int trackX = trackRight - TRACK_W;
        int pctRight = trackX - 6;
        int pctX = pctRight - 40;
        if (mx >= trackX && mx <= trackRight) {
            draggingSound = r.id;
            setSoundFromMouse(r.id, mx);
        } else if (mx >= pctX && mx < trackX) {
            // Click the %/Muted label to toggle silence.
            MufflerSettings.setSoundVolume(r.id, MufflerSettings.isSoundSilenced(r.id) ? 1f : 0f);
        }
    }

    private void setSoundFromMouse(Identifier id, double mx) {
        int trackRight = right - 8;
        int trackX = trackRight - TRACK_W;
        double norm = (mx - trackX) / TRACK_W;
        // Snap to 5% steps; clamp. Persist=false — a drag can fire dozens of
        // times a second; mouseReleased() flushes once when the knob is let go.
        float f = (float) Math.max(0, Math.min(1, Math.round(norm * 20) / 20.0));
        MufflerSettings.setSoundVolume(id, f, false);
    }

    private boolean inPill(double mx) {
        int pillRight = right - 8;
        int pillX = pillRight - PILL_W;
        return mx >= pillX && mx <= pillRight;
    }

    private boolean inButton(double mx) {
        int btnRight = right - 8;
        int btnX = btnRight - BTN_W;
        return mx >= btnX && mx <= btnRight;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (draggingSound != null && click.button() == 0) {
            setSoundFromMouse(draggingSound, click.x());
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (draggingSound != null && click.button() == 0) {
            draggingSound = null;
            MufflerSettings.save();
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void close() {
        MufflerCapture.setActive(false);
        MufflerSettings.save();
        if (this.client != null) this.client.setScreen(parent);
    }
}
