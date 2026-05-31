package com.aleks.prisonsmod.client;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Set;

/**
 * Client-side set of custom item textures the player has turned off in
 * {@code /toggles → Custom Textures}. The server pushes the set (as
 * {@code "<item-id>#<custom-model-data>"} keys) over the prisonsmod channel; the
 * {@code CustomModelDataFloatPropertyMixin} consults it during item-model
 * resolution and, for a disabled (item, CMD) pair, makes range_dispatch fall
 * through to the vanilla model.
 *
 * <p>Keyed on (item, CMD) rather than CMD alone because CMD values are reused
 * across materials (e.g. CMD 1 is a different texture on cobweb vs blaze_rod).
 */
public final class DisabledTextures {

    private static volatile Set<String> disabled = Set.of();

    private DisabledTextures() {}

    public static void update(Set<String> keys) {
        disabled = (keys == null || keys.isEmpty()) ? Set.of() : Set.copyOf(keys);
    }

    public static boolean hasAny() {
        return !disabled.isEmpty();
    }

    /** Build the canonical key for an item id + custom-model-data value. */
    public static String key(String itemId, int cmd) {
        return itemId + "#" + cmd;
    }

    public static boolean isDisabled(ItemStack stack, int cmd) {
        Set<String> d = disabled;
        if (d.isEmpty() || stack == null) return false;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return d.contains(id.toString() + "#" + cmd);
    }
}
