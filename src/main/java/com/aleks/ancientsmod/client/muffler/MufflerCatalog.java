package com.aleks.ancientsmod.client.muffler;

import com.aleks.ancientsmod.AncientsMod;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Friendly groupings + names for the muffler GUI.
 *
 * <ul>
 *   <li><b>Curated sound / particle groups</b> — the common annoyances, hand-named
 *       and bucketed by game system so they're easy to find.</li>
 *   <li><b>Server FX bundles</b> — one-click macros that mute the set of vanilla
 *       ids a given server event/ability emits (KOTH, meteor, bosses, crate
 *       opening, …). Because the server sends plain vanilla particles/sounds,
 *       muffling is by id; a bundle is "muffled" when all of its ids are.</li>
 *   <li><b>Advanced lists</b> — every registered sound-event / particle-type id,
 *       straight from the registry, for power users.</li>
 * </ul>
 *
 * <p>Every curated/bundle id is validated against the live registry on first use
 * and silently dropped if unknown, so a typo here can never crash the client —
 * it just won't appear (and logs once at debug). Lists are built lazily (after
 * registries are frozen) and cached.
 */
public final class MufflerCatalog {

    private MufflerCatalog() {}

    public record SoundEntry(Identifier id, String friendly) {}
    public record ParticleEntry(Identifier id, String friendly) {}
    public record SoundGroup(String name, List<SoundEntry> entries) {}
    public record ParticleGroup(String name, List<ParticleEntry> entries) {}
    public record Bundle(String name, String note, List<Identifier> soundIds, List<Identifier> particleIds) {}

    private static List<SoundGroup> soundGroups;
    private static List<ParticleGroup> particleGroups;
    private static List<Bundle> bundles;
    private static List<Identifier> allSounds;
    private static List<Identifier> allParticles;

    private static Identifier v(String path) { return Identifier.ofVanilla(path); }

    private static boolean soundExists(Identifier id) {
        return id != null && Registries.SOUND_EVENT.getIds().contains(id);
    }

    private static boolean particleExists(Identifier id) {
        return id != null && Registries.PARTICLE_TYPE.getIds().contains(id);
    }

    // ── Curated sound groups ─────────────────────────────────────────────────

