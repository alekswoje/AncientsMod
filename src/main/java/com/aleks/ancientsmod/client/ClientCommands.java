package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.client.screen.MufflerScreen;
import com.aleks.ancientsmod.client.screen.SettingsScreen;
import com.aleks.ancientsmod.client.update.UpdateInstaller;
import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import com.aleks.ancientsmod.render.MinePredictRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Locale;

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
 *   <li>{@code /ancientsmod predict [reset|log]} — mine-prediction diagnostics:
 *       predicted breaks vs. server-confirmed vs. rolled back, confirm latency.
 *       Same counters as the Mine Prediction HUD.</li>
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
                            .then(ClientCommandManager.literal("predict")
                                    .executes(ctx -> predictStats())
                                    .then(ClientCommandManager.literal("reset")
                                            .executes(ctx -> predictReset()))
                                    .then(ClientCommandManager.literal("log")
                                            .executes(ctx -> predictToggleLog())))
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

    private static int predictStats() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return 0;
        MinePredictRenderer.Stats s = MinePredictRenderer.stats();
        long sinceS = Math.max(0L, System.currentTimeMillis() - MinePredictRenderer.statsSinceMs()) / 1000L;
        say(client, String.format(Locale.US, "§dMine prediction §7(last %ds, %d live)", sinceS, MinePredictRenderer.activeCount()));
        say(client, String.format(Locale.US, "§7predicted §f%d §7on swing, §f%d §7server-paced, §f%d §7self-cancelled, §f%d §7cancels from server",
                s.predictions, s.serverPaced, s.selfCancels, s.cancelsReceived));
        say(client, String.format(Locale.US, "§7ghost-broke §f%d §7· confirmed §a%d §7· crack-only confirmed §f%d §7· late server starts adopted §f%d",
                s.swaps, s.confirms, s.crackOnlyConfirms, s.lateStartAdopted));
        say(client, String.format(Locale.US, "§7rolled back §%s%d §7(moved-on %d, timeout %d, re-asserted %d, second-start %d, evicted %d)",
                s.rollbacks() == 0 ? "a" : "c", s.rollbacks(), s.rollbackMovedOn, s.rollbackTimeout,
                s.rollbackReassert, s.rollbackSecondStart, s.rollbackEvicted));
        say(client, String.format(Locale.US, "§7vanilla local breaks frozen §f%d §7(each one would have cost 250ms + a pop-back)",
                s.localBreakFrozen));
        say(client, s.confirms == 0
                ? "§7confirm latency: §8—"
                : String.format(Locale.US, "§7confirm latency: §f%dms §7avg, §f%dms §7max (local break → server block update)",
                        s.confirmLatencyAvgMs(), s.confirmLatencyMaxMs));
        say(client, "§8/ancientsmod predict reset · /ancientsmod predict log (" + (MinePredictRenderer.isDebugLog() ? "on" : "off") + ")");
        return Command.SINGLE_SUCCESS;
    }

    private static int predictReset() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return 0;
        MinePredictRenderer.resetStats();
        say(client, "§dMine prediction §7counters reset.");
        return Command.SINGLE_SUCCESS;
    }

    private static int predictToggleLog() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return 0;
        boolean on = !MinePredictRenderer.isDebugLog();
        MinePredictRenderer.setDebugLog(on);
        say(client, "§dMine prediction §7rollback logging " + (on ? "§aon §7(see latest.log, [MinePredict])" : "§coff"));
        return Command.SINGLE_SUCCESS;
    }

    private static void say(MinecraftClient client, String legacy) {
        if (client.player != null) client.player.sendMessage(Text.literal(legacy), false);
    }

    private ClientCommands() {}
}
