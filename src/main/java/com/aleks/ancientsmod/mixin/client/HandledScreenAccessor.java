package com.aleks.ancientsmod.mixin.client;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * Exposes the container panel's live layout so code outside the screen can
 * line up with it — used by the jewel sockets to sit flush against the
 * inventory panel, including after the recipe book shifts it.
 *
 * <p>Targets {@link HandledScreen}, where these fields are actually declared.
 * {@code @Shadow}ing them from a mixin on a SUBCLASS (InventoryScreen) fails
 * at apply time with "field_2776 was not located in the target class" — an
 * accessor on the declaring class is the way to reach inherited members.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {

    @Accessor("x")
    int ancientsmod$panelX();

    @Accessor("y")
    int ancientsmod$panelY();

    @Accessor("backgroundWidth")
    int ancientsmod$panelWidth();

    @Accessor("backgroundHeight")
    int ancientsmod$panelHeight();

    /**
     * The screen's in-progress quick-craft drag. Only {@code mouseReleased}
     * ever clears it, so anything that swallows a release has to clear it
     * itself or the screen stays wedged mid-drag.
     */
    @Accessor("cursorDragging")
    void ancientsmod$setCursorDragging(boolean dragging);

    @Accessor("cursorDragSlots")
    Set<Slot> ancientsmod$cursorDragSlots();
}
