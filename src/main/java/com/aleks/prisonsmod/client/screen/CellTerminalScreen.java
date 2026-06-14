package com.aleks.prisonsmod.client.screen;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.cellterm.CellTermClient;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.payload.CellTermBundlePayload;
import com.aleks.prisonsmod.net.payload.PvBundlePayload;
import net.minecraft.text.Text;

/**
 * ME-terminal style Cell Vault view: an aggregated, searchable grid of ALL
 * containers in the player's cell (vault + chests + barrels), opened by the
 * server when a modded player opens their cell's vault chest (the server
 * cancels the vanilla chest GUI and pushes {@code PKT_CELLTERM_OPEN} + a
 * bundle — purely server-push, no client command interception).
 *
 * <p>All the grid/search/cursor machinery lives in {@link ItemTerminalScreen}
 * (shared with {@link PvTerminalScreen}); this class binds it to the cell
 * bundle + the cellterm C2S packets. Entries aggregate across containers
 * exactly like PV aggregates across vaults — each tile's sources are
 * (containerId, slotIndex, amount) triples, and the tooltip footer lists the
 * containers' server-sent labels ("Vault, Chest 2").
 *
 * <p>Server-authoritative: after every op the server pushes a fresh
 * {@link CellTermBundlePayload}, which {@link CellTermClient#onBundle} routes
 * to {@link #onBundleUpdated} — all optimistic predictions are discarded the
 * moment it lands. {@code editable=false} (from the OPEN packet's flags)
 * renders the view-only path, mirroring the PV terminal's view-only mode.
 */
public final class CellTerminalScreen extends ItemTerminalScreen {

    private CellTermBundlePayload bundle;

    /** Server-sent cell label from PKT_CELLTERM_OPEN — may carry legacy §
     *  colour codes (incl. §x hex), rendered via {@code legacyText}. */
    private final String cellLabel;

    /** From the OPEN packet's flags (bit0). Fixed for the session — the server
     *  force-closes (rather than re-flags) when access changes. */
    private final boolean editable;

    /** containerId → server-sent display label ("Vault", "Chest 2", …),
     *  refreshed from every bundle; drives the tooltip source line. */
    private final java.util.Map<Integer, String> containerLabels = new java.util.HashMap<>();

    public CellTerminalScreen(CellTermBundlePayload bundle, String cellLabel, boolean editable) {
        super(Text.literal(stripColor(cellLabel == null || cellLabel.isEmpty()
                ? "Cell Terminal" : cellLabel)));
        this.cellLabel = (cellLabel == null || cellLabel.isEmpty()) ? "§eCell Terminal" : cellLabel;
        this.editable = editable;
        this.bundle = bundle;
        rebuildContainerLabels();
    }

    public void onBundleUpdated(CellTermBundlePayload payload) {
        // Note containers whose visible item count changed — used for the
        // post-op tile flash. Only flash on a delta to avoid the cosmetic
        // firing on every periodic (~2s) re-push.
        java.util.Set<Integer> changed = changedContainers(this.bundle, payload);
        this.bundle = payload;
        rebuildContainerLabels();
        applyBundleRefresh(changed);
    }