    public static synchronized List<SoundGroup> soundGroups() {
        if (soundGroups != null) return soundGroups;
        List<SoundGroup> g = new ArrayList<>();
        g.add(soundGroup("Mining & Gathering",
                s("block.stone.break", "Stone break"),
                s("block.deepslate.break", "Deepslate break"),
                s("block.amethyst_block.chime", "Amethyst chime"),
                s("block.amethyst_block.break", "Amethyst break"),
                s("entity.experience_orb.pickup", "XP orb pickup"),
                s("entity.item.pickup", "Item pickup"),
                s("entity.player.levelup", "Level up"),
                s("block.note_block.bell", "Note block: bell"),
                s("entity.generic.eat", "Eating"),
                s("item.firecharge.use", "Fire charge")));
        g.add(soundGroup("Combat",
                s("entity.player.attack.crit", "Critical hit"),
                s("entity.player.attack.strong", "Strong attack"),
                s("entity.player.attack.sweep", "Sweep attack"),
                s("entity.player.attack.knockback", "Knockback hit"),
                s("entity.player.attack.weak", "Weak attack"),
                s("entity.player.attack.nodamage", "No-damage hit"),
                s("entity.arrow.shoot", "Arrow shoot"),
                s("entity.arrow.hit_player", "Arrow hit you"),
                s("item.crossbow.shoot", "Crossbow shoot"),
                s("entity.player.hurt", "Player hurt"),
                s("entity.warden.sonic_boom", "Sonic boom"),
                s("entity.warden.roar", "Warden roar")));
        g.add(soundGroup("Mobs",
                s("entity.zombie.ambient", "Zombie"),
                s("entity.skeleton.ambient", "Skeleton"),
                s("entity.spider.ambient", "Spider"),
                s("entity.creeper.primed", "Creeper fuse"),
                s("entity.vex.ambient", "Vex"),
                s("entity.blaze.ambient", "Blaze"),
                s("entity.blaze.shoot", "Blaze shoot"),
                s("entity.wither.shoot", "Wither shoot"),
                s("entity.wither.hurt", "Wither hurt"),
                s("entity.wither.death", "Wither death"),
                s("entity.ender_dragon.growl", "Dragon growl"),
                s("entity.enderman.teleport", "Enderman teleport"),
                s("entity.husk.ambient", "Husk"),
                s("entity.villager.no", "Villager no"),
                s("entity.villager.yes", "Villager yes")));
        g.add(soundGroup("UI & Inventory",
                s("ui.button.click", "Button click"),
                s("ui.toast.challenge_complete", "Challenge complete"),
                s("ui.toast.in", "Toast in"),
                s("ui.toast.out", "Toast out"),
                s("block.note_block.pling", "Note block: pling"),
                s("block.note_block.hat", "Note block: hat"),
                s("block.note_block.bass", "Note block: bass"),
                s("block.note_block.harp", "Note block: harp"),
                s("block.note_block.chime", "Note block: chime"),
                s("item.book.page_turn", "Page turn"),
                s("block.anvil.land", "Anvil land"),
                s("block.anvil.use", "Anvil use"),
                s("block.enchantment_table.use", "Enchant table"),
                s("block.chain.place", "Chain place"),
                s("block.chain.break", "Chain break")));
        g.add(soundGroup("Player & World",
                s("entity.player.death", "Player death"),
                s("entity.player.big_fall", "Big fall"),
                s("entity.player.small_fall", "Small fall"),
                s("entity.player.burp", "Burp"),
                s("entity.player.splash", "Splash"),
                s("entity.generic.explode", "Explosion"),
                s("entity.lightning_bolt.thunder", "Thunder"),
                s("entity.lightning_bolt.impact", "Lightning impact")));
        g.add(soundGroup("Ambient & Portals",
                s("ambient.cave", "Cave ambience"),
                s("block.portal.ambient", "Portal hum"),
                s("block.portal.trigger", "Portal trigger"),
                s("block.portal.travel", "Portal travel"),
                s("block.end_portal.spawn", "End portal spawn"),
                s("block.beacon.ambient", "Beacon hum"),
                s("block.beacon.activate", "Beacon activate"),
                s("block.beacon.deactivate", "Beacon deactivate"),
                s("block.fire.ambient", "Fire crackle"),
                s("block.bell.use", "Bell")));
        g.add(soundGroup("Music",
                s("music.game", "Game music"),
                s("music.menu", "Menu music"),
                s("music.creative", "Creative music")));
        soundGroups = g;
        return soundGroups;
    }

    // ── Curated particle groups ──────────────────────────────────────────────

