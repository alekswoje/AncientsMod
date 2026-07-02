package com.aleks.ancientsmod.client.skilltree;

import com.aleks.ancientsmod.AncientsMod;
import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps a node's {@code effect} byte (see {@link Protocol} {@code SKILL_EFFECT_*})
 * to its art: a per-effect SYMBOL gem (what the node does) and a state/tier
 * FRAME (drawn around the gem). This is what replaces the old "one grayscale
 * template tinted by branch" look — colour/identity now comes from the effect,
 * not the region, so the tree stops reading as four solid colour blocks.
 *
 * <p>Design (confirmed with the user 2026-06-20):
 * <ul>
 *   <li><b>Symbols</b> — "categories + unique keystones": related stat effects
 *       share one symbol gem (e.g. every crit-chance variant → {@code crit_chance});
 *       each of the 17 keystones gets unique art ({@code ks_*}).</li>
 *   <li><b>Frames</b> — a small shared set the renderer draws around every node:
 *       bronze ring = available, orange spiked sunburst = allocated, plus bigger
 *       ornate frames for notables / keystones. Frame = state, PoE-style.</li>
 * </ul>
 *
 * <p>Every lookup degrades gracefully: if the texture for a key isn't bundled
 * yet, the renderer's existing template/tint path still draws the node, so the
 * tree never breaks while art is being filled in batch by batch.
 */
public final class SkillTreeArt {

    private SkillTreeArt() {}

    private static final String SYM_DIR   = "textures/gui/skilltree/sym/";
    private static final String FRAME_DIR = "textures/gui/skilltree/frame/";

    // ── Shared frames (state × tier) ─────────────────────────────────────────
    public static final Identifier FRAME_BASE            = frame("base");
    public static final Identifier FRAME_BASE_ALLOC      = frame("base_alloc");
    public static final Identifier FRAME_NOTABLE         = frame("notable");
    public static final Identifier FRAME_NOTABLE_ALLOC   = frame("notable_alloc");
    public static final Identifier FRAME_KEYSTONE        = frame("keystone");
    public static final Identifier FRAME_KEYSTONE_ALLOC  = frame("keystone_alloc");

    /** Node art tiers — picks which frame size/ornateness to draw. */
    public enum Tier { BASE, NOTABLE, KEYSTONE }

    /**
     * Frame for the given tier + allocation state. Allocatable and locked both
     * use the non-allocated frame (the renderer differentiates them with a glow
     * / dimming); only a truly unlocked node gets the orange allocated frame.
     */
    public static Identifier frameFor(Tier tier, boolean allocated) {
        return switch (tier) {
            case KEYSTONE -> allocated ? FRAME_KEYSTONE_ALLOC : FRAME_KEYSTONE;
            case NOTABLE  -> allocated ? FRAME_NOTABLE_ALLOC  : FRAME_NOTABLE;
            case BASE     -> allocated ? FRAME_BASE_ALLOC     : FRAME_BASE;
        };
    }

    // ── effect byte → symbol key ─────────────────────────────────────────────
    private static final Map<Byte, String> SYMBOL_KEY = new HashMap<>();

    private static void k(byte effect, String key) { SYMBOL_KEY.put(effect, key); }

