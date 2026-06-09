package com.aleks.prisonsmod.client.buffs;

import com.aleks.prisonsmod.PrisonsMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state + disk persistence for the {@code /pickbuffs} sandbox: the
 * list of user {@link CustomModifier}s and the per-layer overrides (enabled
 * flag and/or value) the player has dialed in with the sliders.
 *
 * <p>Persisted to {@code config/prisonsmod-pickbuffs.properties} in an indexed
 * format so arbitrary layer labels never collide with property-key syntax:
 * <pre>
 *   customCount=1
 *   custom.0.id=1
 *   custom.0.name=Dream booster
 *   custom.0.target=g:MINING
 *   custom.0.kind=1
 *   custom.0.value=2.0
 *   custom.0.enabled=true
 *   overrideCount=1
 *   override.0.ch=1
 *   override.0.label=Booster
 *   override.0.enabled=default      # or true/false
 *   override.0.value=live           # or a number
 * </pre>
 *
 * <p>Overrides are keyed by {@code channelId|label}, so they re-attach to the
 * matching layer when a fresh snapshot arrives even though the snapshot's layer
 * order can change. A dormant override (its layer absent from the current
 * snapshot) is kept on disk untouched.
 */
public final class BuffSandboxStore {

    private static final String FILE_NAME = "prisonsmod-pickbuffs.properties";

    /** Per-layer override; either field may be null meaning "use the live value". */
    public static final class Override {
        public Boolean enabled; // null → use the layer's default (ACTIVE) state
        public Double value;    // null → use the snapshot's live value
        boolean isEmpty() { return enabled == null && value == null; }
    }

    private static final List<CustomModifier> CUSTOMS = new ArrayList<>();
    private static final Map<String, Override> OVERRIDES = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();

    private static int nextId = 1;
    private static volatile boolean loaded = false;

    private BuffSandboxStore() {}

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static String key(byte ch, String label) {
        return ch + "|" + label;
    }

    // ── Custom modifiers ───────────────────────────────────────────────────────

    /** Snapshot copy of all custom modifiers (any target), in creation order. */
    public static List<CustomModifier> customs() {
        synchronized (LOCK) { return new ArrayList<>(CUSTOMS); }
    }

    /** Custom modifiers whose target folds into the given channel. */
    public static List<CustomModifier> customsTargeting(byte ch) {
        synchronized (LOCK) {
            List<CustomModifier> out = new ArrayList<>();
            for (CustomModifier m : CUSTOMS) if (m.target.matches(ch)) out.add(m);
            return out;
        }
    }

    public static CustomModifier customById(int id) {
        synchronized (LOCK) {
            for (CustomModifier m : CUSTOMS) if (m.id == id) return m;
        }
        return null;
    }

    public static CustomModifier addCustom(String name, BuffTarget target, byte kind, double value) {
        CustomModifier m;
        synchronized (LOCK) {
            m = new CustomModifier(nextId++, name, target, kind, value, true);
            CUSTOMS.add(m);
        }
        save();
        return m;
    }

    public static void removeCustom(int id) {
        synchronized (LOCK) { CUSTOMS.removeIf(m -> m.id == id); }
        save();
    }

    public static void clearCustoms() {
        synchronized (LOCK) { CUSTOMS.clear(); }
        save();
    }

    public static void toggleCustom(int id) {
        synchronized (LOCK) {
            for (CustomModifier m : CUSTOMS) if (m.id == id) { m.enabled = !m.enabled; break; }
        }
        save();
    }

    /** Mutate a custom's value without hitting disk (used during a slider drag). */
    public static void setCustomValueNoSave(int id, double v) {
        synchronized (LOCK) {
            for (CustomModifier m : CUSTOMS) if (m.id == id) { m.value = v; break; }
        }
    }

    // ── Per-layer overrides ────────────────────────────────────────────────────

    public static boolean enabled(byte ch, String label, boolean def) {
        Override o = OVERRIDES.get(key(ch, label));
        if (o == null || o.enabled == null) return def;
        return o.enabled;
    }

    public static void setEnabled(byte ch, String label, boolean val, boolean def) {
        String k = key(ch, label);
        Override o = OVERRIDES.computeIfAbsent(k, x -> new Override());
        o.enabled = (val == def) ? null : val;
        if (o.isEmpty()) OVERRIDES.remove(k);
        save();
    }

    public static void toggleEnabled(byte ch, String label, boolean def) {
        setEnabled(ch, label, !enabled(ch, label, def), def);
    }

    public static double value(byte ch, String label, double live) {
        Override o = OVERRIDES.get(key(ch, label));
        if (o == null || o.value == null) return live;
        return o.value;
    }

