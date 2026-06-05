package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.bugreport.BugReportClient;
import com.aleks.prisonsmod.client.pv.PvClient;
import com.aleks.prisonsmod.client.suggest.SuggestClient;
import com.aleks.prisonsmod.client.gangping.GangPingInput;
import com.aleks.prisonsmod.client.gangping.GangPingManager;
import com.aleks.prisonsmod.client.hud.BoosterHud;
import com.aleks.prisonsmod.client.hud.CooldownsHud;
import com.aleks.prisonsmod.client.hud.EventsHud;
import com.aleks.prisonsmod.client.hud.HudPositions;
import com.aleks.prisonsmod.client.hud.HudRegistry;
import com.aleks.prisonsmod.client.hud.HudRenderer;
import com.aleks.prisonsmod.client.hud.HudSettings;
import com.aleks.prisonsmod.client.hud.MeteoriteState;
import com.aleks.prisonsmod.client.hud.OutpostHud;
import com.aleks.prisonsmod.client.hud.StatsHud;
import com.aleks.prisonsmod.client.update.UpdateChecker;
import com.aleks.prisonsmod.client.update.UpdateInstaller;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.render.FloatingNumberRenderer;
import com.aleks.prisonsmod.render.GangPingRenderer;
import com.aleks.prisonsmod.render.MeteoriteLabelRenderer;
import com.aleks.prisonsmod.render.MinePredictRenderer;
import com.aleks.prisonsmod.render.PowerballRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.Objects;

/**
 * Client-side entrypoint. Boots the network handler, allowlist gate, and
 * renderer tick pumps.
 *
 * <p>PrisonsMod is strictly cosmetic — installing it grants no gameplay
 * advantage. The server emits events describing what already happened; the
 * mod renders them. See {@link com.aleks.prisonsmod.net.Protocol} for the
 * full security model.
 *
 * <p>The allowlist ({@link ServerAllowlist}) keeps the mod dormant on any
 * server that isn't RooPrisons. Even on an allowed server, renderers only
 * activate when valid {@code prisonsmod:v1} packets arrive.
 */
public final class PrisonsModClient implements ClientModInitializer {

    /** Last dimension the client was in; used to clear stale pings on world switch. */
    private static RegistryKey<World> lastWorldKey;

    @Override
    public void onInitializeClient() {
        FeatureToggles.load();
        HudPositions.load();
        HudSettings.load();
        ItemLocks.load();
        KeyBinds.register();
        RiftTexturePackManager.register();
        NetworkHandler.register();
        TooltipCollapse.register();
        TooltipScroll.register();
        GangPingInput.register();
        GangPingRenderer.register();
        ClientCommands.register();
        BugReportClient.register();
        SuggestClient.register();
        PvClient.register();
        com.aleks.prisonsmod.client.loot.LootClient.register();
        UpdateInstaller.init();

        // HUD framework: register moveable widgets and the renderer hook.
        HudRegistry.register(BoosterHud.INSTANCE);
        HudRegistry.register(EventsHud.INSTANCE);
        HudRegistry.register(CooldownsHud.INSTANCE);
        HudRegistry.register(StatsHud.INSTANCE);
        HudRegistry.register(OutpostHud.INSTANCE);
        HudRenderer.register();
        MeteoriteLabelRenderer.register();

        // Server allowlist: flip on/off as the player joins/leaves servers.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerAllowlist.onJoin(client.getCurrentServerEntry());
            AutoRejoinManager.onJoin(client.getCurrentServerEntry());
            if (ServerAllowlist.isAllowed()) {
                PrisonsMod.LOGGER.info("PrisonsMod active on this server");
                UpdateChecker.checkAsync(client);
                // Flag this player as modded so /pickbuffs gets the rich snapshot
                // path instead of legacy chat output. Send on the next client
                // tick so the C2S channel is fully registered before we send.
                client.send(() -> {
                    PrisonsMod.LOGGER.info("PrisonsMod: scheduling handshake send");
                    NetworkHandler.sendHandshake();
                    // Tell the server whether the booster HUD widget is on so it
                    // can default the action-bar booster line off when we're
                    // already rendering the same info.
                    NetworkHandler.sendBoosterHudState(FeatureToggles.isBoosterHudEnabled());
                    // Same idea for the Stats HUD mining section: if it's on,
                    // the action-bar XP/h / Energy/h / $/h trio defaults off.
                    NetworkHandler.sendMiningHudState(
                            com.aleks.prisonsmod.client.hud.StatsHud.isMiningEffectivelyEnabled());
                    // Tell the server whether we render Powerball client-side so it
                    // can suppress its per-ball ItemDisplay + per-tick packet stream.
                    NetworkHandler.sendPowerballState(FeatureToggles.isPowerballRenderEnabled());
                });
            }
        });
        // Auto-rejoin overlay: attach a per-screen render callback whenever the
        // vanilla DisconnectedScreen is opened.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> AutoRejoinManager.onScreenInit(screen));
        // Watch system chat for rift queue state-change announcements so the
        // texture pack can pre-load during the queue wait rather than stalling
        // the player when the rift actually starts.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String plain = message.getString();
            if (plain.contains("joined the Tartarus Rift queue")) {
                RiftTexturePackManager.onQueueJoined();
            } else if (plain.contains("left the Tartarus Rift queue")
                    || (plain.contains("Tartarus Rift") && plain.contains("cancelled"))) {
                RiftTexturePackManager.onQueueLeft();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerAllowlist.onDisconnect();
            MinePredictRenderer.reset();
            PowerballRenderer.reset();
            GangPingManager.reset();
            GangRoster.reset();
            DuelState.reset();
            MeteoriteState.reset();
            com.aleks.prisonsmod.client.buffs.BuffSnapshotState.clear();
            BugReportClient.reset();
            SuggestClient.reset();
            lastWorldKey = null;
        });

        // Pump renderer lifecycle so expired entries are evicted even when
        // no new packets are arriving.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.currentTimeMillis();
            FloatingNumberRenderer.tick(now);
            MinePredictRenderer.tick();
            PowerballRenderer.tick(now);
            GangPingManager.tick(now);
            BugReportClient.tick();
            SuggestClient.tick();
            PvClient.tick();
            com.aleks.prisonsmod.client.loot.LootClient.tick();
            AutoRejoinManager.tick(client);
            // Drop pings whose source world the player has since left. The
            // server only forwards meteor/gang pings to players currently in
            // the affected world, so a dimension switch is our cue that any
            // outstanding markers point into the wrong world.
            RegistryKey<World> current = client.world != null ? client.world.getRegistryKey() : null;
            if (!Objects.equals(current, lastWorldKey)) {
                lastWorldKey = current;
                GangPingManager.reset();
                MeteoriteState.reset();
            }
            // Drive rift-pack activation: enabled iff toggle is on AND we're in
            // the rift world. Reload only fires on actual transitions (cheap when
            // nothing changed). Stutter at entry/exit is the accepted trade-off
            // for keeping the recoloured ores scoped to the rift.
            boolean inRift = current != null
                    && RiftTexturePackManager.isRiftWorld(current.getValue().getPath());
            RiftTexturePackManager.update(inRift, FeatureToggles.isRiftTexturePackEnabled());
        });

        PrisonsMod.LOGGER.info("PrisonsMod client initialized");
    }
}
