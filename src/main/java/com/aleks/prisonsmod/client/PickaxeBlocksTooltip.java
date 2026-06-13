package com.aleks.prisonsmod.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Renders the per-ore-type "Blocks Mined" breakdown on RooPrisons pickaxe
 * tooltips, fully client-side.
 *
 * <p>The server used to bake these lines into the pickaxe's lore, but that made
 * the in-hand/inventory pickaxe expensive to deep-compare every tick
 * (AbstractContainerMenu.broadcastChanges / ItemStack.matches). The server now
 * keeps a compact tooltip and only stores the raw ore counts in the pickaxe's
 * {@code minecraft:custom_data} PDC string {@code prisonscore:ore_mined_counts}
 * (format {@code MATERIAL=count,MATERIAL=count}). We read that data — already
 * synced to the client — and draw the breakdown here, so it costs the server
 * nothing while showing the full per-type detail to mod users.
 *
 * <p><b>Marker gate.</b> We render ONLY when the pickaxe also carries the
 * {@code prisonscore:lore_compact} PDC marker the server sets when it builds the
 * compact tooltip. On servers still using the old full lore (no marker) the
 * server already shows the breakdown, so we stay dormant and the tooltip never
 * double-renders. This makes the mod release safe to ship before the server-side
 * compact-lore change reaches a given backend.
 *
 * <p>Tier order, ore colors, and name formatting mirror the old server lore.
 */
public final class PickaxeBlocksTooltip {

    /** Bukkit serializes its PersistentDataContainer into custom_data under this compound. */
    private static final String PDC_ROOT = "PublicBukkitValues";
    /** Marker the plugin sets on a compact-lore pickaxe (NamespacedKey(PrisonsCore, "lore_compact")). */
    private static final String MARKER_KEY = "prisonscore:lore_compact";
    /** PDC key the plugin writes the ore tally to (NamespacedKey(PrisonsCore, "ore_mined_counts")). */
    private static final String ORE_COUNTS_KEY = "prisonscore:ore_mined_counts";

    private static final NumberFormat NUM = NumberFormat.getNumberInstance(Locale.US);

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            if (!ServerAllowlist.isAllowed()) return;
            if (!FeatureToggles.isPickaxeBlocksEnabled()) return;
            List<Text> block = buildBlockLines(stack);
            if (!block.isEmpty()) {
                lines.addAll(block);
            }
        });
    }

    private static List<Text> buildBlockLines(ItemStack stack) {
        List<Text> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return out;

        NbtComponent custom = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (custom == null || custom.isEmpty()) return out;

        NbtCompound root = custom.copyNbt();
        Optional<NbtCompound> pdcOpt = root.getCompound(PDC_ROOT);
        if (pdcOpt.isEmpty()) return out;
        NbtCompound pdc = pdcOpt.get();

        // Only render on compact-lore pickaxes. Without the marker the server's
        // full lore still shows the breakdown — rendering here too would double it.
        if (!pdc.contains(MARKER_KEY)) return out;

        String raw = pdc.getString(ORE_COUNTS_KEY).orElse("");
        if (raw.isEmpty()) return out;

        List<Entry> entries = new ArrayList<>();
        for (String token : raw.split(",")) {
            int eq = token.lastIndexOf('=');
            if (eq <= 0 || eq >= token.length() - 1) continue;
            String mat = token.substring(0, eq).trim();
            long count;
            try {
                count = Long.parseLong(token.substring(eq + 1).trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            if (mat.isEmpty() || count <= 0) continue;
            entries.add(new Entry(mat, count));
        }
        if (entries.isEmpty()) return out;

        // Highest ore tier first (Calcite top, Coal bottom), tie-broken by count — mirrors the old lore.
        entries.sort((a, b) -> {
            int t = Integer.compare(tierRank(b.material), tierRank(a.material));
            if (t != 0) return t;
            return Long.compare(b.count, a.count);
        });

        out.add(Text.empty());
        out.add(Text.literal("Blocks Mined").formatted(Formatting.GRAY, Formatting.BOLD));
        for (Entry e : entries) {
            out.add(Text.literal(prettyName(e.material)).formatted(oreColor(e.material), Formatting.BOLD)
                    .append(Text.literal(": ").formatted(Formatting.GRAY))
                    .append(Text.literal(NUM.format(e.count)).formatted(Formatting.WHITE)));
        }
        return out;
    }

    private static int tierRank(String name) {
        if (name.contains("CALCITE")) return 100;
        if (name.contains("ANCIENT_DEBRIS") || name.contains("NETHERITE")) return 90;
        if (name.contains("EMERALD")) return 80;
        if (name.contains("DIAMOND")) return 70;
        if (name.contains("GOLD")) return 60;
        if (name.contains("REDSTONE")) return 50;
        if (name.contains("LAPIS")) return 40;
        if (name.contains("IRON")) return 30;
        if (name.contains("QUARTZ")) return 20;
        if (name.contains("COAL")) return 10;
        return 0;
    }

    private static Formatting oreColor(String name) {
        if (name.contains("DIAMOND")) return Formatting.AQUA;
        if (name.contains("EMERALD")) return Formatting.GREEN;
        if (name.contains("GOLD")) return Formatting.GOLD;
        if (name.contains("REDSTONE")) return Formatting.RED;
        if (name.contains("LAPIS")) return Formatting.BLUE;
        if (name.contains("ANCIENT_DEBRIS") || name.contains("NETHERITE")) return Formatting.DARK_PURPLE;
        if (name.contains("IRON") || name.contains("QUARTZ")) return Formatting.WHITE;
        if (name.contains("COAL")) return Formatting.DARK_GRAY;
        return Formatting.GRAY;
    }

    /** "REDSTONE_ORE" -> "Redstone Ore" (mirrors the server's formatMaterialName). */
    private static String prettyName(String name) {
        String[] parts = name.toLowerCase(Locale.US).split("_");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return b.length() == 0 ? name : b.toString();
    }

    private record Entry(String material, long count) {}

    private PickaxeBlocksTooltip() {}
}