    public static synchronized List<ParticleGroup> particleGroups() {
        if (particleGroups != null) return particleGroups;
        List<ParticleGroup> g = new ArrayList<>();
        g.add(particleGroup("Explosions & Impact",
                p("explosion", "Explosion"),
                p("explosion_emitter", "Huge explosion"),
                p("sonic_boom", "Sonic boom"),
                p("flash", "Flash"),
                p("firework", "Firework spark")));
        g.add(particleGroup("Fire & Smoke",
                p("flame", "Flame"),
                p("small_flame", "Small flame"),
                p("soul_fire_flame", "Soul flame"),
                p("lava", "Lava"),
                p("smoke", "Smoke"),
                p("large_smoke", "Large smoke"),
                p("campfire_cosy_smoke", "Campfire smoke"),
                p("ash", "Ash"),
                p("soul", "Soul")));
        g.add(particleGroup("Magic & Enchant",
                p("enchant", "Enchant glyphs"),
                p("enchanted_hit", "Enchanted hit"),
                p("crit", "Crit sparks"),
                p("witch", "Witch magic"),
                p("portal", "Portal"),
                p("reverse_portal", "Reverse portal"),
                p("dragon_breath", "Dragon breath"),
                p("end_rod", "End rod beam"),
                p("totem_of_undying", "Totem"),
                p("glow", "Glow"),
                p("electric_spark", "Electric spark"),
                p("nautilus", "Nautilus")));
        g.add(particleGroup("Blocks & Mining",
                p("block", "Block crumble"),
                p("block_marker", "Block marker"),
                p("dust_pillar", "Dust pillar"),
                p("falling_dust", "Falling dust"),
                p("item", "Item bits"),
                p("item_slime", "Slime bits"),
                p("wax_on", "Wax on"),
                p("wax_off", "Wax off"),
                p("scrape", "Scrape")));
        g.add(particleGroup("Status & Friendly",
                p("happy_villager", "Happy sparkle"),
                p("angry_villager", "Angry puff"),
                p("heart", "Heart"),
                p("damage_indicator", "Damage indicator"),
                p("snowflake", "Snowflake"),
                p("composter", "Composter")));
        g.add(particleGroup("Trial & Vault",
                p("vault_connection", "Vault connection"),
                p("trial_spawner_detection", "Trial detection"),
                p("trial_spawner_detection_ominous", "Ominous detection"),
                p("ominous_spawning", "Ominous spawn"),
                p("dust_plume", "Dust plume"),
                p("gust", "Gust"),
                p("gust_emitter_large", "Large gust"),
                p("gust_emitter_small", "Small gust")));
        g.add(particleGroup("Water & Weather",
                p("splash", "Splash"),
                p("bubble", "Bubble"),
                p("rain", "Rain"),
                p("dripping_water", "Dripping water"),
                p("cloud", "Cloud puff")));
        particleGroups = g;
        return particleGroups;
    }

    // ── Server FX bundles ────────────────────────────────────────────────────

    public static synchronized List<Bundle> serverFx() {
        if (bundles != null) return bundles;
        List<Bundle> b = new ArrayList<>();
        b.add(bundle("Meteor strike", "Falling meteor + impact explosion",
                sounds("entity.generic.explode", "entity.lightning_bolt.thunder", "block.stone.break",
                        "block.deepslate.break", "entity.dragon_fireball.explode", "block.amethyst_block.chime"),
                particles("explosion_emitter", "smoke", "large_smoke", "flame", "small_flame", "lava",
                        "campfire_cosy_smoke", "end_rod")));
        b.add(bundle("KOTH announcements", "King-of-the-Hill start / countdown cues",
                sounds("block.note_block.pling", "entity.ender_dragon.growl"),
                particles()));
        b.add(bundle("Boss fights", "Thanatos / Hephaestus abilities (souls, beams, roars, blasts)",
                sounds("entity.warden.roar", "entity.warden.sonic_boom", "entity.wither.shoot", "entity.wither.hurt",
                        "entity.generic.explode", "entity.blaze.shoot", "entity.blaze.ambient", "block.fire.ambient",
                        "block.chain.place", "block.chain.break", "entity.iron_golem.hurt", "block.note_block.bass",
                        "block.anvil.land", "block.enchantment_table.use"),
                particles("soul_fire_flame", "soul", "explosion_emitter", "sonic_boom", "flame", "lava",
                        "witch", "crit", "enchant", "dust", "large_smoke")));
        b.add(bundle("Mining enchant procs", "Explosive / Scorch / Powerball / proc bursts while mining",
                sounds("entity.generic.explode", "block.amethyst_block.chime", "entity.experience_orb.pickup",
                        "item.firecharge.use", "entity.blaze.shoot", "entity.generic.eat", "block.note_block.bell",
                        "entity.player.levelup"),
                particles("explosion", "flame", "lava", "happy_villager", "totem_of_undying", "item", "wax_on")));
        b.add(bundle("Crate / lootbox opening", "The full ancient-crate halo animation",
                sounds("block.enchantment_table.use", "block.beacon.ambient", "block.amethyst_block.chime",
                        "block.note_block.harp", "block.note_block.pling", "block.note_block.chime",
                        "entity.experience_orb.pickup", "block.chain.place", "block.vault.open_shutter",
                        "entity.generic.explode", "block.glass.break", "entity.wither.shoot",
                        "entity.warden.sonic_boom", "entity.ender_dragon.growl", "entity.player.levelup",
                        "ui.toast.challenge_complete", "entity.firework_rocket.twinkle", "block.anvil.land"),
                particles("dust_pillar", "vault_connection", "nautilus", "end_rod", "trial_spawner_detection",
                        "trial_spawner_detection_ominous", "soul", "soul_fire_flame", "sonic_boom", "electric_spark",
                        "gust_emitter_large", "ominous_spawning", "totem_of_undying", "dust_color_transition")));
        b.add(bundle("Mining rush", "Mining-rush win / fail flourish",
                sounds("ui.toast.challenge_complete", "entity.player.levelup", "block.fire.extinguish",
                        "block.glass.break"),
                particles("totem_of_undying", "happy_villager", "smoke", "ash")));
        b.add(bundle("Portals (Cavern / Oracle)", "Cavern-heart & Oracle portal swirls",
                sounds("block.portal.trigger", "block.portal.travel", "block.portal.ambient",
                        "entity.enderman.teleport", "block.end_portal.spawn", "block.note_block.pling"),
                particles("portal", "reverse_portal", "enchant")));
        b.add(bundle("Combat enchant procs", "Lifesteal / Plague / Freeze / Lightning weapon procs",
                sounds("entity.generic.eat", "entity.husk.ambient", "block.iron_trapdoor.close", "block.glass.break",
                        "entity.spider.ambient", "entity.lightning_bolt.thunder", "entity.wither.death",
                        "entity.zombie_villager.converted"),
                particles("heart", "smoke", "snowflake", "item_slime", "electric_spark", "end_rod")));
        b.add(bundle("Shade / NPC kills", "Shade & wraith death effects",
                sounds("block.amethyst_block.chime", "entity.vex.ambient", "entity.player.death"),
                particles("soul", "smoke", "large_smoke")));
        bundles = b;
        return bundles;
    }