    private static java.util.Set<Integer> changedContainers(CellTermBundlePayload before,
                                                            CellTermBundlePayload after) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (before == null || after == null) return out;
        java.util.Map<Integer, Integer> beforeCounts = new java.util.HashMap<>();
        for (CellTermBundlePayload.Container c : before.containers) {
            beforeCounts.put(c.containerId, c.slots.size());
        }
        for (CellTermBundlePayload.Container c : after.containers) {
            Integer prev = beforeCounts.get(c.containerId);
            if (prev == null || prev != c.slots.size()) out.add(c.containerId);
        }
        return out;
    }

    private void rebuildContainerLabels() {
        containerLabels.clear();
        if (bundle == null) return;
        for (CellTermBundlePayload.Container c : bundle.containers) {
            containerLabels.put(c.containerId, c.label);
        }
    }

    // ── ItemTerminalScreen hooks ─────────────────────────────────────────────

    @Override
    protected void forEachVisibleSlot(SlotVisitor visitor) {
        if (bundle == null) return;
        for (CellTermBundlePayload.Container c : bundle.containers) {
            for (PvBundlePayload.Slot s : c.slots) {
                visitor.accept(c.containerId, s);
            }
        }
    }

    /** Whether the player may take/put. Driven by the OPEN packet's editable
     *  flag (server-authoritative; the server also rejects ops it never
     *  granted). When false the screen is the view-only path. */
    @Override
    protected boolean canModify() {
        return editable;
    }

    @Override
    protected int occupiedSlots() {
        int total = 0;
        if (bundle != null) {
            for (CellTermBundlePayload.Container c : bundle.containers) total += c.slots.size();
        }
        return total;
    }

    @Override
    protected int capacitySlots() {
        int total = 0;
        if (bundle != null) {
            for (CellTermBundlePayload.Container c : bundle.containers) total += c.slotCount;
        }
        return total;
    }

    @Override
    protected Text titleText(String statsSuffix) {
        // The cell label may carry legacy § codes including §x hex runs, so
        // build it through legacyText (same renderer PV uses for coloured item
        // names) rather than a raw literal.
        return Text.empty().copy()
                .append(legacyText(cellLabel))
                .append(Text.literal(statsSuffix));
    }

    @Override
    protected String viewOnlyBadge() {
        return "§c⚠ View only";
    }

    @Override
    protected String blockedMessage() {
        return "§cYou don't have permission to modify this cell!";
    }

    @Override
    protected String emptyMessage() {
        return "§7This cell's containers are empty.";
    }

    @Override
    protected String depositHintModifiable() {
        return "§8Shift-click → deposit to cell";
    }

    @Override
    protected String depositHintViewOnly() {
        return "§cView only — you can't deposit here";
    }

    /** "Vault, Chest 2" — the distinct containers this tile's stacks live in
     *  (server-sent labels, colour-stripped, capped like the PV list). */
    @Override
    protected String sourcesLabel(Entry e) {
        java.util.TreeSet<Integer> ids = new java.util.TreeSet<>();
        for (Source s : e.sources) ids.add(s.group);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (int id : ids) {
            if (i >= 6) { sb.append("…"); break; }
            if (i > 0) sb.append(", ");
            String label = containerLabels.get(id);
            sb.append(label == null || label.isEmpty()
                    ? ("Container " + id) : stripColor(label));
            i++;
        }
        return sb.toString();
    }

    @Override
    protected int getSortMode() {
        return FeatureToggles.getCellTermSortMode();
    }

    @Override
    protected int cycleSortMode() {
        return FeatureToggles.cycleCellTermSortMode();
    }

    @Override
    protected boolean autoFocusSearch() {
        // Reuse the PV terminal's auto-focus preference — one knob for "do
        // terminals grab the search box on open".
        return FeatureToggles.isPvTerminalAutoFocusSearchEnabled();
    }

    @Override
    protected void sendExtract(Source ref, byte mode, byte target) {
        NetworkHandler.sendCellTermExtract(ref.group, ref.slotIndex, mode, target);
    }

    @Override
    protected void sendExtractItem(Source ref, byte mode, byte target) {
        NetworkHandler.sendCellTermExtractItem(ref.group, ref.slotIndex, mode, target);
    }

    @Override
    protected void sendDeposit(int playerInvSlot) {
        NetworkHandler.sendCellTermDeposit(playerInvSlot);
    }

    @Override
    protected void sendCursorPlaceInv(int playerInvSlot) {
        NetworkHandler.sendCellTermCursorPlaceInv(playerInvSlot);
    }

    @Override
    protected void sendCursorReturn() {
        NetworkHandler.sendCellTermCursorReturn();
    }

    /** Close-out (after the base's cursor-return, same order as the PV
     *  terminal): notify the client state machine, which clears its pending
     *  state and sends the C2S session close. */
    @Override
    protected void onClosed() {
        CellTermClient.onScreenClosed();
    }
}
