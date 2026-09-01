package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.glass.GlassButton;
import com.aleks.ancientsmod.client.glass.GlassRender;
import com.aleks.ancientsmod.client.glass.GlassTextField;
import com.aleks.ancientsmod.client.glass.GlassTheme;
import com.aleks.ancientsmod.client.hud.MiningSimState;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.MiningSimPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Client-side {@code /miningsim} session view — replaces the end-of-session chat wall.
 *
 * <h2>What it shows that chat could not</h2>
 * <ul>
 *   <li><b>Sources</b> — every reward row, keyed by its full proc chain. The server credits
 *       rewards to the <em>origin</em> of the chain, so a Powerball fired by a perk-driven
 *       Shatter shows as {@code Proc Party > Shatter > Powerball} and its income counts
 *       toward Proc Party. Rows group under their origin so a perk's real worth is one
 *       number instead of scattered across whichever enchant it happened to trigger.</li>
 *   <li><b>Graph</b> — rates over the session, built client-side from the 1 Hz stream.
 *       Paused spans are skipped rather than drawn flat, since a flat line across a break
 *       reads as a rate collapse when nothing was happening.</li>
 *   <li><b>History</b> — finished sessions kept in memory so two runs can be diffed.</li>
 * </ul>
 *
 * <p>Pause is a real server-side pause, not a display freeze: nothing is recorded and the
 * paused span is subtracted from the rate denominator, so stepping away doesn't drag the
 * /hr numbers down.
 */
public final class MiningSimScreen extends Screen {

    private static final int PADDING = 10;
    private static final int ROW_H = 12;
    /** Width of each right-aligned rate column in the saved-sessions list. */
    private static final int RATE_COL_W = 78;
    private static final int PANEL_W = 480;
    private static final int ROWS_VISIBLE = 14;
    /** History rows leave room under them for the compare block. */
    private static final int HISTORY_ROWS_VISIBLE = ROWS_VISIBLE - 5;

    private final @Nullable Screen parent;

    private enum Tab { SOURCES, PROCS, GRAPH, HISTORY }

    private enum SortKey { ORIGIN, XP, ENERGY, MONEY, COUNT }

    private Tab tab = Tab.SOURCES;
    private SortKey sortKey = SortKey.XP;
    private boolean sortDescending = true;
    private int scrollOffset = 0;

    /** Which archived sessions are picked for the compare view (indices into the archive). */
    private int compareA = -1;
    private int compareB = -1;

    /** Auto-stop choices offered next to Start, in minutes. 0 = run until stopped. */
    private static final int[] AUTO_STOP_CHOICES = {0, 3, 5, 10, 30};
    private int autoStopIndex = 0;

    /**
     * Archived session being viewed, or -1 for the live/most-recent one. Loading an
     * archive swaps every tab over to it, so an old run can be read exactly like a
     * running one instead of only appearing as a single summary row.
     */
    private int viewingIndex = -1;

    /** Rename field, non-null only while renaming the viewed session. */
    private @Nullable GlassTextField renameField = null;

    /**
     * Viewing a session somebody shared in chat rather than one of our own. Read-only:
     * the only thing to do with it is Import, which copies it into the archive and turns
     * it into a normal saved session.
     */
    private boolean viewingShared;

    /**
     * Session state the buttons were last built for. The server answers actions
     * asynchronously, so rather than guessing after a click we rebuild whenever the
     * observed state actually changes — the buttons can never disagree with the server.
     */
    private String builtForState = "";

    public MiningSimScreen(@Nullable Screen parent) {
        this(parent, false);
    }

    private MiningSimScreen(@Nullable Screen parent, boolean shared) {
        super(Text.literal("Mining Simulation"));
        this.parent = parent;
        this.viewingShared = shared;
    }

