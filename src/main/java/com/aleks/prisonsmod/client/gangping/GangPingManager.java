package com.aleks.prisonsmod.client.gangping;

import com.aleks.prisonsmod.net.payload.GangPingPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live gang-ping state. One entry per sender — new ping from the same sender
 * replaces their previous one (so a single player spamming their keybind
 * still only produces one marker at a time). Different senders each get
 * their own entry.
 */
public final class GangPingManager {

    /** Hard cap on concurrent pings. Prevents unbounded growth if the server goes rogue. */
    private static final int MAX_ACTIVE = 32;

    /** Mirrors the server's palette in {@code GangPingListener#colorForUuid}. Kept in sync by hand. */
    private static final int[] COLOR_PALETTE = {
            0x7C3AED, 0xA78BFA, 0xF59E0B, 0x4F46E5, 0x06B6D4
    };

    private static final Map<String, GangPing> pings = new ConcurrentHashMap<>();

    public static void onPing(GangPingPayload payload) {
        if (payload == null) return;
        if (pings.size() >= MAX_ACTIVE && !pings.containsKey(payload.senderName())) {
            // Evict an arbitrary oldest-ish entry to make room.
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
        // Skip the sound on the server echoing back our own ping — addLocal()
        // already played the cue at keypress time. Other senders still get one.
        if (!isLocalPlayer(payload.senderName())) {
            playPingSound();
        }
    }

    /**
     * Add a ping produced by the local player, without waiting for the server
     * round-trip. The server's echoed {@link #onPing} will replace this entry
     * in place (same sender-name key) with the authoritative copy — colors
     * match because both sides use the same palette-hash.
     */
    public static void addLocal(String senderName, UUID uuid,
                                double x, double y, double z, String worldName) {
        if (senderName == null || uuid == null) return;
        int color = colorForUuid(uuid);
        pings.put(senderName, new GangPing(senderName, color, x, y, z,
                worldName == null ? "" : worldName,
                System.currentTimeMillis()));
        playPingSound();
    }

    private static int colorForUuid(UUID uuid) {
        int idx = Math.floorMod(uuid.hashCode(), COLOR_PALETTE.length);
        return COLOR_PALETTE[idx];
    }

    private static boolean isLocalPlayer(String name) {
        if (name == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return false;
        return name.equals(mc.player.getName().getString());
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
