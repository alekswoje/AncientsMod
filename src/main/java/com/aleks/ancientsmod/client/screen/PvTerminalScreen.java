package com.aleks.ancientsmod.client.screen;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.pv.PvClient;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.net.payload.PvBundlePayload;
import net.minecraft.text.Text;

/**
 * ME-terminal style PV view. Renders every non-empty stack across every
 * unlocked PV as a single flat grid, with a search bar and a hotbar strip at
 * the bottom for drag-deposits. All the grid/search/cursor machinery lives in
 * {@link ItemTerminalScreen} (shared with the cell-vault terminal); this class
 * binds it to the PV bundle + the PV C2S packets.
 *
 * <p>This is the default {@code /pv} view ({@code pvTerminal} toggle on by
 * default). When the toggle is off, {@link PvClient} opens the
 * {@link PvOverviewScreen} card view instead. Also opened directly (in a
 * read/edit "{@code /pvsee}" mode) when an admin inspects another player's
 * vaults.
 *
 * <h2>Interactions</h2>
 * <ul>
 *   <li>L-click tile → extract 1 ({@link Protocol#PV_EXTRACT_ONE}).</li>
 *   <li>R-click tile → extract half ({@link Protocol#PV_EXTRACT_HALF}).</li>
 *   <li>Shift+L-click tile → extract a full stack summed across all vaults
 *       ({@link Protocol#PV_EXTRACT_STACK}); repeat to pull the next stack.</li>
 *   <li>L-press hotbar slot, drag onto grid, release → deposit that hotbar
 *       slot via {@link NetworkHandler#sendPvShiftClick(int)} (server fills the
 *       first vault with space).</li>
 *   <li>L-press an inventory slot and drag onto another inventory slot → drops
 *       the stack there on release, so two partial stacks pulled out of the
 *       vaults can be merged without closing the terminal.</li>
 * </ul>
 *
 * <p>The screen is bundle-driven: after every extract / deposit the server
 * pushes a fresh {@link PvBundlePayload} which {@link PvClient#onBundle}
 * routes to {@link #onBundleUpdated} for in-place re-render.
 */
public final class PvTerminalScreen extends ItemTerminalScreen {

    private PvBundlePayload bundle;

    /** Non-null only for a {@code /pvsee} admin session (viewing/editing
     *  another player's vaults). Drives the title and the close-out packet that
     *  ends the server-side session. */
    private final String pvSeeTargetName;

    public PvTerminalScreen(PvBundlePayload bundle) {
        this(bundle, null);
    }

    /**
     * @param pvSeeTargetName the inspected player's name for a {@code /pvsee}
     *                        session, or null for the player's own terminal.
     */
    public PvTerminalScreen(PvBundlePayload bundle, String pvSeeTargetName) {
        super(Text.literal(pvSeeTargetName == null || pvSeeTargetName.isEmpty()
                ? "PV Terminal" : pvSeeTargetName + "'s Vaults"));
        this.bundle = bundle;
        this.pvSeeTargetName = (pvSeeTargetName == null || pvSeeTargetName.isEmpty())
                ? null : pvSeeTargetName;
    }

    private boolean isPvSee() {
        return pvSeeTargetName != null;
    }

    public void onBundleUpdated(PvBundlePayload payload) {
        // Note vaults whose visible item count changed — used for the post-op
        // tile flash. Only flash on a delta to avoid the cosmetic firing on
        // every periodic refresh.
        java.util.Set<Integer> changed = changedVaults(this.bundle, payload);
        this.bundle = payload;
        applyBundleRefresh(changed);
    }

