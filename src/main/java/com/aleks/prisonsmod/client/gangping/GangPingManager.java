package com.aleks.prisonsmod.client.gangping;

import com.aleks.prisonsmod.net.payload.GangPingPayload;
import com.aleks.prisonsmod.net.payload.MeteorPingPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live ping state. One entry per sender — new ping from the same sender
 * replaces their previous one (so a single player spamming their keybind
 * still only produces one marker at a time). Different senders each get
 * their own entry. Meteor pings reuse the same machinery, keyed by their
 * label (e.g. "Meteor", "Heroic Meteor").
 */
public final class GangPingManager {

    /** Hard cap on concurrent pings. Prevents unbounded growth if the server goes rogue. */
    private static final int MAX_ACTIVE = 32;

    private static final Map<String, GangPing> pings = new ConcurrentHashMap<>();

    public static void onPing(GangPingPayload payload) {
        if (payload == null) return;
        if (pings.size() >= MAX_ACTIVE && !pings.containsKey(payload.senderName())) {
            pings.entrySet().removeIf(e -> e.getValue().expired(System.currentTimeMillis()));
            if (pings.size() >= MAX_ACTIVE) return;
        }
        pings.put(payload.senderName(), new GangPing(
                payload.senderName(),
                payload.colorRgb(),
                payload.x(),
                payload.y(),
                payload.z(),
                payload.worldName(),
                System.currentTimeMillis()));
        playPingSound();
    }

    /**
     * Handle an incoming meteor ping. Reuses the same renderer path as gang
     * pings — only the label, colour, and lifetime differ, all carried in
     * the payload. Keyed by label so successive meteor announcements for the
     * same meteor (re-fired every ~60s in flight) replace in place rather
     * than stacking.
     */
    public static void onMeteorPing(MeteorPingPayload payload) {
        if (payload == null) return;
        if (pings.size() >= MAX_ACTIVE && !pings.containsKey(payload.label())) {
            pings.entrySet().removeIf(e -> e.getValue().expired(System.currentTimeMillis()));
            if (pings.size() >= MAX_ACTIVE) return;
        }
        pings.put(payload.label(), new GangPing(
                payload.label(),
                payload.colorRgb(),
                payload.x(),
                payload.y(),
                payload.z(),
                payload.worldName(),
                System.currentTimeMillis(),
                payload.lifetimeMs()));
        playPingSound();
    }

    private static void playPingSound() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.6f, 1.4f);
    }

    public static Collection<GangPing> snapshot() {
        return pings.values();
    }

    public static void tick(long now) {
        pings.entrySet().removeIf(e -> e.getValue().expired(now));
    }

    public static void reset() {
        pings.clear();
    }

    private GangPingManager() {}
}
