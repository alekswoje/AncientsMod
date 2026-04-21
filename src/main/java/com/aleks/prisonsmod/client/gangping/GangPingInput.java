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
    private static Vec3d previewTarget = null;
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

        boolean down = KeyBinds.GANG_PING.isPressed();
        long now = System.currentTimeMillis();

        if (down && !wasDown) {
            pressedAtMs = now;
            previewActive = false;
            previewTarget = null;
        }

        if (down) {
            long heldFor = now - pressedAtMs;
            if (heldFor >= Protocol.GANG_PING_HOLD_THRESHOLD_MS) {
                previewActive = true;
                previewTarget = raycastTarget(client);
            } else {
                previewActive = false;
            }
        }

        if (!down && wasDown) {
            long heldFor = now - pressedAtMs;
            boolean isHeld = heldFor >= Protocol.GANG_PING_HOLD_THRESHOLD_MS;
            Vec3d point = isHeld ? raycastTarget(client) : client.player.getEntityPos();
            if (point != null) {
                PrisonsMod.LOGGER.info("Gang ping: {} ({}s held) -> {} {} {}",
                        isHeld ? "held" : "tap", heldFor / 1000.0,
                        point.x, point.y, point.z);
                NetworkHandler.sendGangPingRequest(point.x, point.y, point.z, isHeld);
            }
            resetHold();
        }

        wasDown = down;
    }

    private static void resetHold() {
        wasDown = false;
        previewActive = false;
        previewTarget = null;
    }

    /**
     * Raycast up to {@link Protocol#GANG_PING_MAX_RADIUS} blocks from the
     * player's eyes. Returns the block hit or, if nothing is in range, the
     * raw endpoint at {@code MAX_RADIUS}.
     */
    private static Vec3d raycastTarget(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return null;
        Vec3d start = player.getCameraPosVec(1.0f);
        Vec3d look = player.getRotationVec(1.0f);
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

    public static Vec3d getPreviewTarget() { return previewTarget; }

    private GangPingInput() {}
}
