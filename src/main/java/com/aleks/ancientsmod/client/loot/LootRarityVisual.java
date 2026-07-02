package com.aleks.ancientsmod.client.loot;

/**
 * Maps a server-sent {@code LootRarity} level (0..5, or 255 = none) to a render
 * colour, a display name, and a legacy §-colour code. Mirrors the colours the
 * server's chest GUI uses ({@code LootRarity.color()}) so the two browsers read
 * the same at a glance.
 */
public final class LootRarityVisual {

    public static final int NONE = 0xFF;

    // ARGB (opaque). Match LootRarity.color() ChatColor mappings.
    private static final int C_COMMON    = 0xFFAAAAAA; // gray
    private static final int C_UNCOMMON  = 0xFF55FF55; // green
    private static final int C_RARE      = 0xFF55FFFF; // aqua
    private static final int C_EPIC      = 0xFFFF55FF; // light purple
    private static final int C_LEGENDARY = 0xFFFFAA00; // gold
    private static final int C_MYTHIC    = 0xFFFF55FF; // light purple

    /** Opaque ARGB colour for a rarity level; gray for NONE / unknown. */
    public static int argb(int level) {
        return switch (level) {
            case 0 -> C_COMMON;
            case 1 -> C_UNCOMMON;
            case 2 -> C_RARE;
            case 3 -> C_EPIC;
            case 4 -> C_LEGENDARY;
            case 5 -> C_MYTHIC;
            default -> C_COMMON;
        };
    }

    /** Legacy §-colour code for inline text; empty for NONE / unknown. */
    public static String code(int level) {
        return switch (level) {
            case 0 -> "§7";
            case 1 -> "§a";
            case 2 -> "§b";
            case 3 -> "§d";
            case 4 -> "§6";
            case 5 -> "§d";
            default -> "§7";
        };
    }

    /** Upper-case rarity name, or empty for NONE / unknown. */
    public static String name(int level) {
        return switch (level) {
            case 0 -> "COMMON";
            case 1 -> "UNCOMMON";
            case 2 -> "RARE";
            case 3 -> "EPIC";
            case 4 -> "LEGENDARY";
            case 5 -> "MYTHIC";
            default -> "";
        };
    }

    public static boolean has(int level) {
        return level >= 0 && level <= 5;
    }

    private LootRarityVisual() {}
}
