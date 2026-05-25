package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.net.NetworkHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates client-prediction flicker when shift-clicking items from the
 * player inventory into a Personal Vault menu.
 *
 * <p><b>Why</b>. Without this mixin, shift-clicking in a PV menu fires the
 * vanilla {@code ServerboundContainerClickPacket} which carries the client's
 * <i>predicted</i> post-click state (item now in the open container). The
 * server-side {@code PvAffinityRouter} then cancels the event and reroutes
 * the item to a different PV (or clusters within the open one). The client
 * has already painted the predicted state, so it has to revert when the
 * server's correction arrives — visible flicker.
 *
 * <p><b>What</b>. We intercept the click at HEAD, send a custom
 * {@code PKT_PV_SHIFT_CLICK_REQ} with the player-inventory slot index, and
 * cancel the vanilla call. The server runs the same routing logic and sends
 * authoritative inventory updates through normal channels — the client never
 * predicts, so there's nothing to correct.
 *
 * <p><b>Scope</b>. Only intercepts:
 * <ul>
 *   <li>{@link SlotActionType#QUICK_MOVE} (shift+click)</li>
 *   <li>Screen title starts with "Personal Vault " (per-PV inventory)</li>
 *   <li>Slot is in player inventory (main + hotbar, 0..35), not armor/offhand</li>
 *   <li>The cached PvBundle reports at least one affinity is set —
 *       otherwise vanilla shift-click is exactly what we want anyway</li>
 * </ul>
 *
 * <p>Other directions (PV → player inv, within player inv, hotbar swaps,
 * non-PV screens) pass through to vanilla unchanged.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenShiftClickMixin {

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("HEAD"), cancellable = true)
    private void prisonsmod$interceptPvShiftClick(Slot slot, int slotId, int button,
                                                  SlotActionType actionType, CallbackInfo ci) {
        if (actionType != SlotActionType.QUICK_MOVE) return;
        if (slot == null) return;
        if (!ServerAllowlist.isAllowed()) return;
        // If the player turned off the mod's PV overview feature, let vanilla
        // shift-click handle it. The server-side affinity gate also keys off
        // the same toggle (via PKT_PV_FEATURES_STATE), so without this we'd
        // intercept the click only for the server to fall back — extra round-
        // trip with no benefit. Keeping vanilla also means a vanilla shift-
        // click event fires for any other server listener that cares.
        if (!FeatureToggles.isPvOverviewEnabled()) return;

        // Only intercept on PV per-vault inventories. Strip legacy § color
        // codes — Paper sends the title with embedded §8 codes for DARK_GRAY
        // styling, and Text.getString() can include them depending on how
        // the component was constructed.
        @SuppressWarnings("unchecked")
        HandledScreen<?> self = (HandledScreen<?>)(Object) this;
        Text titleText = self.getTitle();
        if (titleText == null) return;
        String rawTitle = titleText.getString();
        if (rawTitle == null) return;
        String title = stripLegacyColors(rawTitle);
        if (!title.startsWith("Personal Vault ")) return;
        // Exclude the overview chest "Personal Vaults" (plural, no trailing
        // space + number) — startsWith("Personal Vault ") with the trailing
        // space rules it out automatically.

        // Only intercept shift-clicks ORIGINATING in the player inventory.
        // PV → player-inv shift-clicks (the other direction) should stay
        // vanilla — they don't trigger our server-side affinity router.
        if (!(slot.inventory instanceof PlayerInventory)) return;
        int playerInvSlot = slot.getIndex();
        if (playerInvSlot < 0 || playerInvSlot > 35) return; // skip armor (36-39) / offhand (40)

        // No hasAnyAffinity gate — the server falls back to vanilla shift-click
        // if no affinity matches, so it's safe to always route through us. This
        // also avoids a race: the cached PvBundle might not yet exist if the
        // player opened a PV via "/pv 4" directly (bypassing the overview).
        if (!loggedFirstIntercept) {
            loggedFirstIntercept = true;
            PrisonsMod.LOGGER.info("[PvShiftClick] intercepting QUICK_MOVE in PV menu — title='" + title
                    + "' playerInvSlot=" + playerInvSlot);
        }
        NetworkHandler.sendPvShiftClick(playerInvSlot);
        ci.cancel();
    }

    private static volatile boolean loggedFirstIntercept = false;

    private static String stripLegacyColors(String s) {
        if (s.indexOf('§') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                // skip the § and the next style code char
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
