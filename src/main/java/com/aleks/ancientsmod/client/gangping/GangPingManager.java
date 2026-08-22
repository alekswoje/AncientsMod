package com.aleks.ancientsmod.client.gangping;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.net.payload.GangPingPayload;
import com.aleks.ancientsmod.net.payload.MeteorPingPayload;
import com.aleks.ancientsmod.net.payload.MiningRushPingPayload;
import com.aleks.ancientsmod.net.payload.MiningRushPingClearPayload;
import com.aleks.ancientsmod.net.payload.HotZonePingPayload;
import com.aleks.ancientsmod.net.payload.MeteoriteShowerPingPayload;
import com.aleks.ancientsmod.net.payload.TearPingPayload;
import com.aleks.ancientsmod.net.payload.TearPingClearPayload;
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
        long now = System.currentTimeMillis();
        // Each (re-)announce carries the server's current time-to-impact, so the
        // predicted landing time re-syncs in place and stays accurate. Absent /
        // sentinel value (older plugin) → no countdown, plain lifetime fade.
        boolean hasCountdown = payload.msUntilLanding() >= 0;
        long landingAtMs = hasCountdown ? now + payload.msUntilLanding() : 0L;
        pings.put(key, new GangPing(
                payload.label(),
                payload.colorRgb(),
                payload.x(),
                payload.y(),
                payload.z(),
                payload.worldName(),
                now,
                payload.lifetimeMs(),
                hasCountdown,
                landingAtMs));
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

    /**
     * Drop the mining-rush beam(s) anchored to the cleared block. The server
     * sends this the moment a rush ends (mined out / expired / replaced by the
     * next spawn), so the beam vanishes immediately instead of lingering for
     * the rest of its expiry-window lifetime. Matches on world + block coords
     * only (not the tier label) so label formatting can never desync the
     * removal. Silent no-op when no matching marker is present.
     */
    public static void clearMiningRushPing(MiningRushPingClearPayload payload) {
        if (payload == null) return;
        long bx = (long) Math.floor(payload.x());
        long by = (long) Math.floor(payload.y());
        long bz = (long) Math.floor(payload.z());
        String world = payload.worldName();
        pings.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith("mining_rush:")) return false;
            GangPing g = e.getValue();
            return g.worldName.equals(world)
                    && (long) Math.floor(g.x) == bx
                    && (long) Math.floor(g.y) == by
                    && (long) Math.floor(g.z) == bz;
        });
    }

    /**
     * Handle an incoming hot-zone ping. Identical render path to mining-rush
     * pings — only keying and the gating toggle differ. Gated at intake: when
     * the "Hot zone indicator" toggle is off we drop the packet entirely (no
     * sound, no marker). Key is label+world+block-coords so a re-sent zone
     * refreshes in place and distinct tier zones coexist.
     */
    public static void onHotZonePing(HotZonePingPayload payload) {
        if (payload == null) return;
        if (!FeatureToggles.isHotZoneIndicatorEnabled()) return;
        String key = hotZoneKey(payload);
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

    private static String hotZoneKey(HotZonePingPayload p) {
        long bx = (long) Math.floor(p.x());
        long by = (long) Math.floor(p.y());
        long bz = (long) Math.floor(p.z());
        return "hot_zone:" + p.label() + '@' + p.worldName() + ':' + bx + ',' + by + ',' + bz;
    }

    /**
     * Handle an incoming meteorite-shower ping. Identical render path to hot-zone
     * pings — only keying and the gating toggle differ. Gated at intake: when the
     * "Meteorite shower pings" toggle is off we drop the packet entirely (no
     * sound, no marker). Key is label+world+block-coords so back-to-back showers
     * at different centres coexist and a re-sent one refreshes in place.
     */
    public static void onMeteoriteShowerPing(MeteoriteShowerPingPayload payload) {
        if (payload == null) return;
        if (!FeatureToggles.isMeteoriteShowerPingsEnabled()) return;
        String key = meteoriteShowerKey(payload);
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

    private static String meteoriteShowerKey(MeteoriteShowerPingPayload p) {
        long bx = (long) Math.floor(p.x());
        long by = (long) Math.floor(p.y());
        long bz = (long) Math.floor(p.z());
        return "meteorite_shower:" + p.label() + '@' + p.worldName() + ':' + bx + ',' + by + ',' + bz;
    }

    /**
     * Handle an incoming Erebus Tear ping. Identical render path to the shower
     * ping; gated at intake by the "Erebus tear pings" toggle. The server re-sends
     * this every 30s while the tear is up (so players who arrive mid-fight get a
     * beam), and each re-send refreshes the same key in place with the breach's
     * remaining life — only the first one plays the ping sound.
     */
    public static void onTearPing(TearPingPayload payload) {
        if (payload == null) return;
        if (!FeatureToggles.isTearPingsEnabled()) return;
        String key = tearKey(payload);
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

    private static String tearKey(TearPingPayload p) {
        long bx = (long) Math.floor(p.x());
        long by = (long) Math.floor(p.y());
        long bz = (long) Math.floor(p.z());
        return "tear:" + p.label() + '@' + p.worldName() + ':' + bx + ',' + by + ',' + bz;
    }

    /**
     * Drop the tear beam anchored to the cleared block. The server sends this the
     * moment a breach closes (sealed / expired / cancelled) so the beam goes with
     * it instead of outliving it. Matches on world + block coords only (not the
     * label) so label formatting can never desync the removal. Silent no-op when
     * no matching marker is present.
     */
    public static void clearTearPing(TearPingClearPayload payload) {
        if (payload == null) return;
        long bx = (long) Math.floor(payload.x());
        long by = (long) Math.floor(payload.y());
        long bz = (long) Math.floor(payload.z());
        String world = payload.worldName();
        pings.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith("tear:")) return false;
            GangPing g = e.getValue();
            return g.worldName.equals(world)
                    && (long) Math.floor(g.x) == bx
                    && (long) Math.floor(g.y) == by
                    && (long) Math.floor(g.z) == bz;
        });
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