    static {
        // Generic offense
        k(Protocol.SKILL_EFFECT_DUNGEON_DAMAGE_PCT,            "damage");
        k(Protocol.SKILL_EFFECT_DUNGEON_BOSS_DAMAGE_PCT,       "boss_damage");
        k(Protocol.SKILL_EFFECT_DUNGEON_MELEE_DAMAGE_PCT,      "melee");
        k(Protocol.SKILL_EFFECT_DUNGEON_PROJECTILE_DAMAGE_PCT, "projectile");
        k(Protocol.SKILL_EFFECT_DUNGEON_AREA_DAMAGE_PCT,       "area");
        k(Protocol.SKILL_EFFECT_DUNGEON_FULL_LIFE_DAMAGE_PCT,  "full_life");
        k(Protocol.SKILL_EFFECT_DUNGEON_EXECUTE_DAMAGE_PCT,    "execute");
        k(Protocol.SKILL_EFFECT_DUNGEON_CULLING_THRESHOLD_PCT, "execute");

        // Per-weapon damage (the marquee identity icons)
        k(Protocol.SKILL_EFFECT_DUNGEON_SWORD_DAMAGE_PCT, "sword");
        k(Protocol.SKILL_EFFECT_DUNGEON_AXE_DAMAGE_PCT,   "axe");
        k(Protocol.SKILL_EFFECT_DUNGEON_BOW_DAMAGE_PCT,   "bow");
        k(Protocol.SKILL_EFFECT_DUNGEON_WAND_DAMAGE_PCT,  "wand");

        // Crit (generic + every weapon-specific variant share the family icon)
        k(Protocol.SKILL_EFFECT_DUNGEON_CRIT_CHANCE_PCT,       "crit_chance");
        k(Protocol.SKILL_EFFECT_DUNGEON_CRIT_CHANCE_FLAT_PCT,  "crit_chance");
        k(Protocol.SKILL_EFFECT_DUNGEON_SWORD_CRIT_CHANCE_PCT, "crit_chance");
        k(Protocol.SKILL_EFFECT_DUNGEON_AXE_CRIT_CHANCE_PCT,   "crit_chance");
        k(Protocol.SKILL_EFFECT_DUNGEON_BOW_CRIT_CHANCE_PCT,   "crit_chance");
        k(Protocol.SKILL_EFFECT_DUNGEON_WAND_CRIT_CHANCE_PCT,  "crit_chance");
        k(Protocol.SKILL_EFFECT_DUNGEON_CRIT_MULTI_PCT,        "crit_multi");
        k(Protocol.SKILL_EFFECT_DUNGEON_SWORD_CRIT_MULTI_PCT,  "crit_multi");
        k(Protocol.SKILL_EFFECT_DUNGEON_AXE_CRIT_MULTI_PCT,    "crit_multi");
        k(Protocol.SKILL_EFFECT_DUNGEON_BOW_CRIT_MULTI_PCT,    "crit_multi");
        k(Protocol.SKILL_EFFECT_DUNGEON_WAND_CRIT_MULTI_PCT,   "crit_multi");

        // Attack speed (generic + per-weapon)
        k(Protocol.SKILL_EFFECT_DUNGEON_ATTACK_SPEED_PCT,       "attack_speed");
        k(Protocol.SKILL_EFFECT_DUNGEON_SWORD_ATTACK_SPEED_PCT, "attack_speed");
        k(Protocol.SKILL_EFFECT_DUNGEON_AXE_ATTACK_SPEED_PCT,   "attack_speed");
        k(Protocol.SKILL_EFFECT_DUNGEON_BOW_ATTACK_SPEED_PCT,   "attack_speed");
        k(Protocol.SKILL_EFFECT_DUNGEON_WAND_ATTACK_SPEED_PCT,  "attack_speed");

        // Defence / life
        k(Protocol.SKILL_EFFECT_DUNGEON_MAX_HP_FLAT,          "max_hp");
        k(Protocol.SKILL_EFFECT_DUNGEON_MAX_HP_PCT,           "max_hp");
        k(Protocol.SKILL_EFFECT_DUNGEON_LIFE_REGEN,           "life_regen");
        k(Protocol.SKILL_EFFECT_DUNGEON_LIFE_REGEN_PCT,       "life_regen");
        k(Protocol.SKILL_EFFECT_DUNGEON_LIFESTEAL_PCT,        "lifesteal");
        k(Protocol.SKILL_EFFECT_DUNGEON_DAMAGE_REDUCTION_PCT, "armor");

        // Ailments
        k(Protocol.SKILL_EFFECT_DUNGEON_AILMENT_DAMAGE_PCT,   "ailment");
        k(Protocol.SKILL_EFFECT_DUNGEON_AILMENT_DURATION_PCT, "ailment");

        // Mobility
        k(Protocol.SKILL_EFFECT_DUNGEON_MOVE_SPEED_PCT,  "move_speed");
        k(Protocol.SKILL_EFFECT_DUNGEON_JUMP_BOOST_FLAT, "jump");
        k(Protocol.SKILL_EFFECT_DUNGEON_DOUBLE_JUMP_FLAT, "jump");

        // Fortune / utility
        k(Protocol.SKILL_EFFECT_DUNGEON_XP_PCT,             "xp");
        k(Protocol.SKILL_EFFECT_DUNGEON_DUPLICATE_LOOT_PCT, "loot");
        k(Protocol.SKILL_EFFECT_CHEST_COST_REDUCTION_PCT,   "chest_cost");
        k(Protocol.SKILL_EFFECT_RUNE_RARITY_UPGRADE_CHANCE, "rune_fortune");
        k(Protocol.SKILL_EFFECT_BONUS_RUNE_DROP_CHANCE,     "rune_fortune");

        // Keystones — unique art each
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_GLASS_CANNON,       "ks_glass_cannon");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_EXECUTIONER,        "ks_executioner");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_EARTHSPLITTER,      "ks_earthsplitter");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_STORMWEAVER,        "ks_stormweaver");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_AVATAR_HUNT,        "ks_avatar_hunt");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_JUGGERNAUT,         "ks_juggernaut");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_BLOODTHIRST,        "ks_bloodthirst");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_ATTUNEMENT,         "ks_attunement");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_VOLLEY,             "ks_volley");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_PUNCTURE,           "ks_puncture");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_RICOCHET,           "ks_ricochet");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_RIPOSTE,            "ks_riposte");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_BLOODLETTER,        "ks_bloodletter");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_CHAIN_LIGHTNING,    "ks_chain_lightning");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_RESOLUTE_TECHNIQUE, "ks_resolute_technique");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_RAMPAGE,           "ks_rampage");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_AEGIS,             "ks_aegis");
        // ── Keystone expansion (Season 2) — unique gem each (ks_*.png).
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_BLADEDANCER,       "ks_bladedancer");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_VENDETTA,          "ks_vendetta");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_HEADSMAN,          "ks_headsman");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_WHIRLWIND,         "ks_whirlwind");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_EXSANGUINATE,      "ks_exsanguinate");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_PARRY_MASTER,      "ks_parry_master");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_BERSERKERS_RAGE,   "ks_berserkers_rage");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_BONECRUSHER,       "ks_bonecrusher");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_AFTERSHOCK,        "ks_aftershock");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_LAST_STAND,        "ks_last_stand");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_WARMONGER,         "ks_warmonger");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_REAVER,            "ks_reaver");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_OVERCHARGE,        "ks_overcharge");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_RESONANT_CASCADE,  "ks_resonant_cascade");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_PYROCLASM,         "ks_pyroclasm");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_SPELLBLADE,        "ks_spellblade");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_PRISM,             "ks_prism");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_FEEDBACK_LOOP,     "ks_feedback_loop");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_POINT_BLANK,       "ks_point_blank");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_ARROW_RAIN,        "ks_arrow_rain");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_DEADEYES_FOCUS,    "ks_deadeyes_focus");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_SPLINTER,          "ks_splinter");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_TAILWIND,          "ks_tailwind");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_VAULT_HUNTER,      "ks_vault_hunter");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_SANGUINE_PACT,     "ks_sanguine_pact");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_AVATAR_OF_FLAME,   "ks_avatar_of_flame");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_UNWAVERING_WILL,   "ks_unwavering_will");
        k(Protocol.SKILL_EFFECT_DUNGEON_KEYSTONE_GLASS_ACROBAT,     "ks_glass_acrobat");
    }

    /** Symbol texture key for an effect, or {@code null} if none is mapped. */
    public static String symbolKey(byte effect) {
        return SYMBOL_KEY.get(effect);
    }

    /**
     * Per-effect symbol gem texture, or {@code null} if the effect has no
     * mapping. The caller still checks the resource manager (the file may not be
     * bundled yet) and falls back to the generic template when absent.
     */
    public static Identifier symbolTexture(byte effect) {
        String key = SYMBOL_KEY.get(effect);
        return key == null ? null : Identifier.of(AncientsMod.MOD_ID, SYM_DIR + key + ".png");
    }

    private static Identifier frame(String key) {
        return Identifier.of(AncientsMod.MOD_ID, FRAME_DIR + key + ".png");
    }
}
