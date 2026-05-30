package com.aleks.prisonsmod.client;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Builds a display {@link ItemStack} from a server-sent icon key of the form
 * {@code "namespace:path"} or {@code "namespace:path#<customModelData>"}.
 *
 * <p>Mod GUIs (loot browser, PV) reconstruct item icons from these keys rather
 * than from real ItemStacks. Without the CMD the reconstructed stack has no
 * CustomModelData, so our CMD-driven custom item textures fall back to the plain
 * base item. The optional {@code #<cmd>} suffix lets the server carry the value
 * through so the custom texture renders in these screens too. Keys without a
 * suffix behave exactly as before.
 */
public final class IconResolver {

    public static ItemStack resolve(String key, Item fallback, int amount) {
        int amt = Math.max(1, Math.min(amount, 99));
        if (key == null || key.isEmpty()) return new ItemStack(fallback, amt);

        int cmd = 0;
        int hash = key.indexOf('#');
        if (hash >= 0) {
            try {
                cmd = Integer.parseInt(key.substring(hash + 1).trim());
            } catch (NumberFormatException ignored) {
                // malformed suffix → just render the base item
            }
            key = key.substring(0, hash);
        }

        Identifier id = Identifier.tryParse(key);
        Item item = id == null ? Items.AIR : Registries.ITEM.get(id);
        if (item == Items.AIR) return new ItemStack(fallback, amt);

        ItemStack stack = new ItemStack(item, amt);
        if (cmd > 0) {
            stack.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of((float) cmd), List.of(), List.of(), List.of()));
        }
        return stack;
    }

    private IconResolver() {}
}
