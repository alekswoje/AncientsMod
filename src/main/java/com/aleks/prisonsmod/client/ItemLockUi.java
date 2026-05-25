package com.aleks.prisonsmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Shared user-facing helpers for the item-lock feature. Lives in a normal
 * client class (not a mixin) so the slot-key, slot-click, and drop mixins can
 * all call into it without mixin-merge name collisions on the target class.
 */
public final class ItemLockUi {

    private static long lastBlockedMs = 0L;

    private ItemLockUi() {}

    /** Throttled action-bar nudge for a blocked action. Repeats no faster than once per ~400ms so a held click doesn't spam. */
    public static void notifyBlocked(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastBlockedMs < 400L) return;
        lastBlockedMs = now;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        Text msg = Text.literal("[ItemLock] blocked: ")
                .append(Text.literal(reason).formatted(Formatting.YELLOW));
        client.player.sendMessage(msg, true);
    }

    /** Action-bar confirmation when the user toggles a slot's lock state. Not throttled — single discrete action. */
    public static void notifyToggled(int playerInvSlot, boolean nowLocked) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        Formatting color = nowLocked ? Formatting.GREEN : Formatting.GRAY;
        String state = nowLocked ? "LOCKED" : "unlocked";
        Text msg = Text.literal("[ItemLock] " + describeSlot(playerInvSlot) + " ")
                .append(Text.literal(state).formatted(color, Formatting.BOLD));
        client.player.sendMessage(msg, true);
    }

    /** Human-readable label for a PlayerInventory slot index (0..40). */
    public static String describeSlot(int s) {
        if (s >= 0 && s <= 8) return "Hotbar " + (s + 1);
        if (s >= 9 && s <= 35) return "Inventory " + (s - 8);
        return switch (s) {
            case 36 -> "Boots";
            case 37 -> "Leggings";
            case 38 -> "Chestplate";
            case 39 -> "Helmet";
            case 40 -> "Offhand";
            default -> "Slot " + s;
        };
    }
}
