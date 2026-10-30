package com.aleks.ancientsmod.client.melee;

import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.net.NetworkHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;

/**
 * Hold left click to keep swinging a sword or axe (map 5 PvE melee). Vanilla sends one attack per
 * click, so the mod tells the server the attack key is held and the server swings at the player's
 * attack speed, hitting the nearest monster in front. The server decides everything: it only ever
 * swings at monsters, never while PvP-tagged, and ignores this where the feature is off.
 */
public final class MeleeHoldInput {

    /** While held, the state is re-sent this often; the server drops a hold not refreshed in 1.5 s. */
    private static final int RESEND_TICKS = 20;

    private static boolean sentHeld = false;
    private static int ticksSinceSend = 0;

    private MeleeHoldInput() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(MeleeHoldInput::onTick);
    }

    private static void onTick(MinecraftClient client) {
        boolean held = client != null && client.player != null && client.world != null
                && client.currentScreen == null
                && ServerAllowlist.isAllowed()
                && client.options.attackKey.isPressed()
                && isMelee(client.player.getMainHandStack());
        ticksSinceSend++;
        if (held != sentHeld || (held && ticksSinceSend >= RESEND_TICKS)) {
            NetworkHandler.sendMeleeHold(held);
            sentHeld = held;
            ticksSinceSend = 0;
        }
    }

    private static boolean isMelee(ItemStack stack) {
        return stack != null && (stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES));
    }
}
