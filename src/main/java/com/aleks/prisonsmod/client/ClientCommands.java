package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.client.screen.MufflerScreen;
import com.aleks.prisonsmod.client.screen.SettingsScreen;
import com.aleks.prisonsmod.client.skilltree.SkillTreeClient;
import com.aleks.prisonsmod.client.update.UpdateInstaller;
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
 *   <li>{@code /prisonsmod update} — download the latest mod release and stage
 *       it for install on next Minecraft restart. Also wired up as the click
 *       target on the join-time update alert.</li>
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
            // /skilltree — re-open the Tartarus Vision screen without
            // walking back to the Oracle. Requests a fresh layout + state
            // from the server every time so balance edits show up.
            dispatcher.register(
                    ClientCommandManager.literal("skilltree")
                            .executes(ctx -> requestSkillTreeOpen())
            );
        });
    }

    private static int requestSkillTreeOpen() {
        com.aleks.prisonsmod.PrisonsMod.LOGGER.info("/skilltree invoked; allowlisted={}",
                ServerAllowlist.isAllowed());
        if (!ServerAllowlist.isAllowed()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.sendMessage(net.minecraft.text.Text.literal(
                        "/skilltree only works on the Ancients server.")
                        .formatted(net.minecraft.util.Formatting.RED), false);
            }
            return 0;
        }
        SkillTreeClient.requestOpen();
        return Command.SINGLE_SUCCESS;
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
