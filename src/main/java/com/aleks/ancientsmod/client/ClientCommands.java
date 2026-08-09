package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.client.screen.MufflerScreen;
import com.aleks.ancientsmod.client.screen.SettingsScreen;
import com.aleks.ancientsmod.client.update.UpdateInstaller;
import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;

/**
 * Client-side chat commands for AncientsMod. These run entirely on the
 * client; the server never sees them (the leading {@code /} is consumed
 * locally by the Fabric client-command dispatcher).
 *
 * <p>Commands exposed:
 * <ul>
 *   <li>{@code /ancientsmod} — open the settings screen (same as the F9
 *       keybind, but discoverable from chat).</li>
 *   <li>{@code /ancientsmod settings} — explicit alias of the above.</li>
 *   <li>{@code /ancientsmod update} — download the latest mod release and stage
 *       it for install on next Minecraft restart. Also wired up as the click
 *       target on the join-time update alert.</li>
 * </ul>
 */
public final class ClientCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    ClientCommandManager.literal("ancientsmod")
                            .executes(ctx -> openSettings())
                            .then(ClientCommandManager.literal("settings")
                                    .executes(ctx -> openSettings()))
                            .then(ClientCommandManager.literal("muffler")
                                    .executes(ctx -> openMuffler()))
                            .then(ClientCommandManager.literal("update")
                                    .executes(ctx -> runUpdate()))
            );
            // /muffler — open the sound & particle muffler directly.
            dispatcher.register(
                    ClientCommandManager.literal("muffler")
                            .executes(ctx -> openMuffler())
            );
            // /simstats — the mining-sim session view. Deliberately NOT /miningsim:
            // that one is the server's, and shadowing it client-side would swallow
            // "/miningsim on" before it ever reached the server.
            dispatcher.register(
                    ClientCommandManager.literal("simstats")
                            .executes(ctx -> openMiningSim())
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

    private static int openMiningSim() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return 0;
        client.send(() -> {
            if (client.currentScreen == null) {
                client.setScreen(new com.aleks.ancientsmod.client.screen.MiningSimScreen(null));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int openMuffler() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return 0;
        client.send(() -> {
            if (client.currentScreen == null) {
                client.setScreen(new MufflerScreen(null));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int runUpdate() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return 0;
        UpdateInstaller.runFromCommand(client);
        return Command.SINGLE_SUCCESS;
    }

    private ClientCommands() {}
}
