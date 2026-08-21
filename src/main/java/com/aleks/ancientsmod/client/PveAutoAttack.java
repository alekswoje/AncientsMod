package com.aleks.ancientsmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.UUID;

/**
 * Hold left-click to keep swinging at PvE monsters at a fixed 5 clicks/second.
 * Never fires at another player.
 *
 * <p>Deliberately NOT aim assist: the target is whatever vanilla's own
 * {@code crosshairTarget} already resolved to, so reach, ray direction and
 * occlusion are untouched. If your crosshair drifts off the monster it simply
 * stops clicking. The only thing this replaces is your finger.
 *
 * <p><b>Why the PvE check is not {@code instanceof PlayerEntity}:</b> every
 * PrisonsCore monster (shade, wraith, guard) is a <i>fake player</i> — a real
 * Bukkit {@code Player} driven by {@code PrisonsNPC} — so the obvious check has
 * it exactly backwards and would swing at real players. Two independent
 * server-side facts identify a hostile NPC client-side, and a player-shaped
 * entity must satisfy <b>both</b> before it is ever attacked:
 * <ol>
 *   <li><b>Absent from the tab list.</b> NPC profiles are pushed with
 *       {@code listed=false} (PrisonsCore rewrites the player-info packet), so
 *       they never land in {@code getListedPlayerListEntries()}. Real players
 *       always do.</li>
 *   <li><b>In the {@value #HOSTILE_NPC_TEAM} scoreboard team.</b> Only the
 *       hostile NPC families join it — shades/wraiths, Erebus pit guards, mine
 *       guards and cell guards. Friendly NPCs (vendors, oracle, runesmith,
 *       tutorial, market, link) are not in it, so they are never hit either.</li>
 * </ol>
 * A real player cannot satisfy either condition, let alone both.
 *
 * <p>Open-world bosses and their adds are ordinary hostile mobs (cave spider,
 * husk, wither skeleton, piglin brute, shulker eggs), so they pass through the
 * {@link MobEntity} branch. That also keeps armour stands, text displays and
 * item displays out — they are not {@code MobEntity}.
 *
 * <p>Off by default, and gated on holding a sword or axe: holding left-click
 * with a pickaxe is how you mine, and mine guards patrol the mines.
 */
public final class PveAutoAttack {

    /** 5 clicks per second. */
    private static final long INTERVAL_MS = 200L;

    /**
     * Scoreboard team PrisonsCore puts every hostile NPC's profile name into
     * (to suppress the vanilla nametag in favour of the hologram). Friendly
     * NPCs are not members, which is what makes it a hostility signal and not
     * just an NPC signal. Mirrors {@code NpcHiddenNametagEntries.TEAM_NAME}.
     */
    private static final String HOSTILE_NPC_TEAM = "cc_hide_tag";

    /** Next moment we're allowed to swing, in {@code System.currentTimeMillis()}. */
    private static long nextAttackAt = 0L;
    /** Whether attack was already held last tick, so a fresh press can be detected. */
    private static boolean wasHeld = false;

    /** Called every client tick. Cheap no-op whenever the feature can't fire. */
    public static void tick(MinecraftClient client) {
        if (!isArmed(client)) {
            wasHeld = false;
            return;
        }

        ClientPlayerEntity self = client.player;
        long now = System.currentTimeMillis();

        if (!wasHeld) {
            // Vanilla already fires one attack on the press itself. Start our
            // cadence a full interval later so press + auto-clicks together
            // come to exactly 5 CPS instead of a doubled first hit.
            wasHeld = true;
            nextAttackAt = now + INTERVAL_MS;
            return;
        }
        if (now < nextAttackAt) return;

        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof EntityHitResult entityHit)) return;
        Entity target = entityHit.getEntity();
        if (!isPveTarget(target)) return;

        nextAttackAt = now + INTERVAL_MS;
        client.interactionManager.attackEntity(self, target);
        // attackEntity sends the packet but does not animate — vanilla's
        // doAttack() swings separately, so we have to as well.
        self.swingHand(Hand.MAIN_HAND);
    }

    /** Every precondition except the target itself. */
    private static boolean isArmed(MinecraftClient client) {
        if (!ServerAllowlist.isAllowed()) return false;
        if (!FeatureToggles.isPveAutoAttackEnabled()) return false;

        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) return false;
        if (client.interactionManager == null) return false;
        if (self.isSpectator() || self.isUsingItem()) return false;
        // A screen is open, or the game lost focus — the player isn't holding
        // anything on purpose any more.
        if (client.currentScreen != null || !client.isWindowFocused()) return false;
        if (!client.options.attackKey.isPressed()) return false;

        return isMeleeWeapon(self.getMainHandStack());
    }

    private static boolean isMeleeWeapon(ItemStack stack) {
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES);
    }

    /**
     * Whether {@code candidate} is something this feature is allowed to hit.
     * Mirrors the server's own {@code WeaponDamage.isPveTarget} as closely as
     * the client can see: hostile PrisonsCore NPCs plus boss-family mobs.
     */
    public static boolean isPveTarget(Entity candidate) {
        if (candidate == null || !candidate.isAlive()) return false;

        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (self == null || candidate.getId() == self.getId()) return false;

        if (candidate instanceof PlayerEntity player) {
            return !isListedInTab(player.getUuid()) && isInHostileNpcTeam(player);
        }
        return candidate instanceof MobEntity;
    }

    /** True if this UUID is a real, tab-listed player. Fails safe to true. */
    private static boolean isListedInTab(UUID uuid) {
        var network = MinecraftClient.getInstance().getNetworkHandler();
        // No handler means we can't prove it's an NPC, so treat it as a player
        // and don't swing.
        if (network == null) return true;
        for (PlayerListEntry entry : network.getListedPlayerListEntries()) {
            if (uuid.equals(entry.getProfile().id())) return true;
        }
        return false;
    }

    private static boolean isInHostileNpcTeam(PlayerEntity player) {
        AbstractTeam team = player.getScoreboardTeam();
        return team != null && HOSTILE_NPC_TEAM.equals(team.getName());
    }

    /** Drop the cadence so a reconnect can't inherit a mid-hold timer. */
    public static void reset() {
        nextAttackAt = 0L;
        wasHeld = false;
    }

    private PveAutoAttack() {}
}