    public static boolean hasValueOverride(byte ch, String label) {
        Override o = OVERRIDES.get(key(ch, label));
        return o != null && o.value != null;
    }

    /** Set a value override without saving (slider drag); call {@link #save()} on release. */
    public static void setValueNoSave(byte ch, String label, double v) {
        OVERRIDES.computeIfAbsent(key(ch, label), x -> new Override()).value = v;
    }

    public static void clearValue(byte ch, String label) {
        String k = key(ch, label);
        Override o = OVERRIDES.get(k);
        if (o == null) return;
        o.value = null;
        if (o.isEmpty()) OVERRIDES.remove(k);
        save();
    }

    /** Drop every override (enabled + value) for one channel — "reset to live". */
    public static void resetChannel(byte ch) {
        String prefix = ch + "|";
        boolean changed = false;
        for (Iterator<String> it = OVERRIDES.keySet().iterator(); it.hasNext(); ) {
            if (it.next().startsWith(prefix)) { it.remove(); changed = true; }
        }
        if (changed) save();
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path path = configPath();
        if (!Files.isRegularFile(path)) return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to load {}: {}", FILE_NAME, e.getMessage());
            return;
        }
        synchronized (LOCK) {
            CUSTOMS.clear();
            int maxId = 0;
            int customCount = parseInt(p.getProperty("customCount"), 0);
            for (int i = 0; i < customCount; i++) {
                String pre = "custom." + i + ".";
                int id = parseInt(p.getProperty(pre + "id"), i + 1);
                String name = p.getProperty(pre + "name", "");
                BuffTarget target = BuffTarget.parse(p.getProperty(pre + "target", "all"));
                byte kind = (byte) parseInt(p.getProperty(pre + "kind"), 0);
                double value = parseDouble(p.getProperty(pre + "value"), 0.0);
                boolean enabled = Boolean.parseBoolean(p.getProperty(pre + "enabled", "true"));
                CUSTOMS.add(new CustomModifier(id, name, target, kind, value, enabled));
                maxId = Math.max(maxId, id);
            }
            nextId = maxId + 1;
        }
        OVERRIDES.clear();
        int overrideCount = parseInt(p.getProperty("overrideCount"), 0);
        for (int i = 0; i < overrideCount; i++) {
            String pre = "override." + i + ".";
            String label = p.getProperty(pre + "label", "");
            if (label.isEmpty()) continue;
            byte ch = (byte) parseInt(p.getProperty(pre + "ch"), 0);
            Override o = new Override();
            String en = p.getProperty(pre + "enabled", "default");
            o.enabled = "default".equals(en) ? null : Boolean.parseBoolean(en);
            String va = p.getProperty(pre + "value", "live");
            o.value = "live".equals(va) ? null : parseDoubleOrNull(va);
            if (!o.isEmpty()) OVERRIDES.put(key(ch, label), o);
        }
    }

    public static void save() {
        Properties p = new Properties();
        synchronized (LOCK) {
            p.setProperty("customCount", Integer.toString(CUSTOMS.size()));
            for (int i = 0; i < CUSTOMS.size(); i++) {
                CustomModifier m = CUSTOMS.get(i);
                String pre = "custom." + i + ".";
                p.setProperty(pre + "id", Integer.toString(m.id));
                p.setProperty(pre + "name", m.name == null ? "" : m.name);
                p.setProperty(pre + "target", m.target.serialize());
                p.setProperty(pre + "kind", Integer.toString(m.kind));
                p.setProperty(pre + "value", Double.toString(m.value));
                p.setProperty(pre + "enabled", Boolean.toString(m.enabled));
            }
        }
        int i = 0;
        for (Map.Entry<String, Override> e : OVERRIDES.entrySet()) {
            String k = e.getKey();
            Override o = e.getValue();
            int bar = k.indexOf('|');
            if (bar < 0) continue;
            String pre = "override." + i + ".";
            p.setProperty(pre + "ch", k.substring(0, bar));
            p.setProperty(pre + "label", k.substring(bar + 1));
            p.setProperty(pre + "enabled", o.enabled == null ? "default" : Boolean.toString(o.enabled));
            p.setProperty(pre + "value", o.value == null ? "live" : Double.toString(o.value));
            i++;
        }
        p.setProperty("overrideCount", Integer.toString(i));
        try {
            Files.createDirectories(configPath().getParent());
            try (var out = Files.newOutputStream(configPath())) {
                p.store(out, "PrisonsMod /pickbuffs sandbox — custom modifiers + slider overrides");
            }
        } catch (IOException e) {
            PrisonsMod.LOGGER.warn("failed to save {}: {}", FILE_NAME, e.getMessage());
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
