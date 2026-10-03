package com.aleks.ancientsmod.mixin.client;

import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TextFieldWidget.class)
public interface TextFieldSelectionAccessor {
    @Accessor("selectionEnd") int ancientsmod$selectionEnd();
}