    // ── Advanced (registry-driven) ───────────────────────────────────────────

    public static synchronized List<Identifier> allSoundIds() {
        if (allSounds != null) return allSounds;
        List<Identifier> ids = new ArrayList<>(Registries.SOUND_EVENT.getIds());
        ids.sort(Comparator.comparing(Identifier::toString));
        allSounds = ids;
        return allSounds;
    }

    public static synchronized List<Identifier> allParticleIds() {
        if (allParticles != null) return allParticles;
        List<Identifier> ids = new ArrayList<>(Registries.PARTICLE_TYPE.getIds());
        ids.sort(Comparator.comparing(Identifier::toString));
        allParticles = ids;
        return allParticles;
    }

    // ── Friendly-name helper ─────────────────────────────────────────────────

    /** "entity.experience_orb.pickup" → "Entity · Experience Orb · Pickup". */
    public static String prettify(Identifier id) {
        if (id == null) return "?";
        String path = id.getPath();
        StringBuilder out = new StringBuilder();
        for (String seg : path.split("\\.")) {
            if (out.length() > 0) out.append(" · ");
            String[] words = seg.split("_");
            for (int i = 0; i < words.length; i++) {
                if (i > 0) out.append(' ');
                if (words[i].isEmpty()) continue;
                out.append(Character.toUpperCase(words[i].charAt(0)));
                if (words[i].length() > 1) out.append(words[i].substring(1));
            }
        }
        return out.toString();
    }

    // ── Builders (validate against the registry, drop unknown) ───────────────

    private record PendingSound(String path, String friendly) {}
    private record PendingParticle(String path, String friendly) {}

    private static PendingSound s(String path, String friendly) { return new PendingSound(path, friendly); }
    private static PendingParticle p(String path, String friendly) { return new PendingParticle(path, friendly); }

