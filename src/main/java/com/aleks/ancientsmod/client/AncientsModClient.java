package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.client.bugreport.BugReportClient;
import com.aleks.ancientsmod.client.pv.PvClient;
import com.aleks.ancientsmod.client.suggest.SuggestClient;
import com.aleks.ancientsmod.client.gangping.GangPingInput;
import com.aleks.ancientsmod.client.gangping.GangPingManager;
import com.aleks.ancientsmod.client.hud.ArmorDurabilityHud;
import com.aleks.ancientsmod.client.hud.BoosterHud;
import com.aleks.ancientsmod.client.hud.ClockHud;
import com.aleks.ancientsmod.client.hud.JewelHud;
import com.aleks.ancientsmod.client.hud.CooldownsHud;
import com.aleks.ancientsmod.client.hud.EventsHud;
import com.aleks.ancientsmod.client.hud.HudPositions;
import com.aleks.ancientsmod.client.hud.HudRegistry;
import com.aleks.ancientsmod.client.hud.HudRenderer;
import com.aleks.ancientsmod.client.hud.HudSettings;
import com.aleks.ancientsmod.client.hud.MeteoriteState;
import com.aleks.ancientsmod.client.hud.OutpostHud;
import com.aleks.ancientsmod.client.hud.StatsHud;
import com.aleks.ancientsmod.client.update.UpdateChecker;
import com.aleks.ancientsmod.client.update.UpdateInstaller;
import com.aleks.ancientsmod.net.NetworkHandler;
import com.aleks.ancientsmod.render.FloatingNumberRenderer;
import com.aleks.ancientsmod.render.GangPingRenderer;
import com.aleks.ancientsmod.render.MeteoriteLabelRenderer;
import com.aleks.ancientsmod.render.MinePredictRenderer;
import com.aleks.ancientsmod.render.PowerballRenderer;
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
 * <p>AncientsMod is strictly cosmetic — installing it grants no gameplay
 * advantage. The server emits events describing what already happened; the
 * mod renders them. See {@link com.aleks.ancientsmod.net.Protocol} for the
 * full security model.
 *
 * <p>The allowlist ({@link ServerAllowlist}) keeps the mod dormant on any
 * server that isn't RooPrisons. Even on an allowed server, renderers only
 * activate when valid {@code prisonsmod:v1} packets arrive.
 */
public final class AncientsModClient implements ClientModInitializer {

    /** Last dimension the client was in; used to clear stale pings on world switch. */
    private static RegistryKey<World> lastWorldKey;

