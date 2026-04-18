package com.aleks.prisonsmod.client;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.render.CascadeEffectRenderer;
import com.aleks.prisonsmod.render.FloatingNumberRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client-side entrypoint. Boots the network handler and renderer tick pumps.
 *
 * <p>PrisonsMod is strictly cosmetic — installing it grants no gameplay
 * advantage. The server emits events describing what already happened; the
 * mod renders them. See {@link com.aleks.prisonsmod.net.Protocol} for the
 * full security model.
 */
public final class PrisonsModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NetworkHandler.register();

        // Pump renderer lifecycle so expired entries are evicted even when
        // no new packets are arriving.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.currentTimeMillis();
            FloatingNumberRenderer.tick(now);
            CascadeEffectRenderer.tick(now);
        });

        PrisonsMod.LOGGER.info("PrisonsMod client initialized");
    }
}
