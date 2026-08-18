package com.aleks.ancientsmod.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.equipment.trim.ArmorTrim;
import net.minecraft.item.equipment.trim.ArmorTrimMaterial;
import net.minecraft.item.equipment.trim.ArmorTrimPattern;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;

/**
 * Builds a display {@link ItemStack} from a server-sent icon key. The key is a
 * base item id followed by zero or more {@code '#'}-delimited tokens:
 * <ul>
 *   <li>{@code "namespace:path"} — the base item.</li>
 *   <li>{@code "#<customModelData>"} — a numeric token carrying CustomModelData
 *       so our CMD-driven custom textures render in these screens too.</li>
 *   <li>{@code "#t<pattern>.<material>"} — a VANILLA armor-trim token (server
 *       minor ≥ 3) so the trim overlay renders on the icon. The pattern and
 *       material are looked up in the client's own trim registries.</li>
 *   <li>{@code "#m<namespace>:<path>"} — an ITEM MODEL token. This is the one
 *       that matters for our custom art: CustomModelData range-dispatch is
 *       broken on Leaf 1.21.11, so every custom item on the server renders via
 *       {@code ItemMeta#setItemModel} instead. Without this token those items
 *       arrive here as their bare base material and the browser shows a plain
 *       string / anvil / amethyst shard where the real icon should be. A bare
 *       path with no namespace is read as {@code minecraft:}.</li>
 * </ul>
 *
 * <p>Mod GUIs (loot browser, PV / cell terminals) reconstruct item icons from
 * these keys rather than from real ItemStacks (the bundle carries no NBT).
 * Tokens are order-independent and any token that fails to parse is ignored, so
 * an older key ({@code "ns:path"} or {@code "ns:path#cmd"}) behaves exactly as
 * before and an unknown/forge trim simply renders untrimmed.
 */
public final class IconResolver {

    public static ItemStack resolve(String key, Item fallback, int amount) {
        int amt = Math.max(1, Math.min(amount, 99));
        if (key == null || key.isEmpty()) return new ItemStack(fallback, amt);

        // Split into base + optional '#'-tokens. Tokens are classified by shape
        // (numeric = CMD, "t<pattern>.<material>" = trim), so order doesn't matter.
        String base = key;
        int cmd = 0;
        String trimPattern = null;
        String trimMaterial = null;
        String itemModel = null;
        int hash = key.indexOf('#');
        if (hash >= 0) {
            base = key.substring(0, hash);
            String[] tokens = key.substring(hash + 1).split("#");
            for (String tok : tokens) {
                if (tok.isEmpty()) continue;
                if (tok.charAt(0) == 'm' && tok.length() > 1) {
                    // Item-model token. Checked before the trim token because a
                    // model path may legally contain a '.', which the trim
                    // classifier would otherwise claim.
                    itemModel = tok.substring(1).trim();
                } else if (tok.charAt(0) == 't' && tok.indexOf('.') > 1) {
                    String body = tok.substring(1);
                    int dot = body.indexOf('.');
                    String p = body.substring(0, dot);
                    String m = body.substring(dot + 1);
                    if (!p.isEmpty() && !m.isEmpty()) {
                        trimPattern = p;
                        trimMaterial = m;
                    }
                } else {
                    try {
                        cmd = Integer.parseInt(tok.trim());
                    } catch (NumberFormatException ignored) {
                        // malformed token → render the base item without it
                    }
                }
            }
        }

        Identifier id = Identifier.tryParse(base);
        Item item = id == null ? Items.AIR : Registries.ITEM.get(id);
        if (item == Items.AIR) return new ItemStack(fallback, amt);

        ItemStack stack = new ItemStack(item, amt);
        if (cmd > 0) {
            stack.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of((float) cmd), List.of(), List.of(), List.of()));
        }
        if (trimPattern != null && trimMaterial != null) {
            applyTrim(stack, trimPattern, trimMaterial);
        }
        if (itemModel != null && !itemModel.isEmpty()) {
            Identifier model = itemModel.indexOf(':') >= 0
                    ? Identifier.tryParse(itemModel)
                    : Identifier.tryParse("minecraft:" + itemModel);
            // An unresolvable model leaves the base item rendering, which is the
            // pre-token behaviour rather than an error.
            if (model != null) stack.set(DataComponentTypes.ITEM_MODEL, model);
        }
        return stack;
    }

    /**
     * Reconstruct a vanilla armor trim from its pattern/material paths and set it
     * on the stack so the icon renders the trim overlay. Looks both up in the
     * client's dynamic trim registries; a missing world/registry or unknown id
     * just leaves the stack untrimmed.
     */
    private static void applyTrim(ItemStack stack, String patternPath, String materialPath) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return;
        try {
            Registry<ArmorTrimPattern> patReg = mc.world.getRegistryManager().getOrThrow(RegistryKeys.TRIM_PATTERN);
            Registry<ArmorTrimMaterial> matReg = mc.world.getRegistryManager().getOrThrow(RegistryKeys.TRIM_MATERIAL);
            Optional<RegistryEntry.Reference<ArmorTrimPattern>> pat =
                    patReg.getEntry(Identifier.of("minecraft", patternPath));
            Optional<RegistryEntry.Reference<ArmorTrimMaterial>> mat =
                    matReg.getEntry(Identifier.of("minecraft", materialPath));
            if (pat.isPresent() && mat.isPresent()) {
                stack.set(DataComponentTypes.TRIM, new ArmorTrim(mat.get(), pat.get()));
            }
        } catch (Exception ignored) {
            // unknown trim id / registry unavailable → render untrimmed
        }
    }

    private IconResolver() {}
}