    @Override
    public void onInitializeClient() {
        FeatureToggles.load();
        com.aleks.ancientsmod.client.muffler.MufflerSettings.load();
        HudPositions.load();
        HudSettings.load();
        com.aleks.ancientsmod.client.hud.MiningSimState.load();
        com.aleks.ancientsmod.client.buffs.BuffSandboxStore.load();
        ItemLocks.load();
        KeyBinds.register();
        RiftTexturePackManager.register();
        NetworkHandler.register();
        TooltipCollapse.register();
        TooltipScroll.register();
        PickaxeBlocksTooltip.register();
        com.aleks.ancientsmod.client.wiki.InteractiveItemTooltip.register();
        GangPingInput.register();
        GangPingRenderer.register();
        ClientCommands.register();
        BugReportClient.register();
        SuggestClient.register();
        PvClient.register();
        com.aleks.ancientsmod.client.loot.LootClient.register();
        com.aleks.ancientsmod.client.energycalc.EnergyCalcClient.register();
        UpdateInstaller.init();

        // HUD framework: register moveable widgets and the renderer hook.
        HudRegistry.register(BoosterHud.INSTANCE);
        HudRegistry.register(EventsHud.INSTANCE);
        HudRegistry.register(CooldownsHud.INSTANCE);
        HudRegistry.register(StatsHud.INSTANCE);
        HudRegistry.register(OutpostHud.INSTANCE);
        HudRegistry.register(ArmorDurabilityHud.INSTANCE);
        HudRegistry.register(ClockHud.INSTANCE);
        HudRegistry.register(JewelHud.INSTANCE);
        HudRenderer.register();
        SaturationOverlay.register();
        MeteoriteLabelRenderer.register();
        PowerballRenderer.register();

        // Server allowlist: flip on/off as the player joins/leaves servers.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerAllowlist.onJoin(client.getCurrentServerEntry());
            AutoRejoinManager.onJoin(client.getCurrentServerEntry());
            // Drop any loot catalog cached from a previous backend before the
            // player can reopen /loot here. Without this, hopping to a server
            // whose loot packets are renumbered (season2 vs master) renders the
            // prior server's stale snapshot, since the fresh one never arrives.
            com.aleks.ancientsmod.client.loot.LootClient.reset();
            // Same for the jewel sockets. The allowlist covers the whole
            // network, so without this the HUD and the inventory sockets kept
            // drawing the last backend's jewels on the hub — which has no
            // jewel system at all — and would have shown dev's sockets after
            // hopping to main. Cleared here, they stay hidden until the
            // backend we actually landed on pushes its own state.
            com.aleks.ancientsmod.client.hud.JewelState.clear();
            if (ServerAllowlist.isAllowed()) {
                AncientsMod.LOGGER.info("AncientsMod active on this server");
                UpdateChecker.checkAsync(client);
                // Flag this player as modded so /pickbuffs gets the rich snapshot
                // path instead of legacy chat output. Send on the next client
                // tick so the C2S channel is fully registered before we send.
                client.send(() -> {
                    AncientsMod.LOGGER.info("AncientsMod: scheduling handshake send");
                    NetworkHandler.sendHandshake();
                    // Report our local timezone so the server renders all player-facing
                    // clock times (event schedules, daily resets, payout/expiry stamps,
                    // raid-protection window) in this client's own zone.
                    NetworkHandler.sendClientTimezone();
                    // Tell the server whether the booster HUD widget is on so it
                    // can default the action-bar booster line off when we're
                    // already rendering the same info.
                    NetworkHandler.sendBoosterHudState(FeatureToggles.isBoosterHudEnabled());
                    // Same idea for the Stats HUD mining section: if it's on,
                    // the action-bar XP/h / Energy/h / $/h trio defaults off.
                    NetworkHandler.sendMiningHudState(
                            com.aleks.ancientsmod.client.hud.StatsHud.isMiningEffectivelyEnabled());
                    // Tell the server whether we render Powerball client-side so it
                    // can suppress its per-ball ItemDisplay + per-tick packet stream.
                    NetworkHandler.sendPowerballState(FeatureToggles.isPowerballRenderEnabled());
                    // Tell the server whether we run swing-time mine prediction so it
                    // streams the speed table and suppresses its own crack/effects.
                    NetworkHandler.sendMinePredictState(FeatureToggles.isMinePredictEnabled());
                    // Tell the server whether the cell-vault terminal is enabled so
                    // it knows to intercept vault-chest opens with the custom
                    // terminal (disabled → the vanilla chest GUI stays).
                    NetworkHandler.sendCellTermState(FeatureToggles.isCellTerminalEnabled());
                });
            }
        });
        // Auto-rejoin overlay: attach a per-screen render callback whenever the
        // vanilla DisconnectedScreen is opened.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> AutoRejoinManager.onScreenInit(screen));
        // Hover-to-copy in chat. The chat lines themselves are drawn by the
        // in-game HUD BEFORE the screen, so afterRender paints over them;
        // allowMouseClick only arms the copied flash and always returns true,
        // because the copy itself is vanilla's ChatScreen click handling.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screen.ChatScreen)) return;
            com.aleks.ancientsmod.client.chat.ChatCopyOverlay.reset();
            ScreenEvents.afterRender(screen).register((s, ctx, mouseX, mouseY, delta) ->
                    com.aleks.ancientsmod.client.chat.ChatCopyOverlay.render(ctx, mouseX, mouseY));
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen)
                    .register((s, click) -> {
                        com.aleks.ancientsmod.client.chat.ChatCopyOverlay.onMouseClick(click);
                        return true;
                    });
        });
        // Jewel sockets in the survival inventory. Deliberately the Fabric
        // screen API rather than a mixin on render: afterRender fires after the
        // screen has drawn everything (status effects, recipe book included),
        // so the sockets can't be painted over — a TAIL inject on
        // HandledScreen#render was, and shadowing InventoryScreen's inherited
        // layout fields fails outright. Panel geometry comes from an accessor.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)) return;
            com.aleks.ancientsmod.mixin.client.HandledScreenAccessor panel =
                    (com.aleks.ancientsmod.mixin.client.HandledScreenAccessor) screen;
            // Cells go in afterBackground — the screen draws the cursor stack at
            // the end of its own render, so drawing them afterwards puts the
            // sockets ON TOP of an item being dragged. Nothing else paints in
            // this strip, so early is safe.
            ScreenEvents.afterBackground(screen).register((s, ctx, mouseX, mouseY, delta) ->
                    JewelSockets.render(ctx, panel, mouseX, mouseY));
            // The tooltip still wants to be last so it sits over everything.
            ScreenEvents.afterRender(screen).register((s, ctx, mouseX, mouseY, delta) ->
                    JewelSockets.renderTooltip(ctx, panel, mouseX, mouseY));
            JewelSockets.resetGesture();
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen)
                    .register((s, click) -> {
                        int button = click.button();
                        if (button != 0 && button != 1) return true;
                        // GLFW_MOD_SHIFT — Screen.hasShiftDown() is gone in
                        // 1.21.11, the Click record carries the modifiers now.
                        boolean shift = (click.modifiers() & 0x0001) != 0;
                        // false = swallow the click, so the cursor stack isn't dropped.
                        // false = swallow, so the cursor stack isn't dropped.
                        return !JewelSockets.onClick(panel, click.x(), click.y(), shift);
                    });
            // The release matters as much as the press: HandledScreen's
            // "clicked outside the panel" drop (slot -999, PICKUP) lives in
            // mouseReleased, so swallowing only the press still threw the
            // cursor stack on the floor — and the resulting desync made the
            // client replay the held-item equip animation.
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseRelease(screen)
                    .register((s, click) -> !JewelSockets.onRelease(panel, click.x(), click.y()));
            net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseDrag(screen)
                    .register((s, click, dx, dy) -> !JewelSockets.onDrag());
        });
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
            PveAutoAttack.reset();
            MeteoriteState.reset();
            com.aleks.ancientsmod.client.buffs.BuffSnapshotState.clear();
            BugReportClient.reset();
            SuggestClient.reset();
            com.aleks.ancientsmod.client.Fullbright.clear();
            com.aleks.ancientsmod.client.wiki.InteractiveItemTooltip.reset();
            com.aleks.ancientsmod.client.loot.LootClient.reset();
            com.aleks.ancientsmod.client.hud.JewelState.clear();
            com.aleks.ancientsmod.client.cellterm.CellTermClient.reset();
            com.aleks.ancientsmod.client.pv.PvClient.reset();
            com.aleks.ancientsmod.client.energycalc.EnergyReferenceState.clear();
            com.aleks.ancientsmod.client.hud.MiningSimState.reset();
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
            PveAutoAttack.tick(client);
            com.aleks.ancientsmod.client.loot.LootClient.tick();
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

        AncientsMod.LOGGER.info("AncientsMod client initialized");
    }
}
