package com.aleks.ancientsmod.client.energycalc;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.client.screen.EnergyCalcScreen;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import java.util.Locale;

/**
 * Intercepts a bare {@code /energycalc} and opens the mod's screen instead of
 * letting the chat command through.
 *
 * <p>Only the bare command is intercepted. The server's own
 * {@code /energycalc <targetLevel>} does something the client genuinely cannot —
 * it walks the whole per-level cost curve from the server config — so anything
 * with arguments passes straight through and still answers in chat. Same
 * bare-command-only rule as {@code PvClient} and {@code LootClient}.
 *
 * <p>No packet is involved: the screen reads the player's own items.
 */
public final class EnergyCalcClient {

    private EnergyCalcClient() {}

    public static void register() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(EnergyCalcClient::onCommand);
    }

    private static boolean onCommand(String command) {
        if (command == null || command.isEmpty()) return true;
        if (!ServerAllowlist.isAllowed()) return true;
        if (!FeatureToggles.isEnergyCalcUiEnabled()) return true;

        String trimmed = command.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.equals("energycalc")) return true;   // args → server handles it

        EnergyCalcScreen.openNow(null);
        return false; // cancel the outbound command
    }
}
