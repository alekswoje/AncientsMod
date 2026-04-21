package com.aleks.prisonsmod.client.gangping;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.KeyBinds;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.net.NetworkHandler;
import com.aleks.prisonsmod.net.Protocol;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Driver for the gang-ping keybind.
 *
 * <ul>
 *   <li><b>Tap</b> (release before {@link Protocol#GANG_PING_HOLD_THRESHOLD_MS}):
 *       ping the player's feet.</li>
 *   <li><b>Hold</b> (release after the threshold): raycast from the player's
 *       eye up to {@link Protocol#GANG_PING_MAX_RADIUS} blocks; ping the first
 *       block hit, or the ray endpoint if nothing is in range.</li>
 * </ul>
 *
 * <p>The "held" flag is included in the outbound request for the server's
 * telemetry/log; all enforcement (distance, cooldown, gang membership)
 * happens server-side.
 */
public final class GangPingInput {

    private static boolean wasDown = false;
    private static long pressedAtMs = 0L;
    private static boolean previewActive = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(GangPingInput::onTick);
    }

    private static void onTick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            resetHold();
            return;
        }
        if (!ServerAllowlist.isAllowed()) {
            resetHold();
            return;
        }

        // Consume any queued key-press events so edge detection works even if
        // something else (vanilla Pick Block, GUI close, etc.) swallowed the
        // "held" state between ticks.
        while (KeyBinds.GANG_PING.wasPressed()) {
            PrisonsMod.LOGGER.info("Gang ping: wasPressed edge");
        }

        boolean down = KeyBinds.GANG_PING.isPressed();
        long now = System.currentTimeMillis();

        if (down && !wasDown) {
            PrisonsMod.LOGGER.info("Gang ping: press edge detected");
            pressedAtMs = now;
            previewActive = false;
        }

        if (down) {
            long heldFor = now - pressedAtMs;
            previewActive = heldFor >= Protocol.GANG_PING_HOLD_THRESHOLD_MS;
        }

        if (!down && wasDown) {
            long heldFor = now - pressedAtMs;
            boolean isHeld = heldFor >= Protocol.GANG_PING_HOLD_THRESHOLD_MS;
            Vec3d point = isHeld ? computeLiveTarget(client, 1.0f) : client.player.getEntityPos();
            if (point != null) {
                PrisonsMod.LOGGER.info("Gang ping: {} ({}s held) -> {} {} {}",
                        isHeld ? "held" : "tap", heldFor / 1000.0,
                        point.x, point.y, point.z);
                NetworkHandler.sendGangPingRequest(point.x, point.y, point.z, isHeld);
                // Don't add a local echo — server is authoritative. If the
                // player isn't in an active gang or is over a rate limit,
                // the request is silently rejected and no marker appears,
                // which is the intended behavior. Normal latency means the
                // server's echo lands within ~50-100ms.
            }
            resetHold();
        }

        wasDown = down;
    }

    private static void resetHold() {
        wasDown = false;
        previewActive = false;
    }

    /**
     * Raycast up to {@link Protocol#GANG_PING_MAX_RADIUS} blocks from the
     * player's eyes. Returns the block hit or, if nothing is in range, the
     * raw endpoint at {@code MAX_RADIUS}. Called on each render frame while
     * the hold preview is active so the marker tracks the view at display
     * refresh rate rather than the 20 Hz client tick rate.
     *
     * @param tickDelta fractional tick progress for the current render frame
     *                  (0.0 = last tick, 1.0 = next tick). Passing the real
     *                  render tickDelta smooths out the preview when the
     *                  player is moving with WASD, since camera position
     *                  lerps across the tick boundary rather than snapping.
     */
    public static Vec3d computeLiveTarget(MinecraftClient client, float tickDelta) {
        if (client == null) return null;
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return null;
        Vec3d start = player.getCameraPosVec(tickDelta);
        Vec3d look = player.getRotationVec(tickDelta);
        Vec3d end = start.add(look.multiply(Protocol.GANG_PING_MAX_RADIUS));
        try {
            HitResult hit = client.world.raycast(new RaycastContext(
                    start, end,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player));
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                return ((BlockHitResult) hit).getPos();
            }
            return end;
        } catch (Throwable t) {
            PrisonsMod.LOGGER.debug("ping raycast failed", t);
            return end;
        }
    }

    public static boolean isPreviewActive() { return previewActive; }

    private GangPingInput() {}
}