    /** Open on the next client tick — safe to call from inside a command dispatch. */
    public static void openNow(@Nullable Screen parent) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> mc.setScreen(new MiningSimScreen(parent)));
    }

    /**
     * Open on the session behind a {@code [sim]} chat link the player just clicked.
     * {@link MiningSimState#shared()} already holds it — the packet lands first.
     */
    public static void openShared() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> mc.setScreen(new MiningSimScreen(null, true)));
    }

    /**
     * The server's verdict on a share upload. On success the player is handed straight to
     * chat with the token already typed: the upload exists only so {@code [sim]} resolves,
     * and leaving them to remember the token would waste the round trip.
     */
    public static void onShareAck(byte status) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        mc.execute(() -> {
            if (status == Protocol.MININGSIM_SHARE_OK) {
                // draft=false: the token is text the player actually typed, not a greyed
                // suggestion that the first keystroke would wipe.
                mc.setScreen(new net.minecraft.client.gui.screen.ChatScreen("[sim] ", false));
                return;
            }
            if (mc.player == null) return;
            String msg = status == Protocol.MININGSIM_SHARE_EMPTY
                    ? "That session recorded nothing worth sharing."
                    : "Couldn't share that session — wait a moment and try again.";
            mc.player.sendMessage(Text.literal(msg)
                    .formatted(net.minecraft.util.Formatting.RED), false);
        });
    }

    @Override
    protected void init() {
        // Pull a fresh snapshot on open so the screen never shows a stale mid-session
        // state from before it was closed. Skipped for a shared session: it is somebody
        // else's finished run, and our own live state is not what this screen is showing.
        if (!viewingShared) {
            NetworkHandler.sendMiningSimCommand(Protocol.MININGSIM_ACTION_REFRESH);
        }

        int tabW = 104;
        int tabY = PADDING + 40;
        int totalW = tabW * 4 + 12;
        int tabX = (this.width - totalW) / 2;
        addDrawableChild(tabButton(tabX, tabY, tabW, "Sources", Tab.SOURCES));
        addDrawableChild(tabButton(tabX + (tabW + 4), tabY, tabW, "Procs", Tab.PROCS));
        addDrawableChild(tabButton(tabX + (tabW + 4) * 2, tabY, tabW, "Graph", Tab.GRAPH));
        addDrawableChild(tabButton(tabX + (tabW + 4) * 3, tabY, tabW, "History", Tab.HISTORY));

        int btnY = this.height - PADDING - 24;
        boolean live = MiningSimState.liveSession() != null;
        boolean paused = MiningSimState.isPaused();
        builtForState = stateSignature();

        if (viewingShared && MiningSimState.shared() != null) {
            // Somebody else's run: nothing here can be renamed, deleted or stopped. Import
            // is the one action, and it turns the share into a normal saved session.
            addDrawableChild(new GlassButton(this.width / 2 - 158, btnY, 100, 20,
                    Text.literal("Import"), () -> {
                int idx = MiningSimState.importShared(MiningSimState.shared());
                if (idx >= 0) {
                    // Land on the imported copy rather than bouncing to the list, so the
                    // run you were reading stays on screen and is now yours.
                    viewingShared = false;
                    viewingIndex = idx;
                    tab = Tab.SOURCES;
                    scrollOffset = 0;
                }
                this.clearAndInit();
            }).primary());
            addDrawableChild(new GlassButton(this.width / 2 - 52, btnY, 100, 20,
                    Text.literal("Back to mine"), () -> {
                viewingShared = false;
                MiningSimState.clearShared();
                scrollOffset = 0;
                this.clearAndInit();
            }));
            addDrawableChild(new GlassButton(this.width / 2 + 54, btnY, 100, 20,
                    Text.literal("Done"), this::close));
            return;
        }

        MiningSimState.ArchivedSession viewed = viewedArchive();
        if (viewed != null) {
            // Viewing an archived run: its controls replace the session controls, because
            // Pause/Stop would be meaningless against a run that already finished.
            if (renameField != null) {
                addDrawableChild(renameField);
                addDrawableChild(new GlassButton(this.width / 2 + 54, btnY, 100, 20,
                        Text.literal("Save name"), this::commitRename).primary());
            } else {
                // Five controls now, so they are narrower than the two-or-three-button
                // rows elsewhere on this screen.
                final int w = 84, gap = 4, step = w + gap;
                int x = this.width / 2 - (w * 5 + gap * 4) / 2;
                addDrawableChild(new GlassButton(x, btnY, w, 20,
                        Text.literal("Rename"), () -> {
                    GlassTextField f = new GlassTextField(this.textRenderer,
                            this.width / 2 - 210, btnY, 204, 20, Text.literal("Session name"));
                    f.setMaxLength(48);
                    f.setText(viewed.label());
                    f.setSelectionStart(0);
                    f.setSelectionEnd(f.getText().length());
                    renameField = f;
                    this.clearAndInit();
                    setFocused(renameField);
                    renameField.setFocused(true);
                }));
                // Share uploads this run to the server and hands us to chat with the
                // [sim] token typed, so anyone can click through to the same breakdown.
                addDrawableChild(new GlassButton(x + step, btnY, w, 20,
                        Text.literal("Share"), () -> NetworkHandler.sendMiningSimShare(viewed)));
                addDrawableChild(new GlassButton(x + step * 2, btnY, w, 20,
                        Text.literal("Delete"), () -> {
                    MiningSimState.delete(viewingIndex);
                    viewingIndex = -1;
                    tab = Tab.HISTORY;
                    this.clearAndInit();
                }));
                addDrawableChild(new GlassButton(x + step * 3, btnY, w, 20,
                        Text.literal("Back"), () -> {
                    viewingIndex = -1;
                    scrollOffset = 0;
                    this.clearAndInit();
                }).primary());
                addDrawableChild(new GlassButton(x + step * 4, btnY, w, 20,
                        Text.literal("Done"), this::close));
            }
            return;
        }

        if (live) {
            GlassButton pause = new GlassButton(this.width / 2 - 158, btnY, 100, 20,
                    Text.literal(paused ? "Resume" : "Pause"), () ->
                    NetworkHandler.sendMiningSimCommand(paused
                            ? Protocol.MININGSIM_ACTION_RESUME
                            : Protocol.MININGSIM_ACTION_PAUSE));
            addDrawableChild(paused ? pause.primary() : pause);

            addDrawableChild(new GlassButton(this.width / 2 - 52, btnY, 100, 20,
                    Text.literal("Stop"), () ->
                    NetworkHandler.sendMiningSimCommand(Protocol.MININGSIM_ACTION_STOP)));
            addDrawableChild(new GlassButton(this.width / 2 + 54, btnY, 100, 20,
                    Text.literal("Done"), this::close));
            return;
        }

        // Not running. The last archived session is the run that just ended, so Share sits
        // here too — the common case is wanting to show off the session you only just
        // stopped, and making that a trip through the History tab would be a detour.
        List<MiningSimState.ArchivedSession> archive = MiningSimState.archive();
        MiningSimState.ArchivedSession latestRun = archive.isEmpty() ? null : archive.get(archive.size() - 1);
        int startX = latestRun != null ? this.width / 2 - 210 : this.width / 2 - 158;

        addDrawableChild(new GlassButton(startX, btnY, 100, 20,
                Text.literal("Auto-stop: " + autoStopLabel()), () -> {
            autoStopIndex = (autoStopIndex + 1) % AUTO_STOP_CHOICES.length;
            this.clearAndInit();
        }));

        addDrawableChild(new GlassButton(startX + 106, btnY, 100, 20,
                Text.literal("Start"), () ->
                NetworkHandler.sendMiningSimCommand(Protocol.MININGSIM_ACTION_START,
                        AUTO_STOP_CHOICES[autoStopIndex])).primary());

        if (latestRun != null) {
            addDrawableChild(new GlassButton(startX + 212, btnY, 100, 20,
                    Text.literal("Share last"), () -> NetworkHandler.sendMiningSimShare(latestRun)));
        }

        addDrawableChild(new GlassButton(startX + (latestRun != null ? 318 : 212), btnY, 100, 20,
                Text.literal("Done"), this::close));
    }

    private String autoStopLabel() {
        int m = AUTO_STOP_CHOICES[autoStopIndex];
        return m == 0 ? "off" : m + "m";
    }

    /** The archived session being viewed, or null when showing the live/most-recent one. */
    private MiningSimState.@Nullable ArchivedSession viewedArchive() {
        if (viewingIndex < 0) return null;
        List<MiningSimState.ArchivedSession> archive = MiningSimState.archive();
        if (viewingIndex >= archive.size()) return null;
        return archive.get(viewingIndex);
    }

    /** The shared session being viewed, or null when this is one of our own runs. */
    private com.aleks.ancientsmod.net.payload.@Nullable MiningSimSharedPayload viewedShare() {
        return viewingShared ? MiningSimState.shared() : null;
    }

    /** The snapshot every tab renders — a shared session if one is open, else the loaded
     *  archive, else the live one. */
    private @Nullable MiningSimPayload viewedSnapshot() {
        var shared = viewedShare();
        if (shared != null) return shared.snapshot();
        MiningSimState.ArchivedSession a = viewedArchive();
        return a != null ? a.finalSnapshot() : MiningSimState.latest();
    }

    private List<MiningSimState.RatePoint> viewedHistory() {
        var shared = viewedShare();
        if (shared != null) return shared.history();
        MiningSimState.ArchivedSession a = viewedArchive();
        return a != null ? a.history() : MiningSimState.history();
    }

    /**
     * Cheap fingerprint of everything the button row depends on. Compared each tick so
     * the row rebuilds the moment the server's answer lands, rather than on a guess made
     * at click time that a refused action would leave wrong.
     */
    private String stateSignature() {
        if (viewingShared) return "shared:" + (MiningSimState.shared() != null);
        if (viewingIndex >= 0) return "archive:" + viewingIndex + ":" + (renameField != null);
        MiningSimPayload s = MiningSimState.liveSession();
        if (s == null) return "none";
        return "live:" + s.paused();
    }

    /** Commit the rename field's text and drop back to the normal control row. */
    private void commitRename() {
        if (renameField != null && viewingIndex >= 0) {
            MiningSimState.rename(viewingIndex, renameField.getText());
        }
        renameField = null;
        this.clearAndInit();
    }

    @Override
    public void tick() {
        super.tick();
        if (!stateSignature().equals(builtForState)) {
            this.clearAndInit();
        }
    }

    private GlassButton tabButton(int x, int y, int w, String label, Tab target) {
        GlassButton b = new GlassButton(x, y, w, 18, Text.literal(label), () -> {
            this.tab = target;
            this.scrollOffset = 0;
            this.clearAndInit();
        });
        return tab == target ? b.primary() : b;
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        GlassRender.menuBackdrop(ctx, this.width, this.height);

        MiningSimPayload snap = viewedSnapshot();
        MiningSimState.ArchivedSession viewed = viewedArchive();

        var shared = viewedShare();
        String title;
        if (shared != null) {
            title = shared.ownerName() + "'s Mining Sim — " + shared.label();
        } else if (viewed != null) {
            title = "Mining Simulation — " + viewed.label();
        } else {
            title = "Mining Simulation";
        }
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(title),
                this.width / 2, PADDING + 2, GlassTheme.ACCENT_SOFT);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(headline(snap)),
                this.width / 2, PADDING + 14, GlassTheme.textDim());
        if (snap != null) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(totalsLine(snap)),
                    this.width / 2, PADDING + 26, GlassTheme.text());
        }

        switch (tab) {
            case SOURCES -> renderSources(ctx, snap, mouseX, mouseY);
            case PROCS -> renderProcs(ctx, snap, mouseX, mouseY);
            case GRAPH -> renderGraph(ctx);
            case HISTORY -> renderHistory(ctx, mouseX, mouseY);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private String headline(@Nullable MiningSimPayload snap) {
        var shared = viewedShare();
        if (shared != null) {
            // Say whose it is on every tab, not just in the title: these are somebody
            // else's numbers and mistaking them for your own is the one real hazard here.
            return (shared.own() ? "Your shared run" : "Shared by " + shared.ownerName())
                    + " · " + formatDuration(shared.snapshot().elapsedMs())
                    + " · Import to keep it";
        }
        MiningSimState.ArchivedSession viewed = viewedArchive();
        if (viewed != null) {
            return "Saved session · " + formatDuration(viewed.finalSnapshot().elapsedMs());
        }
        if (snap == null) {
            return "No session running — press Start.";
        }
        if (snap.isFinal()) {
            return "Session ended after " + formatDuration(snap.elapsedMs());
        }
        if (!MiningSimState.isLive()) {
            return "Session lost contact — last seen " + formatDuration(snap.elapsedMs()) + " in";
        }
        return (snap.paused() ? "PAUSED at " : "Running for ") + formatDuration(snap.elapsedMs());
    }

    private String totalsLine(MiningSimPayload s) {
        return compact(s.totalXp()) + " XP (" + compact(s.perHour(s.totalXp())) + "/hr)"
             + "   " + compact(s.totalEnergy()) + " energy (" + compact(s.perHour(s.totalEnergy())) + "/hr)"
             + "   " + money(s.totalMoney()) + " (" + money(s.moneyPerHour()) + "/hr)";
    }

    // ── Sources ─────────────────────────────────────────────────────────────

    private int panelX() { return (this.width - PANEL_W) / 2; }
    private int panelY() { return PADDING + 64; }
    private int panelH() { return ROWS_VISIBLE * ROW_H + 26; }

    private List<MiningSimPayload.Row> sortedSources(@Nullable MiningSimPayload snap) {
        if (snap == null) return List.of();
        List<MiningSimPayload.Row> rows = new ArrayList<>(snap.sources());
        Comparator<MiningSimPayload.Row> cmp = switch (sortKey) {
            case ORIGIN -> Comparator.comparing(MiningSimPayload.Row::source, String.CASE_INSENSITIVE_ORDER);
            case ENERGY -> Comparator.comparingLong(MiningSimPayload.Row::energy);
            case MONEY -> Comparator.comparingDouble(MiningSimPayload.Row::money);
            case COUNT, XP -> Comparator.comparingLong(MiningSimPayload.Row::xp);
        };
        rows.sort(sortDescending ? cmp.reversed() : cmp);
        return rows;
    }

    private void renderSources(DrawContext ctx, @Nullable MiningSimPayload snap, int mouseX, int mouseY) {
        int px = panelX(), py = panelY();
        GlassRender.panel(ctx, px, py, PANEL_W, panelH());

        int left = px + 8;
        int xpRight = px + PANEL_W - 250;
        int energyRight = px + PANEL_W - 130;
        int moneyRight = px + PANEL_W - 10;
        int y = py + 6;

        ctx.drawText(textRenderer, Text.literal(header("Source", SortKey.ORIGIN)), left, y, GlassTheme.textDim(), false);
        drawRight(ctx, header("XP", SortKey.XP), xpRight, y, GlassTheme.textDim());
        drawRight(ctx, header("Energy", SortKey.ENERGY), energyRight, y, GlassTheme.textDim());
        drawRight(ctx, header("Money", SortKey.MONEY), moneyRight, y, GlassTheme.textDim());
        y += 11;
        ctx.fill(px + 4, y, px + PANEL_W - 4, y + 1, GlassTheme.rim());
        y += 3;

        List<MiningSimPayload.Row> rows = sortedSources(snap);
        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("Nothing recorded yet."),
                    left, y + 4, GlassTheme.textMuted(), false);
            return;
        }

        int end = Math.min(rows.size(), scrollOffset + ROWS_VISIBLE);
        for (int i = scrollOffset; i < end; i++) {
            MiningSimPayload.Row r = rows.get(i);
            int textY = y + 2;
            if (mouseY >= y && mouseY < y + ROW_H && mouseX >= px && mouseX < px + PANEL_W) {
                ctx.fill(px + 4, y, px + PANEL_W - 4, y + ROW_H, GlassTheme.rowHover());
            }

            // Origin in accent, the rest of the chain dimmed — the origin is the number
            // that matters, the tail is just how it got there.
            String origin = r.origin();
            String path = r.path();
            ctx.drawText(textRenderer, Text.literal(origin), left, textY, GlassTheme.ACCENT_SOFT, false);
            if (!path.isEmpty()) {
                int ox = left + textRenderer.getWidth(origin);
                ctx.drawText(textRenderer, Text.literal(" > " + path), ox, textY, GlassTheme.textMuted(), false);
            }

            drawRight(ctx, r.xp() > 0 ? compact(r.xp()) : "—", xpRight, textY,
                    r.xp() > 0 ? GlassTheme.text() : GlassTheme.textMuted());
            drawRight(ctx, r.energy() > 0 ? compact(r.energy()) : "—", energyRight, textY,
                    r.energy() > 0 ? GlassTheme.VALUE : GlassTheme.textMuted());
            drawRight(ctx, r.money() > 0 ? money(r.money()) : "—", moneyRight, textY,
                    r.money() > 0 ? GlassTheme.OK : GlassTheme.textMuted());
            y += ROW_H;
        }

        drawScrollHint(ctx, rows.size(), px, py);
    }

    // ── Procs ───────────────────────────────────────────────────────────────

    private List<MiningSimPayload.ProcRow> sortedProcs(@Nullable MiningSimPayload snap) {
        if (snap == null) return List.of();
        List<MiningSimPayload.ProcRow> rows = new ArrayList<>(snap.procs());
        Comparator<MiningSimPayload.ProcRow> cmp = sortKey == SortKey.ORIGIN
                ? Comparator.comparing(MiningSimPayload.ProcRow::name, String.CASE_INSENSITIVE_ORDER)
                : Comparator.comparingInt(MiningSimPayload.ProcRow::count);
        rows.sort(sortDescending ? cmp.reversed() : cmp);
        return rows;
    }

    private void renderProcs(DrawContext ctx, @Nullable MiningSimPayload snap, int mouseX, int mouseY) {
        int px = panelX(), py = panelY();
        GlassRender.panel(ctx, px, py, PANEL_W, panelH());

        int left = px + 8;
        int countRight = px + PANEL_W - 130;
        int rateRight = px + PANEL_W - 10;
        int y = py + 6;

        ctx.drawText(textRenderer, Text.literal(header("Proc chain", SortKey.ORIGIN)), left, y, GlassTheme.textDim(), false);
        drawRight(ctx, header("Procs", SortKey.COUNT), countRight, y, GlassTheme.textDim());
        drawRight(ctx, "Per hour", rateRight, y, GlassTheme.textDim());
        y += 11;
        ctx.fill(px + 4, y, px + PANEL_W - 4, y + 1, GlassTheme.rim());
        y += 3;

        List<MiningSimPayload.ProcRow> rows = sortedProcs(snap);
        if (rows.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("No procs recorded yet."),
                    left, y + 4, GlassTheme.textMuted(), false);
            return;
        }

        long denom = snap == null ? 0L : snap.miningElapsedMs();
        int end = Math.min(rows.size(), scrollOffset + ROWS_VISIBLE);
        for (int i = scrollOffset; i < end; i++) {
            MiningSimPayload.ProcRow r = rows.get(i);
            int textY = y + 2;
            if (mouseY >= y && mouseY < y + ROW_H && mouseX >= px && mouseX < px + PANEL_W) {
                ctx.fill(px + 4, y, px + PANEL_W - 4, y + ROW_H, GlassTheme.rowHover());
            }
            String origin = r.origin();
            String path = r.path();
            ctx.drawText(textRenderer, Text.literal(origin), left, textY, GlassTheme.ACCENT_SOFT, false);
            if (!path.isEmpty()) {
                int ox = left + textRenderer.getWidth(origin);
                ctx.drawText(textRenderer, Text.literal(" > " + path), ox, textY, GlassTheme.textMuted(), false);
            }
            drawRight(ctx, String.valueOf(r.count()), countRight, textY, GlassTheme.text());
            String perHour = denom > 0
                    ? compact(Math.round(r.count() * (3_600_000.0 / denom)))
                    : "—";
            drawRight(ctx, perHour, rateRight, textY, GlassTheme.textDim());
            y += ROW_H;
        }

        drawScrollHint(ctx, rows.size(), px, py);
    }

    // ── Graph ───────────────────────────────────────────────────────────────

    private void renderGraph(DrawContext ctx) {
        int px = panelX(), py = panelY();
        int ph = panelH();
        GlassRender.panel(ctx, px, py, PANEL_W, ph);

        List<MiningSimState.RatePoint> pts = viewedHistory();
        if (pts.size() < 2) {
            // A shared run can legitimately arrive without a curve — the server only ever
            // knows totals, so an old session captured server-side has no samples to send.
            String empty = viewedShare() != null
                    ? "No rate graph was shared with this session."
                    : "Not enough samples yet — the graph fills in as you mine.";
            ctx.drawText(textRenderer, Text.literal(empty),
                    px + 8, py + 8, GlassTheme.textMuted(), false);
            return;
        }

        int plotX = px + 8, plotY = py + 18;
        int plotW = PANEL_W - 16, plotH = ph - 34;

        long maxXp = 1, maxEnergy = 1;
        double maxMoney = 1;
        for (MiningSimState.RatePoint p : pts) {
            maxXp = Math.max(maxXp, p.xpPerHour());
            maxEnergy = Math.max(maxEnergy, p.energyPerHour());
            maxMoney = Math.max(maxMoney, p.moneyPerHour());
        }

        ctx.drawText(textRenderer, Text.literal("XP/hr"), plotX, py + 6, GlassTheme.text(), false);
        ctx.drawText(textRenderer, Text.literal("Energy/hr"), plotX + 54, py + 6, GlassTheme.VALUE, false);
        ctx.drawText(textRenderer, Text.literal("$/hr"), plotX + 128, py + 6, GlassTheme.OK, false);
        drawRight(ctx, "peak " + compact(maxXp) + " XP/hr", px + PANEL_W - 10, py + 6, GlassTheme.textDim());

        // Each series is normalised against its own peak — they share no unit, so a shared
        // axis would flatten whichever one is numerically smaller into the baseline.
        plotSeries(ctx, pts, plotX, plotY, plotW, plotH, maxXp, GlassTheme.text(), 0);
        plotSeries(ctx, pts, plotX, plotY, plotW, plotH, maxEnergy, GlassTheme.VALUE, 1);
        plotSeries(ctx, pts, plotX, plotY, plotW, plotH, (long) Math.ceil(maxMoney), GlassTheme.OK, 2);

        ctx.fill(plotX, plotY + plotH, plotX + plotW, plotY + plotH + 1, GlassTheme.rim());
    }

    /** Draw one normalised series as a column chart — one column per horizontal pixel bucket. */
    private void plotSeries(DrawContext ctx, List<MiningSimState.RatePoint> pts,
                            int plotX, int plotY, int plotW, int plotH,
                            long max, int color, int series) {
        if (max <= 0) return;
        int n = pts.size();
        for (int x = 0; x < plotW; x++) {
            int idx = (int) ((long) x * (n - 1) / Math.max(1, plotW - 1));
            MiningSimState.RatePoint p = pts.get(Math.min(idx, n - 1));
            long v = switch (series) {
                case 0 -> p.xpPerHour();
                case 1 -> p.energyPerHour();
                default -> Math.round(p.moneyPerHour());
            };
            int h = (int) Math.round((double) v / max * plotH);
            if (h <= 0) continue;
            int top = plotY + plotH - h;
            // 1px columns, offset per series so overlapping lines stay readable.
            if ((x + series) % 3 != 0) continue;
            ctx.fill(plotX + x, top, plotX + x + 1, plotY + plotH, color);
        }
    }

    // ── History / compare ───────────────────────────────────────────────────

    private void renderHistory(DrawContext ctx, int mouseX, int mouseY) {
        int px = panelX(), py = panelY();
        GlassRender.panel(ctx, px, py, PANEL_W, panelH());

        List<MiningSimState.ArchivedSession> archive = MiningSimState.archive();
        int left = px + 8;
        int y = py + 6;

        ctx.drawText(textRenderer, Text.literal("Saved sessions — click to open, shift-click two to compare"),
                left, y, GlassTheme.textDim(), false);
        drawRight(ctx, "XP/hr", px + PANEL_W - 10 - RATE_COL_W * 2, y, GlassTheme.textDim());
        drawRight(ctx, "Energy/hr", px + PANEL_W - 10 - RATE_COL_W, y, GlassTheme.textDim());
        drawRight(ctx, "$/hr", px + PANEL_W - 10, y, GlassTheme.textDim());
        y += 11;
        ctx.fill(px + 4, y, px + PANEL_W - 4, y + 1, GlassTheme.rim());
        y += 3;

        if (archive.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("No saved sessions yet. Stop a session to save it."),
                    left, y + 4, GlassTheme.textMuted(), false);
            return;
        }

        int end = Math.min(archive.size(), scrollOffset + HISTORY_ROWS_VISIBLE);
        for (int i = scrollOffset; i < end; i++) {
            MiningSimState.ArchivedSession s = archive.get(i);
            MiningSimPayload f = s.finalSnapshot();
            boolean picked = (i == compareA || i == compareB);
            if (mouseY >= y && mouseY < y + ROW_H && mouseX >= px && mouseX < px + PANEL_W) {
                ctx.fill(px + 4, y, px + PANEL_W - 4, y + ROW_H, GlassTheme.rowHover());
            }
            int nameColor = picked ? GlassTheme.ACCENT : GlassTheme.text();
            ctx.drawText(textRenderer, Text.literal((picked ? "> " : "  ") + s.label()
                            + "  (" + formatDuration(f.elapsedMs()) + ")"),
                    left, y + 2, nameColor, false);
            // All three rates, right-aligned in fixed columns. Comparing sessions on XP alone
            // hid the interesting differences — two runs can match on XP while one pays double
            // the money, which is exactly what separates the perks being tuned here.
            drawRight(ctx, compact(f.perHour(f.totalXp())) + " xp",
                    px + PANEL_W - 10 - RATE_COL_W * 2, y + 2, GlassTheme.textDim());
            drawRight(ctx, compact(f.perHour(f.totalEnergy())) + " e",
                    px + PANEL_W - 10 - RATE_COL_W, y + 2, GlassTheme.textDim());
            drawRight(ctx, "$" + compact(Math.round(f.moneyPerHour())),
                    px + PANEL_W - 10, y + 2, GlassTheme.textDim());
            y += ROW_H;
        }

        if (compareA >= 0 && compareB >= 0
                && compareA < archive.size() && compareB < archive.size()) {
            y += 6;
            ctx.fill(px + 4, y, px + PANEL_W - 4, y + 1, GlassTheme.rim());
            y += 4;
            MiningSimPayload a = archive.get(compareA).finalSnapshot();
            MiningSimPayload b = archive.get(compareB).finalSnapshot();
            ctx.drawText(textRenderer, Text.literal(archive.get(compareA).label()
                            + "  vs  " + archive.get(compareB).label()),
                    left, y, GlassTheme.ACCENT_SOFT, false);
            y += 12;
            drawDelta(ctx, left, y, "XP/hr", a.perHour(a.totalXp()), b.perHour(b.totalXp()));
            y += 11;
            drawDelta(ctx, left, y, "Energy/hr", a.perHour(a.totalEnergy()), b.perHour(b.totalEnergy()));
            y += 11;
            drawDelta(ctx, left, y, "$/hr", Math.round(a.moneyPerHour()), Math.round(b.moneyPerHour()));
        }
    }

    private void drawDelta(DrawContext ctx, int x, int y, String label, long a, long b) {
        long diff = b - a;
        double pct = a == 0 ? 0.0 : (diff * 100.0 / a);
        int color = diff > 0 ? GlassTheme.OK : (diff < 0 ? GlassTheme.WARN : GlassTheme.textDim());
        String sign = diff > 0 ? "+" : "";
        ctx.drawText(textRenderer, Text.literal(label + ": " + compact(a) + " -> " + compact(b)),
                x, y, GlassTheme.text(), false);
        ctx.drawText(textRenderer,
                Text.literal("   " + sign + compact(diff)
                        + (a == 0 ? "" : "  (" + sign + String.format(Locale.ROOT, "%.1f", pct) + "%)")),
                x + 210, y, color, false);
    }

    // ── Input ───────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyInput input) {
        if (renameField != null) {
            // Enter commits, Escape backs out of the rename rather than closing the whole
            // screen — losing the screen because you changed your mind about a name would
            // be a nasty way to lose your place.
            if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
                commitRename();
                return true;
            }
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
                renameField = null;
                this.clearAndInit();
                return true;
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int rows = switch (tab) {
            case SOURCES -> sortedSources(viewedSnapshot()).size();
            case PROCS -> sortedProcs(viewedSnapshot()).size();
            case HISTORY -> MiningSimState.archive().size();
            default -> 0;
        };
        int visible = tab == Tab.HISTORY ? HISTORY_ROWS_VISIBLE : ROWS_VISIBLE;
        int maxOffset = Math.max(0, rows - visible);
        if (maxOffset <= 0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        scrollOffset -= (int) Math.signum(verticalAmount);
        scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset));
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        if (click.button() == 0) {
            int px = panelX(), py = panelY();
            double mx = click.x(), my = click.y();

            // Column headers toggle the sort. Same band for both tables.
            if ((tab == Tab.SOURCES || tab == Tab.PROCS)
                    && my >= py + 4 && my < py + 16 && mx >= px && mx < px + PANEL_W) {
                SortKey clicked = columnAt(mx, px);
                if (clicked != null) {
                    if (clicked == sortKey) {
                        sortDescending = !sortDescending;
                    } else {
                        sortKey = clicked;
                        sortDescending = true;
                    }
                    scrollOffset = 0;
                    return true;
                }
            }

            if (tab == Tab.HISTORY) {
                int rowsTop = py + 20;
                int idx = scrollOffset + (int) ((my - rowsTop) / ROW_H);
                if (my < rowsTop || idx >= scrollOffset + HISTORY_ROWS_VISIBLE) idx = -1;
                List<MiningSimState.ArchivedSession> archive = MiningSimState.archive();
                if (idx >= 0 && idx < archive.size() && mx >= px && mx < px + PANEL_W) {
                    // Screen.hasShiftDown() is gone in this mapping; the Click record
                    // carries GLFW_MOD_SHIFT directly.
                    if ((click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
                        // Two-slot picker: newest pick lands in B, previous B shifts to A.
                        if (idx == compareA) {
                            compareA = -1;
                        } else if (idx == compareB) {
                            compareB = -1;
                        } else {
                            compareA = compareB;
                            compareB = idx;
                        }
                    } else {
                        // Plain click opens the run — every tab switches over to it, and
                        // it takes over from a shared session if one was being viewed.
                        viewingShared = false;
                        viewingIndex = idx;
                        tab = Tab.SOURCES;
                        scrollOffset = 0;
                        this.clearAndInit();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    private @Nullable SortKey columnAt(double mx, int px) {
        if (tab == Tab.SOURCES) {
            if (mx < px + PANEL_W - 250) return SortKey.ORIGIN;
            if (mx < px + PANEL_W - 130) return SortKey.XP;
            if (mx < px + PANEL_W - 10) return SortKey.ENERGY;
            return SortKey.MONEY;
        }
        if (mx < px + PANEL_W - 130) return SortKey.ORIGIN;
        return SortKey.COUNT;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String header(String label, SortKey key) {
        if (sortKey != key) return label;
        return label + (sortDescending ? " v" : " ^");
    }

    private void drawScrollHint(DrawContext ctx, int total, int px, int py) {
        if (total <= ROWS_VISIBLE) return;
        int shown = Math.min(total, scrollOffset + ROWS_VISIBLE);
        drawRight(ctx, (scrollOffset + 1) + "-" + shown + " of " + total,
                px + PANEL_W - 10, py + panelH() - 12, GlassTheme.textMuted());
    }

    private void drawRight(DrawContext ctx, String s, int right, int y, int color) {
        ctx.drawText(textRenderer, Text.literal(s), right - textRenderer.getWidth(s), y, color, false);
    }

    private static String formatDuration(long ms) {
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        sec %= 60;
        if (min < 60) return min + "m " + sec + "s";
        long hr = min / 60;
        min %= 60;
        return hr + "h " + min + "m";
    }

    private static String compact(long v) {
        boolean neg = v < 0;
        long a = Math.abs(v);
        String s;
        if (a >= 1_000_000_000L) s = String.format(Locale.ROOT, "%.2fB", a / 1_000_000_000.0);
        else if (a >= 1_000_000L) s = String.format(Locale.ROOT, "%.2fM", a / 1_000_000.0);
        else if (a >= 1_000L) s = String.format(Locale.ROOT, "%.1fK", a / 1_000.0);
        else s = String.valueOf(a);
        return neg ? "-" + s : s;
    }

    private static String money(double v) {
        return "$" + compact(Math.round(v));
    }
}