    private static SoundGroup soundGroup(String name, PendingSound... pending) {
        List<SoundEntry> entries = new ArrayList<>();
        for (PendingSound ps : pending) {
            Identifier id = v(ps.path());
            if (soundExists(id)) {
                entries.add(new SoundEntry(id, ps.friendly()));
            } else {
                AncientsMod.LOGGER.debug("Muffler: curated sound id not in registry, dropping: {}", id);
            }
        }
        return new SoundGroup(name, entries);
    }

    private static ParticleGroup particleGroup(String name, PendingParticle... pending) {
        List<ParticleEntry> entries = new ArrayList<>();
        for (PendingParticle pp : pending) {
            Identifier id = v(pp.path());
            if (particleExists(id)) {
                entries.add(new ParticleEntry(id, pp.friendly()));
            } else {
                AncientsMod.LOGGER.debug("Muffler: curated particle id not in registry, dropping: {}", id);
            }
        }
        return new ParticleGroup(name, entries);
    }

    private static List<Identifier> sounds(String... paths) {
        List<Identifier> ids = new ArrayList<>();
        for (String path : paths) {
            Identifier id = v(path);
            if (soundExists(id)) ids.add(id);
            else AncientsMod.LOGGER.debug("Muffler: bundle sound id not in registry, dropping: {}", id);
        }
        return ids;
    }

    private static List<Identifier> particles(String... paths) {
        List<Identifier> ids = new ArrayList<>();
        for (String path : paths) {
            Identifier id = v(path);
            if (particleExists(id)) ids.add(id);
            else AncientsMod.LOGGER.debug("Muffler: bundle particle id not in registry, dropping: {}", id);
        }
        return ids;
    }

    private static Bundle bundle(String name, String note, List<Identifier> soundIds, List<Identifier> particleIds) {
        return new Bundle(name, note, soundIds, particleIds);
    }

    // ── Bundle state helpers (a bundle is "muffled" when all its ids are) ─────

    public static boolean isBundleMuffled(Bundle b) {
        for (Identifier id : b.soundIds()) {
            if (!MufflerSettings.isSoundSilenced(id)) return false;
        }
        for (Identifier id : b.particleIds()) {
            if (!MufflerSettings.isParticleMutedGui(id)) return false;
        }
        return !(b.soundIds().isEmpty() && b.particleIds().isEmpty());
    }

    /**
     * Mute (true) or restore (false) every id in the bundle, persisting once.
     *
     * <p>Because bundles share vanilla ids (e.g. {@code entity.generic.explode}
     * appears in Meteor, Boss and Mining-proc bundles), un-muffling is made
     * overlap-aware: an id is only restored if no <em>other</em> currently
     * fully-muffled bundle still wants it muted. So toggling one event's bundle
     * off won't audibly un-silence ids another still-muffled bundle relies on.
     * (Manual per-id mutes in the curated tabs aren't tracked here — a bundle
     * toggle is a convenience macro over the shared ids it lists.)
     */
    public static void setBundleMuffled(Bundle b, boolean muffled) {
        if (muffled) {
            for (Identifier id : b.soundIds()) MufflerSettings.setSoundVolume(id, 0f, false);
            for (Identifier id : b.particleIds()) MufflerSettings.setParticleMuted(id, true, false);
            MufflerSettings.save();
            return;
        }
        // Restore, but keep ids another fully-muffled bundle still needs.
        Set<Identifier> keepSounds = new HashSet<>();
        Set<Identifier> keepParticles = new HashSet<>();
        for (Bundle other : serverFx()) {
            if (other.name().equals(b.name()) || !isBundleMuffled(other)) continue;
            keepSounds.addAll(other.soundIds());
            keepParticles.addAll(other.particleIds());
        }
        for (Identifier id : b.soundIds()) {
            if (!keepSounds.contains(id)) MufflerSettings.setSoundVolume(id, 1f, false);
        }
        for (Identifier id : b.particleIds()) {
            if (!keepParticles.contains(id)) MufflerSettings.setParticleMuted(id, false, false);
        }
        MufflerSettings.save();
    }
}