    private static java.util.Set<Integer> changedVaults(PvBundlePayload before, PvBundlePayload after) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        if (before == null || after == null) return out;
        java.util.Map<Integer, Integer> beforeCounts = new java.util.HashMap<>();
        for (PvBundlePayload.Vault v : before.vaults) beforeCounts.put(v.vaultNumber, v.slots.size());
        for (PvBundlePayload.Vault v : after.vaults) {
            Integer prev = beforeCounts.get(v.vaultNumber);
            if (prev == null || prev != v.slots.size()) out.add(v.vaultNumber);
        }
        return out;
    }

    // ── ItemTerminalScreen hooks ─────────────────────────────────────────────

    @Override
    protected void forEachVisibleSlot(SlotVisitor visitor) {
        if (bundle == null) return;
        for (PvBundlePayload.Vault v : bundle.vaults) {
            if (!v.isAccessible()) continue;
            for (PvBundlePayload.Slot s : v.slots) {
                visitor.accept(v.vaultNumber, s);
            }
        }
    }

    /** Whether the player may currently take/put. Driven by the bundle's
     *  safe-zone flag (server-authoritative). When false, extract + deposit are
     *  blocked client-side and the screen renders a view-only notice. */
    @Override
    protected boolean canModify() {
        return bundle == null || bundle.safe;
    }

    @Override
    protected int occupiedSlots() {
        int total = 0;
        if (bundle != null) {
            for (PvBundlePayload.Vault v : bundle.vaults) {
                if (v.isAccessible()) total += v.slots.size();
            }
        }
        return total;
    }

    @Override
    protected int capacitySlots() {
        int total = 0;
        if (bundle != null) {
            for (PvBundlePayload.Vault v : bundle.vaults) {
                if (v.isAccessible()) total += v.slotCount;
            }
        }
        return total;
    }

    @Override
    protected Text titleText(String statsSuffix) {
        String titleLabel = isPvSee() ? "§d" + pvSeeTargetName + "'s Vaults" : "§ePV Terminal";
        return Text.literal(titleLabel + statsSuffix);
    }

    @Override
    protected String viewOnlyBadge() {
        // You can browse/search your PVs anywhere, but taking/depositing is
        // safe-zone only.
        return "§c⚠ View only · safe zone";
    }

    @Override
    protected String blockedMessage() {
        return "§cYou can only use your vault in a safe zone!";
    }

    @Override
    protected String emptyMessage() {
        return "§7Your vaults are empty.";
    }

    @Override
    protected String depositHintModifiable() {
        return "§8Shift-click → deposit to PV";
    }

    @Override
    protected String depositHintViewOnly() {
        return "§cView only — deposit in a safe zone";
    }

    /** "PV 1, 3, 5" — the distinct vaults this tile's stacks live in (capped). */
    @Override
    protected String sourcesLabel(Entry e) {
        java.util.TreeSet<Integer> pvs = new java.util.TreeSet<>();
        for (Source s : e.sources) pvs.add(s.group);
        StringBuilder sb = new StringBuilder(pvs.size() == 1 ? "PV " : "PVs ");
        int i = 0;
        for (int pv : pvs) {
            if (i >= 6) { sb.append("…"); break; }
            if (i > 0) sb.append(", ");
            sb.append(pv);
            i++;
        }
        return sb.toString();
    }

    @Override
    protected int getSortMode() {
        return FeatureToggles.getPvTerminalSortMode();
    }

    @Override
    protected int cycleSortMode() {
        return FeatureToggles.cyclePvTerminalSortMode();
    }

    @Override
    protected boolean autoFocusSearch() {
        return FeatureToggles.isPvTerminalAutoFocusSearchEnabled();
    }

    @Override
    protected void sendExtract(Source ref, byte mode, byte target) {
        NetworkHandler.sendPvExtract(ref.group, ref.slotIndex, mode, target);
    }

    @Override
    protected void sendExtractItem(Source ref, byte mode, byte target) {
        NetworkHandler.sendPvExtractItem(ref.group, ref.slotIndex, mode, target);
    }

    @Override
    protected void sendDeposit(int playerInvSlot) {
        NetworkHandler.sendPvShiftClick(playerInvSlot);
    }

    @Override
    protected void sendCursorPlaceInv(int playerInvSlot) {
        NetworkHandler.sendPvCursorPlaceInv(playerInvSlot);
    }

    @Override
    protected void sendCursorReturn() {
        NetworkHandler.sendPvCursorReturn();
    }

    /** Close-out (after the base's cursor-return): end the server-side /pvsee
     *  session so the admin's own PV packets stop acting on the inspected
     *  player's vaults, then release the PV state machine. */
    @Override
    protected void onClosed() {
        if (isPvSee()) {
            NetworkHandler.sendPvSeeClose();
        }
        // End the server-side /pvsee session so the admin's own PV packets stop
        // acting on the inspected player's vaults.
        if (isPvSee()) {
            NetworkHandler.sendPvSeeClose();
        }
        PvClient.onScreenClosed();
    }
}
