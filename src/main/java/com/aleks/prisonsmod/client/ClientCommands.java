package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.client.screen.SettingsScreen;
import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;

/**
 * Client-side chat commands for PrisonsMod. These run entirely on the
 * client; the server never sees them (the leading {@code /} is consumed
 * locally by the Fabric client-command dispatcher).
 *
 * <p>Commands exposed:
 * <ul>
 *   <li>{@code /prisonsmod} — open the settings screen (same as the F9
 *       keybind, but discoverable from chat).</li>
 *   <li>{@code /prisonsmod settings} — explicit alias of the above.</li>
 * </ul>
 */
public final class ClientCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    ClientCommandManager.literal("prisonsmod")
                            .executes(ctx -> openSettings())
                            .then(ClientCommandManager.literal("settings")
                                    .executes(ctx -> openSettings()))
            );
        });
    }

    private static int openSettings() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return 0;
        // Schedule the screen change for the next client tick so we don't
        // swap screens mid command-dispatch, which can otherwise log a
        // "setScreen during screen render" warning in some versions.
        client.send(() -> {
            if (client.currentScreen == null) {
                client.setScreen(new SettingsScreen(null));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private ClientCommands() {}
}
