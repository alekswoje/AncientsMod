package com.aleks.ancientsmod.client;

import com.aleks.ancientsmod.AncientsMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Per-slot item-lock state for the player inventory.
 *
 * <p>A "locked" slot is read-only client-side: the mixins refuse to send any
 * slot-mutating click (PICKUP / QUICK_MOVE / SWAP / THROW / PICKUP_ALL) and
 * refuse to send the Q drop packet when the locked slot is the held hotbar
 * slot. Lock state is per <i>slot index</i> (not per item) — if you swap items
 * into a locked slot, whatever sits there is locked.
 *
 * <p>Slot index space (PlayerInventory):
 * <ul>
 *   <li>0..8 — hotbar</li>
 *   <li>9..35 — main inventory (3 rows of 9)</li>
 *   <li>36..39 — armor (boots, leggings, chest, helmet)</li>
 *   <li>40 — offhand</li>
 * </ul>
 *
 * <p>Persisted to {@code config/ancientsmod-locks.properties} under a single
 * {@code slots=} key with comma-separated indices. Kept in its own file (not
 * the main toggles file) so the feature can grow without diluting the toggle
 * list — same pattern as {@link com.aleks.ancientsmod.client.hud.HudPositions}.
 */
public final class ItemLocks {

    private static final String FILE_NAME = "ancientsmod-locks.properties";
    private static final int MIN_SLOT = 0;
    private static final int MAX_SLOT = 40;

    /** Lock-free read path — mixins query this on every slot click. */
    private static final Set<Integer> LOCKED = new CopyOnWriteArraySet<>();
    private static volatile boolean loaded = false;

    private ItemLocks() {}

    private static Path configPath() {
        return com.aleks.ancientsmod.client.ConfigPaths.resolve(FILE_NAME);
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path path = configPath();
        if (!Files.isRegularFile(path)) return;
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            AncientsMod.LOGGER.warn("failed to load {}: {}", FILE_NAME, e.getMessage());
            return;
        }
        String raw = props.getProperty("slots", "").trim();
        if (raw.isEmpty()) return;
        for (String token : raw.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                int slot = Integer.parseInt(t);
                if (slot >= MIN_SLOT && slot <= MAX_SLOT) LOCKED.add(slot);
            } catch (NumberFormatException ignored) {
                // skip garbage tokens — fall-forward on partial corruption
            }
        }
    }

    private static synchronized void save() {
        Properties props = new Properties();
        // Sorted for stable file content on disk (easier to diff if the user peeks).
        StringBuilder sb = new StringBuilder();
        for (Integer slot : new TreeSet<>(LOCKED)) {
            if (sb.length() > 0) sb.append(',');
            sb.append(slot);
        }
        props.setProperty("slots", sb.toString());
        try {
            Files.createDirectories(configPath().getParent());
            try (var out = Files.newOutputStream(configPath())) {
                props.store(out, "AncientsMod item-lock slot indices (PlayerInventory: 0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand)");
            }
        } catch (IOException e) {
            AncientsMod.LOGGER.warn("failed to save {}: {}", FILE_NAME, e.getMessage());
        }
    }

    /** True if this player-inv slot is locked. Does NOT consult the feature toggle — callers should gate on {@link FeatureToggles#isItemLockEnabled()} themselves. */
    public static boolean isLocked(int playerInvSlot) {
        return LOCKED.contains(playerInvSlot);
    }

    /** Toggle the lock on a slot. Returns the new locked state. No-op + returns false if slot is out of range. */
    public static boolean toggle(int playerInvSlot) {
        if (playerInvSlot < MIN_SLOT || playerInvSlot > MAX_SLOT) return false;
        boolean nowLocked;
        if (LOCKED.contains(playerInvSlot)) {
            LOCKED.remove(playerInvSlot);
            nowLocked = false;
        } else {
            LOCKED.add(playerInvSlot);
            nowLocked = true;
        }
        save();
        return nowLocked;
    }

    /** Wipe all locks. Used by the settings screen "Clear all" action. */
    public static void clear() {
        if (LOCKED.isEmpty()) return;
        LOCKED.clear();
        save();
    }

    /** Read-only snapshot — for the settings screen / debug. */
    public static Set<Integer> getLocked() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(LOCKED)));
    }

    public static int count() {
        return LOCKED.size();
    }
}
