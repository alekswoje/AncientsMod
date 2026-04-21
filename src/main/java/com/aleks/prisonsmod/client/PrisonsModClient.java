package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.gangping.GangPingInput;
import com.aleks.prisonsmod.client.gangping.GangPingManager;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.render.CascadeEffectRenderer;
import com.aleks.prisonsmod.render.FloatingNumberRenderer;
import com.aleks.prisonsmod.render.GangPingRenderer;
import com.aleks.prisonsmod.render.MinePredictRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

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

    @Override
    public void onInitializeClient() {
        FeatureToggles.load();
        KeyBinds.register();
        NetworkHandler.register();
        TooltipCollapse.register();
        TooltipScroll.register();
        GangPingInput.register();
        GangPingRenderer.register();

        // Server allowlist: flip on/off as the player joins/leaves servers.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerAllowlist.onJoin(client.getCurrentServerEntry());
            if (ServerAllowlist.isAllowed()) {
                PrisonsMod.LOGGER.info("PrisonsMod active on this server");
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerAllowlist.onDisconnect();
            MinePredictRenderer.reset();
            GangPingManager.reset();
        });

        // Pump renderer lifecycle so expired entries are evicted even when
        // no new packets are arriving.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.currentTimeMillis();
            FloatingNumberRenderer.tick(now);
            CascadeEffectRenderer.tick(now);
            MinePredictRenderer.tick();
            GangPingManager.tick(now);
        });

        PrisonsMod.LOGGER.info("PrisonsMod client initialized");
    }
}
