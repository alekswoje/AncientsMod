package com.aleks.prisonsmod.client.gangping;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.net.payload.GangPingPayload;
import com.aleks.prisonsmod.net.payload.MeteorPingPayload;
import com.aleks.prisonsmod.net.payload.MiningRushPingPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live ping state. Gang pings are one-per-sender so a single player spamming
 * their keybind only produces one marker at a time; different senders each
 * get their own entry. Meteor pings reuse the same renderer but key per
 * (label + world + coords) so two meteors falling simultaneously don't
 * overwrite each other while the server's periodic re-announce of the same
 * meteor still refreshes its existing entry in place.
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
     * pings — only the label, colour, and lifetime differ. Key is
     * label+world+block-coords: re-announces of the same meteor refresh in
     * place, distinct meteors get their own entry.
     */
    public static void onMeteorPing(MeteorPingPayload payload) {
        if (payload == null) return;
        String key = meteorKey(payload);
        if (pings.size() >= MAX_ACTIVE && !pings.containsKey(key)) {
            pings.entrySet().removeIf(e -> e.getValue().expired(System.currentTimeMillis()));
            if (pings.size() >= MAX_ACTIVE) return;
        }
        boolean refresh = pings.containsKey(key);
        pings.put(key, new GangPing(
                payload.label(),
                payload.colorRgb(),
                payload.x(),
                payload.y(),
                payload.z(),
                payload.worldName(),
                System.currentTimeMillis(),
                payload.lifetimeMs()));
        if (!refresh) playPingSound();
    }

    /**
     * Handle an incoming mining-rush ping. Identical render path to meteor
     * pings — only keying and the gating toggle differ. Gated at intake: when
     * the "Mining rush pings" toggle is off we drop the packet entirely (no
     * sound, no marker), so the toggle never touches the shared renderer and
     * can't affect gang or meteor pings. Key is label+world+block-coords so a
     * re-sent rush refreshes in place and distinct tier rushes coexist.
     */
    public static void onMiningRushPing(MiningRushPingPayload payload) {
        if (payload == null) return;
        if (!FeatureToggles.isMiningRushPingsEnabled()) return;
        String key = miningRushKey(payload);
        if (pings.size() >= MAX_ACTIVE && !pings.containsKey(key)) {
            pings.entrySet().removeIf(e -> e.getValue().expired(System.currentTimeMillis()));
            if (pings.size() >= MAX_ACTIVE) return;
        }
        boolean refresh = pings.containsKey(key);
        pings.put(key, new GangPing(
                payload.label(),
                payload.colorRgb(),
                payload.x(),
                payload.y(),
                payload.z(),
                payload.worldName(),
                System.currentTimeMillis(),
                payload.lifetimeMs()));
        if (!refresh) playPingSound();
    }

    private static String miningRushKey(MiningRushPingPayload p) {
        long bx = (long) Math.floor(p.x());
        long by = (long) Math.floor(p.y());
        long bz = (long) Math.floor(p.z());
        return "mining_rush:" + p.label() + '@' + p.worldName() + ':' + bx + ',' + by + ',' + bz;
    }

    private static String meteorKey(MeteorPingPayload p) {
        // Server sends int block coords as x+0.5; floor gets us back to the
        // stable integer so re-announces collide and distinct meteors don't.
        long bx = (long) Math.floor(p.x());
        long by = (long) Math.floor(p.y());
        long bz = (long) Math.floor(p.z());
        return "meteor:" + p.label() + '@' + p.worldName() + ':' + bx + ',' + by + ',' + bz;
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
